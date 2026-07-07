package com.trevornk.ramblr

import android.content.Context
import android.content.SharedPreferences

/**
 * Opt-in gate for the preview-before-inject stage (#40): when off (the default), cleanup
 * behaves exactly as before this feature existed — the cleaned-up candidate is injected
 * immediately. When on, [WhisperAccessibilityService] holds the candidate and waits for an
 * explicit commit tap (or falls back to the raw transcript on discard/timeout) before injecting.
 * Backed by the same "ramblr" prefs file [PostProcessingToggle] and the Settings switches use.
 */
object PreviewBeforeInjectToggle {
    private const val PREFS_NAME = "ramblr"
    const val KEY = "preview_before_inject_enabled"
    private const val DEFAULT = false

    fun isEnabled(prefs: SharedPreferences): Boolean = prefs.getBoolean(KEY, DEFAULT)

    fun setEnabled(prefs: SharedPreferences, enabled: Boolean) {
        prefs.edit().putBoolean(KEY, enabled).apply()
    }

    fun isEnabled(context: Context): Boolean = isEnabled(prefs(context))

    fun setEnabled(context: Context, enabled: Boolean) = setEnabled(prefs(context), enabled)

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
