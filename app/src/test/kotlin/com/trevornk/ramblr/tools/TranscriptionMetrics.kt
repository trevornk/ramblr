package com.trevornk.ramblr.tools

import java.text.Normalizer
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/**
 * Pure-Kotlin transcription scoring for the manual Gemini transcription benchmark (#129). No
 * Android dependencies, no I/O, no network — everything here is a deterministic function of its
 * inputs so it can be unit tested exhaustively without touching a provider API.
 *
 * Two comparison modes are supported and both are reported, because they answer different
 * questions:
 *  - [Normalization.STRICT]  — "did the model produce the exact text the user would have typed?"
 *    Case and punctuation count. Only Unicode compatibility folding (NFKC), apostrophe/dash
 *    variant folding, and whitespace collapsing are applied, since those differences are
 *    typography noise rather than transcription errors.
 *  - [Normalization.NORMALIZED] — the conventional ASR scoring mode: additionally lowercased and
 *    punctuation-stripped, so a model that declines to punctuate isn't penalised on word accuracy.
 *
 * Corpus aggregation is deliberately **micro** (sum of edits / sum of reference lengths), not an
 * unweighted mean of per-clip rates. A macro mean lets one three-word clip outweigh a two-minute
 * one and produces numbers that don't correspond to any real error count; both are reported so
 * the difference is visible, but the headline figure is micro.
 */
object TranscriptionMetrics {

    enum class Normalization { STRICT, NORMALIZED }

    /** Edit-distance decomposition. [referenceLength] is the token count of the *reference*, which
     *  is the denominator for WER/CER — insertions can push a rate above 1.0, by design. */
    data class EditCounts(
        val substitutions: Int,
        val deletions: Int,
        val insertions: Int,
        val referenceLength: Int,
    ) {
        val total: Int get() = substitutions + deletions + insertions
    }

    /** All scores for one reference/hypothesis pair. */
    data class ClipScore(
        val reference: String,
        val hypothesis: String,
        val wordEdits: EditCounts,
        val charEdits: EditCounts,
        val wer: Double,
        val cer: Double,
        val strictExactMatch: Boolean,
        val normalizedExactMatch: Boolean,
    )

    /** Corpus-level roll-up. Micro figures are the headline; macro figures are reported alongside
     *  purely so a skewed corpus is obvious rather than hidden. */
    data class Aggregate(
        val clipCount: Int,
        val totalWordEdits: Int,
        val totalReferenceWords: Int,
        val totalCharEdits: Int,
        val totalReferenceChars: Int,
        val microWer: Double,
        val microCer: Double,
        val macroWer: Double,
        val macroCer: Double,
        val strictExactMatchRate: Double,
        val normalizedExactMatchRate: Double,
        val totalSubstitutions: Int,
        val totalDeletions: Int,
        val totalInsertions: Int,
    )

    /** Latency distribution. Every field is null for an empty sample rather than a fake 0, so a
     *  report can't imply "0 ms" when it actually means "no successful calls". */
    data class LatencyStats(
        val count: Int,
        val meanMs: Double?,
        val p50Ms: Long?,
        val p95Ms: Long?,
        val minMs: Long?,
        val maxMs: Long?,
    )

    // ------------------------------------------------------------------ normalization

    private val APOSTROPHE_VARIANTS = charArrayOf(
        '\u2018', '\u2019', '\u201A', '\u201B', '\u02BC', '\u02B9', '\u00B4', '\u2032', '\u0060',
    )
    private val DASH_VARIANTS = charArrayOf(
        '\u2010', '\u2011', '\u2012', '\u2013', '\u2014', '\u2015', '\u2212', '\uFE58', '\uFE63', '\uFF0D',
    )

    /** Strips everything that is neither a letter, a digit, whitespace, nor an apostrophe. */
    private val PUNCTUATION = Regex("[^\\p{L}\\p{N}\\s']")

    /** Apostrophes not sitting between two word characters — i.e. quote marks, not contractions. */
    private val EDGE_APOSTROPHE = Regex("(?<![\\p{L}\\p{N}])'|'(?![\\p{L}\\p{N}])")

    private val WHITESPACE = Regex("\\s+")

    /**
     * Applies the scoring contract's text normalization. Both modes apply NFKC (so ligatures,
     * full-width forms, and decomposed accents don't register as errors), fold curly apostrophes
     * and Unicode dash variants to their ASCII equivalents, collapse runs of whitespace, and trim.
     * [Normalization.NORMALIZED] additionally lowercases and removes punctuation, keeping
     * contraction apostrophes so "don't" doesn't become the two tokens "don" and "t".
     */
    fun normalize(text: String, mode: Normalization): String {
        var s = Normalizer.normalize(text, Normalizer.Form.NFKC)
        val sb = StringBuilder(s.length)
        for (c in s) {
            when {
                c in APOSTROPHE_VARIANTS -> sb.append('\'')
                c in DASH_VARIANTS -> sb.append('-')
                else -> sb.append(c)
            }
        }
        s = sb.toString()
        if (mode == Normalization.NORMALIZED) {
            s = s.lowercase()
            s = PUNCTUATION.replace(s, " ")
            s = EDGE_APOSTROPHE.replace(s, "")
        }
        return WHITESPACE.replace(s, " ").trim()
    }

    private fun words(text: String, mode: Normalization): List<String> =
        normalize(text, mode).let { if (it.isEmpty()) emptyList() else it.split(' ') }

    private fun chars(text: String, mode: Normalization): List<Char> =
        normalize(text, mode).toList()

    // ------------------------------------------------------------------ edit distance

    /**
     * Levenshtein with per-operation backtracking so substitutions, deletions, and insertions are
     * reported separately instead of collapsed into a single distance. Ties are broken
     * substitution > deletion > insertion, which is the conventional ASR alignment preference.
     *
     * O(n*m) time, O(n*m) memory for the backtrace — fine for dictation-length clips.
     */
    private fun <T> edits(reference: List<T>, hypothesis: List<T>): EditCounts {
        val n = reference.size
        val m = hypothesis.size
        if (n == 0) return EditCounts(0, 0, m, 0)
        if (m == 0) return EditCounts(0, n, 0, n)

        val cost = Array(n + 1) { IntArray(m + 1) }
        for (i in 0..n) cost[i][0] = i
        for (j in 0..m) cost[0][j] = j
        for (i in 1..n) {
            for (j in 1..m) {
                val sub = cost[i - 1][j - 1] + if (reference[i - 1] == hypothesis[j - 1]) 0 else 1
                val del = cost[i - 1][j] + 1 // reference token absent from hypothesis
                val ins = cost[i][j - 1] + 1 // hypothesis token absent from reference
                cost[i][j] = min(sub, min(del, ins))
            }
        }

        var i = n
        var j = m
        var substitutions = 0
        var deletions = 0
        var insertions = 0
        while (i > 0 || j > 0) {
            when {
                i > 0 && j > 0 && reference[i - 1] == hypothesis[j - 1] && cost[i][j] == cost[i - 1][j - 1] -> {
                    i--; j--
                }
                i > 0 && j > 0 && cost[i][j] == cost[i - 1][j - 1] + 1 -> {
                    substitutions++; i--; j--
                }
                i > 0 && cost[i][j] == cost[i - 1][j] + 1 -> {
                    deletions++; i--
                }
                j > 0 && cost[i][j] == cost[i][j - 1] + 1 -> {
                    insertions++; j--
                }
                // Unreachable for a well-formed DP table; fail loudly rather than silently
                // mis-attributing an edit category.
                else -> error("Edit backtrace stalled at ($i,$j) — DP table is inconsistent")
            }
        }
        return EditCounts(substitutions, deletions, insertions, n)
    }

    /** Word-level edit decomposition under [mode] (default: the conventional ASR normalization). */
    fun wordEdits(reference: String, hypothesis: String, mode: Normalization = Normalization.NORMALIZED): EditCounts =
        edits(words(reference, mode), words(hypothesis, mode))

    /** Character-level edit decomposition under [mode]. */
    fun charEdits(reference: String, hypothesis: String, mode: Normalization = Normalization.NORMALIZED): EditCounts =
        edits(chars(reference, mode), chars(hypothesis, mode))

    /** Rate contract: edits / reference length; an empty reference scores 0.0 when the hypothesis
     *  is also empty and 1.0 otherwise (the ratio is undefined, and 1.0 is the pessimistic read). */
    private fun rate(counts: EditCounts): Double =
        if (counts.referenceLength == 0) {
            if (counts.total == 0) 0.0 else 1.0
        } else {
            counts.total.toDouble() / counts.referenceLength.toDouble()
        }

    fun wer(reference: String, hypothesis: String, mode: Normalization = Normalization.NORMALIZED): Double =
        rate(wordEdits(reference, hypothesis, mode))

    fun cer(reference: String, hypothesis: String, mode: Normalization = Normalization.NORMALIZED): Double =
        rate(charEdits(reference, hypothesis, mode))

    /** Equal after typography folding only — case and punctuation still count. */
    fun strictExactMatch(reference: String, hypothesis: String): Boolean =
        normalize(reference, Normalization.STRICT) == normalize(hypothesis, Normalization.STRICT)

    /** Equal after the full ASR normalization (case- and punctuation-insensitive). */
    fun normalizedExactMatch(reference: String, hypothesis: String): Boolean =
        normalize(reference, Normalization.NORMALIZED) == normalize(hypothesis, Normalization.NORMALIZED)

    /** Scores one reference/hypothesis pair on every metric at once. */
    fun score(reference: String, hypothesis: String): ClipScore {
        val w = wordEdits(reference, hypothesis)
        val c = charEdits(reference, hypothesis)
        return ClipScore(
            reference = reference,
            hypothesis = hypothesis,
            wordEdits = w,
            charEdits = c,
            wer = rate(w),
            cer = rate(c),
            strictExactMatch = strictExactMatch(reference, hypothesis),
            normalizedExactMatch = normalizedExactMatch(reference, hypothesis),
        )
    }

    // ------------------------------------------------------------------ aggregation

    /**
     * Corpus roll-up. [Aggregate.microWer]/[Aggregate.microCer] divide the summed edit counts by
     * the summed reference lengths — the only aggregation that equals "how many words did this
     * model get wrong across everything I gave it". [Aggregate.macroWer]/[Aggregate.macroCer]
     * (the unweighted mean of per-clip rates) are reported for contrast only; they are NOT the
     * headline metric and must never be substituted for the micro figures.
     */
    fun aggregate(scores: List<ClipScore>): Aggregate {
        if (scores.isEmpty()) {
            return Aggregate(0, 0, 0, 0, 0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0, 0, 0)
        }
        val totalWordEdits = scores.sumOf { it.wordEdits.total }
        val totalReferenceWords = scores.sumOf { it.wordEdits.referenceLength }
        val totalCharEdits = scores.sumOf { it.charEdits.total }
        val totalReferenceChars = scores.sumOf { it.charEdits.referenceLength }
        return Aggregate(
            clipCount = scores.size,
            totalWordEdits = totalWordEdits,
            totalReferenceWords = totalReferenceWords,
            totalCharEdits = totalCharEdits,
            totalReferenceChars = totalReferenceChars,
            microWer = if (totalReferenceWords == 0) 0.0 else totalWordEdits.toDouble() / totalReferenceWords.toDouble(),
            microCer = if (totalReferenceChars == 0) 0.0 else totalCharEdits.toDouble() / totalReferenceChars.toDouble(),
            macroWer = scores.map { it.wer }.average(),
            macroCer = scores.map { it.cer }.average(),
            strictExactMatchRate = scores.count { it.strictExactMatch }.toDouble() / scores.size.toDouble(),
            normalizedExactMatchRate = scores.count { it.normalizedExactMatch }.toDouble() / scores.size.toDouble(),
            totalSubstitutions = scores.sumOf { it.wordEdits.substitutions },
            totalDeletions = scores.sumOf { it.wordEdits.deletions },
            totalInsertions = scores.sumOf { it.wordEdits.insertions },
        )
    }

    // ------------------------------------------------------------------ latency

    /** Nearest-rank percentiles (ceil(p * n) on the sorted sample), which always returns an
     *  actually-observed latency rather than an interpolated value that never happened. */
    fun latencyStats(samplesMs: List<Long>): LatencyStats {
        if (samplesMs.isEmpty()) return LatencyStats(0, null, null, null, null, null)
        val sorted = samplesMs.sorted()
        return LatencyStats(
            count = sorted.size,
            meanMs = sorted.average(),
            p50Ms = percentile(sorted, 0.50),
            p95Ms = percentile(sorted, 0.95),
            minMs = sorted.first(),
            maxMs = sorted.last(),
        )
    }

    private fun percentile(sorted: List<Long>, p: Double): Long {
        val rank = ceil(p * sorted.size).toInt()
        return sorted[min(sorted.size - 1, max(0, rank - 1))]
    }
}
