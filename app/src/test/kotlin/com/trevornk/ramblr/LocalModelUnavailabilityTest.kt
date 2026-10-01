package com.trevornk.ramblr

import android.content.ComponentCallbacks2
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalModelUnavailabilityTest {
    @Test fun `UI_HIDDEN does not release transcribers (#280)`() {
        assertFalse(TranscriberTrimPolicy.shouldReleaseTranscribers(ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN))
    }

    @Test fun `real memory pressure still releases transcribers`() {
        listOf(
            ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW,
            ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL,
            ComponentCallbacks2.TRIM_MEMORY_BACKGROUND,
            ComponentCallbacks2.TRIM_MEMORY_MODERATE,
            ComponentCallbacks2.TRIM_MEMORY_COMPLETE,
        ).forEach { assertTrue("level $it", TranscriberTrimPolicy.shouldReleaseTranscribers(it)) }
    }

    @Test fun `below RUNNING_LOW never releases`() {
        assertFalse(TranscriberTrimPolicy.shouldReleaseTranscribers(ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE))
    }

    @Test fun `installed model never reports downloading`() {
        assertEquals(LocalModelUnavailability.FAILED_TO_LOAD, LocalModelUnavailability.message(installed = true, downloadInFlight = true))
        assertEquals(LocalModelUnavailability.FAILED_TO_LOAD, LocalModelUnavailability.message(installed = true, downloadInFlight = false))
    }

    @Test fun `downloading only when nothing installed and a download is in flight`() {
        assertEquals(LocalModelUnavailability.DOWNLOADING, LocalModelUnavailability.message(installed = false, downloadInFlight = true))
        assertEquals(LocalModelUnavailability.NOT_INSTALLED, LocalModelUnavailability.message(installed = false, downloadInFlight = false))
    }
}
