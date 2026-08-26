package com.trevornk.ramblr

import android.content.Context
import android.content.SharedPreferences

/**
 * Persistence for the #156 "service killed by the OS shortcut toggle" guard rail. Two flags in
 * the shared "ramblr" prefs file (same pattern as [IconHiddenState]/[HideIconToggle]):
 *
 *  - [KEY_SERVICE_WAS_ENABLED]: set true the first time the service connects, never cleared by
 *    the service's own lifecycle (onDestroy also fires for reboots and app updates, which say
 *    nothing about the user's intent). It means "this install has had a working service at least
 *    once", which is what separates the invisible-toggle kill from a fresh install that never
 *    finished setup.
 *  - [KEY_BANNER_DISMISSED]: the non-nagging bit. Set when the user dismisses the recovery
 *    banner; cleared when the service next connects, so the banner can fire again on the NEXT
 *    fresh kill but never re-nags about the one already dismissed.
 *
 * The decision itself ([shouldShowServiceKilledBanner]) is pure and unit-tested; this object is
 * only the prefs plumbing plus the live-state assembly ([shouldShowBanner]).
 */
object InvocationGuardRail {
    private const val PREFS_NAME = "ramblr"
    const val KEY_SERVICE_WAS_ENABLED = "service_was_enabled"
    const val KEY_BANNER_DISMISSED = "service_killed_banner_dismissed"

    /** Called from [WhisperAccessibilityService.onServiceConnected]: records that this install
     *  has a working service and re-arms the banner for the next fresh detection. */
    fun recordServiceConnected(context: Context) {
        prefs(context).edit()
            .putBoolean(KEY_SERVICE_WAS_ENABLED, true)
            .putBoolean(KEY_BANNER_DISMISSED, false)
            .apply()
    }

    /** The user tapped the banner's dismiss affordance: stay quiet until a fresh detection. */
    fun dismissBanner(context: Context) {
        prefs(context).edit().putBoolean(KEY_BANNER_DISMISSED, true).apply()
    }

    /** Assembles the live inputs for the pure [shouldShowServiceKilledBanner] decision. Uses
     *  `enabled_accessibility_services` (not [WhisperAccessibilityService.instance]) for the
     *  "enabled now" input: the enabled set is what the invisible-toggle sync edits, and a
     *  connected-but-crashed service would otherwise false-positive the banner. */
    fun shouldShowBanner(context: Context): Boolean {
        val p = prefs(context)
        return shouldShowServiceKilledBanner(
            serviceWasEnabled = p.getBoolean(KEY_SERVICE_WAS_ENABLED, false),
            serviceEnabledNow = InvocationSecureSettings.isServiceEnabled(context),
            anyShortcutBound = InvocationSecureSettings.anyShortcutBound(context),
            bannerDismissed = p.getBoolean(KEY_BANNER_DISMISSED, false),
        )
    }

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
