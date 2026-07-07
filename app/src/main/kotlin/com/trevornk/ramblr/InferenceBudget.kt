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
     * "no deadline" and maps to [NO_DEADLINE]; anything else is the signed millisecond remainder,
     * which is deliberately allowed to go negative when the deadline has already passed.
     */
    fun budgetMs(deadlineAtMs: Long, nowMs: Long): Long =
        if (deadlineAtMs == Long.MAX_VALUE) NO_DEADLINE else deadlineAtMs - nowMs
}
