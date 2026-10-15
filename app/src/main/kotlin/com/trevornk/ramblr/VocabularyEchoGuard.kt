package com.trevornk.ramblr

/**
 * Deterministic guard against a transcription model answering with its own vocabulary context
 * instead of the speech (#306).
 *
 * Every cloud transcription path hands the user's Personal Vocabulary to a model that can echo it
 * back: Gemini's inline prompt, OpenAI's `prompt` / `keywords[]`, the Interactions API's
 * `custom_vocabulary` and the Live API's `customVocabulary`. Given silence or near-silence an
 * audio-LLM (and Whisper-family models, on their `prompt`) has nothing to transcribe and returns
 * the context instead -- on a real device the whole vocabulary list, comma-joined, was inserted
 * into a text field. [LocalCleanupOutputValidator] already rejects the same failure for local
 * cleanup (#164/#182); this is the transcription-stage equivalent.
 *
 * A transcript is an echo when either
 *  - it consists *entirely* of vocabulary terms -- at least [MIN_LIST_ITEMS] of them, each a term
 *    from the list, in list order (contiguous or with gaps), separated by commas, newlines,
 *    bullets or plain spaces; or
 *  - it reproduces a run of [PROMPT_ECHO_MIN_WORDS]+ consecutive words of the prompt that was sent.
 *
 * False-positive stance: real speech essentially never produces three or more vocabulary terms,
 * in list order, and nothing else. A single term ("Hetzner"), two terms, a sentence containing
 * vocabulary words, or a list with any non-vocabulary word in it is never an echo. The cost of a
 * false positive is one discarded dictation ("No speech detected"); the cost of a false negative
 * is junk typed into the user's field, which is the bug.
 */
object VocabularyEchoGuard {
    /** Fewer items than this is indistinguishable from someone dictating one or two names. */
    const val MIN_LIST_ITEMS = 3

    /** Consecutive prompt words that, if reproduced, cannot be coincidence in spoken text. */
    const val PROMPT_ECHO_MIN_WORDS = 8

    private val WHITESPACE = Regex("\\s+")
    private val WORD = Regex("[\\p{L}\\p{N}]+")
    private val LIST_MARKER = Regex("[-*\u2022\u2013\u2014]+|\\d{1,2}[.)]")

    /**
     * True when [text] is the vocabulary list (or prompt text) coming back instead of speech.
     * [terms] is the ordered list sent to the model; [promptText] is the full prompt when the
     * path has one (Gemini batch), so reproduced instruction text is caught too. An empty [terms]
     * never matches: with no vocabulary there is nothing to echo.
     */
    fun isEcho(text: String?, terms: List<String>, promptText: String? = null): Boolean {
        if (text.isNullOrBlank() || terms.isEmpty()) return false
        return isVocabularyList(text, terms) || reproducesPrompt(text, promptText)
    }

    private fun canonical(s: String): String = s.trim().lowercase().replace(WHITESPACE, " ")

    private fun isVocabularyList(text: String, terms: List<String>): Boolean {
        val vocabulary = terms.map(::canonical).filter { it.isNotEmpty() }
        if (vocabulary.size < MIN_LIST_ITEMS) return false
        val maxWords = vocabulary.maxOf { it.count { c -> c == ' ' } + 1 }
        val tokens = canonical(text.replace(',', ' ').replace(';', ' ')).split(' ').filter { it.isNotEmpty() && !LIST_MARKER.matches(it) }

        var position = 0
        var lastIndex = -1
        var items = 0
        while (position < tokens.size) {
            var matchedIndex = -1
            var consumed = 0
            for (width in minOf(maxWords, tokens.size - position) downTo 1) {
                val candidate = tokens.subList(position, position + width).joinToString(" ")
                val index = indexOfTermAfter(vocabulary, candidate, lastIndex)
                if (index >= 0) { matchedIndex = index; consumed = width; break }
            }
            if (matchedIndex < 0) return false // a non-vocabulary word: this is speech, not an echo
            lastIndex = matchedIndex
            position += consumed
            items++
        }
        return items >= MIN_LIST_ITEMS
    }

    /** Index (> [after]) of the term equal to [candidate] once list punctuation is stripped. A
     *  trailing `.` is tried both kept and dropped so `fast.ai` and "...Hetzner." both resolve. */
    private fun indexOfTermAfter(vocabulary: List<String>, candidate: String, after: Int): Int {
        val stripped = candidate.trim { it in ",;:\"'()[]" }
        for (variant in listOf(stripped, stripped.trimEnd('.')).distinct()) {
            if (variant.isEmpty()) continue
            for (i in after + 1 until vocabulary.size) if (vocabulary[i] == variant) return i
        }
        return -1
    }

    private fun reproducesPrompt(text: String, promptText: String?): Boolean {
        if (promptText.isNullOrBlank()) return false
        val prompt = WORD.findAll(promptText.lowercase()).map { it.value }.toList()
        val output = WORD.findAll(text.lowercase()).map { it.value }.toList()
        if (prompt.size < PROMPT_ECHO_MIN_WORDS || output.size < PROMPT_ECHO_MIN_WORDS) return false
        val promptRuns = HashSet<List<String>>()
        for (i in 0..prompt.size - PROMPT_ECHO_MIN_WORDS) promptRuns.add(prompt.subList(i, i + PROMPT_ECHO_MIN_WORDS))
        for (i in 0..output.size - PROMPT_ECHO_MIN_WORDS) {
            if (output.subList(i, i + PROMPT_ECHO_MIN_WORDS) in promptRuns) return true
        }
        return false
    }
}
