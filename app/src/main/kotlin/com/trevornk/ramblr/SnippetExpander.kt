package com.trevornk.ramblr

/**
 * Deterministic post-transcription expansion pass for #248 (Snippets / voice-triggered canned
 * text): scans final delivery text for configured trigger phrases and replaces each occurrence
 * with its canned expansion, verbatim.
 *
 * ## Where this runs, and why
 *
 * This is applied to the FINAL text right before delivery -- after cleanup (local or cloud) and
 * after [VocabularyPostCorrector]'s vocabulary correction pass, never before either. The #248
 * issue calls out the alternative directly: expand before cleanup and a cleanup model may
 * paraphrase or "improve" the canned text (an address, a signature) the user asked to have
 * inserted exactly. Running last guarantees the expansion text a user configured reaches the
 * field byte-for-byte, at the cost of an explicit, tested limitation described below.
 *
 * This also means expansion runs in the shared [DictationRuntime]/`RuntimeListener` delivery
 * path used by every invocation host (floating ring, system controls, Quick Settings tile,
 * Ramblr Voice IME) and separately in [ProcessTextActivity]'s selection-cleanup path -- never
 * inside the accessibility service's event callback, so it works identically whether or not
 * Accessibility is even enabled, matching #248's explicit design note.
 *
 * ## The cleanup-interaction limitation (explicit, not silently assumed)
 *
 * Because matching happens on cleanup's OUTPUT, a cleanup step that rewrites the trigger phrase
 * beyond case/punctuation -- paraphrasing "send my home address" into "share my home's address",
 * for instance -- destroys the surface this pass matches against, and the trigger will not fire.
 * This is a real, tested trade-off (see `SnippetExpanderCleanupInteractionTest`), not a
 * guarantee papered over with a claim of reliability: this pass tolerates exactly the variation
 * ASR and light grammar cleanup are expected to introduce (casing, punctuation, added/removed
 * filler words are NOT tolerated -- see below), not arbitrary rewrites.
 *
 * ## Matching contract
 *
 *  - **Case-insensitive.** "My Address" and "my address" are the same trigger.
 *  - **Punctuation-tolerant.** Matching strips ALL punctuation from each word before comparing
 *    (not just leading/trailing), so "don't" / "dont", "e-mail" / "email", and a trigger spoken
 *    with a comma or period glued to it by ASR ("my address," / "my address.") all match the
 *    same configured trigger.
 *  - **Exact word sequence, no fuzzy edit distance.** Unlike [VocabularyPostCorrector], this
 *    pass never treats an ASR mishearing as a match -- that would risk silently replacing
 *    ordinary prose with a large block of unrelated canned text, a far worse failure mode than
 *    a vocabulary mis-correction. A trigger either normalizes identically to a window of the
 *    text or it does not match at all.
 *  - **Word-boundary aware, whitespace-adjacent only.** A multi-word trigger only matches when
 *    its words are separated by nothing but whitespace in the source text -- "Claude. Code"
 *    across a sentence boundary is not one occurrence of "Claude Code", exactly as in
 *    [VocabularyPostCorrector].
 *  - **Deterministic and bounded.** A single left-to-right scan over the text's word tokens;
 *    at each unconsumed position, the longest matching configured trigger wins (so "my address"
 *    and "my address book" configured together never fire out of order). Cost is
 *    O(tokens * longest trigger word count), bounded by [SnippetEntryValidation.MAX_TRIGGER_WORDS]
 *    regardless of how many snippets are configured.
 *  - **No recursive expansion.** Only the ORIGINAL text's tokens are ever scanned; an inserted
 *    expansion is never rescanned for further trigger matches, even if it happens to contain
 *    text that looks like another trigger. Replacements are computed against fixed token
 *    offsets and spliced in a single pass, the same non-overlapping-replacement structure
 *    [VocabularyPostCorrector] uses.
 *  - **Verbatim replacement.** The matched span (word-core to word-core; surrounding whitespace
 *    and punctuation outside the match are untouched) is replaced with the expansion exactly as
 *    configured -- no recasing, no punctuation merging.
 */
object SnippetExpander {

    /**
     * Applies every configured [SnippetEntry] trigger as a one-shot, non-recursive replacement
     * over [text]. Returns [text] unchanged (same instance) when [entries] is empty or nothing
     * matched. Pure function of its inputs.
     */
    fun expand(text: String, entries: List<SnippetEntry>): String {
        if (entries.isEmpty() || text.isBlank()) return text
        val specs = entries.mapNotNull(::specFor)
        if (specs.isEmpty()) return text
        val maxWords = specs.maxOf { it.words.size }
        val tokens = tokenize(text)
        if (tokens.isEmpty()) return text

        val consumed = BooleanArray(tokens.size)
        val replacements = ArrayList<Replacement>()
        var i = 0
        while (i < tokens.size) {
            if (consumed[i]) {
                i++
                continue
            }
            var advancedTo = -1
            // Longest window first: a longer configured trigger always wins over a shorter one
            // that happens to be its prefix (deterministic, see class kdoc).
            for (n in minOf(maxWords, tokens.size - i) downTo 1) {
                if ((i until i + n).any { consumed[it] }) continue
                if (!windowIsAdjacent(text, tokens, i, n)) continue
                val candidateWords = List(n) { tokens[i + it].normalized }
                val spec = specs.firstOrNull { it.words == candidateWords } ?: continue
                val start = tokens[i].coreStart
                val end = tokens[i + n - 1].coreEnd
                replacements += Replacement(start, end, spec.expansion)
                for (k in i until i + n) consumed[k] = true
                advancedTo = i + n
                break
            }
            i = if (advancedTo >= 0) advancedTo else i + 1
        }

        if (replacements.isEmpty()) return text
        val out = StringBuilder(text.length + 64)
        var cursor = 0
        for (r in replacements) {
            out.append(text, cursor, r.start).append(r.replacement)
            cursor = r.end
        }
        out.append(text, cursor, text.length)
        return out.toString()
    }

    /** Splits [trigger] into lowercased, fully-punctuation-stripped words -- the same
     *  normalization [tokenize] applies to text being matched against, so a configured trigger
     *  and the text it should match always compare on identical terms. Public so
     *  [SnippetEntryValidation] can bound/dedupe on the exact same normalization the matcher
     *  uses (a trigger that is empty after normalization, e.g. all punctuation, would otherwise
     *  silently never match anything). */
    fun normalizeWords(trigger: String): List<String> =
        trigger.trim().split(Regex("\\s+"))
            .map { word -> word.lowercase().filter { it.isLetterOrDigit() } }
            .filter { it.isNotEmpty() }

    // --- internals ------------------------------------------------------------------------------

    private class Spec(val words: List<String>, val expansion: String)

    private fun specFor(entry: SnippetEntry): Spec? {
        val words = normalizeWords(entry.trigger)
        if (words.isEmpty()) return null
        return Spec(words, entry.expansion)
    }

    private data class Replacement(val start: Int, val end: Int, val replacement: String)

    /** One word token of the text being scanned. [normalized] is fully punctuation-stripped and
     *  lowercased (see [normalizeWords]); [coreStart]/[coreEnd] index the ORIGINAL (punctuated,
     *  cased) span in the source text, which is exactly what a match replaces -- so
     *  "my address," keeps its trailing comma if the token before it isn't consumed, and loses
     *  it (along with everything else in the matched span) only when the comma sits inside the
     *  matched core itself. */
    private data class Token(val coreStart: Int, val coreEnd: Int, val normalized: String)

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
            var coreStart = start
            var coreEnd = pos
            while (coreStart < coreEnd && !text[coreStart].isLetterOrDigit()) coreStart++
            while (coreEnd > coreStart && !text[coreEnd - 1].isLetterOrDigit()) coreEnd--
            if (coreEnd > coreStart) {
                val normalized = text.substring(coreStart, coreEnd).lowercase().filter { it.isLetterOrDigit() }
                if (normalized.isNotEmpty()) tokens += Token(coreStart, coreEnd, normalized)
            }
        }
        return tokens
    }

    /** A multi-word window is only a candidate when nothing but whitespace separates its words'
     *  cores -- mirrors [VocabularyPostCorrector]'s identical guard and for the identical reason:
     *  "my address. Book a table" must not be read as containing the trigger "my address book". */
    private fun windowIsAdjacent(text: String, tokens: List<Token>, first: Int, count: Int): Boolean {
        for (k in first until first + count - 1) {
            val gap = text.substring(tokens[k].coreEnd, tokens[k + 1].coreStart)
            if (gap.isEmpty() || !gap.all { it.isWhitespace() }) return false
        }
        return true
    }
}
