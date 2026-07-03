package com.kafkasl.phonewhisper

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

    @Test fun `workName differs for different archives, so unrelated model downloads don't collide`() {
        val names = MODEL_CATALOG.map { ModelDownloadWorker.workName(it.archive) }
        assertEquals(names.size, names.toSet().size)
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
}
