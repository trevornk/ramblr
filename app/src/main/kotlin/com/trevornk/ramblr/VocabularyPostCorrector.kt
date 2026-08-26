package com.trevornk.ramblr

/**
 * Deterministic personal-vocabulary correction pass over LOCAL cleanup output (#182, option 2).
 *
 * Why post-process instead of prompt: interpolating the vocabulary clause into the local system
 * prompt made LFM2.5-350M echo the term list back as the "cleaned" transcript (valid-output rate
 * fell 8/10 -> 2/10 with 22 terms), and `mumble-cleanup-2stage` declares its own training prompt
 * with no vocabulary placeholder at all -- so prompting delivered the feature for neither local
 * model. This pass runs AFTER the model, on plain text, so it cannot contaminate the prompt, works
 * identically for every local model, and is a pure deterministic function that is trivially
 * unit-testable. Cloud cleanup keeps its prompt interpolation ([PostProcessor.interpolateVocabulary]),
 * where it demonstrably works; this pass must therefore only be applied to on-device output,
 * never stacked on a cloud result.
 *
 * Design bias: a false positive (rewriting a legitimate word into a vocabulary term) is much
 * worse than a false negative (leaving a mishearing uncorrected), so every knob here is set
 * conservatively:
 *
 *  - **Word-boundary aware.** Matching operates on whole word tokens, never substrings, so
 *    "Pi" can never fire inside "spin" and "fastcore" can never rewrite part of "fastcorean".
 *  - **Bounded edit distance, scaled to length.** Per word: alphanumeric length <= 3 requires an
 *    exact (case-insensitive) match, 4-6 allows Damerau-Levenshtein distance 1, >= 7 allows 2.
 *    Multi-word terms additionally cap the summed distance at the whole-term budget.
 *  - **First letter must agree** (case-insensitively). ASR mishearings of proper nouns almost
 *    always preserve the initial sound/letter; requiring it removes a large class of accidental
 *    near-misses ("codex" vs "rodex"-style coincidences) at negligible recall cost.
 *  - **Common English words are never rewritten.** A fuzzy (distance > 0) candidate that is
 *    itself an everyday dictionary word ([CommonEnglishWords.contains]) is left alone: "code"
 *    is one deletion from "Codex", and rewriting it would corrupt ordinary sentences. Exact
 *    case-insensitive matches bypass this guard -- if the user configured the term "Pi", a
 *    lowercase "pi" in the output is exactly what they asked to have recased.
 *  - **Terms containing digits or '@' match exactly only** (case-insensitive). An email address
 *    or versioned name is high-consequence to guess at; the only correction applied is recasing
 *    to the canonical spelling.
 *  - **Ambiguity aborts.** If two different terms match one window at the same best distance,
 *    neither is applied.
 *
 * Replacement preserves nothing of the matched surface except its position: the term's canonical
 * casing is substituted verbatim. Text that already matches a term exactly (including case) is
 * left byte-for-byte untouched, which also makes the pass idempotent.
 */
object VocabularyPostCorrector {

    /** Per-word Damerau-Levenshtein budget: exact for short words, 1 for medium, 2 for long.
     *  Length is counted in letters/digits only, so "fast.ai" budgets as 6, not 7. */
    fun editBudgetFor(alnumLength: Int): Int = when {
        alnumLength <= 3 -> 0
        alnumLength <= 6 -> 1
        else -> 2
    }

    /**
     * Applies the correction pass: every near-miss occurrence of a configured term in [text] is
     * replaced with that term's canonical spelling. Returns [text] unchanged (same instance) when
     * [terms] is empty or nothing matched. Pure function of its inputs.
     */
    fun correct(text: String, terms: List<String>): String {
        if (terms.isEmpty() || text.isBlank()) return text
        val specs = terms.mapNotNull(::termSpec)
        if (specs.isEmpty()) return text
        val maxWords = specs.maxOf { it.words.size }
        val tokens = tokenize(text)
        if (tokens.isEmpty()) return text

        val consumed = BooleanArray(tokens.size)
        // (startOffset, endOffset, replacementText), non-overlapping, in ascending order.
        val replacements = ArrayList<Replacement>()
        var i = 0
        while (i < tokens.size) {
            if (consumed[i]) {
                i++
                continue
            }
            var advancedTo = -1
            // Longest window first so "Claude Code" wins over a hypothetical "Claude" term.
            for (n in minOf(maxWords, tokens.size - i) downTo 1) {
                if ((i until i + n).any { consumed[it] }) continue
                if (!windowIsAdjacent(text, tokens, i, n)) continue
                val candidate = List(n) { tokens[i + it].core }
                val best = bestMatch(candidate, specs) ?: continue
                val start = tokens[i].coreStart
                val end = tokens[i + n - 1].coreEnd
                val surface = text.substring(start, end)
                if (surface != best.canonical) {
                    replacements += Replacement(start, end, best.canonical)
                }
                for (k in i until i + n) consumed[k] = true
                advancedTo = i + n
                break
            }
            i = if (advancedTo >= 0) advancedTo else i + 1
        }

        if (replacements.isEmpty()) return text
        val out = StringBuilder(text.length + 16)
        var cursor = 0
        for (r in replacements) {
            out.append(text, cursor, r.start).append(r.replacement)
            cursor = r.end
        }
        out.append(text, cursor, text.length)
        return out.toString()
    }

    // --- internals ------------------------------------------------------------------------------

    private data class Replacement(val start: Int, val end: Int, val replacement: String)

    /** One word token of the output text. [core] is the token with leading/trailing punctuation
     *  trimmed (so "Hetzner," matches the term "Hetzner"); [coreStart]/[coreEnd] index [core]'s
     *  span in the original text, which is the exact span a replacement overwrites -- surrounding
     *  punctuation survives untouched. */
    private data class Token(val coreStart: Int, val coreEnd: Int, val core: String)

    /** Characters that may appear INSIDE a word token in addition to letters/digits. Covers the
     *  shapes real vocabulary terms take: "fast.ai", "Answer.AI", "trevor@nashkellermedia.com",
     *  "Nash-Keller", "isn't". */
    private fun isTokenChar(c: Char): Boolean =
        c.isLetterOrDigit() || c == '\'' || c == '\u2019' || c == '@' || c == '.' || c == '-' || c == '_'

    private fun tokenize(text: String): List<Token> {
        val tokens = ArrayList<Token>()
        var pos = 0
        while (pos < text.length) {
            if (!isTokenChar(text[pos])) {
                pos++
                continue
            }
            val start = pos
            while (pos < text.length && isTokenChar(text[pos])) pos++
            // Trim to a core that starts and ends on a letter or digit, so sentence punctuation
            // glued to a word ("Hetzner.", "'Codex'") doesn't defeat matching.
            var coreStart = start
            var coreEnd = pos
            while (coreStart < coreEnd && !text[coreStart].isLetterOrDigit()) coreStart++
            while (coreEnd > coreStart && !text[coreEnd - 1].isLetterOrDigit()) coreEnd--
            // Strip a trailing possessive ('s / \u2019s) from the core so "hetzner's" matches the
            // term "Hetzner" on its stem and the replacement leaves the possessive suffix in
            // place ("Hetzner's"), instead of the whole token fuzzy-matching the term and the
            // correction silently swallowing the apostrophe-s.
            if (coreEnd - coreStart > 2 &&
                (text[coreEnd - 1] == 's' || text[coreEnd - 1] == 'S') &&
                (text[coreEnd - 2] == '\'' || text[coreEnd - 2] == '\u2019')
            ) {
                coreEnd -= 2
            }
            if (coreEnd > coreStart) {
                tokens += Token(coreStart, coreEnd, text.substring(coreStart, coreEnd))
            }
        }
        return tokens
    }

    /** A multi-word window is only a candidate when nothing but whitespace separates its words'
     *  cores: "Claude Code" spans a plain space, but "Claude. Code" has a sentence boundary
     *  between the cores and must not be treated as one term occurrence. */
    private fun windowIsAdjacent(text: String, tokens: List<Token>, first: Int, count: Int): Boolean {
        for (k in first until first + count - 1) {
            val gap = text.substring(tokens[k].coreEnd, tokens[k + 1].coreStart)
            if (gap.isEmpty() || !gap.all { it.isWhitespace() }) return false
        }
        return true
    }

    private class TermSpec(
        val canonical: String,
        /** Lowercased word cores of the canonical spelling, split on whitespace. */
        val words: List<String>,
        /** Digits/'@' make a term exact-match-only: see class kdoc. */
        val exactOnly: Boolean,
        /** Whole-term distance cap (sum across words), from the term's total alnum length. */
        val totalBudget: Int,
    )

    private fun termSpec(term: String): TermSpec? {
        val words = term.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
            .map { word -> word.trim { c -> !c.isLetterOrDigit() }.lowercase() }
            .filter { it.isNotEmpty() }
        if (words.isEmpty()) return null
        val alnumTotal = words.sumOf { w -> w.count { it.isLetterOrDigit() } }
        val exactOnly = term.any { it.isDigit() || it == '@' }
        return TermSpec(term.trim(), words, exactOnly, editBudgetFor(alnumTotal))
    }

    /** The unique best-matching spec for [candidate] (word cores of one window), or null when
     *  nothing matches or the best distance is shared by two different terms. */
    private fun bestMatch(candidate: List<String>, specs: List<TermSpec>): TermSpec? {
        var best: TermSpec? = null
        var bestDistance = Int.MAX_VALUE
        var ambiguous = false
        for (spec in specs) {
            val d = matchDistance(candidate, spec) ?: continue
            if (d < bestDistance) {
                best = spec
                bestDistance = d
                ambiguous = false
            } else if (d == bestDistance && best != null && spec.canonical != best.canonical) {
                ambiguous = true
            }
        }
        return if (ambiguous) null else best
    }

    /**
     * Total edit distance if [candidate] is an acceptable occurrence of [spec], else null.
     *
     * Multi-word anchoring: in a multi-word window where at least one word already matches its
     * term word exactly, the remaining fuzzy words get one extra point of edit budget (capped at
     * 2) and are allowed to be common English words. The exact sibling is strong contextual
     * evidence -- "clawed code" is vanishingly unlikely to be legitimate prose, but is a classic
     * ASR rendering of "Claude Code" -- whereas a lone common word ("code", "fast", "answer")
     * carries no such evidence and stays protected.
     */
    private fun matchDistance(candidate: List<String>, spec: TermSpec): Int? {
        if (candidate.size != spec.words.size) return null
        val lowered = candidate.map { it.lowercase() }
        if (lowered == spec.words) return 0
        if (spec.exactOnly || spec.totalBudget == 0) return null
        val anchored = spec.words.size > 1 &&
            spec.words.indices.any { lowered[it] == spec.words[it] }
        var total = 0
        for (j in spec.words.indices) {
            val cand = lowered[j]
            val word = spec.words[j]
            if (cand == word) continue
            // Mishearings of names/jargon nearly always keep the first letter; requiring it
            // cheaply eliminates most coincidental near-misses.
            if (cand.first() != word.first()) return null
            // A fuzzy candidate that is itself an everyday word must never be rewritten --
            // "code" -> "Codex" would corrupt ordinary prose -- unless an exact sibling word
            // anchors the window (see kdoc above). Exact matches returned above.
            if (!anchored && CommonEnglishWords.contains(cand)) return null
            val budget = (editBudgetFor(word.count { it.isLetterOrDigit() }) + if (anchored) 1 else 0)
                .coerceAtMost(2)
            if (budget == 0) return null
            val d = boundedDamerauLevenshtein(cand, word, budget) ?: return null
            total += d
            if (total > spec.totalBudget) return null
        }
        return total
    }

    /**
     * Damerau-Levenshtein (optimal string alignment) distance between [a] and [b], or null when
     * it exceeds [max]. Plain O(len_a * len_b) DP -- inputs are single words, so no banding is
     * needed. Transposition counts 1 because swapped adjacent letters ("clawed" ~ "claude"'s
     * "ed"/"de") are a classic ASR/typo shape.
     */
    private fun boundedDamerauLevenshtein(a: String, b: String, max: Int): Int? {
        if (kotlin.math.abs(a.length - b.length) > max) return null
        val rows = a.length + 1
        val cols = b.length + 1
        val d = Array(rows) { IntArray(cols) }
        for (r in 0 until rows) d[r][0] = r
        for (c in 0 until cols) d[0][c] = c
        for (r in 1 until rows) {
            for (c in 1 until cols) {
                val cost = if (a[r - 1] == b[c - 1]) 0 else 1
                var v = minOf(
                    d[r - 1][c] + 1, // deletion
                    d[r][c - 1] + 1, // insertion
                    d[r - 1][c - 1] + cost, // substitution
                )
                if (r > 1 && c > 1 && a[r - 1] == b[c - 2] && a[r - 2] == b[c - 1]) {
                    v = minOf(v, d[r - 2][c - 2] + 1) // transposition
                }
                d[r][c] = v
            }
        }
        val result = d[a.length][b.length]
        return if (result <= max) result else null
    }
}
