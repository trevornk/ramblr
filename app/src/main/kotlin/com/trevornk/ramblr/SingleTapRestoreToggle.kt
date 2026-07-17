package com.trevornk.ramblr

import android.content.Context
import android.content.SharedPreferences

/**
 * Off-by-default toggle (#119) for whether a single tap on a currently-peeked ring both restores
 * it to full visibility AND starts/stops recording in one motion, instead of the default
 * two-step interaction (first tap only restores; a second, separate tap is needed to actually
 * start recording).
 *
 * Defaults to false because the two-tap behavior is the established interaction this app has
 * always had -- flipping the default would silently change behavior for existing users. Enabling
 * this does not change how tap vs. long-press vs. drag are disambiguated while peeked (see
 * WhisperAccessibilityService's overlay touch listener): with this on, a touch-down on a peeked
 * ring simply stops short-circuiting into an early return, and instead flows through the exact
 * same tap/long-press/drag resolution used for a normal (non-peeked) touch, with the restore
 * triggered as a side effect of that same gesture.
 *
 * Backed by the same "ramblr" prefs file as [AutoPeekToggle]/[HideIconToggle]/
 * [PerAppPersonaToggle]/[DebugVisibilityToggle], following the same pattern.
 */
object SingleTapRestoreToggle {
    private const val PREFS_NAME = "ramblr"
    const val KEY = "single_tap_restore_and_record_enabled"
    private const val DEFAULT = false

    fun isEnabled(prefs: SharedPreferences): Boolean = prefs.getBoolean(KEY, DEFAULT)

    fun setEnabled(prefs: SharedPreferences, enabled: Boolean) {
        prefs.edit().putBoolean(KEY, enabled).apply()
    }

    fun isEnabled(context: Context): Boolean = isEnabled(prefs(context))

    fun setEnabled(context: Context, enabled: Boolean) = setEnabled(prefs(context), enabled)

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
