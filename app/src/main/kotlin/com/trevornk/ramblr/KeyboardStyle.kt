package com.trevornk.ramblr

import android.content.Context

/**
 * The keyboard's style list: "As is" (no AI cleanup) plus the styles chosen for the floating
 * icon's long-press menu. A tap writes exactly what that menu writes (the cleanup switch, the
 * "cleanup_style"/"post_processing_prompt" pair, the per-app memory), so the keyboard, the menu
 * and Settings always agree on what is selected.
 */
internal object KeyboardStyle {
    const val AS_IS = "as_is"
    const val AS_IS_TITLE = "As is"
    const val AS_IS_SUBTITLE = "No AI cleanup. Inserts exactly what was recognized"

    data class Option(val key: String, val title: String, val subtitle: String)

    enum class Selection { SELECTED, NEEDS_CONSENT }

    /** "As is", then the quick-menu styles. The current style is added if the quick menu lacks it,
     *  so the selected one is always listed. */
    fun options(context: Context, packageName: String?): List<Option> {
        val personas = QuickMenuPersonaStore.load(context).map { PersonaRegistry.resolve(context, it) }
        val current = currentPersona(context, packageName)
        val shown = if (personas.any { it.key == current.key }) personas else listOf(current) + personas
        return listOf(Option(AS_IS, AS_IS_TITLE, AS_IS_SUBTITLE)) + shown.map { Option(it.key, it.title, it.subtitle) }
    }

    fun selectedKey(context: Context, packageName: String?): String =
        if (!PostProcessingToggle.isEnabled(context)) AS_IS else currentPersona(context, packageName).key

    /** Same resolution live dictation uses: the per-app memory when that feature is on, else the
     *  global style. */
    fun currentPersona(context: Context, packageName: String?): CleanupPersona {
        val prefs = prefs(context)
        val savedKey = prefs.getString("cleanup_style", null)
        val global = PersonaRegistry.currentPersona(
            context, savedKey, PostProcessor.effectiveSavedPrompt(prefs.getString("post_processing_prompt", null), savedKey)
        )
        return if (PerAppPersonaToggle.isEnabled(context)) PerAppPersonaStore.resolvePersona(context, packageName, global)
            else global
    }

    fun select(context: Context, packageName: String?, key: String): Selection {
        if (key == AS_IS) {
            PostProcessingToggle.setEnabled(context, false)
            return Selection.SELECTED
        }
        val persona = PersonaRegistry.resolve(context, key)
        if (PerAppPersonaToggle.isEnabled(context)) PerAppPersonaStore.record(context, packageName, persona)
        prefs(context).edit()
            .putString("cleanup_style", persona.key)
            .putString("post_processing_prompt", CleanupPersonas.promptForExplicitSelection(persona))
            .apply()
        return if (CleanupSwitch.ensureEnabled(context)) Selection.SELECTED else Selection.NEEDS_CONSENT
    }

    private fun prefs(context: Context) = context.getSharedPreferences("ramblr", Context.MODE_PRIVATE)
}

/** Turning cleanup on from outside Settings (the floating icon's style menu, the keyboard). */
internal object CleanupSwitch {
    /** Turns cleanup on unless the local-transcription consent (#23) is still owed, which only
     *  Settings may collect. Returns whether cleanup ended up on. */
    fun ensureEnabled(context: Context): Boolean {
        if (PostProcessingToggle.isEnabled(context)) return true
        val prefs = context.getSharedPreferences("ramblr", Context.MODE_PRIVATE)
        val useLocal = prefs.getBoolean("use_local", true)
        val hasConsented = prefs.getBoolean("local_cleanup_consent_seen", false)
        val cleanupIsLocalOnly = !CloudFeatureToggle.cleanupEnabled(context)
        if (LocalCleanupConsent.shouldPrompt(useLocal, usePostProcessing = true, hasConsented = hasConsented, cleanupIsLocalOnly = cleanupIsLocalOnly)) return false
        PostProcessingToggle.setEnabled(context, true)
        return true
    }
}
