package com.trevornk.ramblr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** Per-chunk time budget derived from measured throughput (#325 follow-up). */
class LocalCleanupChunkBudgetTest {

    private fun budgetAfter(chunkChars: Int, perChunkMs: Long, chunks: Int = 3): LocalCleanupChunkBudget =
        LocalCleanupChunkBudget().also { b -> repeat(chunks) { b.recordCompleted(chunkChars, perChunkMs) } }

    @Test fun `with no measurement the first chunk gets the roomy initial budget`() {
        val b = LocalCleanupChunkBudget()
        assertNull(b.estimateMsPerChar())
        assertNull(b.expectedMs(300))
        assertEquals(LocalCleanupChunkBudget.INITIAL_BUDGET_MS, b.budgetMs(300))
    }

    @Test fun `initial budget covers a phone four times slower than the 10a Mumble median`() {
        // Pixel 10a + Mumble: ~14 s for a 300-character chunk.
        assertTrue(LocalCleanupChunkBudget.INITIAL_BUDGET_MS >= 4 * 14_000L)
    }

    @Test fun `a fast device keeps the floor the flat budget used to give`() {
        // LFM2.5 on the 10a is ~7.5 s per 300 characters; 2.5x is below the 20 s floor.
        val b = budgetAfter(200, 5_000)
        assertEquals(LocalCleanupChunkBudget.MIN_BUDGET_MS, b.budgetMs(200))
        assertEquals(5_000L, b.expectedMs(200))
    }

    @Test fun `the 10a with Mumble gets room above its slowest completed chunk`() {
        // Median 13 s per ~270 characters; the slowest completed chunk measured was 18.3 s and one
        // needed more than 20 s. The derived budget must clear both.
        val b = budgetAfter(270, 13_000)
        assertTrue("budget ${b.budgetMs(270)}", b.budgetMs(270) >= 30_000L)
    }

    @Test fun `a slow phone gets proportionally more time`() {
        val fast = budgetAfter(200, 6_000)
        val slow = budgetAfter(200, 40_000)
        assertEquals(100_000L, slow.budgetMs(200))
        assertTrue(slow.budgetMs(200) > 3 * fast.budgetMs(200))
    }

    @Test fun `budget scales with the chunk's own length and respects the floor`() {
        val b = budgetAfter(200, 20_000) // 100 ms/char
        assertEquals(LocalCleanupChunkBudget.MAX_BUDGET_MS, b.budgetMs(1_000)) // 250 s => ceiling
        assertEquals(LocalCleanupChunkBudget.MIN_BUDGET_MS, b.budgetMs(50)) // 12.5 s => floor
        assertEquals(50_000L, b.budgetMs(200))
    }

    @Test fun `the budget never exceeds the ceiling however slow the device is`() {
        val b = budgetAfter(200, 200_000)
        assertEquals(LocalCleanupChunkBudget.MAX_BUDGET_MS, b.budgetMs(200))
    }

    @Test fun `one stalled chunk does not inflate the budget once the window is healthy`() {
        val b = budgetAfter(200, 10_000, chunks = 4)
        b.recordCompleted(200, 55_000) // thermal stall
        assertEquals(10_000L, b.expectedMs(200))
    }

    @Test fun `one lucky short chunk does not deflate it either`() {
        val b = budgetAfter(200, 10_000, chunks = 4)
        b.recordCompleted(200, 1_000)
        assertEquals(10_000L, b.expectedMs(200))
    }

    @Test fun `an early timeout raises the next budget instead of repeating it`() {
        val b = LocalCleanupChunkBudget()
        val first = b.budgetMs(300)
        b.recordTimedOut(300, first)
        val second = b.budgetMs(300)
        assertTrue("second $second must exceed first $first", second > first)
        assertEquals(LocalCleanupChunkBudget.MAX_BUDGET_MS, second)
    }

    @Test fun `repeated timeouts keep raising the estimate until the ceiling`() {
        val b = budgetAfter(200, 5_000, chunks = 1)
        val before = b.budgetMs(200)
        b.recordTimedOut(200, before)
        b.recordTimedOut(200, before)
        assertTrue(b.budgetMs(200) > before)
    }

    @Test fun `the window forgets old evidence so a device that recovers gets its budget back`() {
        val b = budgetAfter(200, 60_000, chunks = 5)
        assertTrue(b.budgetMs(200) > 100_000L)
        repeat(LocalCleanupChunkBudget.WINDOW) { b.recordCompleted(200, 5_000) }
        assertEquals(LocalCleanupChunkBudget.MIN_BUDGET_MS, b.budgetMs(200))
    }

    @Test fun `a zero length chunk is ignored rather than dividing by zero`() {
        val b = LocalCleanupChunkBudget()
        b.recordCompleted(0, 5_000)
        assertNull(b.estimateMsPerChar())
    }

    @Test fun `even-sized window uses the mean of the two middle samples`() {
        val b = LocalCleanupChunkBudget()
        b.recordCompleted(100, 1_000) // 10 ms/char
        b.recordCompleted(100, 3_000) // 30 ms/char
        assertEquals(20.0, b.estimateMsPerChar()!!, 1e-9)
    }

    // ---- LocalCleanupPaceStore ---------------------------------------------------------------

    @Test fun `the store hands back the same budget for a model within the ttl`() {
        val store = LocalCleanupPaceStore(ttlMs = 1_000)
        val a = store.budgetFor("/m", 0)
        a.recordCompleted(300, 10_000)
        assertSame(a, store.budgetFor("/m", 900))
    }

    @Test fun `use renews the ttl so a long run never goes stale`() {
        val store = LocalCleanupPaceStore(ttlMs = 1_000)
        val a = store.budgetFor("/m", 0)
        store.touch("/m", 900)
        assertSame(a, store.budgetFor("/m", 1_800))
    }

    @Test fun `a stale estimate is dropped and starts from no evidence`() {
        val store = LocalCleanupPaceStore(ttlMs = 1_000)
        store.budgetFor("/m", 0).recordCompleted(300, 10_000)
        val fresh = store.budgetFor("/m", 1_001)
        assertNull(fresh.estimateMsPerChar())
        assertEquals(LocalCleanupChunkBudget.INITIAL_BUDGET_MS, fresh.budgetMs(300))
    }

    @Test fun `each model keeps its own pace`() {
        val store = LocalCleanupPaceStore()
        store.budgetFor("/lfm", 0).recordCompleted(300, 7_000)
        assertNull(store.budgetFor("/mumble", 0).estimateMsPerChar())
    }

    @Test fun `clear forgets everything`() {
        val store = LocalCleanupPaceStore()
        store.budgetFor("/m", 0).recordCompleted(300, 7_000)
        store.clear()
        assertNull(store.budgetFor("/m", 1).estimateMsPerChar())
    }
}
