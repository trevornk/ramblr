package com.trevornk.ramblr

import android.content.Context
import android.content.SharedPreferences

/**
 * On-by-default toggle for whether the floating ring auto-hides to a peeked sliver after
 * [RingPeek.IDLE_TIMEOUT_MS] of inactivity (Feature A). Turning this off in Advanced settings
 * keeps the ring fully visible at all times -- for anyone who'd rather always see it than have it
 * slide toward the edge. This is a separate, independent concern from [HideIconToggle]/
 * [IconHiddenState] (Feature B's full manual hide-to-notification path): disabling auto-peek here
 * does not affect the "Hide icon" long-press menu option or force-restore an icon already fully
 * hidden. Backed by the same "ramblr" prefs file as [HideIconToggle]/[PerAppPersonaToggle]/
 * [PreviewBeforeInjectToggle]/[DebugVisibilityToggle], following the same pattern.
 */
object AutoPeekToggle {
    private const val PREFS_NAME = "ramblr"
    const val KEY = "auto_peek_enabled"
    private const val DEFAULT = true

    fun isEnabled(prefs: SharedPreferences): Boolean = prefs.getBoolean(KEY, DEFAULT)

    fun setEnabled(prefs: SharedPreferences, enabled: Boolean) {
        prefs.edit().putBoolean(KEY, enabled).apply()
    }

    fun isEnabled(context: Context): Boolean = isEnabled(prefs(context))

    fun setEnabled(context: Context, enabled: Boolean) = setEnabled(prefs(context), enabled)

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
