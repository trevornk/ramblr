package com.trevornk.ramblr

import org.junit.Assert.assertEquals
import org.junit.Test

class OverlayVisibilityTest {

    @Test fun `hidden while MainActivity is in the foreground`() {
        assertEquals(false, overlayShouldBeVisible(mainActivityForeground = true, hiddenByUser = false, lockedByKeyguard = false))
    }

    @Test fun `visible once MainActivity is no longer in the foreground and not user-hidden or locked`() {
        assertEquals(true, overlayShouldBeVisible(mainActivityForeground = false, hiddenByUser = false, lockedByKeyguard = false))
    }

    @Test fun `hidden when the user has explicitly hidden it, even with MainActivity backgrounded`() {
        assertEquals(false, overlayShouldBeVisible(mainActivityForeground = false, hiddenByUser = true, lockedByKeyguard = false))
    }

    @Test fun `hidden when both MainActivity is foreground and the user has hidden it`() {
        assertEquals(false, overlayShouldBeVisible(mainActivityForeground = true, hiddenByUser = true, lockedByKeyguard = false))
    }

    @Test fun `hidden while the device is locked at the keyguard, even otherwise visible`() {
        assertEquals(false, overlayShouldBeVisible(mainActivityForeground = false, hiddenByUser = false, lockedByKeyguard = true))
    }

    @Test fun `hidden while locked even if user has not hidden it and MainActivity is backgrounded`() {
        assertEquals(false, overlayShouldBeVisible(mainActivityForeground = false, hiddenByUser = false, lockedByKeyguard = true))
    }

    // --- #103: onboarding "Try it out" force-visible override ---

    @Test fun `force-visible override makes the overlay visible despite MainActivity being foregrounded`() {
        assertEquals(true, overlayShouldBeVisible(mainActivityForeground = true, hiddenByUser = false, lockedByKeyguard = false, forceVisibleOverride = true))
    }

    @Test fun `force-visible override still respects an explicit user hide`() {
        assertEquals(false, overlayShouldBeVisible(mainActivityForeground = true, hiddenByUser = true, lockedByKeyguard = false, forceVisibleOverride = true))
    }

    @Test fun `force-visible override can never bypass the keyguard security guarantee`() {
        assertEquals(false, overlayShouldBeVisible(mainActivityForeground = true, hiddenByUser = false, lockedByKeyguard = true, forceVisibleOverride = true))
    }

    @Test fun `force-visible override defaults to false, preserving existing callers' behavior`() {
        // No forceVisibleOverride argument at all -- confirms the default parameter doesn't
        // change behavior for any pre-#103 call site that doesn't know about it.
        assertEquals(false, overlayShouldBeVisible(mainActivityForeground = true, hiddenByUser = false, lockedByKeyguard = false))
    }

    // --- #256: per-app exclusion ---

    @Test fun `hidden when the foreground app is excluded, even though otherwise visible`() {
        assertEquals(
            false,
            overlayShouldBeVisible(
                mainActivityForeground = false, hiddenByUser = false, lockedByKeyguard = false,
                excludedForeground = true,
            ),
        )
    }

    @Test fun `exclusion is checked before the force-visible override, not after`() {
        // The onboarding "Try it out" override must never paint the ring over an app the user
        // explicitly excluded -- same tier ordering as the keyguard check.
        assertEquals(
            false,
            overlayShouldBeVisible(
                mainActivityForeground = true, hiddenByUser = false, lockedByKeyguard = false,
                forceVisibleOverride = true, excludedForeground = true,
            ),
        )
    }

    @Test fun `exclusion defaults to false, preserving existing callers' behavior`() {
        assertEquals(
            true,
            overlayShouldBeVisible(mainActivityForeground = false, hiddenByUser = false, lockedByKeyguard = false),
        )
    }

    @Test fun `keyguard still wins over a non-excluded foreground app`() {
        assertEquals(
            false,
            overlayShouldBeVisible(
                mainActivityForeground = false, hiddenByUser = false, lockedByKeyguard = true,
                excludedForeground = false,
            ),
        )
    }
}
