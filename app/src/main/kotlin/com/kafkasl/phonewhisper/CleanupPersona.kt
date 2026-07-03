package com.kafkasl.phonewhisper

/**
 * One selectable cleanup tone/persona: which system prompt is sent to the cleanup model.
 * [key] is the value persisted to SharedPreferences and must stay stable once shipped — existing
 * installs already have one of the original three saved under the "cleanup_style" pref key (#3).
 * [title]/[subtitle] drive the Settings picker row.
 *
 * This is deliberately a plain data class rather than an enum (#40): [CleanupPersonas.BUILT_IN]
 * is just a `List<CleanupPersona>`, so adding a persona — built-in now, user-authored later — is
 * one more list entry instead of a new enum constant plus every exhaustive `when` over it.
 */
data class CleanupPersona(
    val key: String,
    val title: String,
    val subtitle: String,
    val prompt: String,
)

/**
 * Built-in personas (#3, #40). [FORMAL]/[CASUAL]/[NOTES] are the original global tone/style
 * presets from #3, reusing [PostProcessor]'s existing prompt variants byte-for-byte — Formal is
 * the precise, structure-preserving [PostProcessor.DEV_PROMPT], Casual is the light-touch
 * [PostProcessor.SIMPLE_PROMPT], and Notes & lists is the rambling-to-list
 * [PostProcessor.STRUCTURED_PROMPT] (#1) — so existing users' saved selection resolves to an
 * identical prompt after this refactor. [GANGSTER]/[SMART]/[TEACHER] are new tone filters (#40,
 * Trevor's suggestions) layered the same way.
 */
object CleanupPersonas {
    val FORMAL = CleanupPersona(
        key = "formal",
        title = "Formal",
        subtitle = "Precise grammar and punctuation — best for coding, CLI, and project names",
        prompt = PostProcessor.DEV_PROMPT,
    )
    val CASUAL = CleanupPersona(
        key = "casual",
        title = "Casual",
        subtitle = "Grammar, punctuation, and light cleanup",
        prompt = PostProcessor.SIMPLE_PROMPT,
    )
    val NOTES = CleanupPersona(
        key = "notes",
        title = "Notes & lists",
        subtitle = "Removes filler, resolves self-corrections, turns rambling into lists",
        prompt = PostProcessor.STRUCTURED_PROMPT,
    )
    val GANGSTER = CleanupPersona(
        key = "gangster",
        title = "Gangster",
        subtitle = "Rewrites the cleaned-up text in a playful street/gangster voice",
        prompt = PostProcessor.GANGSTER_PROMPT,
    )
    val SMART = CleanupPersona(
        key = "smart",
        title = "Smart",
        subtitle = "Rewrites the cleaned-up text to sound more articulate and intelligent",
        prompt = PostProcessor.SMART_PROMPT,
    )
    val TEACHER = CleanupPersona(
        key = "teacher",
        title = "Teacher",
        subtitle = "Rewrites the cleaned-up text the way a patient teacher would explain it",
        prompt = PostProcessor.TEACHER_PROMPT,
    )

    /** Every built-in persona, in the order shown in Settings. */
    val BUILT_IN: List<CleanupPersona> = listOf(FORMAL, CASUAL, NOTES, GANGSTER, SMART, TEACHER)

    val DEFAULT = FORMAL

    fun fromKey(key: String?): CleanupPersona = BUILT_IN.firstOrNull { it.key == key } ?: DEFAULT

    /**
     * Resolves the prompt that should actually be sent to the cleanup model. An explicit
     * custom prompt always wins over the persona selector, so users who already customized
     * their prompt see no behavior change from the addition of personas (#3, #40).
     */
    fun resolvePrompt(persona: CleanupPersona, customPrompt: String?): String =
        if (!customPrompt.isNullOrBlank() && customPrompt != PostProcessor.DEFAULT_PROMPT) customPrompt
        else persona.prompt
}
