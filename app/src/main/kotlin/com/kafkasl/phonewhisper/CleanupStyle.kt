package com.kafkasl.phonewhisper

/**
 * Global tone/style presets for the cleanup prompt (see #3). Each style reuses one of the
 * existing prompt variants from [PostProcessor] rather than duplicating prompt content: Formal
 * reuses the precise, structure-preserving [PostProcessor.DEV_PROMPT], Casual reuses the
 * light-touch [PostProcessor.SIMPLE_PROMPT], and Notes & lists reuses the rambling-to-list
 * [PostProcessor.STRUCTURED_PROMPT] (#1). Per-app auto-mapping is out of scope for this pass —
 * this is a single global default, selected in Settings.
 */
enum class CleanupStyle(val key: String, val title: String, val subtitle: String) {
    FORMAL(
        key = "formal",
        title = "Formal",
        subtitle = "Precise grammar and punctuation — best for coding, CLI, and project names"
    ),
    CASUAL(
        key = "casual",
        title = "Casual",
        subtitle = "Grammar, punctuation, and light cleanup"
    ),
    NOTES(
        key = "notes",
        title = "Notes & lists",
        subtitle = "Removes filler, resolves self-corrections, turns rambling into lists"
    );

    val prompt: String
        get() = when (this) {
            FORMAL -> PostProcessor.DEV_PROMPT
            CASUAL -> PostProcessor.SIMPLE_PROMPT
            NOTES -> PostProcessor.STRUCTURED_PROMPT
        }

    companion object {
        val DEFAULT = FORMAL

        fun fromKey(key: String?): CleanupStyle = entries.firstOrNull { it.key == key } ?: DEFAULT

        /**
         * Resolves the prompt that should actually be sent to the cleanup model. An explicit
         * custom prompt always wins over the style selector, so users who already customized
         * their prompt see no behavior change from the addition of styles (#3).
         */
        fun resolvePrompt(style: CleanupStyle, customPrompt: String?): String =
            if (!customPrompt.isNullOrBlank() && customPrompt != PostProcessor.DEFAULT_PROMPT) customPrompt
            else style.prompt
    }
}
