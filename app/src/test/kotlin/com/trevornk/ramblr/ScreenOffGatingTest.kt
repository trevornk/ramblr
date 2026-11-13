package com.trevornk.ramblr

import android.view.Display
import org.junit.Assert.assertEquals
import org.junit.Test

/** #373: Always-On-Display / doze gating for the ring and the feedback bubble. */
class ScreenOffGatingTest {

    @Test fun `displayIsOff is false only for ON and UNKNOWN`() {
        assertEquals(false, displayIsOff(Display.STATE_ON))
        assertEquals(false, displayIsOff(Display.STATE_UNKNOWN))
        assertEquals(true, displayIsOff(Display.STATE_OFF))
        assertEquals(true, displayIsOff(Display.STATE_DOZE))
        assertEquals(true, displayIsOff(Display.STATE_DOZE_SUSPEND))
        assertEquals(true, displayIsOff(Display.STATE_ON_SUSPEND))
        assertEquals(true, displayIsOff(Display.STATE_VR))
    }

    @Test fun `ring hidden when screen off regardless of override and with keyguard unlocked`() {
        for (force in listOf(false, true)) {
            assertEquals(
                false,
                overlayShouldBeVisible(
                    mainActivityForeground = false, hiddenByUser = false, lockedByKeyguard = false,
                    forceVisibleOverride = force, screenOff = true,
                ),
            )
        }
    }

    @Test fun `ring visibility is restored on wake and unlock`() {
        assertEquals(
            true,
            overlayShouldBeVisible(
                mainActivityForeground = false, hiddenByUser = false, lockedByKeyguard = false,
                screenOff = false,
            ),
        )
    }

    @Test fun `bubble suppressed while screen off or keyguard locked`() {
        assertEquals(false, feedbackBubbleAllowed(lockedByKeyguard = false, screenOff = true))
        assertEquals(false, feedbackBubbleAllowed(lockedByKeyguard = true, screenOff = false))
        assertEquals(false, feedbackBubbleAllowed(lockedByKeyguard = true, screenOff = true))
    }

    @Test fun `bubble shown when screen on and unlocked`() {
        assertEquals(true, feedbackBubbleAllowed(lockedByKeyguard = false, screenOff = false))
    }
}
