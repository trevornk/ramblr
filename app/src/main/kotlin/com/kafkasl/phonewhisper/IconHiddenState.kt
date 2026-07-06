package com.kafkasl.phonewhisper

import android.content.Context
import android.content.SharedPreferences

/**
 * Persisted "is the floating icon currently user-hidden right now" state (Feature B) -- a
 * deliberately separate concern from [HideIconToggle] (whether the "Hide icon" menu row is
 * offered at all). This is the actual runtime hidden/shown flag: set true when the long-press
 * menu's "Hide icon" row is tapped, set false when the restore notification (or the Advanced
 * screen fallback, for anyone who turned [HideIconToggle] off while already hidden) is used.
 * Survives service restarts/reboots -- read in WhisperAccessibilityService.onServiceConnected()
 * so a hidden icon stays hidden until the user explicitly asks for it back. Backed by the same
 * "phonewhisper" prefs file as the rest of these toggles.
 */
object IconHiddenState {
    private const val PREFS_NAME = "phonewhisper"
    const val KEY = "icon_hidden_by_user"
    private const val DEFAULT = false

    fun isHidden(prefs: SharedPreferences): Boolean = prefs.getBoolean(KEY, DEFAULT)

    fun setHidden(prefs: SharedPreferences, hidden: Boolean) {
        prefs.edit().putBoolean(KEY, hidden).apply()
    }

    fun isHidden(context: Context): Boolean = isHidden(prefs(context))

    fun setHidden(context: Context, hidden: Boolean) = setHidden(prefs(context), hidden)

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
