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

    // --- #169: prefetch at local-mode selection ---------------------------------------------
    //
    // shouldFetch alone still leaves exactly one bad take: the fetch it triggers is asynchronous,
    // so the dictation that triggers it always decodes unsegmented (665 MB RSS for 47 s of audio
    // on a 4 GB device). shouldPrefetchForLocalMode moves provisioning to the moment on-device
    // transcription is *chosen*, so the download overlaps setup instead of racing first use.

    @Test fun `prefetches when local mode is selected and no model is installed`() {
        assertTrue(
            VadModelProvisioning.shouldPrefetchForLocalMode(
                localTranscriptionSelected = true,
                modelInstalled = false,
            )
        )
    }

    /** A cloud-only user must never pay for a download they will never use. */
    @Test fun `does not prefetch for a cloud transcription user`() {
        assertFalse(
            VadModelProvisioning.shouldPrefetchForLocalMode(
                localTranscriptionSelected = false,
                modelInstalled = false,
            )
        )
    }

    /** Keeps repeated visits to the Transcription screen from re-enqueueing forever. */
    @Test fun `does not prefetch when the model is already installed`() {
        assertFalse(
            VadModelProvisioning.shouldPrefetchForLocalMode(
                localTranscriptionSelected = true,
                modelInstalled = true,
            )
        )
    }

    /** Cloud mode wins even with nothing on disk — mode is the gate, not installedness. */
    @Test fun `does not prefetch for a cloud user even with the model installed`() {
        assertFalse(
            VadModelProvisioning.shouldPrefetchForLocalMode(
                localTranscriptionSelected = false,
                modelInstalled = true,
            )
        )
    }

    /**
     * The regression this issue is actually about. A fresh install that picked on-device
     * transcription must have provisioning already triggered *before* the first dictation runs,
     * so that dictation sees an installed model and takes the segmented path. Asserting the two
     * predicates in sequence is what distinguishes the fix from the pre-#169 behavior: without
     * the prefetch the first take is the thing that enqueues, and it decodes unsegmented.
     */
    @Test fun `fresh install selecting local mode provisions before the first dictation`() {
        // Step 1: user picks on-device transcription during onboarding. Nothing on disk yet.
        val prefetches = VadModelProvisioning.shouldPrefetchForLocalMode(
            localTranscriptionSelected = true,
            modelInstalled = false,
        )
        assertTrue("selecting local mode must provision the VAD model up front", prefetches)

        // Step 2: the download lands during setup, so the first dictation finds it installed and
        // must NOT need the transcription-time backstop at all.
        assertFalse(
            "first dictation should not have to fetch — the model is already there",
            VadModelProvisioning.shouldFetch(modelInstalled = true, downloadInFlight = false)
        )
    }

    /**
     * No regression to the genuinely-missing-model case (#169 acceptance criterion 2): a user who
     * selected local mode while offline gets no model from the prefetch, and the transcription-time
     * backstop must still fire so the model eventually arrives.
     */
    @Test fun `transcription-time backstop still fires when the prefetch could not deliver`() {
        assertTrue(
            VadModelProvisioning.shouldFetch(modelInstalled = false, downloadInFlight = false)
        )
    }
}
