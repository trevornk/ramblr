package com.trevornk.ramblr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RingPeekTest {

    private val screenW = 1000
    private val ringSize = 100

    @Test fun `snapped to right edge when x is past screen midpoint`() {
        assertTrue(RingPeek.isSnappedToRightEdge(x = 900, screenW, ringSize))
    }

    @Test fun `snapped to left edge when x is before screen midpoint`() {
        assertFalse(RingPeek.isSnappedToRightEdge(x = 0, screenW, ringSize))
    }

    @Test fun `peeked x on the right edge leaves only the sliver on-screen`() {
        val peekVisiblePx = 20
        val x = RingPeek.peekedX(x = 900, screenW, ringSize, peekVisiblePx)
        assertEquals(screenW - peekVisiblePx, x)
    }

    @Test fun `peeked x on the left edge leaves only the sliver on-screen`() {
        val peekVisiblePx = 20
        val x = RingPeek.peekedX(x = 0, screenW, ringSize, peekVisiblePx)
        assertEquals(peekVisiblePx - ringSize, x)
    }

    @Test fun `auto-peek allowed only while idle`() {
        assertTrue(RingPeek.shouldAutoPeek(RecordingStateMachine.State.IDLE))
        assertFalse(RingPeek.shouldAutoPeek(RecordingStateMachine.State.RECORDING))
        assertFalse(RingPeek.shouldAutoPeek(RecordingStateMachine.State.TRANSCRIBING))
    }
}
