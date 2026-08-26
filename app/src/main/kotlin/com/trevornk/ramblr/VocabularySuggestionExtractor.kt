package com.trevornk.ramblr

/**
 * Signal extraction for smart vocabulary suggestions (#216): given one accepted dictation's raw
 * ASR transcript and its accepted final text, produces the candidate terms this dictation is
 * evidence for. Pure function of its inputs — the dictionary lookup is injected — so the whole
 * pipeline is host-side unit-testable. Persistence and thresholds live in
 * [VocabularySuggestionStore]; the service-side wiring in [VocabularySuggestionCollector].
 *
 * Two signals, per the #216 spike (see the PR for the evidence base):
 *
 *  - **Signal 1 — correction pairs.** Tokens the cleanup step *changed* (raw "hetzler" → final
 *    "Hetzner") are aligned via LCS diff and extracted as heard→intended substitution pairs.
 *    A pair survives only if the intended word is NOT in the bundled suggestion-filter
 *    dictionary (~54k common lemmas, no proper nouns — [SuggestionFilterDictionary]), both
 *    sides are ≥ 4 chars, and the pair is not a US/UK variant-spelling normalization
 *    (colour→color, practise→practice — the spike's dominant junk class, killed by
 *    [variantNormalize]). Pairs within [VocabularyPostCorrector]'s matching bounds (first char
 *    agrees + Damerau-Levenshtein within [VocabularyPostCorrector.editBudgetFor]) are locally
 *    correctable; pairs outside those bounds are still valid suggestions but only help cloud
 *    prompts, so they're tagged [CandidateEvent.cloudOnly].
 *
 *  - **Signal 2 — recurring novel words.** A final-text word suggests itself iff it's not in
 *    the suggestion-filter dictionary, ≥ 4 chars, and capitalized somewhere OTHER than
 *    sentence-initial position (the "name-shaped" requirement: mid-sentence capitalization is
 *    what separates `Hetzner` from ordinary sentence starts — measured 16% → 38%+ precision
 *    in the spike).
 *
 * Privacy invariant: nothing here retains transcript text. The only output is the candidate
 * word itself (plus, for pairs, the single misheard token as evidence); callers must store
 * nothing else. Texts longer than [MAX_TOKENS] tokens are skipped entirely so collection stays
 * cheap on the dictation path.
 */
object VocabularySuggestionExtractor {

    /** Collection skips any dictation longer than this many tokens on either side — keeps the
     *  O(raw·final) LCS alignment bounded (~40k cells worst case) on the accept path. */
    const val MAX_TOKENS = 200

    /** Both sides of a pair, and any Signal-2 word, must be at least this long. Short tokens
     *  are dominated by function-word confusions (the spike's `−short` filter column). */
    const val MIN_CANDIDATE_LENGTH = 4

    /**
     * One candidate observation from one dictation. [term] keeps the final text's surface
     * casing ("Hetzner"); [heardForm] is the misheard token for Signal-1 pairs (null for
     * Signal 2); [cloudOnly] marks pairs outside the local corrector's matching bounds.
     */
    data class CandidateEvent(val term: String, val heardForm: String?, val cloudOnly: Boolean)

    /**
     * Extracts every candidate this dictation supports, at most one event per distinct term
     * (case-insensitive). Terms already in [vocabularyTerms] (case-insensitive, including the
     * constituent words of multi-word terms) never produce events. [isCommonWord] is the
     * suggestion-filter dictionary lookup, lowercase-in.
     */
    fun extract(
        rawText: String,
        finalText: String,
        vocabularyTerms: List<String>,
        isCommonWord: (String) -> Boolean,
    ): List<CandidateEvent> {
        if (finalText.isBlank()) return emptyList()
        val rawTokens = tokenize(rawText)
        val finalTokens = tokenize(finalText)
        if (finalTokens.isEmpty()) return emptyList()
        if (rawTokens.size > MAX_TOKENS || finalTokens.size > MAX_TOKENS) return emptyList()

        // Vocab suppression set: whole terms plus each word of multi-word terms, so "Claude"
        // never gets suggested while "Claude Code" is already configured.
        val vocabWords = HashSet<String>()
        for (term in vocabularyTerms) {
            val trimmed = term.trim()
            if (trimmed.isEmpty()) continue
            vocabWords += trimmed.lowercase()
            for (word in trimmed.split(Regex("\\s+"))) {
                val core = word.trim { !it.isLetterOrDigit() }.lowercase()
                if (core.isNotEmpty()) vocabWords += core
            }
        }

        val events = LinkedHashMap<String, CandidateEvent>()

        // --- Signal 1: correction pairs (heard → intended) ---------------------------------
        for ((heardTok, intendedTok) in substitutionPairs(rawTokens, finalTokens)) {
            val heard = heardTok.core.lowercase()
            val intended = intendedTok.core.lowercase()
            if (heard == intended) continue
            if (heard.length < MIN_CANDIDATE_LENGTH || intended.length < MIN_CANDIDATE_LENGTH) continue
            // Digits/'@' shapes (emails, versioned names) are high-consequence to guess at from
            // one substitution — mirrors the corrector's exact-only stance by never suggesting.
            if (intended.any { it.isDigit() } || '@' in intended) continue
            if (isCommonWord(intended)) continue
            if (intended in vocabWords) continue
            // Variant-spelling kill rule: US/UK orthography pairs are cleanup normalizations,
            // not vocabulary, and were the spike's worst recurring junk.
            if (variantNormalize(heard) == variantNormalize(intended)) continue
            val cloudOnly = !withinCorrectorBounds(heard, intended)
            events[intended] = CandidateEvent(intendedTok.core, heardTok.core, cloudOnly)
        }

        // --- Signal 2: name-shaped novel words in the accepted text -------------------------
        for (tok in finalTokens) {
            val lower = tok.core.lowercase()
            if (lower in events) continue // pair evidence is stronger; keep it
            if (tok.core.length < MIN_CANDIDATE_LENGTH) continue
            if (!tok.core[0].isUpperCase()) continue
            if (tok.sentenceInitial) continue
            if (tok.core.any { it.isDigit() } || '@' in tok.core) continue
            if (isCommonWord(lower)) continue
            if (lower in vocabWords) continue
            events[lower] = CandidateEvent(tok.core, heardForm = null, cloudOnly = false)
        }

        return events.values.toList()
    }

    // --- variant-spelling normalization ---------------------------------------------------------

    /**
     * Normalizes known US/UK orthography differences so a pair whose sides differ ONLY by
     * dialect spelling normalizes to the same string and can be killed: -our/-or (colour),
     * doubled-L (travelling), -re/-er endings (centre), -ce/-se endings (practice/practise),
     * -ise/-ize and -yse/-yze (organise, analyse), -isa-/-iza- (organisation), -ogue/-og
     * (catalogue), plus a trailing plural 's' strip so `colours→colors` dies with `colour→color`.
     *
     * Deliberately aggressive: it's only ever used to compare BOTH sides of an already-aligned
     * substitution pair for equality, so over-normalization can only kill pairs whose sides
     * were near-identical anyway — a conservative failure mode for a suggestion feature.
     */
    internal fun variantNormalize(word: String): String {
        var s = word.lowercase()
        if (s.length > 4 && s.endsWith("s")) s = s.dropLast(1)
        s = s.replace("our", "or")
        s = s.replace("ll", "l")
        if (s.endsWith("ogue")) s = s.dropLast(4) + "og"
        if (s.endsWith("re")) s = s.dropLast(2) + "er"
        if (s.endsWith("ce")) s = s.dropLast(2) + "se"
        s = s.replace("isa", "iza")
        s = s.replace("ise", "ize")
        s = s.replace("yse", "yze")
        return s
    }

    // --- corrector-bounds classification --------------------------------------------------------

    /**
     * Whether [VocabularyPostCorrector] could locally apply a term equal to [intended] against
     * a mishearing shaped like [heard]: first letters agree (case-insensitive; both args are
     * already lowercase here) and the Damerau-Levenshtein distance fits
     * [VocabularyPostCorrector.editBudgetFor]'s length-scaled budget. Pairs failing this are
     * still real suggestions — they just only help the cloud prompt path — so callers tag them
     * cloud-only instead of dropping them.
     */
    internal fun withinCorrectorBounds(heard: String, intended: String): Boolean {
        if (heard.first() != intended.first()) return false
        val budget = VocabularyPostCorrector.editBudgetFor(intended.count { it.isLetterOrDigit() })
        if (budget == 0) return false
        return boundedDamerauLevenshtein(heard, intended, budget) != null
    }

    /** Same optimal-string-alignment DL as [VocabularyPostCorrector]'s private helper (which
     *  stays private there); null when the distance exceeds [max]. */
    private fun boundedDamerauLevenshtein(a: String, b: String, max: Int): Int? {
        if (kotlin.math.abs(a.length - b.length) > max) return null
        val d = Array(a.length + 1) { IntArray(b.length + 1) }
        for (r in 0..a.length) d[r][0] = r
        for (c in 0..b.length) d[0][c] = c
        for (r in 1..a.length) {
            for (c in 1..b.length) {
                val cost = if (a[r - 1] == b[c - 1]) 0 else 1
                var v = minOf(d[r - 1][c] + 1, d[r][c - 1] + 1, d[r - 1][c - 1] + cost)
                if (r > 1 && c > 1 && a[r - 1] == b[c - 2] && a[r - 2] == b[c - 1]) {
                    v = minOf(v, d[r - 2][c - 2] + 1)
                }
                d[r][c] = v
            }
        }
        return d[a.length][b.length].takeIf { it <= max }
    }

    // --- tokenization + alignment ---------------------------------------------------------------

    /** One word token: [core] is the punctuation-trimmed surface (casing preserved),
     *  [sentenceInitial] is true when the token starts the text or follows sentence-ending
     *  punctuation — Signal 2's mid-cap test needs to ignore those capitals. */
    internal data class Token(val core: String, val sentenceInitial: Boolean)

    /** Same in-token character set as [VocabularyPostCorrector]'s tokenizer, so both features
     *  agree on what a "word" is (fast.ai, isn't, Nash-Keller). */
    private fun isTokenChar(c: Char): Boolean =
        c.isLetterOrDigit() || c == '\'' || c == '\u2019' || c == '@' || c == '.' || c == '-' || c == '_'

    private fun endsSentence(c: Char): Boolean =
        c == '.' || c == '!' || c == '?' || c == '\u2026' || c == '\n' || c == ':'

    internal fun tokenize(text: String): List<Token> {
        val tokens = ArrayList<Token>()
        var pos = 0
        while (pos < text.length) {
            if (!isTokenChar(text[pos])) {
                pos++
                continue
            }
            val start = pos
            while (pos < text.length && isTokenChar(text[pos])) pos++
            var coreStart = start
            var coreEnd = pos
            while (coreStart < coreEnd && !text[coreStart].isLetterOrDigit()) coreStart++
            while (coreEnd > coreStart && !text[coreEnd - 1].isLetterOrDigit()) coreEnd--
            // Strip a trailing possessive so "Hetzner's" counts as evidence for "Hetzner".
            if (coreEnd - coreStart > 2 &&
                (text[coreEnd - 1] == 's' || text[coreEnd - 1] == 'S') &&
                (text[coreEnd - 2] == '\'' || text[coreEnd - 2] == '\u2019')
            ) {
                coreEnd -= 2
            }
            if (coreEnd > coreStart) {
                // Sentence-initial: nothing but whitespace/quotes/brackets between this token
                // and either the text start or the last sentence-ending character.
                var back = coreStart - 1
                while (back >= 0 && (text[back].isWhitespace() || text[back] in "\"'\u2018\u2019\u201C\u201D([{-\u2014")) back--
                val sentenceInitial = back < 0 || endsSentence(text[back])
                tokens += Token(text.substring(coreStart, coreEnd), sentenceInitial)
            }
        }
        return tokens
    }

    /**
     * LCS-aligns [raw] and [final] on lowercased cores and returns the positional substitution
     * pairs: within each non-matching run between two matches, the i-th deleted raw token pairs
     * with the i-th inserted final token (jiwer-style); leftover insert/delete tokens are not
     * pairs. Equal texts produce no pairs.
     */
    internal fun substitutionPairs(raw: List<Token>, final: List<Token>): List<Pair<Token, Token>> {
        val n = raw.size
        val m = final.size
        if (n == 0 || m == 0) return emptyList()
        val rawLower = raw.map { it.core.lowercase() }
        val finalLower = final.map { it.core.lowercase() }
        val lcs = Array(n + 1) { IntArray(m + 1) }
        for (i in n - 1 downTo 0) {
            for (j in m - 1 downTo 0) {
                lcs[i][j] = if (rawLower[i] == finalLower[j]) {
                    lcs[i + 1][j + 1] + 1
                } else {
                    maxOf(lcs[i + 1][j], lcs[i][j + 1])
                }
            }
        }
        val pairs = ArrayList<Pair<Token, Token>>()
        val deleted = ArrayList<Token>()
        val inserted = ArrayList<Token>()
        fun flush() {
            for (k in 0 until minOf(deleted.size, inserted.size)) pairs += deleted[k] to inserted[k]
            deleted.clear()
            inserted.clear()
        }
        var i = 0
        var j = 0
        while (i < n && j < m) {
            when {
                rawLower[i] == finalLower[j] -> {
                    flush()
                    i++
                    j++
                }
                lcs[i + 1][j] >= lcs[i][j + 1] -> deleted += raw[i++]
                else -> inserted += final[j++]
            }
        }
        while (i < n) deleted += raw[i++]
        while (j < m) inserted += final[j++]
        flush()
        return pairs
    }
}
