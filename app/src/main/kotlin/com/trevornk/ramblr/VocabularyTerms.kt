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

    /**
     * Whether the vocabulary terms are actually applied anywhere in the user's current
     * configuration (#185, updated for #182 option 2). The terms reach three paths:
     *
     *  - cloud transcription ([TranscriberClient.vocabularyFormParts] / Gemini's inline prompt)
     *  - cloud cleanup ([PostProcessor.interpolateVocabulary] on the persona prompt)
     *  - LOCAL cleanup, as a deterministic post-pass over the model's output
     *    ([VocabularyPostCorrector], #182 option 2) -- NOT via the prompt, which made LFM2.5
     *    echo the term list; the post-pass is model-agnostic, so `mumble-cleanup-2stage` gets
     *    vocabulary support too despite declaring its own placeholder-free training prompt.
     *
     * Only local *transcription* still ignores the terms (#131 closed local-ASR hotword biasing
     * as not-planned). So the fully-local privacy configuration -- local ASR plus local cleanup
     * -- now DOES apply the vocabulary, at the cleanup stage; the terms are only inert when no
     * cleanup path is active at all on top of local transcription. The Settings UI uses this to
     * say so instead of letting users discover it from behavior.
     *
     * Each `*Active` flag must already fold in everything that path needs to actually run
     * (toggles, gates, chain entries, an installed model for local cleanup); this stays a pure
     * function of the three path-level booleans.
     */
    fun inEffect(
        cloudTranscriptionActive: Boolean,
        cloudCleanupActive: Boolean,
        localCleanupActive: Boolean,
    ): Boolean = cloudTranscriptionActive || cloudCleanupActive || localCleanupActive

    /** The user-facing note shown when [inEffect] is false (#185); null when terms do apply.
     *  Since #182's post-pass made local cleanup vocabulary-capable, this only fires when local
     *  transcription runs with no active cleanup at all -- the one remaining configuration where
     *  the terms genuinely do nothing. */
    fun localOnlyNote(
        cloudTranscriptionActive: Boolean,
        cloudCleanupActive: Boolean,
        localCleanupActive: Boolean,
    ): String? =
        if (inEffect(cloudTranscriptionActive, cloudCleanupActive, localCleanupActive)) null
        else "Your current setup is local transcription with cleanup off, so these terms are " +
            "not used: local transcription doesn't support vocabulary biasing. They apply " +
            "once cleanup is enabled."
}
