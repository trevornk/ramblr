package com.trevornk.ramblr

import org.junit.Assert.*
import org.junit.Test

class SilenceAutoStopDecisionTest {

    @Test fun `not triggered before the threshold`() {
        assertFalse(SilenceAutoStopDecision.shouldTrigger(lastSpeechAtMs = 0, nowMs = 1999, thresholdMs = 2000))
    }

    @Test fun `triggered exactly at the threshold`() {
        assertTrue(SilenceAutoStopDecision.shouldTrigger(lastSpeechAtMs = 0, nowMs = 2000, thresholdMs = 2000))
    }

    @Test fun `triggered past the threshold`() {
        assertTrue(SilenceAutoStopDecision.shouldTrigger(lastSpeechAtMs = 0, nowMs = 5000, thresholdMs = 2000))
    }

    @Test fun `works with a non-zero last-speech time`() {
        assertFalse(SilenceAutoStopDecision.shouldTrigger(lastSpeechAtMs = 10_000, nowMs = 11_999, thresholdMs = 2000))
        assertTrue(SilenceAutoStopDecision.shouldTrigger(lastSpeechAtMs = 10_000, nowMs = 12_000, thresholdMs = 2000))
    }

    @Test fun `silence from the very start of a recording still counts`() {
        // lastSpeechAtMs seeded to the recording's start time when no speech has happened yet --
        // a user who never speaks still gets auto-stopped once the threshold elapses.
        val recordingStartedAtMs = 50_000L
        assertTrue(SilenceAutoStopDecision.shouldTrigger(
            lastSpeechAtMs = recordingStartedAtMs, nowMs = recordingStartedAtMs + 2500, thresholdMs = 2000
        ))
    }
}
