package com.kafkasl.phonewhisper

import android.content.Context

/**
 * Unified persona lookup across [CleanupPersonas.BUILT_IN] and [CustomPersonaStore] (#103 Style
 * manager). Existing call sites that only ever dealt with built-ins (e.g.
 * [CleanupPersonas.fromKey]) are left alone for their built-in-only use cases and tests; this is
 * the one new entry point that also finds a user-authored (or seeded-legacy) custom persona, so
 * MainActivity's Style manager, the long-press quick menu, and live dictation's per-app/global
 * persona resolution all agree on the full set instead of three separate partial lookups.
 */
object PersonaRegistry {
    /** Every persona the user could currently have selected, built-in first, custom personas
     *  after in the order they were created -- the order the Style manager list displays them in. */
    fun all(context: Context): List<CleanupPersona> = CleanupPersonas.BUILT_IN + CustomPersonaStore.load(context)

    /** Resolves [key] against custom personas first (so an edited/seeded-legacy persona's current
     *  title/prompt wins), then built-ins/legacy-retired constants, falling back to
     *  [CleanupPersonas.DEFAULT] for an unknown or deleted key -- mirrors
     *  [CleanupPersonas.fromKey]'s "never crash on a stale key" contract. */
    fun resolve(context: Context, key: String?): CleanupPersona {
        if (key == null) return CleanupPersonas.DEFAULT
        return CustomPersonaStore.fromKey(context, key) ?: CleanupPersonas.fromKey(key)
    }

    /**
     * Custom-persona-aware equivalent of [CleanupPersonas.currentPersona]: the saved key wins if
     * present (resolved via [resolve], so it finds custom/seeded-legacy personas too), otherwise
     * falls back to inferring from whichever built-in prompt matches [currentPrompt] -- preserves
     * the pre-#103 upgrade-path behavior for installs that predate the "cleanup_style" key
     * entirely, where there is no saved key to resolve in the first place.
     */
    fun currentPersona(context: Context, savedKey: String?, currentPrompt: String): CleanupPersona {
        if (savedKey != null) return resolve(context, savedKey)
        return CleanupPersonas.BUILT_IN.firstOrNull { it.prompt == currentPrompt } ?: CleanupPersonas.DEFAULT
    }
}
