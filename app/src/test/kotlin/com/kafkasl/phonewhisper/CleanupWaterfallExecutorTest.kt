package com.kafkasl.phonewhisper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CleanupWaterfallPlannerTest {

    @Test fun `empty steps produce no groups`() {
        assertEquals(emptyList<List<CleanupStep>>(), CleanupWaterfallPlanner.groupConsecutive(emptyList()))
    }

    @Test fun `single step is its own group`() {
        val steps = listOf(CleanupStep(CleanupStepGroup.LEGACY, "gpt-4o-mini"))
        val groups = CleanupWaterfallPlanner.groupConsecutive(steps)
        assertEquals(1, groups.size)
        assertEquals(steps, groups[0])
    }

    @Test fun `consecutive same-group steps merge into one group`() {
        val steps = listOf(
            CleanupStep(CleanupStepGroup.OMNIROUTE, "claude/claude-sonnet-4-6"),
            CleanupStep(CleanupStepGroup.OMNIROUTE, "cx/gpt-5.5"),
            CleanupStep(CleanupStepGroup.OMNIROUTE, "gemini/gemini-2.5-flash"),
        )
        val groups = CleanupWaterfallPlanner.groupConsecutive(steps)
        assertEquals(1, groups.size)
        assertEquals(3, groups[0].size)
    }

    @Test fun `different groups stay separate even when adjacent`() {
        val steps = listOf(
            CleanupStep(CleanupStepGroup.OMNIROUTE, "claude/claude-sonnet-4-6"),
            CleanupStep(CleanupStepGroup.OPENAI_DIRECT, "gpt-4o-mini"),
            CleanupStep(CleanupStepGroup.ANTHROPIC_DIRECT, "claude-haiku-4-5"),
        )
        val groups = CleanupWaterfallPlanner.groupConsecutive(steps)
        assertEquals(3, groups.size)
        assertEquals(CleanupStepGroup.OMNIROUTE, groups[0][0].group)
        assertEquals(CleanupStepGroup.OPENAI_DIRECT, groups[1][0].group)
        assertEquals(CleanupStepGroup.ANTHROPIC_DIRECT, groups[2][0].group)
    }

    @Test fun `the full 5-step waterfall from ADR-0001 groups into 3 units`() {
        val steps = listOf(
            CleanupStep(CleanupStepGroup.OMNIROUTE, "claude/claude-sonnet-4-6"),
            CleanupStep(CleanupStepGroup.OMNIROUTE, "cx/gpt-5.5"),
            CleanupStep(CleanupStepGroup.OMNIROUTE, "gemini/gemini-2.5-flash"),
            CleanupStep(CleanupStepGroup.OPENAI_DIRECT, "gpt-4o-mini"),
            CleanupStep(CleanupStepGroup.ANTHROPIC_DIRECT, "claude-haiku-4-5"),
        )
        val groups = CleanupWaterfallPlanner.groupConsecutive(steps)
        assertEquals(3, groups.size)
        assertEquals(3, groups[0].size) // OmniRoute sub-steps fail together
        assertEquals(1, groups[1].size)
        assertEquals(1, groups[2].size)
    }

    @Test fun `revisiting the same group later stays a separate group (non-consecutive)`() {
        // Pathological but should not silently merge non-adjacent runs of the same group.
        val steps = listOf(
            CleanupStep(CleanupStepGroup.OMNIROUTE, "claude/claude-sonnet-4-6"),
            CleanupStep(CleanupStepGroup.OPENAI_DIRECT, "gpt-4o-mini"),
            CleanupStep(CleanupStepGroup.OMNIROUTE, "cx/gpt-5.5"),
        )
        val groups = CleanupWaterfallPlanner.groupConsecutive(steps)
        assertEquals(3, groups.size)
    }

    @Test fun `flattenIndex reverses groupConsecutive`() {
        val steps = listOf(
            CleanupStep(CleanupStepGroup.OMNIROUTE, "claude/claude-sonnet-4-6"),
            CleanupStep(CleanupStepGroup.OMNIROUTE, "cx/gpt-5.5"),
            CleanupStep(CleanupStepGroup.OPENAI_DIRECT, "gpt-4o-mini"),
        )
        val groups = CleanupWaterfallPlanner.groupConsecutive(steps)
        assertEquals(steps, CleanupWaterfallPlanner.flattenIndex(groups))
    }
}

class CleanupWaterfallCursorTest {

    @Test fun `starts at index 0 with no prior success`() {
        val cursor = CleanupWaterfallCursor()
        assertEquals(0, cursor.startIndex(nowMs = 1_000L))
    }

    @Test fun `starts at last successful index within the idle window`() {
        val cursor = CleanupWaterfallCursor(idleExpiryMs = 10_000L)
        cursor.recordSuccess(stepIndex = 3, nowMs = 1_000L)
        assertEquals(3, cursor.startIndex(nowMs = 5_000L))
    }

    @Test fun `expires back to 0 after the idle window elapses`() {
        val cursor = CleanupWaterfallCursor(idleExpiryMs = 10_000L)
        cursor.recordSuccess(stepIndex = 3, nowMs = 1_000L)
        assertEquals(0, cursor.startIndex(nowMs = 20_000L))
    }

    @Test fun `reset forces back to 0 immediately, simulating a network change`() {
        val cursor = CleanupWaterfallCursor(idleExpiryMs = 10_000L)
        cursor.recordSuccess(stepIndex = 3, nowMs = 1_000L)
        cursor.reset()
        assertEquals(0, cursor.startIndex(nowMs = 1_001L))
    }

    @Test fun `a fresh success after reset moves the cursor again`() {
        val cursor = CleanupWaterfallCursor(idleExpiryMs = 10_000L)
        cursor.recordSuccess(stepIndex = 3, nowMs = 1_000L)
        cursor.reset()
        cursor.recordSuccess(stepIndex = 1, nowMs = 2_000L)
        assertEquals(1, cursor.startIndex(nowMs = 2_500L))
    }

    @Test fun `idle expiry is evaluated relative to the last success, not first`() {
        val cursor = CleanupWaterfallCursor(idleExpiryMs = 10_000L)
        cursor.recordSuccess(stepIndex = 2, nowMs = 1_000L)
        cursor.recordSuccess(stepIndex = 2, nowMs = 15_000L) // renews the clock
        assertTrue(cursor.startIndex(nowMs = 20_000L) == 2) // only 5s since last success
    }
}
