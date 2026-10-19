package com.trevornk.ramblr

import android.content.Context

/**
 * Host-independent model + writes behind the cleanup-style menu (#312).
 *
 * Two surfaces let the user toggle cleanup on/off and pick a style without opening Settings: the
 * floating ring's long-press menu ([WhisperAccessibilityService]) and the Ramblr Voice keyboard's
 * style picker ([RamblrImeService]). Both go through this object so they read and write the SAME
 * prefs (`use_post_processing`, `cleanup_style`, `post_processing_prompt`, the per-app memory) and
 * can never disagree. No pref key is defined here: each one is owned by the store it already
 * belonged to ([PostProcessingToggle], [PerAppPersonaStore], [QuickMenuPersonaStore]); the two
 * literal keys below are the ones MainActivity's Settings picker also writes (see
 * `MainActivity.selectPrompt`).
 *
 * Context only: no window, view or service dependency, so it works from an AccessibilityService, an
 * InputMethodService, and unit tests alike.
 */
object CleanupStyleMenu {
    private const val PREFS_NAME = "ramblr"
    private const val KEY_STYLE = "cleanup_style"
    private const val KEY_PROMPT = "post_processing_prompt"

    /** What a menu needs to render: whether cleanup runs, which style is current, which to offer. */
    data class Model(
        val cleanupEnabled: Boolean,
        val current: CleanupPersona,
        val personas: List<CleanupPersona>,
    )

    enum class ToggleResult { TURNED_ON, TURNED_OFF, NEEDS_SETTINGS }

    data class Selection(val persona: CleanupPersona, val cleanupEnabled: Boolean)

    /**
     * [packageName] is the app the dictation will land in (the ring's foreground window, or the
     * keyboard's bound editor). With per-app memory on, that app's remembered style wins over the
     * global one -- exactly the rule DictationRuntime applies when cleanup actually runs.
     */
    fun load(context: Context, packageName: String?): Model {
        val prefs = prefs(context)
        val savedKey = prefs.getString(KEY_STYLE, null)
        val global = PersonaRegistry.currentPersona(
            context,
            savedKey,
            PostProcessor.effectiveSavedPrompt(prefs.getString(KEY_PROMPT, null), savedKey),
        )
        val current = if (PerAppPersonaToggle.isEnabled(context)) {
            PerAppPersonaStore.resolvePersona(context, packageName, global)
        } else {
            global
        }
        return Model(
            cleanupEnabled = PostProcessingToggle.isEnabled(context),
            current = current,
            personas = QuickMenuPersonaStore.load(context).map { PersonaRegistry.resolve(context, it) },
        )
    }

    /**
     * Cleanup on/off (#53). Turning it OFF is always instant. Turning it ON respects the local
     * transcription + cleanup consent gate (#23): if consent hasn't been given it is refused with
     * [ToggleResult.NEEDS_SETTINGS] instead of silently sending local transcripts off-device.
     */
    fun toggleCleanup(context: Context): ToggleResult {
        if (PostProcessingToggle.isEnabled(context)) {
            PostProcessingToggle.setEnabled(context, false)
            return ToggleResult.TURNED_OFF
        }
        return if (ensureCleanupEnabled(context)) ToggleResult.TURNED_ON else ToggleResult.NEEDS_SETTINGS
    }

    /**
     * Persona/style switch (#40, #53, #57): writes the exact two prefs Settings' picker writes, and
     * remembers the choice for [packageName] when per-app memory is on. Picking a style is also a
     * request to hear it, so cleanup is turned on too -- unless the consent gate blocks it, in
     * which case the style is still saved (it takes effect once cleanup is enabled) and
     * [Selection.cleanupEnabled] is false so the caller can say so.
     */
    fun select(context: Context, packageName: String?, persona: CleanupPersona): Selection {
        if (PerAppPersonaToggle.isEnabled(context)) {
            PerAppPersonaStore.record(context, packageName, persona)
        }
        prefs(context).edit()
            .putString(KEY_STYLE, persona.key)
            .putString(KEY_PROMPT, CleanupPersonas.promptForExplicitSelection(persona))
            .apply()
        return Selection(persona, ensureCleanupEnabled(context))
    }

    /** Turns cleanup on if it isn't already, behind the same consent gate (#23). Returns whether
     *  cleanup ended up enabled -- true if already on or turning it on succeeded. */
    fun ensureCleanupEnabled(context: Context): Boolean {
        if (PostProcessingToggle.isEnabled(context)) return true
        val prefs = prefs(context)
        val useLocal = prefs.getBoolean("use_local", true)
        val hasConsented = prefs.getBoolean("local_cleanup_consent_seen", false)
        val cleanupIsLocalOnly = !CloudFeatureToggle.cleanupEnabled(context)
        if (LocalCleanupConsent.shouldPrompt(
                useLocal, usePostProcessing = true, hasConsented = hasConsented, cleanupIsLocalOnly = cleanupIsLocalOnly,
            )
        ) return false
        PostProcessingToggle.setEnabled(context, true)
        return true
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
