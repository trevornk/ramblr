package com.trevornk.ramblr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #128: a rotation while the ring is PEEKED desyncs the peek state from the screen.
 *
 * [isFoldSizeChange] deliberately reports false for a plain width/height swap, so
 * handleScreenSizeChange returns early and never repositions the ring on rotation -- correct for a
 * docked ring (rotation has never moved it), but wrong for a peeked one, whose window x is defined
 * relative to a screen edge that just moved. The peeked ring keeps its old absolute x against the
 * new screen width, so it either floats into the middle of the screen (portrait -> landscape) or
 * falls entirely off it (landscape -> portrait), while isPeeked stays true.
 *
 * These tests pin the rotation-aware peek repositioning that fixes it.
 */
class PeekRotationTest {

    private val ringSize = 154
    private val margin = 22
    private val peekVisiblePx = 55
    private val inset = 16

    @Test fun `rotation is still not a fold change`() {
        assertFalse(isFoldSizeChange(oldW = 1080, oldH = 2400, newW = 2400, newH = 1080))
    }

    @Test fun `peeked ring rotated portrait to landscape re-derives against the new width`() {
        val portraitW = 1080
        val landscapeW = 2400
        // Docked at the right edge in portrait, then peeked.
        val dockedX = portraitW - ringSize - margin
        val (newPeekedX, newDockedX) = peekedPositionForScreenChange(
            dockedX, portraitW, landscapeW, ringSize, margin, peekVisiblePx, inset
        )
        // Must hug the NEW right edge, not sit stranded at the old screen's coordinate.
        assertEquals(landscapeW - ringSize - margin, newDockedX)
        assertEquals(landscapeW - peekVisiblePx - inset, newPeekedX)
        assertTrue("peeked ring must stay on-screen", newPeekedX < landscapeW)
    }

    @Test fun `peeked ring rotated landscape to portrait does not fall off the screen`() {
        val landscapeW = 2400
        val portraitW = 1080
        val dockedX = landscapeW - ringSize - margin
        val (newPeekedX, _) = peekedPositionForScreenChange(
            dockedX, landscapeW, portraitW, ringSize, margin, peekVisiblePx, inset
        )
        // The whole bug: without re-derivation this stayed at ~2345, far past portrait's 1080.
        assertTrue("peeked x must be within the new screen", newPeekedX < portraitW)
        assertEquals(portraitW - peekVisiblePx - inset, newPeekedX)
    }

    @Test fun `a left-edge peeked ring survives rotation too`() {
        val (newPeekedX, newDockedX) = peekedPositionForScreenChange(
            margin, 1080, 2400, ringSize, margin, peekVisiblePx, inset
        )
        assertEquals(margin, newDockedX)
        assertEquals(peekVisiblePx - ringSize + inset, newPeekedX)
    }

    private fun assertFalse(b: Boolean) = assertEquals(false, b)
}
