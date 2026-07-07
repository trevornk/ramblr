package com.trevornk.ramblr

/**
 * One selectable cleanup tone/persona: which system prompt is sent to the cleanup model.
 * [key] is the value persisted to SharedPreferences and must stay stable once shipped — existing
 * installs already have one of the original three saved under the "cleanup_style" pref key (#3).
 * [title]/[subtitle] drive the Settings picker row. [isBuiltIn] distinguishes the fixed, shipped
 * personas from user-authored ones stored in [CustomPersonaStore] (#103) -- built-ins can't be
 * deleted and editing one forks a custom copy instead of mutating it in place.
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
    val isBuiltIn: Boolean = true,
)

/**
 * Built-in personas (#3, #40, #103). [FORMAL]/[CASUAL]/[NOTES] are the original global tone/style
 * presets from #3, reusing [PostProcessor]'s existing prompt variants byte-for-byte — Formal is
 * the precise, structure-preserving [PostProcessor.DEV_PROMPT], Casual is the light-touch
 * [PostProcessor.SIMPLE_PROMPT], and Notes & lists is the rambling-to-list
 * [PostProcessor.STRUCTURED_PROMPT] (#1) — so existing users' saved selection resolves to an
 * identical prompt after this refactor. [EMAIL]/[CONCISE] are new task-oriented presets (#103)
 * added after surveying Wispr Flow / Superwhisper / Willow / Aqua's built-in modes -- every one of
 * them ships task/context presets (email, message, notes) rather than character personas, so these
 * two fill the gap FORMAL/CASUAL/NOTES don't cover.
 *
 * [GANGSTER]/[SMART]/[TEACHER] were the original #40 tone-filter built-ins; #103 demoted them to
 * [LEGACY_RETIRED] -- no longer presented as defaults, but still resolvable by key (any existing
 * install with one of these saved as "cleanup_style" keeps working unchanged) and seeded once into
 * [CustomPersonaStore] on upgrade so they remain visible/editable/deletable in the new Style
 * manager instead of silently vanishing (see [CustomPersonaStore.ensureLegacySeeded]).
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
    val EMAIL = CleanupPersona(
        key = "email",
        title = "Email",
        subtitle = "Polished, professional tone for email bodies",
        prompt = PostProcessor.EMAIL_PROMPT,
    )
    val CONCISE = CleanupPersona(
        key = "concise",
        title = "Concise",
        subtitle = "Tightens rambling into the shortest faithful version",
        prompt = PostProcessor.CONCISE_PROMPT,
    )

    // Retired from BUILT_IN by #103 -- kept as named constants so fromKey/legacy resolution and
    // the one-time custom-store seeding still have a single source of truth for their prompts.
    val GANGSTER = CleanupPersona(
        key = "gangster",
        title = "Gangster",
        subtitle = "Rewrites the cleaned-up text in a playful street/gangster voice",
        prompt = PostProcessor.GANGSTER_PROMPT,
        isBuiltIn = false,
    )
    val SMART = CleanupPersona(
        key = "smart",
        title = "Smart",
        subtitle = "Rewrites the cleaned-up text to sound more articulate and intelligent",
        prompt = PostProcessor.SMART_PROMPT,
        isBuiltIn = false,
    )
    val TEACHER = CleanupPersona(
        key = "teacher",
        title = "Teacher",
        subtitle = "Rewrites the cleaned-up text the way a patient teacher would explain it",
        prompt = PostProcessor.TEACHER_PROMPT,
        isBuiltIn = false,
    )

    /** Every built-in persona, in the order shown in Settings (#103: 5, down from 6 -- see class doc). */
    val BUILT_IN: List<CleanupPersona> = listOf(FORMAL, CASUAL, NOTES, EMAIL, CONCISE)

    /** Retired built-ins (#103): no longer shown as defaults, but still resolvable by key and
     *  seeded once into [CustomPersonaStore] so existing selections/quick-menu entries survive. */
    val LEGACY_RETIRED: List<CleanupPersona> = listOf(GANGSTER, SMART, TEACHER)

    val DEFAULT = FORMAL

    /** Built-in-only lookup by key, falling back through [LEGACY_RETIRED] before [DEFAULT] --
     *  used directly by call sites and tests that only care about shipped personas. Custom
     *  (user-authored) personas are NOT resolvable here; use [PersonaRegistry.resolve] for a
     *  lookup that also checks [CustomPersonaStore] (#103). */
    fun fromKey(key: String?): CleanupPersona =
        BUILT_IN.firstOrNull { it.key == key }
            ?: LEGACY_RETIRED.firstOrNull { it.key == key }
            ?: DEFAULT

    /**
     * Currently selected persona: the saved key wins if present, otherwise it's inferred from
     * whichever built-in prompt matches the active prompt (e.g. an install that predates #40's
     * "cleanup_style" key), falling back to [DEFAULT]. Built-in-only -- see [PersonaRegistry] for
     * the custom-persona-aware equivalent used by MainActivity's Settings picker and the overlay's
     * long-press style menu (#53) since #103.
     */
    fun currentPersona(savedKey: String?, currentPrompt: String): CleanupPersona {
        if (savedKey != null) return fromKey(savedKey)
        return BUILT_IN.firstOrNull { it.prompt == currentPrompt } ?: DEFAULT
    }

    /**
     * Resolves the prompt that should actually be sent to the cleanup model. An explicit
     * custom prompt always wins over the persona selector, so users who already customized
     * their prompt see no behavior change from the addition of personas (#3, #40).
     */
    fun resolvePrompt(persona: CleanupPersona, customPrompt: String?): String =
        if (!customPrompt.isNullOrBlank() && customPrompt != PostProcessor.DEFAULT_PROMPT) customPrompt
        else persona.prompt

    /**
     * The prompt to save when the user explicitly taps a built-in persona row in Settings (#48).
     * Unlike [resolvePrompt] -- whose "custom prompt always wins" rule exists for the Edit-prompt
     * dialog and upgrade-path persona inference -- a deliberate tap on a specific persona must
     * always take effect, even when a custom prompt is saved from an earlier, unrelated edit.
     * [MainActivity.selectPrompt] previously called [resolvePrompt] here instead, so any saved
     * custom prompt silently overrode every persona selection.
     */
    fun promptForExplicitSelection(persona: CleanupPersona): String = persona.prompt
}
