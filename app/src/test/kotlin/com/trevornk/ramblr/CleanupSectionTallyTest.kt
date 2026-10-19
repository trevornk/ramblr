package com.trevornk.ramblr

import org.junit.Assert.*
import org.junit.Test

class CleanupSectionTallyTest {
    @Test fun `nothing run is neither cleaned nor failed`() {
        val t = CleanupSectionTally()
        assertTrue(t.isNone)
        assertFalse(t.allCleaned); assertFalse(t.noneCleaned); assertFalse(t.partlyCleaned)
    }

    @Test fun `every piece cleaned is the success state`() {
        val t = CleanupSectionTally()
        t.pieceCleaned(PartialCleanup(5, 5)); t.pieceCleaned(null); t.pieceCleaned(PartialCleanup(3, 3))
        assertTrue(t.allCleaned)
        assertEquals(9, t.cleaned); assertEquals(9, t.total)
        assertFalse(t.partlyCleaned)
    }

    @Test fun `no piece cleaned is the failed state`() {
        val t = CleanupSectionTally()
        t.pieceRaw(5); t.pieceRaw(3)
        assertTrue(t.noneCleaned)
        assertEquals(0, t.cleaned); assertEquals(8, t.total)
        assertFalse(t.partlyCleaned)
    }

    @Test fun `a mix of cleaned chunks and raw chunks is partial with real counts`() {
        val t = CleanupSectionTally()
        t.pieceCleaned(PartialCleanup(2, 5))   // chunked run that left 3 chunks raw
        t.pieceRaw(5)                           // piece that cleaned nothing
        t.pieceCleaned(PartialCleanup(5, 5))
        t.pieceCleaned(null)                    // one cloud call
        assertTrue(t.partlyCleaned)
        assertEquals(8, t.cleaned); assertEquals(16, t.total)
    }

    @Test fun `a raw piece always counts as at least one section`() {
        val t = CleanupSectionTally()
        t.pieceCleaned(null); t.pieceRaw(0)
        assertEquals(1, t.cleaned); assertEquals(2, t.total)
        assertTrue(t.partlyCleaned)
    }
}
