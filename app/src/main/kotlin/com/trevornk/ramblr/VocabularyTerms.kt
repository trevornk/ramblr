package com.trevornk.ramblr

/**
 * Personal vocabulary: project names and jargon the cleanup model tends to mishear, editable in
 * Settings instead of hardcoded into the prompt (see #26). Terms are stored in prefs as a
 * newline-delimited string — they aren't secrets, so plain prefs is fine.
 */
object VocabularyTerms {
    /** Seeded into prefs on first run so existing behavior doesn't regress (see #26). */
    val DEFAULTS = listOf(
        "Solveit", "fast.ai", "Answer.AI", "nbdev", "fastcore", "FastHTML", "Pi", "Codex", "Claude Code", "Hetzner"
    )

    val DEFAULT_SERIALIZED = serialize(DEFAULTS)

    /**
     * Parses the newline-delimited prefs value into a clean term list: blank lines dropped,
     * entries trimmed, and duplicates (case-insensitive) removed while keeping the first-seen
     * spelling and order.
     */
    fun parse(raw: String?): List<String> {
        if (raw.isNullOrBlank()) return emptyList()
        val seen = HashSet<String>()
        return raw.split("\n")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .filter { seen.add(it.lowercase()) }
    }

    /** Serializes a term list back to the newline-delimited prefs format. */
    fun serialize(terms: List<String>): String = terms.joinToString("\n")

    /**
     * Renders [terms] as a comma-joined prompt string for transcription-stage vocabulary
     * biasing (#114): OpenAI's `/v1/audio/transcriptions` `prompt` field and Gemini's
     * inline-audio prompt both accept free text that nudges decoding toward specific words --
     * a plain comma-joined list is the documented pattern for both APIs, distinct from
     * [PostProcessor.vocabularyClause]'s full sentence used for cleanup-stage prompts. Returns
     * an empty string when [terms] is empty so callers can skip sending anything.
     */
    fun asTranscriptionPrompt(terms: List<String>): String = terms.joinToString(", ")
}
