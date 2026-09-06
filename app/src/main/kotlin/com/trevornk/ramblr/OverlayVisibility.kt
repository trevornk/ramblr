package com.trevornk.ramblr

/**
 * Whether the floating mic overlay (ring, cleanup badge, feedback bubble) should be visible,
 * given whether Ramblr's own MainActivity is currently in the foreground (#35), whether the
 * user has explicitly hidden it via the long-press "Hide icon" menu row (Feature B), whether
 * the device is currently locked at the keyguard, and whether the onboarding wizard's "Try it
 * out" step has explicitly forced it back on (#103). The ring is pinned to the right edge,
 * vertically centered -- the same region where Settings right-aligns its switches -- so there's
 * no reason to float a tap-to-dictate trigger over the app's own Settings screen while the user
 * is looking at it, and no reason to keep showing it once the user has intentionally asked for
 * it to go away until they restore it from the notification (or the Advanced screen fallback).
 *
 * TYPE_ACCESSIBILITY_OVERLAY windows draw above the lock screen and ambient display by design (so
 * accessibility tools keep working while locked) -- without this check, anyone with physical
 * access to a locked phone could tap the mic and trigger a recording (and, if a cloud provider is
 * configured, an unauthenticated network call spending Trevor's API credits) without ever entering
 * a PIN or biometric. Hiding while locked closes that off; the overlay reappears the instant the
 * device is unlocked (see WhisperAccessibilityService's SCREEN_ON/OFF/USER_PRESENT receiver).
 * [forceVisibleOverride] is intentionally checked AFTER [lockedByKeyguard], never before: forcing
 * the overlay visible for onboarding's "Try it out" step must never bypass the keyguard's own
 * hide-while-locked security guarantee.
 *
 * [excludedForeground] is #256's per-app exclusion signal (see [ExclusionGating.ringHiddenForExclusion]):
 * true only when the foreground app's package identity is positively known AND on the user's
 * exclusion list. Checked in the same tier as [lockedByKeyguard] -- before [forceVisibleOverride]
 * -- so the onboarding "Try it out" override can't accidentally paint the ring over an app the
 * user explicitly asked Ramblr to stay out of. This is evaluated only when a caller already has a
 * fresh foreground-package read (an existing [applyOverlayVisibility] trigger), never from a new
 * poll or subscription -- see [ExclusionGating]'s doc for the honest limits that implies.
 */
fun overlayShouldBeVisible(
    mainActivityForeground: Boolean,
    hiddenByUser: Boolean,
    lockedByKeyguard: Boolean,
    forceVisibleOverride: Boolean = false,
    excludedForeground: Boolean = false,
): Boolean =
    if (lockedByKeyguard) false
    else if (excludedForeground) false
    else if (forceVisibleOverride) !hiddenByUser
    else !mainActivityForeground && !hiddenByUser
