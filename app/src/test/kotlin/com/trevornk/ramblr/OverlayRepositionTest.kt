package com.trevornk.ramblr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OverlayRepositionTest {

    // --- isFoldSizeChange ---

    @Test fun `unchanged size is not a fold change`() {
        assertFalse(isFoldSizeChange(oldW = 1080, oldH = 2364, newW = 1080, newH = 2364))
    }

    @Test fun `a plain width-height swap is treated as rotation, not a fold change`() {
        assertFalse(isFoldSizeChange(oldW = 1080, oldH = 2364, newW = 2364, newH = 1080))
    }

    @Test fun `a genuinely different size is a fold change`() {
        // Pixel Fold cover screen closed (1080x2364) to inner screen opened (2076x2152).
        assertTrue(isFoldSizeChange(oldW = 1080, oldH = 2364, newW = 2076, newH = 2152))
    }

    // --- snappedXForScreenChange ---

    @Test fun `ring near the right edge stays pinned to the right edge on the new screen`() {
        val ringSize = 168
        val margin = 24
        val oldScreenW = 1080
        val oldX = oldScreenW - ringSize - margin // pinned to right edge when closed
        val newScreenW = 2076

        val newX = snappedXForScreenChange(oldX, oldScreenW, newScreenW, ringSize, margin)

        assertEquals(newScreenW - ringSize - margin, newX)
    }

    @Test fun `ring near the left edge stays pinned to the left edge on the new screen`() {
        val ringSize = 168
        val margin = 24
        val oldScreenW = 1080
        val oldX = margin // pinned to left edge
        val newScreenW = 2076

        val newX = snappedXForScreenChange(oldX, oldScreenW, newScreenW, ringSize, margin)

        assertEquals(margin, newX)
    }

    // --- proportionalYForScreenChange ---

    @Test fun `vertical position is preserved proportionally across a screen size change`() {
        val ringSize = 168
        val margin = 24
        val oldScreenH = 2364
        val oldY = (oldScreenH * 0.3f - ringSize / 2).toInt() // ring centered 30% down the screen
        val newScreenH = 2152

        val newY = proportionalYForScreenChange(oldY, oldScreenH, newScreenH, ringSize, margin)
        val newCenterRatio = (newY + ringSize / 2f) / newScreenH

        assertEquals(0.3f, newCenterRatio, 0.01f)
    }

    @Test fun `vertical position is clamped within the new screen bounds`() {
        val ringSize = 168
        val margin = 24
        val oldScreenH = 2364
        val oldY = oldScreenH - ringSize // ring centered near the very bottom
        val newScreenH = 400 // a much shorter new screen

        val newY = proportionalYForScreenChange(oldY, oldScreenH, newScreenH, ringSize, margin)

        assertTrue(newY >= margin)
        assertTrue(newY <= newScreenH - ringSize - margin)
    }

    // --- peekedPositionForScreenChange ---

    @Test fun `a peeked ring stays peeked on the correct edge after a fold change`() {
        val ringSize = 168
        val margin = 24
        val peekVisiblePx = 60
        val oldScreenW = 1080
        val oldDockedX = oldScreenW - ringSize - margin // was docked to the right edge before peeking
        val newScreenW = 2076

        val (newPeekedX, newDockedX) = peekedPositionForScreenChange(
            oldDockedX, oldScreenW, newScreenW, ringSize, margin, peekVisiblePx
        )

        // The re-derived docked x is still pinned to the right edge on the new screen.
        assertEquals(newScreenW - ringSize - margin, newDockedX)
        // The peeked x is the right-edge peek position for that docked x, not the docked x itself.
        assertEquals(RingPeek.peekedX(newDockedX, newScreenW, ringSize, peekVisiblePx), newPeekedX)
        assertEquals(newScreenW - peekVisiblePx, newPeekedX)
    }

    @Test fun `a peeked ring on the left edge stays pinned left after a fold change`() {
        val ringSize = 168
        val margin = 24
        val peekVisiblePx = 60
        val oldScreenW = 1080
        val oldDockedX = margin // was docked to the left edge before peeking
        val newScreenW = 2076

        val (newPeekedX, newDockedX) = peekedPositionForScreenChange(
            oldDockedX, oldScreenW, newScreenW, ringSize, margin, peekVisiblePx
        )

        assertEquals(margin, newDockedX)
        assertEquals(peekVisiblePx - ringSize, newPeekedX)
    }

    // --- clampRestoredPosition ---

    @Test fun `a restored position within current screen bounds is left unchanged`() {
        val ringSize = 168
        val margin = 24
        val screenW = 1080
        val screenH = 2364
        val x = screenW - ringSize - margin
        val y = screenH / 2 - ringSize / 2

        val (clampedX, clampedY) = clampRestoredPosition(x, y, screenW, screenH, ringSize, margin)

        assertEquals(x, clampedX)
        assertEquals(y, clampedY)
    }

    @Test fun `a restored position saved for a wider old screen is clamped onto a narrower new screen`() {
        val ringSize = 168
        val margin = 24
        val oldScreenW = 2076
        val x = oldScreenW - ringSize - margin // pinned to the right edge of the OLD, wider screen
        val newScreenW = 1080
        val newScreenH = 2364

        val (clampedX, _) = clampRestoredPosition(x, margin, newScreenW, newScreenH, ringSize, margin)

        assertTrue(clampedX <= newScreenW - ringSize - margin)
        assertTrue(clampedX >= margin)
    }

    @Test fun `a restored position saved for a taller old screen is clamped onto a shorter new screen`() {
        val ringSize = 168
        val margin = 24
        val oldScreenH = 2364
        val y = oldScreenH - ringSize - margin // near the bottom of the OLD, taller screen
        val newScreenW = 1080
        val newScreenH = 400

        val (_, clampedY) = clampRestoredPosition(margin, y, newScreenW, newScreenH, ringSize, margin)

        assertTrue(clampedY <= newScreenH - ringSize - margin)
        assertTrue(clampedY >= margin)
    }

    @Test fun `a stale negative restored position is clamped to the on-screen minimum`() {
        val ringSize = 168
        val margin = 24
        val screenW = 1080
        val screenH = 2364

        val (clampedX, clampedY) = clampRestoredPosition(-500, -500, screenW, screenH, ringSize, margin)

        assertEquals(margin, clampedX)
        assertEquals(margin, clampedY)
    }
}
