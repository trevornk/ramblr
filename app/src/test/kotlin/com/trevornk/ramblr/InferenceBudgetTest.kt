package com.trevornk.ramblr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers the pure deadline -> native-budget mapping [LlamaCppInference.complete] hands to
 * [LlamaCppInference.setInferenceBudgetMs] (#92). The native side turns a positive budget into a
 * mid-decode abort deadline, zero into "no deadline", and a negative value into "abort at the
 * first check" -- so the sign boundaries here are the contract that keeps a stuck/slow
 * `llama_decode` from running past the waterfall's wall-clock budget.
 */
class InferenceBudgetTest {
    @Test fun `the no-deadline sentinel maps to zero`() {
        assertEquals(InferenceBudget.NO_DEADLINE, InferenceBudget.budgetMs(Long.MAX_VALUE, 1_000L))
        assertEquals(0L, InferenceBudget.NO_DEADLINE)
    }

    @Test fun `a future deadline maps to the positive remaining budget`() {
        assertEquals(15_000L, InferenceBudget.budgetMs(deadlineAtMs = 65_000L, nowMs = 50_000L))
    }

    @Test fun `an already-passed deadline maps to -1 so decoding aborts immediately`() {
        val budget = InferenceBudget.budgetMs(deadlineAtMs = 50_000L, nowMs = 65_000L)
        assertTrue("expected a negative budget, was $budget", budget < 0)
        assertEquals(-1L, budget)
    }

    @Test fun `a deadline exactly now maps to -1, not zero, so it never collides with NO_DEADLINE`() {
        // 0 is the NO_DEADLINE sentinel; a spent budget must abort at the first check (-1), not be
        // read as "no deadline at all" (L1).
        val budget = InferenceBudget.budgetMs(deadlineAtMs = 50_000L, nowMs = 50_000L)
        assertEquals(-1L, budget)
        assertNotEquals(InferenceBudget.NO_DEADLINE, budget)
    }
}
