package com.trevornk.ramblr

/**
 * Decides whether a SUCCEEDED model-download WorkInfo should trigger its one-time completion
 * side effects -- auto-select, "ready!" toast, native model reload (#76).
 *
 * WorkManager retains finished unique work indefinitely and re-delivers the retained SUCCEEDED
 * WorkInfo to every fresh observer, and MainActivity is recreated with fresh in-memory state on
 * every rotation/fold-posture change. Deduping by archive name in a plain instance set therefore
 * reset on each recreation: for days after any successful download, every app open / rotation /
 * fold re-ran selectModel(...), silently reverting the user's manual model choice to whichever
 * downloaded archive's observer fired last, plus a spurious toast and a heavyweight reload.
 *
 * The rule: act on a success only if this instance previously observed the same work id in an
 * unfinished state (a live download), and only once. Stale successes re-delivered at observer
 * registration were never seen in-flight here, so they are ignored. Keyed by work id rather than
 * archive so a genuine re-download of the same model acts again.
 */
class DownloadCompletionGate {
    private val seenInFlight = mutableSetOf<String>()
    private val acted = mutableSetOf<String>()

    /** Record that [workId] was observed in a live (unfinished) state. Idempotent. */
    fun onInFlight(workId: String) {
        seenInFlight.add(workId)
    }

    /** True exactly once per [workId], and only if it was previously seen in-flight. */
    fun shouldActOnSuccess(workId: String): Boolean =
        seenInFlight.contains(workId) && acted.add(workId)
}
