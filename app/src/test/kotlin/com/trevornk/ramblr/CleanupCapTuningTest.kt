package com.trevornk.ramblr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the waterfall hard cap to the real-usage evidence it was tuned against (#137 follow-up).
 *
 * The cap was lowered 15s -> 8s using 34 days of Trevor's own dictation (318 successful cleanups
 * in benchmark_log.jsonl). These aren't testing arithmetic for its own sake -- they encode the
 * observed latency distribution so that anyone retuning the cap has to consciously accept killing
 * real cleanups that used to succeed, rather than discovering it in production.
 */
class CleanupCapTuningTest {

    /** Slowest cleanup that actually SUCCEEDED in 34 days of real use (GEMINI_DIRECT, 08-08). */
    private val slowestObservedSuccessMs = 7_859L

    /** p99 of those 318 successful cleanups. */
    private val observedP99Ms = 3_828L

    @Test
    fun `the cap still admits the slowest cleanup ever observed to succeed`() {
        assertTrue(
            "Cap ${CLEANUP_WATERFALL_HARD_CAP_MS}ms would abort the slowest real success " +
                "(${slowestObservedSuccessMs}ms), turning a cleanup that works today into a " +
                "raw-text fallback. Re-check benchmark_log.jsonl before lowering this.",
            CLEANUP_WATERFALL_HARD_CAP_MS > slowestObservedSuccessMs,
        )
    }

    @Test
    fun `the cap stays tight enough to beat the old 15s offline wait`() {
        // The offline case is what this budget actually costs: every provider burns its full
        // timeout in sequence before the chain gives up. Two such waterfalls were logged at
        // 18.2s and 19.7s under the old 15s cap (which #137 made real). Anything at or above
        // 15s means that tuning never happened.
        assertTrue(
            "Cap ${CLEANUP_WATERFALL_HARD_CAP_MS}ms is back at/above the old un-tuned 15s.",
            CLEANUP_WATERFALL_HARD_CAP_MS < 15_000L,
        )
        assertEquals(8_000L, CLEANUP_WATERFALL_HARD_CAP_MS)
    }

    @Test
    fun `the read timeout still fits inside the cap`() {
        // readMs is deliberately 7s -- that is what let the 7859ms success through. If the cap
        // ever drops below it, the per-phase budget becomes unreachable and the clamp in
        // cloudStepTimeouts silently starts truncating every slow-but-healthy call.
        assertTrue(
            "readMs (${CleanupStepTimeouts.DEFAULT.readMs}ms) no longer fits in the " +
                "${CLEANUP_WATERFALL_HARD_CAP_MS}ms cap.",
            CleanupStepTimeouts.DEFAULT.readMs < CLEANUP_WATERFALL_HARD_CAP_MS,
        )
    }

    @Test
    fun `a step dispatched at the p99 mark still gets a usable budget`() {
        // Typical bad case: an earlier step burned p99 before failing over. The next step must
        // still get meaningfully more than the floor, or the waterfall is single-step in practice.
        val start = 1_000_000L
        val deadline = start + CLEANUP_WATERFALL_HARD_CAP_MS
        val t = cloudStepTimeouts(deadline, start + observedP99Ms)

        val remaining = CLEANUP_WATERFALL_HARD_CAP_MS - observedP99Ms
        assertEquals(remaining, t.callMs)
        assertTrue(
            "Only ${t.callMs}ms left for a follow-up step after a p99 first attempt.",
            t.callMs!! > MIN_STEP_CALL_BUDGET_MS * 4,
        )
        // And that leftover still covers the median cleanup (901ms) comfortably.
        assertTrue("Leftover budget can't even fit a median cleanup.", t.callMs!! > 901L)
    }
}
