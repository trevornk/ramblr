package com.trevornk.ramblr

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadCompletionGateTest {

    @Test fun `a success witnessed live in this instance acts exactly once`() {
        val gate = DownloadCompletionGate()
        gate.onInFlight("work-1")
        assertTrue(gate.shouldActOnSuccess("work-1"))
        // WorkManager LiveData can re-deliver the same terminal WorkInfo; only the first acts.
        assertFalse(gate.shouldActOnSuccess("work-1"))
    }

    @Test fun `a stale success never seen in-flight is ignored`() {
        // Fresh Activity instance after rotation/fold: retained SUCCEEDED WorkInfo arrives at
        // observer registration without any prior ENQUEUED/RUNNING sighting.
        val gate = DownloadCompletionGate()
        assertFalse(gate.shouldActOnSuccess("stale-work"))
        // And staying stale doesn't accidentally mark it acted-upon-able later either.
        gate.onInFlight("stale-work")
        assertTrue(gate.shouldActOnSuccess("stale-work"))
    }

    @Test fun `a re-downloaded model (new work id) acts again while the old id stays dead`() {
        val gate = DownloadCompletionGate()
        gate.onInFlight("attempt-1")
        assertTrue(gate.shouldActOnSuccess("attempt-1"))

        gate.onInFlight("attempt-2")
        assertTrue(gate.shouldActOnSuccess("attempt-2"))
        assertFalse(gate.shouldActOnSuccess("attempt-1"))
    }

    @Test fun `repeated in-flight sightings are idempotent`() {
        val gate = DownloadCompletionGate()
        gate.onInFlight("w")
        gate.onInFlight("w")
        gate.onInFlight("w")
        assertTrue(gate.shouldActOnSuccess("w"))
        assertFalse(gate.shouldActOnSuccess("w"))
    }
}
