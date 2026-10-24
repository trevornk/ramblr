package com.trevornk.ramblr

import android.content.Context
import android.content.SharedPreferences

/**
 * On-by-default toggle (#361) for whether a long-press on the floating ring may cancel a
 * transcription in progress (#20) or undo the last insertion (#27). On keeps the long-standing
 * behavior unchanged. Off removes the two destructive actions: holding while TRANSCRIBING does
 * nothing (the long-press is not even armed), and holding while IDLE always opens the style menu
 * (#53), even with an undoable insertion pending -- for anyone who lost a dictation to an
 * accidental hold. See [overlayLongPressActionFor] for the resulting state x setting map.
 *
 * Read fresh at every touch-down and when the long-press timer fires, so flipping it in Settings
 * applies without restarting the service. Backed by the same "ramblr" prefs file as
 * [AutoPeekToggle]/[SingleTapRestoreToggle], following the same pattern.
 */
object LongPressCancelUndoToggle {
    private const val PREFS_NAME = "ramblr"
    const val KEY = "long_press_cancels_and_undoes_enabled"
    private const val DEFAULT = true

    fun isEnabled(prefs: SharedPreferences): Boolean = prefs.getBoolean(KEY, DEFAULT)

    fun setEnabled(prefs: SharedPreferences, enabled: Boolean) {
        prefs.edit().putBoolean(KEY, enabled).apply()
    }

    fun isEnabled(context: Context): Boolean = isEnabled(prefs(context))

    fun setEnabled(context: Context, enabled: Boolean) = setEnabled(prefs(context), enabled)

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
