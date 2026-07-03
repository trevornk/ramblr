package com.kafkasl.phonewhisper

import org.junit.Assert.assertEquals
import org.junit.Test

class OverlayVisibilityTest {

    @Test fun `hidden while MainActivity is in the foreground`() {
        assertEquals(false, overlayShouldBeVisible(mainActivityForeground = true))
    }

    @Test fun `visible once MainActivity is no longer in the foreground`() {
        assertEquals(true, overlayShouldBeVisible(mainActivityForeground = false))
    }
}
