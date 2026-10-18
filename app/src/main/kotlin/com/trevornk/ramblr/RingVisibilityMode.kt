package com.trevornk.ramblr

import android.content.Context
import android.content.SharedPreferences

/**
 * When the floating ring is on screen (see [ringHiddenByVisibilityMode]). [ALWAYS] is the
 * long-standing default. [KEYBOARD] shows it only while an on-screen keyboard window is up.
 * [TEXT_FIELD] keeps it up for as long as a text field holds input focus, so dismissing the
 * keyboard with Back while the cursor is still in the field doesn't take the ring away with it.
 */
enum class RingVisibilityMode {
    ALWAYS,
    KEYBOARD,
    TEXT_FIELD;

    companion object {
        private const val PREFS_NAME = "ramblr"
        const val KEY = "ring_visibility_mode"

        fun read(prefs: SharedPreferences): RingVisibilityMode {
            val stored = prefs.getString(KEY, null) ?: return ALWAYS
            return entries.firstOrNull { it.name == stored } ?: ALWAYS
        }

        fun write(prefs: SharedPreferences, mode: RingVisibilityMode) {
            prefs.edit().putString(KEY, mode.name).apply()
        }

        fun read(context: Context): RingVisibilityMode = read(prefs(context))

        fun write(context: Context, mode: RingVisibilityMode) = write(prefs(context), mode)

        private fun prefs(context: Context) = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }
}
