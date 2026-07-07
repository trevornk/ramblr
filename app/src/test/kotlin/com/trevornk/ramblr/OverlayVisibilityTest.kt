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
}
