package com.trevornk.ramblr

/**
 * One piece of a transcript to be cleaned by a single on-device completion (#325). [joinBefore]
 * is the whitespace that separated this piece from the previous one in the source text -- `" "`
 * between sentences, `"\n\n"` (or whatever line break the source had) between paragraphs, `""`
 * for the first piece -- so re-joining cleaned pieces keeps the speaker's paragraph structure.
 */
data class LocalCleanupChunk(val text: String, val joinBefore: String)

/**
 * Splits a transcript that is too long for one on-device cleanup pass into sentence-aligned
 * pieces (#325). Pure Kotlin so every rule is a plain JVM test; the executor owns the loop,
 * budgets and failure policy (see [CleanupWaterfallExecutor]'s LOCAL_LLM branch).
 *
 * ## Why a long transcript cannot go through in one pass
 *
 * The local model runs with a 2048-token context ([LlamaCppInference.DEFAULT_CONTEXT_SIZE]) and a
 * 512-piece response cap ([LlamaCppInference.MAX_RESPONSE_TOKENS]); the native side throws
 * "context size reached" (it never truncates) when prompt + generation would overflow, and the
 * accumulator throws at 512 pieces. Either way the step fails and the user gets the raw text.
 * Ten minutes of speech is ~1,500 words, roughly 2,000 tokens in and 2,000 out.
 *
 * ## Chunk size: the three bounds, and which one binds
 *
 * Token counts are estimated from characters, deliberately pessimistically: 3 characters per
 * token (English prose is ~4; digits, names and e-mail addresses are worse, so 3 is safe).
 * [maxChunkChars] takes the minimum of:
 *
 *  1. **Output cap (512 pieces).** Cleanup output is at most ~1.5x its input (the validator
 *     rejects 2x as [LocalCleanupValidation.Reason.LENGTH_EXPANSION]). To keep 2x headroom under
 *     the cap, the output may use at most 256 tokens => `256 / 1.5 * 3` = 512 chars of input.
 *  2. **Context (2048 tokens).** `systemPrompt + chat template + input + output` must stay
 *     within 75% of the context (25% reserved). With the production prompt (SIMPLE_PROMPT, ~560 chars =>
 *     ~190 tokens) that leaves ~1,300 tokens for `input + 1.5 * input` = 2.5 tokens per 3 chars =>
 *     ~1,500 chars. A much longer system prompt shrinks this bound, which is why it is computed
 *     from the prompt actually sent rather than assumed.
 *  3. **Wall clock ([MAX_CHUNK_CHARS] = 300 chars, ~50 words, ~75 tokens).** This is the one that
 *     binds. Measured on a Pixel 10a with LFM2.5-350M-Q4_0, a 300-character piece takes 4-10 s
 *     end to end (~10 tokens/s decode), against a 12 s terminal budget
 *     ([TERMINAL_LOCAL_LLM_STEP_BUDGET_MS]); 600 characters would not reliably fit even a 20 s
 *     per-chunk budget on a slower phone. 300 is also the piece size the audio-files path has
 *     shipped with since #285 (small models also collapse on longer multi-sentence input -- the
 *     validator rejects that), so output quality is unchanged from what that path already saw.
 *
 * So bounds 1 and 2 are met with 1.7x and 5x headroom respectively; bound 3 sets the size.
 *
 * ## Splitting rules
 *
 * Paragraph breaks are hard boundaries. Within a paragraph, a sentence ends at `.`, `!`, `?` or
 * `…` (plus any closing quote/bracket) followed by whitespace, unless the word before the period
 * is a known abbreviation (`Dr.`, `e.g.`, `Inc.`), a single initial (`J. Smith`), or the next
 * word starts lowercase. Periods inside tokens never end a sentence because no whitespace follows
 * them: `3.5`, `example.com/a.b`, `a.m.`. Sentences are packed greedily up to the limit. A single
 * sentence over the limit (an unpunctuated ramble) is cut at clause punctuation (`, ; :`), then
 * at spaces, and only as a last resort mid-token (a 400-character URL), never inside a surrogate
 * pair.
 */
object LocalCleanupChunker {
    /** Largest chunk -- and the single-pass threshold -- imposed by wall clock; see class kdoc. */
    const val MAX_CHUNK_CHARS = 300

    /** Smallest chunk we will ever ask for, so a huge system prompt cannot drive this to zero. */
    const val MIN_CHUNK_CHARS = 100

    private const val CHARS_PER_TOKEN = 3.0
    private const val OUTPUT_TO_INPUT_RATIO = 1.5
    private const val CHAT_TEMPLATE_TOKENS = 48
    private const val CONTEXT_TOKENS = LlamaCppInference.DEFAULT_CONTEXT_SIZE.toInt()
    private const val CONTEXT_RESERVE_FRACTION = 0.25
    private const val OUTPUT_CAP_HEADROOM_TOKENS = LlamaCppInference.MAX_RESPONSE_TOKENS / 2

    /** The chunk size to use with [systemPrompt]: the minimum of the three bounds in the class kdoc. */
    fun maxChunkChars(systemPrompt: String): Int {
        val promptTokens = Math.ceil(systemPrompt.length / CHARS_PER_TOKEN).toInt()
        val contextBudgetTokens =
            CONTEXT_TOKENS * (1 - CONTEXT_RESERVE_FRACTION) - promptTokens - CHAT_TEMPLATE_TOKENS
        val contextBound = (contextBudgetTokens / (1 + OUTPUT_TO_INPUT_RATIO) * CHARS_PER_TOKEN).toInt()
        val outputBound = (OUTPUT_CAP_HEADROOM_TOKENS / OUTPUT_TO_INPUT_RATIO * CHARS_PER_TOKEN).toInt()
        return minOf(MAX_CHUNK_CHARS, outputBound, contextBound).coerceAtLeast(MIN_CHUNK_CHARS)
    }

    /** True when [text] must be split. Short text returns false and takes the single-pass path. */
    fun needsChunking(text: String, systemPrompt: String): Boolean =
        text.trim().length > maxChunkChars(systemPrompt)

    /**
     * Splits [text] into chunks of at most [maxChars] characters. Never returns an empty list for
     * non-blank input; returns a single chunk when the trimmed text already fits.
     */
    fun split(text: String, maxChars: Int): List<LocalCleanupChunk> {
        require(maxChars >= MIN_CHUNK_CHARS) { "maxChars too small" }
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return emptyList()
        if (trimmed.length <= maxChars) return listOf(LocalCleanupChunk(trimmed, ""))

        val out = ArrayList<LocalCleanupChunk>()
        var pendingJoin = ""
        for ((paragraph, joinBefore) in paragraphs(trimmed)) {
            pendingJoin = if (out.isEmpty()) "" else joinBefore
            var current = StringBuilder()
            var currentJoin = pendingJoin
            fun flush() {
                if (current.isNotEmpty()) {
                    out += LocalCleanupChunk(current.toString(), currentJoin)
                    current = StringBuilder()
                    currentJoin = " "
                }
            }
            for (sentence in sentences(paragraph)) {
                if (sentence.length > maxChars) {
                    flush()
                    val pieces = splitOverlong(sentence, maxChars)
                    pieces.forEachIndexed { i, piece ->
                        out += LocalCleanupChunk(piece, if (i == 0) currentJoin else " ")
                        currentJoin = " "
                    }
                    continue
                }
                val fits = current.isEmpty() || current.length + 1 + sentence.length <= maxChars
                if (!fits) flush()
                if (current.isNotEmpty()) current.append(' ')
                current.append(sentence)
            }
            flush()
        }
        return out
    }

    /** Re-assembles cleaned (or raw) chunk texts using the original separators. */
    fun join(chunks: List<LocalCleanupChunk>, texts: List<String>): String {
        require(chunks.size == texts.size) { "one text per chunk" }
        val sb = StringBuilder()
        for (i in chunks.indices) {
            if (i > 0) sb.append(chunks[i].joinBefore)
            sb.append(texts[i])
        }
        return sb.toString()
    }

    // ---- paragraphs ------------------------------------------------------------------------

    private val PARAGRAPH_BREAK = Regex("[ \\t]*\\r?\\n[ \\t]*(?:\\r?\\n[ \\t]*)+")

    /** (paragraph, separator that preceded it). */
    private fun paragraphs(text: String): List<Pair<String, String>> {
        val result = ArrayList<Pair<String, String>>()
        var from = 0
        var join = ""
        for (m in PARAGRAPH_BREAK.findAll(text)) {
            val piece = text.substring(from, m.range.first).trim()
            if (piece.isNotEmpty()) result += piece to join
            join = "\n\n"
            from = m.range.last + 1
        }
        val tail = text.substring(from).trim()
        if (tail.isNotEmpty()) result += tail to join
        return result
    }

    // ---- sentences -------------------------------------------------------------------------

    /** Lowercased words (without the trailing period) after which a period does not end a sentence. */
    private val ABBREVIATIONS = setOf(
        "mr", "mrs", "ms", "dr", "prof", "sr", "jr", "st", "mt", "vs", "etc", "inc", "ltd", "co",
        "corp", "dept", "est", "approx", "fig", "no", "gen", "sen", "rep", "gov", "lt", "col",
        "capt", "sgt", "rev", "hon", "e.g", "i.e", "u.s", "u.k", "a.m", "p.m", "ph.d", "cf",
        "jan", "feb", "mar", "apr", "jun", "jul", "aug", "sep", "sept", "oct", "nov", "dec",
    )

    private val TERMINATORS = charArrayOf('.', '!', '?', '…')
    private val CLOSERS = charArrayOf('"', '\'', ')', ']', '”', '’', '»')

    /** Sentences of one paragraph, whitespace-trimmed, in order. */
    internal fun sentences(paragraph: String): List<String> {
        val out = ArrayList<String>()
        var start = 0
        var i = 0
        val n = paragraph.length
        while (i < n) {
            val c = paragraph[i]
            if (c in TERMINATORS) {
                var end = i + 1
                while (end < n && (paragraph[end] in TERMINATORS || paragraph[end] in CLOSERS)) end++
                if (end >= n) break // trailing terminator: the tail is added below
                if (paragraph[end].isWhitespace() && endsSentence(paragraph, i, end)) {
                    val s = paragraph.substring(start, end).trim()
                    if (s.isNotEmpty()) out += s
                    while (end < n && paragraph[end].isWhitespace()) end++
                    start = end
                    i = end
                    continue
                }
                i = end
                continue
            }
            i++
        }
        val tail = paragraph.substring(start).trim()
        if (tail.isNotEmpty()) out += tail
        return out
    }

    /**
     * Whether the terminator run ending just before [end] (whitespace follows) closes a sentence.
     * [dot] is the index of the first terminator in the run.
     */
    private fun endsSentence(p: String, dot: Int, end: Int): Boolean {
        // Next visible character: a lowercase continuation means "e.g. this" / "etc. and so on".
        var next = end
        while (next < p.length && p[next].isWhitespace()) next++
        if (next < p.length) {
            var look = next
            while (look < p.length && p[look] in "\"'“‘(["
            ) look++
            if (look < p.length && p[look].isLowerCase() && p[dot] == '.') return false
        }
        if (p[dot] != '.') return true // ! ? … are decisive
        // Word before the period.
        var ws = dot
        while (ws > 0 && !p[ws - 1].isWhitespace()) ws--
        val word = p.substring(ws, dot).trim { !it.isLetterOrDigit() && it != '.' }.lowercase()
        if (word.isEmpty()) return true
        if (word in ABBREVIATIONS) return false
        // Single initial ("J. Smith") but not a one-letter word that legitimately ends a sentence
        // like "I." -- an uppercase single letter followed by an uppercase word is ambiguous; we
        // keep initials together, the cheaper mistake (a slightly longer chunk).
        if (word.length == 1 && word[0].isLetter() && p[ws].isUpperCase() && word != "i") return false
        return true
    }

    // ---- over-long sentences ---------------------------------------------------------------

    /** Splits one sentence longer than [maxChars]: clauses, then words, then (last resort) characters. */
    private fun splitOverlong(sentence: String, maxChars: Int): List<String> {
        val clauses = splitAfter(sentence, setOf(',', ';', ':'), maxChars)
        val out = ArrayList<String>()
        var current = StringBuilder()
        for (clause in clauses) {
            val pieces = if (clause.length > maxChars) splitWords(clause, maxChars) else listOf(clause)
            for (piece in pieces) {
                if (current.isNotEmpty() && current.length + 1 + piece.length > maxChars) {
                    out += current.toString()
                    current = StringBuilder()
                }
                if (current.isNotEmpty()) current.append(' ')
                current.append(piece)
            }
        }
        if (current.isNotEmpty()) out += current.toString()
        return out
    }

    /** Cuts after each of [marks] that is followed by a space, keeping the mark with its clause. */
    private fun splitAfter(s: String, marks: Set<Char>, @Suppress("UNUSED_PARAMETER") maxChars: Int): List<String> {
        val out = ArrayList<String>()
        var start = 0
        for (i in 0 until s.length - 1) {
            if (s[i] in marks && s[i + 1].isWhitespace()) {
                val piece = s.substring(start, i + 1).trim()
                if (piece.isNotEmpty()) out += piece
                start = i + 1
            }
        }
        val tail = s.substring(start).trim()
        if (tail.isNotEmpty()) out += tail
        return out
    }

    private fun splitWords(s: String, maxChars: Int): List<String> {
        val out = ArrayList<String>()
        var rest = s.trim()
        while (rest.length > maxChars) {
            val cut = pickWordCut(rest, maxChars)
            val piece = rest.substring(0, cut).trim()
            if (piece.isNotEmpty()) out += piece
            rest = rest.substring(cut).trim()
        }
        if (rest.isNotEmpty()) out += rest
        return out
    }

    /**
     * Where to cut [rest] (longer than [maxChars]) when there is no punctuation to cut at -- the
     * normal case for an ASR model that emits no sentence punctuation. In order of preference:
     *  - a space in the last 40% of the window that is followed by a capitalised word other than
     *    "I": ASR output that drops its periods typically still capitalises where one belonged;
     *  - otherwise the last space in the window;
     * and never a space in the middle of a spoken number ("three hundred | forty thousand"), which
     * would turn one amount into two when each side is normalised on its own.
     * Falls back to cutting mid-token only when no usable space exists, and never inside a
     * surrogate pair.
     */
    private fun pickWordCut(rest: String, maxChars: Int): Int {
        val floor = maxChars / 2
        val preferredFloor = maxChars * 6 / 10
        var fallback = -1
        var numberSplitFallback = -1
        var i = rest.lastIndexOf(' ', maxChars)
        while (i >= floor) {
            if (splitsNumberRun(rest, i)) {
                if (numberSplitFallback < 0) numberSplitFallback = i
            } else {
                if (fallback < 0) fallback = i
                if (i >= preferredFloor && startsCapitalised(rest, i + 1)) return i
            }
            if (i == 0) break
            i = rest.lastIndexOf(' ', i - 1)
        }
        if (fallback >= 0) return fallback
        if (numberSplitFallback >= 0) return numberSplitFallback
        var cut = maxChars
        if (Character.isHighSurrogate(rest[cut - 1])) cut--
        return cut
    }

    private fun startsCapitalised(s: String, from: Int): Boolean {
        if (from >= s.length || !s[from].isUpperCase()) return false
        var end = from
        while (end < s.length && s[end].isLetterOrDigit()) end++
        return s.substring(from, end) != "I"
    }

    private val NUMBER_WORDS = setOf(
        "zero", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten",
        "eleven", "twelve", "thirteen", "fourteen", "fifteen", "sixteen", "seventeen", "eighteen",
        "nineteen", "twenty", "thirty", "forty", "fifty", "sixty", "seventy", "eighty", "ninety",
        "hundred", "thousand", "million", "billion",
    )

    private fun wordAt(s: String, from: Int, forward: Boolean): String {
        if (forward) {
            var start = from
            while (start < s.length && s[start] == ' ') start++
            var end = start
            while (end < s.length && s[end] != ' ') end++
            return s.substring(start, end).trim { !it.isLetterOrDigit() }.lowercase()
        }
        var end = from
        while (end > 0 && s[end - 1] == ' ') end--
        var start = end
        while (start > 0 && s[start - 1] != ' ') start--
        return s.substring(start, end).trim { !it.isLetterOrDigit() }.lowercase()
    }

    /** True when cutting at the space at [space] would separate two halves of one spoken number. */
    private fun splitsNumberRun(s: String, space: Int): Boolean {
        val before = wordAt(s, space, forward = false)
        val after = wordAt(s, space + 1, forward = true)
        if (before in NUMBER_WORDS && after in NUMBER_WORDS) return true
        // "four hundred | and fifty" and "four hundred and | fifty"
        if (before in NUMBER_WORDS && after == "and") {
            val afterAnd = s.indexOf(' ', s.indexOf(after, space + 1) + after.length)
            return afterAnd >= 0 && wordAt(s, afterAnd + 1, forward = true) in NUMBER_WORDS
        }
        if (before == "and" && after in NUMBER_WORDS) {
            val andStart = s.lastIndexOf(' ', space - 1)
            return andStart >= 0 && wordAt(s, andStart, forward = false) in NUMBER_WORDS
        }
        return false
    }
}
