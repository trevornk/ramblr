package com.trevornk.ramblr

/**
 * Whether the floating mic overlay (ring, cleanup badge, feedback bubble) should be visible,
 * given whether Ramblr's own MainActivity is currently in the foreground (#35), whether the
 * user has explicitly hidden it via the long-press "Hide icon" menu row (Feature B), and whether
 * the device is currently locked at the keyguard. The ring is pinned to the right edge, vertically
 * centered -- the same region where Settings right-aligns its switches -- so there's no reason to
 * float a tap-to-dictate trigger over the app's own Settings screen while the user is looking at it,
 * and no reason to keep showing it once the user has intentionally asked for it to go away until
 * they restore it from the notification (or the Advanced screen fallback).
 *
 * TYPE_ACCESSIBILITY_OVERLAY windows draw above the lock screen and ambient display by design (so
 * accessibility tools keep working while locked) -- without this check, anyone with physical
 * access to a locked phone could tap the mic and trigger a recording (and, if a cloud provider is
 * configured, an unauthenticated network call spending Trevor's API credits) without ever entering
 * a PIN or biometric. Hiding while locked closes that off; the overlay reappears the instant the
 * device is unlocked (see WhisperAccessibilityService's SCREEN_ON/OFF/USER_PRESENT receiver).
 */
fun overlayShouldBeVisible(mainActivityForeground: Boolean, hiddenByUser: Boolean, lockedByKeyguard: Boolean): Boolean =
    !mainActivityForeground && !hiddenByUser && !lockedByKeyguard
