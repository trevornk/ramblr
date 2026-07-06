package com.kafkasl.phonewhisper

import android.content.Context
import android.content.SharedPreferences

/**
 * On-by-default toggle for per-app cleanup persona memory (see [PerAppPersonaStore]). Some users
 * may not want the app silently remembering/auto-switching personas per foreground app -- this
 * lets them turn that behavior off entirely and fall back to always using the one global
 * persona, same as before [PerAppPersonaStore] existed. Backed by the same "phonewhisper" prefs
 * file as [PreviewBeforeInjectToggle]/[DebugVisibilityToggle], following the same pattern.
 */
object PerAppPersonaToggle {
    private const val PREFS_NAME = "phonewhisper"
    const val KEY = "per_app_persona_enabled"
    private const val DEFAULT = true

    fun isEnabled(prefs: SharedPreferences): Boolean = prefs.getBoolean(KEY, DEFAULT)

    fun setEnabled(prefs: SharedPreferences, enabled: Boolean) {
        prefs.edit().putBoolean(KEY, enabled).apply()
    }

    fun isEnabled(context: Context): Boolean = isEnabled(prefs(context))

    fun setEnabled(context: Context, enabled: Boolean) = setEnabled(prefs(context), enabled)

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
