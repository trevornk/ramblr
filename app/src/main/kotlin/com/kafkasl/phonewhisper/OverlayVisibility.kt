package com.kafkasl.phonewhisper

/**
 * Whether the floating mic overlay (ring, cleanup badge, feedback bubble) should be visible,
 * given whether Ramblr's own MainActivity is currently in the foreground (#35). The ring is
 * pinned to the right edge, vertically centered -- the same region where Settings right-aligns
 * its switches -- so there's no reason to float a push-to-talk trigger over the app's own
 * Settings screen while the user is looking at it.
 */
fun overlayShouldBeVisible(mainActivityForeground: Boolean): Boolean = !mainActivityForeground
