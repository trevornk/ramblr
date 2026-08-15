package com.trevornk.ramblr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #139: the #132 segmented-decode OOM fix only engages when `silero_vad.onnx` is installed, but
 * the model was previously downloaded *only* as a side effect of enabling silence auto-stop
 * (#108). A local-transcription user who never opened that toggle therefore decoded every take
 * unsegmented — the exact single-shot native decode #132 exists to prevent.
 *
 * These cover the provisioning decision itself. The decode-strategy fallback is unchanged and
 * still asserted by the existing #132 tests: this only governs whether we ever *fetch* the model.
 */
class VadModelProvisioningTest {

    @Test fun `fetches when a local transcription runs with no model installed`() {
        assertTrue(
            VadModelProvisioning.shouldFetch(modelInstalled = false, downloadInFlight = false)
        )
    }

    @Test fun `does not fetch when the model is already installed`() {
        assertFalse(
            VadModelProvisioning.shouldFetch(modelInstalled = true, downloadInFlight = false)
        )
    }

    /**
     * WorkManager's unique-work KEEP policy would already collapse a duplicate enqueue, but
     * short-circuiting here keeps repeated dictations from churning WorkManager on every take
     * during the download window.
     */
    @Test fun `does not re-fetch while a download is already in flight`() {
        assertFalse(
            VadModelProvisioning.shouldFetch(modelInstalled = false, downloadInFlight = true)
        )
    }

    @Test fun `installed model wins even if a stale download is somehow in flight`() {
        assertFalse(
            VadModelProvisioning.shouldFetch(modelInstalled = true, downloadInFlight = true)
        )
    }

    /**
     * The VAD model must stay free-licensed: [ModelDownloadWorker.enqueue] refuses to download any
     * model needing license consent, so a non-free VAD model would make this path silently no-op
     * and permanently strand segmented decode — the very bug #139 fixes.
     */
    @Test fun `silero vad is free-licensed so provisioning needs no consent prompt`() {
        assertTrue(SILERO_VAD_MODEL.license.isFree)
        assertTrue(SILERO_VAD_MODEL.isVadModel)
    }

    /** Guards the "trivial one-time fetch" claim in #139 — this must not become a bulky download. */
    @Test fun `silero vad stays a small download`() {
        assertEquals(1, SILERO_VAD_MODEL.sizeMb)
    }
}
