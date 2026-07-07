package com.trevornk.ramblr

import android.content.Context
import android.content.SharedPreferences

/**
 * User-configurable override for how many seconds the floating icon's normal (non-peeked)
 * feedback bubble/idle-peek timeout should wait before [WhisperAccessibilityService] auto-peeks
 * it to the edge -- exposed in Advanced settings as a plain number (seconds), converted to millis
 * at the read site. Defaults to [RingPeek.IDLE_TIMEOUT_MS] converted to seconds so existing
 * behavior doesn't change for anyone who's never touched the setting. Only meaningful while
 * [AutoPeekToggle] is on; this store doesn't gate anything itself. Backed by the same "ramblr"
 * prefs file as the other Advanced toggles, following the same pattern.
 */
object AutoPeekDelay {
    private const val PREFS_NAME = "ramblr"
    const val KEY = "auto_peek_delay_seconds"
    const val DEFAULT_SECONDS = (RingPeek.IDLE_TIMEOUT_MS / 1000L).toInt()
    const val MIN_SECONDS = 1
    const val MAX_SECONDS = 60

    fun secondsOrDefault(prefs: SharedPreferences): Int =
        prefs.getInt(KEY, DEFAULT_SECONDS).coerceIn(MIN_SECONDS, MAX_SECONDS)

    fun setSeconds(prefs: SharedPreferences, seconds: Int) {
        prefs.edit().putInt(KEY, seconds.coerceIn(MIN_SECONDS, MAX_SECONDS)).apply()
    }

    fun millisOrDefault(prefs: SharedPreferences): Long = secondsOrDefault(prefs) * 1000L

    fun secondsOrDefault(context: Context): Int = secondsOrDefault(prefs(context))

    fun setSeconds(context: Context, seconds: Int) = setSeconds(prefs(context), seconds)

    fun millisOrDefault(context: Context): Long = millisOrDefault(prefs(context))

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
