package com.trevornk.ramblr

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * #128: the peeked sliver is supposed to leave [PeekVisibleSize] worth of the ring ON SCREEN, but
 * the ring window is deliberately larger than the mic button it draws (RING_DP 56 vs BTN_DP 44,
 * the button centred inside it), so the outer ~6dp of the window on every side is fully
 * transparent padding. Measuring the sliver against the WINDOW therefore puts 6dp of dead,
 * invisible margin on-screen and only (peekVisible - inset) of actual drawn ink -- 14dp for the
 * shipped 20dp default, which is the exact number PeekVisibleSize's own kdoc calls out as the
 * cause of the original peek-restore reliability bug.
 *
 * These tests pin the sliver to the DRAWN ink instead.
 */
class RingPeekInsetTest {

    private val screenW = 1000
    private val ringSize = 154      // 56dp @ 2.75 density
    private val inset = 16          // (56dp - 44dp) / 2, scaled
    private val peekVisiblePx = 55  // 20dp @ 2.75 density

    @Test fun `right-edge peek leaves a full sliver of drawn ink on-screen, not window padding`() {
        val x = RingPeek.peekedX(x = 900, screenW, ringSize, peekVisiblePx, ringInsetPx = inset)
        // The drawn button's left edge -- not the window's -- must land exactly peekVisiblePx in.
        assertEquals(screenW - peekVisiblePx, x + inset)
    }

    @Test fun `left-edge peek leaves a full sliver of drawn ink on-screen, not window padding`() {
        val x = RingPeek.peekedX(x = 0, screenW, ringSize, peekVisiblePx, ringInsetPx = inset)
        // The drawn button's right edge must land exactly peekVisiblePx in from the left.
        assertEquals(peekVisiblePx, x + ringSize - inset)
    }

    @Test fun `a zero inset behaves exactly like the original window-measured math`() {
        assertEquals(
            RingPeek.peekedX(x = 900, screenW, ringSize, peekVisiblePx),
            RingPeek.peekedX(x = 900, screenW, ringSize, peekVisiblePx, ringInsetPx = 0)
        )
        assertEquals(
            RingPeek.peekedX(x = 0, screenW, ringSize, peekVisiblePx),
            RingPeek.peekedX(x = 0, screenW, ringSize, peekVisiblePx, ringInsetPx = 0)
        )
    }

    @Test fun `inset peek keeps strictly more of the ring on-screen than the window-measured math`() {
        val right = RingPeek.peekedX(x = 900, screenW, ringSize, peekVisiblePx, ringInsetPx = inset)
        // Sliding further onto the screen means a SMALLER x on the right edge...
        assertEquals(screenW - peekVisiblePx - inset, right)
        val left = RingPeek.peekedX(x = 0, screenW, ringSize, peekVisiblePx, ringInsetPx = inset)
        // ...and a LARGER (less negative) x on the left edge.
        assertEquals(peekVisiblePx - ringSize + inset, left)
    }
}
