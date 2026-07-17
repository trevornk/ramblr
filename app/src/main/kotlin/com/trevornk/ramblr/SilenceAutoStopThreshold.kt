package com.trevornk.ramblr

import android.content.Context
import android.content.SharedPreferences

/**
 * How many trailing seconds of VAD-detected silence trigger auto-stop when
 * [SilenceAutoStopToggle] is on (#108, mode 1). Stored as whole seconds x10 (deciseconds) so the
 * forgiving preset values below (1.5s / 2.0s / 2.5s) round-trip exactly instead of losing
 * precision through an Int-seconds store the way [AutoPeekDelay] uses.
 *
 * Defaults to 2.0s -- the middle of the product owner's stated 1.5-2.5s preference range, and
 * deliberately NOT the more aggressive 1-2s range the issue explicitly warns against. Presets are
 * exposed in the Behavior settings UI; MIN/MAX bound a "custom" value entered directly, kept
 * comfortably inside the same forgiving neighborhood so a fat-fingered value can't make the
 * feature useless (e.g. 0.1s stopping mid-word) or pointless (e.g. 60s never triggering in
 * practice).
 */
object SilenceAutoStopThreshold {
    private const val PREFS_NAME = "ramblr"
    const val KEY = "silence_auto_stop_deciseconds"
    const val DEFAULT_DECISECONDS = 20 // 2.0s
    const val MIN_DECISECONDS = 10 // 1.0s floor -- below this the feature would clip live speech
    const val MAX_DECISECONDS = 50 // 5.0s ceiling -- above this it stops feeling like "auto"

    /** Forgiving presets shown in the Behavior settings picker, in deciseconds (1.5s/2.0s/2.5s). */
    val PRESET_DECISECONDS = listOf(15, 20, 25)

    fun decisecondsOrDefault(prefs: SharedPreferences): Int =
        prefs.getInt(KEY, DEFAULT_DECISECONDS).coerceIn(MIN_DECISECONDS, MAX_DECISECONDS)

    fun setDeciseconds(prefs: SharedPreferences, deciseconds: Int) {
        prefs.edit().putInt(KEY, deciseconds.coerceIn(MIN_DECISECONDS, MAX_DECISECONDS)).apply()
    }

    fun millisOrDefault(prefs: SharedPreferences): Long = decisecondsOrDefault(prefs) * 100L

    fun decisecondsOrDefault(context: Context): Int = decisecondsOrDefault(prefs(context))

    fun setDeciseconds(context: Context, deciseconds: Int) = setDeciseconds(prefs(context), deciseconds)

    fun millisOrDefault(context: Context): Long = millisOrDefault(prefs(context))

    /** Human-readable "1.5s" / "2.0s" style formatting for the settings summary/dialog. */
    fun formatSeconds(deciseconds: Int): String =
        "%.1fs".format(deciseconds / 10.0)

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
