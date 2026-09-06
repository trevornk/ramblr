package com.trevornk.ramblr

/**
 * Voice-triggered canned text (#248): a user-configured (trigger phrase -> canned expansion)
 * pair. Speaking the trigger during dictation replaces it with the expansion -- useful for
 * addresses, signatures, and other boilerplate the user would otherwise have to fully dictate
 * every time.
 *
 * [key] is a stable random identifier (never derived from the trigger text) so renaming a
 * trigger never breaks anything keyed on it, mirroring [CustomPersonaStore]'s key convention.
 */
data class SnippetEntry(
    val key: String,
    val trigger: String,
    val expansion: String,
)

/** Why a candidate (trigger, expansion) pair was rejected by [SnippetEntry.validate]. */
enum class SnippetValidationError {
    BLANK_TRIGGER,
    BLANK_EXPANSION,
    TRIGGER_HAS_NO_WORD_CHARACTERS,
    TRIGGER_TOO_LONG,
    TRIGGER_TOO_MANY_WORDS,
    EXPANSION_TOO_LONG,
    DUPLICATE_TRIGGER,
}

object SnippetEntryValidation {
    /** Caps chosen to keep [SnippetExpander]'s per-dictation matching cost bounded and
     *  predictable regardless of how many snippets a user configures -- see its kdoc's
     *  "deterministic bounded matching" contract. A trigger is spoken text, so it stays short;
     *  an expansion is meant for addresses/signatures/boilerplate, so it gets much more room. */
    const val MAX_TRIGGER_CHARS = 60
    const val MAX_TRIGGER_WORDS = 8
    const val MAX_EXPANSION_CHARS = 4000

    /**
     * Validates a candidate (trigger, expansion) pair before it is stored. [existingTriggers]
     * is every OTHER configured trigger's normalized form (see [SnippetExpander.normalizeWords]),
     * used to reject a duplicate that would otherwise make matching arbitrarily pick whichever
     * entry happens to load first -- see [SnippetExpander]'s ambiguity-avoidance contract.
     * Returns null when the pair is acceptable.
     */
    fun validate(trigger: String, expansion: String, existingTriggers: Set<List<String>>): SnippetValidationError? {
        val trimmedTrigger = trigger.trim()
        val trimmedExpansion = expansion.trim()
        return when {
            trimmedTrigger.isEmpty() -> SnippetValidationError.BLANK_TRIGGER
            trimmedExpansion.isEmpty() -> SnippetValidationError.BLANK_EXPANSION
            trimmedTrigger.length > MAX_TRIGGER_CHARS -> SnippetValidationError.TRIGGER_TOO_LONG
            trimmedExpansion.length > MAX_EXPANSION_CHARS -> SnippetValidationError.EXPANSION_TOO_LONG
            else -> {
                val words = SnippetExpander.normalizeWords(trimmedTrigger)
                when {
                    words.isEmpty() -> SnippetValidationError.TRIGGER_HAS_NO_WORD_CHARACTERS
                    words.size > MAX_TRIGGER_WORDS -> SnippetValidationError.TRIGGER_TOO_MANY_WORDS
                    words in existingTriggers -> SnippetValidationError.DUPLICATE_TRIGGER
                    else -> null
                }
            }
        }
    }

    /** User-facing copy for the editor dialog's inline error. */
    fun message(error: SnippetValidationError): String = when (error) {
        SnippetValidationError.BLANK_TRIGGER -> "Trigger phrase can't be empty"
        SnippetValidationError.BLANK_EXPANSION -> "Expansion text can't be empty"
        SnippetValidationError.TRIGGER_HAS_NO_WORD_CHARACTERS -> "Trigger phrase needs at least one letter or digit"
        SnippetValidationError.TRIGGER_TOO_LONG -> "Trigger phrase is too long (max $MAX_TRIGGER_CHARS characters)"
        SnippetValidationError.TRIGGER_TOO_MANY_WORDS -> "Trigger phrase is too long (max $MAX_TRIGGER_WORDS words)"
        SnippetValidationError.EXPANSION_TOO_LONG -> "Expansion is too long (max $MAX_EXPANSION_CHARS characters)"
        SnippetValidationError.DUPLICATE_TRIGGER -> "Another snippet already uses that trigger phrase"
    }
}
