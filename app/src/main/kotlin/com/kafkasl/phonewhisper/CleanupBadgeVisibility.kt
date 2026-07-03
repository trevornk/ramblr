package com.kafkasl.phonewhisper

import android.content.Context
import android.content.SharedPreferences

/**
 * Gates the overlay's #34 cleanup-toggle badge behind actual use (#38): a user who only ever
 * dictates raw, local transcripts and has never touched cleanup shouldn't see any badge on the
 * overlay, not even a grey "off" one -- it reads as unexplained AI-assist clutter bolted onto the
 * mic button. Once the top-level cleanup toggle has been turned on at least once, the badge
 * becomes a permanent quick-toggle affordance exactly like before this change, even if the user
 * later turns cleanup back off.
 */
object CleanupBadgeVisibility {
    private const val PREFS_NAME = "phonewhisper"
    const val KEY_EVER_CONFIGURED = "cleanup_ever_configured"

    /**
     * Pure gating decision. [everConfigured] is the persisted "has this user turned cleanup on
     * at least once" flag; [currentlyEnabled] additionally covers cleanup being on *right now* --
     * so an install that's actively relying on the badge never has it pulled out from under it,
     * even in the (currently unreachable) case where [everConfigured] hasn't been recorded yet.
     */
    fun shouldShowBadge(everConfigured: Boolean, currentlyEnabled: Boolean): Boolean =
        everConfigured || currentlyEnabled

    fun hasEverConfigured(prefs: SharedPreferences): Boolean = prefs.getBoolean(KEY_EVER_CONFIGURED, false)

    fun markConfigured(prefs: SharedPreferences) {
        if (!hasEverConfigured(prefs)) prefs.edit().putBoolean(KEY_EVER_CONFIGURED, true).apply()
    }

    fun hasEverConfigured(context: Context): Boolean = hasEverConfigured(prefs(context))

    fun markConfigured(context: Context) = markConfigured(prefs(context))

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
