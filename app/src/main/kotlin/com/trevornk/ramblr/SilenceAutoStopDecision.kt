package com.trevornk.ramblr

/**
 * Pure "has trailing silence exceeded the configured threshold" check for silence-based
 * auto-stop (#108, mode 1), extracted the same way [RecordingDurationCap] extracts the
 * max-duration check -- so the actual stop decision is unit-testable without any native VAD
 * dependency or Android [android.content.Context].
 *
 * [lastSpeechAtMs] is the wall-clock time speech was last detected (or the recording's start
 * time, if no speech has been detected yet -- silence from the very start of a recording still
 * counts toward the threshold, same as silence after some speech).
 */
object SilenceAutoStopDecision {
    fun shouldTrigger(lastSpeechAtMs: Long, nowMs: Long, thresholdMs: Long): Boolean =
        nowMs - lastSpeechAtMs >= thresholdMs
}
