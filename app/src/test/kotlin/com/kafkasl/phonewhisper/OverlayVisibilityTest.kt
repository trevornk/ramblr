package com.kafkasl.phonewhisper

import org.junit.Assert.assertEquals
import org.junit.Test

class OverlayVisibilityTest {

    @Test fun `hidden while MainActivity is in the foreground`() {
        assertEquals(false, overlayShouldBeVisible(mainActivityForeground = true, hiddenByUser = false))
    }

    @Test fun `visible once MainActivity is no longer in the foreground and not user-hidden`() {
        assertEquals(true, overlayShouldBeVisible(mainActivityForeground = false, hiddenByUser = false))
    }

    @Test fun `hidden when the user has explicitly hidden it, even with MainActivity backgrounded`() {
        assertEquals(false, overlayShouldBeVisible(mainActivityForeground = false, hiddenByUser = true))
    }

    @Test fun `hidden when both MainActivity is foreground and the user has hidden it`() {
        assertEquals(false, overlayShouldBeVisible(mainActivityForeground = true, hiddenByUser = true))
    }
}
