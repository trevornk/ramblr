package com.kafkasl.phonewhisper

/**
 * Whether the floating mic overlay (ring, cleanup badge, feedback bubble) should be visible,
 * given whether Ramblr's own MainActivity is currently in the foreground (#35) and whether the
 * user has explicitly hidden it via the long-press "Hide icon" menu row (Feature B). The ring is
 * pinned to the right edge, vertically centered -- the same region where Settings right-aligns
 * its switches -- so there's no reason to float a push-to-talk trigger over the app's own
 * Settings screen while the user is looking at it, and no reason to keep showing it once the
 * user has intentionally asked for it to go away until they restore it from the notification (or
 * the Advanced screen fallback).
 */
fun overlayShouldBeVisible(mainActivityForeground: Boolean, hiddenByUser: Boolean): Boolean =
    !mainActivityForeground && !hiddenByUser
