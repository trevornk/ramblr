package com.kafkasl.phonewhisper

import android.content.Context
import android.content.SharedPreferences

/**
 * Off-by-default "debug/visibility" toggle (#33). ADR-0001 refers to a toggle like this as if it
 * already existed -- it didn't; this is that toggle. Gates niceties that are informative but
 * noisy for everyday use, starting with dictation history's "paid fallback" badge (see
 * [CleanupStepGroup.isPaidFallback]). Backed by the same "phonewhisper" prefs file as
 * [PreviewBeforeInjectToggle] and the other Settings switches.
 */
object DebugVisibilityToggle {
    private const val PREFS_NAME = "phonewhisper"
    const val KEY = "debug_visibility_enabled"
    private const val DEFAULT = false

    fun isEnabled(prefs: SharedPreferences): Boolean = prefs.getBoolean(KEY, DEFAULT)

    fun setEnabled(prefs: SharedPreferences, enabled: Boolean) {
        prefs.edit().putBoolean(KEY, enabled).apply()
    }

    fun isEnabled(context: Context): Boolean = isEnabled(prefs(context))

    fun setEnabled(context: Context, enabled: Boolean) = setEnabled(prefs(context), enabled)

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
