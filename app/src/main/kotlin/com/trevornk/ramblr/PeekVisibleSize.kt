package com.trevornk.ramblr

import android.content.Context
import android.content.SharedPreferences

/**
 * User-configurable override for how much of the ring stays visible on-screen once peeked (see
 * [RingPeek.peekedX]), exposed in Advanced settings as a plain dp number. Bigger values mean an
 * easier-to-hit restore tap at the cost of a chunkier sliver poking in from the edge; this is
 * exactly the same size/reachability tradeoff that motivated bumping the shipped default up from
 * the original 14dp (too close to Android's minimum recommended 48dp touch target, and the direct
 * cause of the SchildiChat peek-restore bug -- see [RingPeek]'s gesture-exclusion doc) to
 * [DEFAULT_DP]. Only meaningful while [AutoPeekToggle] is on; this store doesn't gate anything
 * itself. Backed by the same "ramblr" prefs file as the other Advanced settings, following the
 * same pattern as [AutoPeekDelay].
 */
object PeekVisibleSize {
    private const val PREFS_NAME = "ramblr"
    const val KEY = "peek_visible_size_dp"
    const val DEFAULT_DP = 20
    const val MIN_DP = 8
    const val MAX_DP = 40

    fun dpOrDefault(prefs: SharedPreferences): Int =
        prefs.getInt(KEY, DEFAULT_DP).coerceIn(MIN_DP, MAX_DP)

    fun setDp(prefs: SharedPreferences, dp: Int) {
        prefs.edit().putInt(KEY, dp.coerceIn(MIN_DP, MAX_DP)).apply()
    }

    fun dpOrDefault(context: Context): Int = dpOrDefault(prefs(context))

    fun setDp(context: Context, dp: Int) = setDp(prefs(context), dp)

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
