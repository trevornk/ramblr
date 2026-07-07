package com.trevornk.ramblr

import org.junit.Assert.*
import org.junit.Test

class RecordingDurationCapTest {

    @Test fun `not exceeded before the cap`() {
        assertFalse(RecordingDurationCap.exceeded(startedAtMs = 0, nowMs = 500, maxDurationMs = 1000))
    }

    @Test fun `exceeded exactly at the cap`() {
        assertTrue(RecordingDurationCap.exceeded(startedAtMs = 0, nowMs = 1000, maxDurationMs = 1000))
    }

    @Test fun `exceeded past the cap`() {
        assertTrue(RecordingDurationCap.exceeded(startedAtMs = 0, nowMs = 1500, maxDurationMs = 1000))
    }

    @Test fun `works with a non-zero start time`() {
        assertFalse(RecordingDurationCap.exceeded(startedAtMs = 10_000, nowMs = 10_999, maxDurationMs = 1000))
        assertTrue(RecordingDurationCap.exceeded(startedAtMs = 10_000, nowMs = 11_000, maxDurationMs = 1000))
    }
}
