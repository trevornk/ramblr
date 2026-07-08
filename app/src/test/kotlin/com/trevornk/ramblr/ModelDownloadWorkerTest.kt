package com.trevornk.ramblr

import androidx.work.WorkInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelDownloadWorkerTest {

    // -- unique work name keying --

    @Test fun `workName is derived from the archive name`() {
        assertEquals(
            "model-download:sherpa-onnx-nemo-parakeet-tdt-0.6b-v3-int8",
            ModelDownloadWorker.workName("sherpa-onnx-nemo-parakeet-tdt-0.6b-v3-int8")
        )
    }

    @Test fun `workName differs for different archives across all three catalogs, so downloads don't collide`() {
        val names = (MODEL_CATALOG + STREAMING_MODEL_CATALOG + LOCAL_CLEANUP_MODEL_CATALOG)
            .map { ModelDownloadWorker.workName(it.archive) }
        assertEquals(names.size, names.toSet().size)
    }

    @Test fun `worker's model lookup covers the offline, streaming, and local-cleanup catalogs`() {
        // Mirrors ModelDownloadWorker.doWork()'s lookup so a download request for a model in any of
        // the three catalogs is recognized, not rejected as "Unknown model". Previously the third
        // (local-cleanup) catalog wasn't exercised here (audit test-gap #7).
        val all = MODEL_CATALOG + STREAMING_MODEL_CATALOG + LOCAL_CLEANUP_MODEL_CATALOG
        assertEquals(STREAMING_MODEL, all.firstOrNull { it.archive == STREAMING_MODEL.archive })
        assertEquals(MODEL_CATALOG.first(), all.firstOrNull { it.archive == MODEL_CATALOG.first().archive })
        assertEquals(
            LOCAL_CLEANUP_MODEL_CATALOG.first(),
            all.firstOrNull { it.archive == LOCAL_CLEANUP_MODEL_CATALOG.first().archive },
        )
    }

    // -- reload-kind classification for post-download service activation (#H5) --

    @Test fun `a downloaded offline model needs a transcription reload`() {
        assertEquals(
            ModelDownloadWorker.ModelReloadKind.TRANSCRIPTION,
            ModelDownloadWorker.reloadKindFor(MODEL_CATALOG.first().archive)
        )
    }

    @Test fun `a downloaded streaming model needs a streaming reload`() {
        assertEquals(
            ModelDownloadWorker.ModelReloadKind.STREAMING,
            ModelDownloadWorker.reloadKindFor(STREAMING_MODEL.archive)
        )
    }

    @Test fun `a downloaded local-cleanup model needs no native reload`() {
        // Local-cleanup models are loaded on demand by file path at cleanup time, so there is no
        // slot to reload -- but the archive must still be recognized (not null).
        assertEquals(
            ModelDownloadWorker.ModelReloadKind.LOCAL_CLEANUP,
            ModelDownloadWorker.reloadKindFor(LOCAL_CLEANUP_MODEL.archive)
        )
    }

    @Test fun `an unknown archive maps to no reload`() {
        assertEquals(null, ModelDownloadWorker.reloadKindFor("not-a-real-archive"))
    }

    @Test fun `every downloadable catalog model classifies to a non-null reload kind`() {
        // Guards against a future catalog addition that the post-download activation path would
        // silently ignore (never reloading it into the running service, reviving the #H5 bug).
        (MODEL_CATALOG + STREAMING_MODEL_CATALOG + LOCAL_CLEANUP_MODEL_CATALOG).forEach { model ->
            assertTrue(
                "no reload kind for ${model.archive}",
                ModelDownloadWorker.reloadKindFor(model.archive) != null
            )
        }
    }

    @Test fun `workName is stable for the same archive, so re-enqueuing targets the same unique work`() {
        val a = ModelDownloadWorker.workName("sherpa-onnx-whisper-base.en")
        val b = ModelDownloadWorker.workName("sherpa-onnx-whisper-base.en")
        assertEquals(a, b)
    }

    // -- duplicate-tap guard state --

    @Test fun `isInFlight is true while enqueued or running`() {
        assertTrue(ModelDownloadWorker.isInFlight(WorkInfo.State.ENQUEUED))
        assertTrue(ModelDownloadWorker.isInFlight(WorkInfo.State.RUNNING))
    }

    @Test fun `isInFlight is false once terminal or absent, so a retry tap is allowed`() {
        assertFalse(ModelDownloadWorker.isInFlight(WorkInfo.State.SUCCEEDED))
        assertFalse(ModelDownloadWorker.isInFlight(WorkInfo.State.FAILED))
        assertFalse(ModelDownloadWorker.isInFlight(WorkInfo.State.CANCELLED))
        assertFalse(ModelDownloadWorker.isInFlight(null))
    }

    @Test fun `isInFlight never overlaps a finished WorkInfo State`() {
        // Guards against the guard becoming stale if WorkManager ever adds a new
        // State: an in-flight state must never also read as finished, or a
        // completed/failed download would stay stuck showing as in-progress.
        for (state in WorkInfo.State.values()) {
            if (ModelDownloadWorker.isInFlight(state)) assertFalse(state.isFinished)
        }
    }

    // -- retry classification (#86) --

    @Test fun `transient IO failures retry while attempts remain`() {
        assertTrue(ModelDownloadWorker.shouldRetry(java.io.IOException("HTTP 503"), runAttemptCount = 0))
        assertTrue(ModelDownloadWorker.shouldRetry(java.io.IOException("unexpected end of stream"), runAttemptCount = 1))
    }

    @Test fun `retries stop at the attempt cap even for transient failures`() {
        assertFalse(
            ModelDownloadWorker.shouldRetry(
                java.io.IOException("HTTP 503"),
                runAttemptCount = ModelDownloadWorker.MAX_ATTEMPTS - 1,
            )
        )
    }

    @Test fun `checksum mismatch and not-enough-space are terminal despite being IOExceptions`() {
        // Complete-but-wrong bytes and a full disk can't be fixed by re-running the download.
        assertFalse(ModelDownloadWorker.shouldRetry(ChecksumMismatchException("aa", "bb"), runAttemptCount = 0))
        assertFalse(ModelDownloadWorker.shouldRetry(NotEnoughSpaceException(2_000_000L, 1_000_000L), runAttemptCount = 0))
    }

    @Test fun `non-IO failures and unknown causes are terminal`() {
        assertFalse(ModelDownloadWorker.shouldRetry(IllegalStateException("No checksum configured"), runAttemptCount = 0))
        assertFalse(ModelDownloadWorker.shouldRetry(IllegalArgumentException("Path traversal"), runAttemptCount = 0))
        assertFalse(ModelDownloadWorker.shouldRetry(null, runAttemptCount = 0))
    }

    @Test fun `a cancelled download is terminal, never retried by the backoff (L10)`() {
        assertFalse(ModelDownloadWorker.shouldRetry(DownloadCancelledException(), runAttemptCount = 0))
    }
}
