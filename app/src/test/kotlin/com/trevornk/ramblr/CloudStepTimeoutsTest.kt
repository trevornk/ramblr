package com.trevornk.ramblr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #137: the waterfall's documented hard cap was only enforced *between* steps, so a step
 * dispatched just under the cap still got its full per-phase connect+read allowance and could
 * overrun the cap by ~8.5s. These pin the clamp that makes the cap actually hold.
 */
class CloudStepTimeoutsTest {

    private val start = 1_000_000L
    private val deadline = start + CLEANUP_WATERFALL_HARD_CAP_MS

    @Test
    fun `a step starting with the full budget keeps the normal per-phase timeouts`() {
        val t = cloudStepTimeouts(deadline, start)

        // Nothing is clamped away when there's plenty of budget: the phase timeouts are what
        // ADR-0001 chose them to be, and only the whole-call bound is added.
        assertEquals(CleanupStepTimeouts.DEFAULT.connectMs, t.connectMs)
        assertEquals(CleanupStepTimeouts.DEFAULT.readMs, t.readMs)
        assertEquals(CLEANUP_WATERFALL_HARD_CAP_MS, t.callMs)
    }

    @Test
    fun `the whole-call bound never exceeds the budget the waterfall has left`() {
        // The exact scenario from Trevor's benchmark log: a third cleanup step dispatched at
        // ~12.1s into a 15s budget. Previously it got connect+read on top of that, reaching
        // ~18.2s; now its whole call is bounded by what remains.
        val nowMs = start + 12_100L
        val t = cloudStepTimeouts(deadline, nowMs)

        assertEquals(2_900L, t.callMs)
        assertTrue(
            "call budget must not outlive the waterfall deadline",
            nowMs + t.callMs!! <= deadline,
        )
    }

    @Test
    fun `phase timeouts are clamped down too so neither phase can outlive the deadline`() {
        // 1.2s left is less than the 1.5s connect and 7s read defaults -- if the phases weren't
        // clamped, connect alone could outlast the whole waterfall.
        val t = cloudStepTimeouts(deadline, deadline - 1_200L)

        assertEquals(1_200L, t.connectMs)
        assertEquals(1_200L, t.readMs)
        assertEquals(1_200L, t.callMs)
    }

    @Test
    fun `a spent budget floors instead of collapsing to zero`() {
        // OkHttp treats a 0 timeout as INFINITE, so clamping a spent budget to 0 would remove
        // the cap entirely -- the precise opposite of this fix's intent.
        val t = cloudStepTimeouts(deadline, deadline + 5_000L)

        assertEquals(MIN_STEP_CALL_BUDGET_MS, t.callMs)
        assertTrue("a spent budget must never yield an infinite timeout", t.callMs!! > 0L)
    }

    @Test
    fun `the worst case is bounded by the cap rather than cap plus a full step allowance`() {
        // Regression pin for the original defect's arithmetic. The between-steps gate lets a step
        // start at cap-1ms; before the clamp that step could still spend connectMs + readMs.
        val latestLegalStart = deadline - 1L
        val t = cloudStepTimeouts(deadline, latestLegalStart)

        val oldWorstCase = 1L + CleanupStepTimeouts.DEFAULT.connectMs + CleanupStepTimeouts.DEFAULT.readMs
        assertEquals(8_501L, oldWorstCase)

        assertNotNull(t.callMs)
        assertTrue(
            "clamped budget must be far below the old connect+read worst case",
            t.callMs!! < oldWorstCase,
        )
        assertEquals(MIN_STEP_CALL_BUDGET_MS, t.callMs)
    }

    @Test
    fun `an explicit base is respected but still clamped`() {
        val base = CleanupStepTimeouts(connectMs = 500L, readMs = 900L)
        val roomy = cloudStepTimeouts(deadline, start, base)

        // A tighter-than-default base is preserved rather than widened back to the default.
        assertEquals(500L, roomy.connectMs)
        assertEquals(900L, roomy.readMs)

        val tight = cloudStepTimeouts(deadline, deadline - 300L, base)
        assertEquals(300L, tight.connectMs)
        assertEquals(300L, tight.readMs)
        assertEquals(300L, tight.callMs)
    }

    @Test
    fun `default timeouts carry no call bound so unaware callers are unaffected`() {
        // Test fakes and any caller that doesn't know the deadline must keep the old behavior
        // (leave the shared client's own callTimeout alone) rather than get a surprise bound.
        assertEquals(null, CleanupStepTimeouts.DEFAULT.callMs)
    }
}
