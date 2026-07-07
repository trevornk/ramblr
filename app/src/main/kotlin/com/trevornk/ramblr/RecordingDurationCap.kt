package com.trevornk.ramblr

/** Pure elapsed-time check for the recording max-duration cap, extracted so it's unit-testable. */
object RecordingDurationCap {
    fun exceeded(startedAtMs: Long, nowMs: Long, maxDurationMs: Long): Boolean =
        nowMs - startedAtMs >= maxDurationMs
}
