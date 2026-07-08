package com.trevornk.ramblr

/**
 * Pure mapping from the waterfall's wall-clock deadline to the native inference budget passed to
 * [LlamaCppInference.setInferenceBudgetMs] (#92). Split out from [LlamaCppInference] so it's
 * unit-testable without touching that class's companion `init` block, which loads a native library
 * unavailable in a plain JVM test -- the same pattern as [LlamaCompletionAccumulator] and
 * [LocalCleanupModelSlot].
 *
 * The native side reads the result as:
 *   - `> 0` : abort an in-flight `llama_decode` once this many ms elapse.
 *   - `== 0` ([NO_DEADLINE]): no native deadline -- used by the [Long.MAX_VALUE] default that
 *     direct callers and tests pass when they don't want one.
 *   - `< 0` : the deadline is already in the past, so decoding aborts at the first check instead
 *     of starting work it has no budget to finish.
 */
object InferenceBudget {
    const val NO_DEADLINE = 0L

    /**
     * Remaining budget for a completion that must finish by [deadlineAtMs] (a
     * [System.currentTimeMillis] wall-clock value), evaluated at [nowMs]. [Long.MAX_VALUE] means
     * "no deadline" and maps to [NO_DEADLINE]; a future deadline maps to its positive millisecond
     * remainder. A deadline that is exactly now or already past maps to `-1`, never `0`: a `0`
     * would collide with the [NO_DEADLINE] sentinel and tell the native side "no deadline" for a
     * budget that is actually spent -- the opposite of intended -- so any non-positive remainder is
     * clamped to `-1` ("abort at the first check") instead (L1).
     */
    fun budgetMs(deadlineAtMs: Long, nowMs: Long): Long {
        if (deadlineAtMs == Long.MAX_VALUE) return NO_DEADLINE
        val remaining = deadlineAtMs - nowMs
        return if (remaining <= 0) -1 else remaining
    }
}
