package com.kafkasl.phonewhisper

/**
 * Timeouts for one waterfall step's network client. See ADR-0001 / docs/adr/0001-cleanup-
 * waterfall.md: the connect timeout is deliberately short so an unreachable host (e.g. OmniRoute
 * while away from home) fails fast instead of hanging to the full read timeout, and the read
 * timeout absorbs a real but slow completion without letting one bad step wreck the whole chain.
 */
data class CleanupStepTimeouts(
    val connectMs: Long = 1_500L,
    val readMs: Long = 7_000L,
) {
    companion object {
        val DEFAULT = CleanupStepTimeouts()
    }
}

/** Hard cap on the whole waterfall, regardless of how many steps remain, before falling back to raw injection. */
const val CLEANUP_WATERFALL_HARD_CAP_MS = 15_000L

/**
 * Pure grouping/ordering logic for [CleanupWaterfall.steps], split out from the network-calling
 * executor so it's independently unit-testable without any I/O. Groups consecutive steps that
 * share a [CleanupStepGroup] into one fail-fast unit: if the first step in a group fails due to
 * a connection-level failure (host unreachable/timeout — see [CleanupStepOutcome.ConnectionFailed]),
 * every remaining step in that same group is skipped without being attempted, since they'd fail
 * identically against the same dead host. A non-connection failure (e.g. a real HTTP error from
 * a reachable host) only fails that one step; the next step in the same group is still attempted.
 */
object CleanupWaterfallPlanner {
    /**
     * Splits [steps] into runs of consecutive equal-[CleanupStep.group] entries, preserving
     * overall order. Each inner list is one fail-fast unit for [CleanupWaterfallExecutor].
     */
    fun groupConsecutive(steps: List<CleanupStep>): List<List<CleanupStep>> {
        if (steps.isEmpty()) return emptyList()
        val result = mutableListOf<MutableList<CleanupStep>>()
        for (step in steps) {
            val last = result.lastOrNull()
            if (last != null && last.last().group == step.group) {
                last.add(step)
            } else {
                result.add(mutableListOf(step))
            }
        }
        return result
    }

    /**
     * Flattens [groups] back into a single ordered index -> step list, used to translate a
     * cursor position (see [CleanupWaterfallCursor]) into "which step to start at".
     */
    fun flattenIndex(groups: List<List<CleanupStep>>): List<CleanupStep> = groups.flatten()
}

/** Why a waterfall step did not produce a result. Distinguishes connection-level failures
 *  (which disqualify the rest of that step's group) from step-level failures (which don't). */
sealed class CleanupStepOutcome {
    data class Success(val text: String) : CleanupStepOutcome()
    /** Host unreachable, connect timeout, or read timeout — the whole group is dead for this call. */
    data class ConnectionFailed(val message: String?) : CleanupStepOutcome()
    /** Reachable host, but this step failed on its own (bad model name, HTTP error, malformed response). */
    data class StepFailed(val message: String?) : CleanupStepOutcome()
}

/**
 * Tracks the index of the last waterfall step that succeeded, so the next cleanup call can
 * start there instead of re-probing from step 0 every time (see ADR-0001). Reset to 0 on
 * Android network-change (SSID/VPN change) by the caller (see WhisperAccessibilityService's
 * ConnectivityManager.NetworkCallback registration) and on idle expiry, both handled by the
 * owner of this cursor, not by this class itself — this class only tracks the index and idle
 * timing so both reset triggers can be tested without Android framework dependencies.
 */
class CleanupWaterfallCursor(private val idleExpiryMs: Long = 5 * 60 * 1000L) {
    @Volatile private var index: Int = 0
    @Volatile private var lastSuccessAt: Long = 0L

    /** The step index to start the next call at, given the current time. Expires to 0 if idle too long. */
    fun startIndex(nowMs: Long): Int {
        if (index != 0 && nowMs - lastSuccessAt > idleExpiryMs) {
            index = 0
        }
        return index
    }

    fun recordSuccess(stepIndex: Int, nowMs: Long) {
        index = stepIndex
        lastSuccessAt = nowMs
    }

    /** Called on Android connectivity/network change (SSID/VPN transition). */
    fun reset() {
        index = 0
    }
}
