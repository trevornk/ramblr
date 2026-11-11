package com.trevornk.ramblr

import androidx.work.Data
import androidx.work.WorkInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** #368: a backing-off retry is ENQUEUED to WorkManager; the UI must say why instead of
 *  "Starting download...". */
class RetryStatusTextTest {

    @Test fun `formats attempt, max and message`() {
        assertEquals("Retrying (2 of 3): HTTP 503", ModelDownloadWorker.retryStatusText(2, 3, "HTTP 503"))
    }

    @Test fun `null and blank messages omit the colon`() {
        assertEquals("Retrying (1 of 3)", ModelDownloadWorker.retryStatusText(1, 3, null))
        assertEquals("Retrying (1 of 3)", ModelDownloadWorker.retryStatusText(1, 3, "   "))
    }

    @Test fun `long messages are capped`() {
        val text = ModelDownloadWorker.retryStatusText(2, 3, "x".repeat(500))
        assertEquals("Retrying (2 of 3): ".length + ModelDownloadWorker.RETRY_MESSAGE_MAX_CHARS, text.length)
    }

    private fun retrying() = Data.Builder()
        .putString(ModelDownloadWorker.KEY_PHASE, ModelDownloadWorker.PHASE_RETRYING)
        .putString(ModelDownloadWorker.KEY_ERROR, "timeout")
        .putInt(ModelDownloadWorker.KEY_ATTEMPT, 2)
        .putInt(ModelDownloadWorker.KEY_MAX_ATTEMPTS, 3)
        .build()

    @Test fun `ENQUEUED with retrying progress shows the retry line`() {
        assertEquals("Retrying (2 of 3): timeout", ModelDownloadWorker.retryStatusFor(WorkInfo.State.ENQUEUED, retrying()))
    }

    @Test fun `RUNNING or non-retrying progress shows no retry line`() {
        assertNull(ModelDownloadWorker.retryStatusFor(WorkInfo.State.RUNNING, retrying()))
        assertNull(ModelDownloadWorker.retryStatusFor(WorkInfo.State.ENQUEUED, Data.EMPTY))
    }
}
