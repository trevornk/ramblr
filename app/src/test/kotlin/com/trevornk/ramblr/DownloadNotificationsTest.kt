package com.trevornk.ramblr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [DownloadNotifications] is mostly thin Android-API-bound notification building
 * (NotificationCompat.Builder, NotificationManager) with nothing meaningfully pure to unit test
 * without Robolectric, which this project doesn't use (see ModelDownloadWorkerTest for the same
 * constraint on WorkManager types). The three functions here are the actual pure logic extracted
 * out of that Android-bound code, so they're the ones worth testing directly.
 */
class DownloadNotificationsTest {

    // -- update throttling --

    @Test fun `does not post again for less than a 5 point jump`() {
        assertFalse(DownloadNotifications.shouldPostUpdate(lastPercent = 10, currentPercent = 12))
        assertFalse(DownloadNotifications.shouldPostUpdate(lastPercent = 0, currentPercent = 4))
    }

    @Test fun `posts once a 5 point jump is reached`() {
        assertTrue(DownloadNotifications.shouldPostUpdate(lastPercent = 10, currentPercent = 15))
        assertTrue(DownloadNotifications.shouldPostUpdate(lastPercent = 0, currentPercent = 5))
    }

    @Test fun `always posts on reaching 100, even from a partial step`() {
        assertTrue(DownloadNotifications.shouldPostUpdate(lastPercent = 97, currentPercent = 100))
    }

    @Test fun `first update always posts, since -100 as the initial sentinel is far below any real percent`() {
        assertTrue(DownloadNotifications.shouldPostUpdate(lastPercent = -100, currentPercent = 0))
    }

    @Test fun `never regresses -- a jump backwards never posts spuriously`() {
        assertFalse(DownloadNotifications.shouldPostUpdate(lastPercent = 50, currentPercent = 48))
    }

    // -- notification ids --

    @Test fun `notificationId is stable for the same archive`() {
        assertEquals(
            DownloadNotifications.notificationId("sherpa-onnx-whisper-base.en"),
            DownloadNotifications.notificationId("sherpa-onnx-whisper-base.en")
        )
    }

    @Test fun `notificationId differs across the real catalog, so concurrent downloads don't collide`() {
        val all = MODEL_CATALOG + STREAMING_MODEL_CATALOG + LOCAL_CLEANUP_MODEL_CATALOG
        val ids = all.map { DownloadNotifications.notificationId(it.archive) }
        assertEquals(ids.size, ids.toSet().size)
    }

    @Test fun `notificationId is never negative, since it's used as a notification id`() {
        val all = MODEL_CATALOG + STREAMING_MODEL_CATALOG + LOCAL_CLEANUP_MODEL_CATALOG
        for (model in all) {
            assertTrue(DownloadNotifications.notificationId(model.archive) >= 0)
        }
    }

    @Test fun `notificationId stays non-negative even for an Int MIN_VALUE hashCode, and its plus-one never overflows (L7)`() {
        // "polygenelubricants" is the canonical String whose hashCode() is Int.MIN_VALUE, where the
        // old abs()-based id overflowed back to a negative value and resultNotificationId's +1 could
        // wrap. The mask keeps both non-negative.
        val worstCase = "polygenelubricants"
        assertEquals(Int.MIN_VALUE, worstCase.hashCode())
        assertTrue(DownloadNotifications.notificationId(worstCase) >= 0)
        assertTrue(DownloadNotifications.resultNotificationId(worstCase) >= 0)
        assertNotEquals(
            DownloadNotifications.notificationId(worstCase),
            DownloadNotifications.resultNotificationId(worstCase),
        )
    }

    @Test fun `resultNotificationId differs from the in-progress id, so WorkManager tearing down the foreground notification can't race-cancel it`() {
        val all = MODEL_CATALOG + STREAMING_MODEL_CATALOG + LOCAL_CLEANUP_MODEL_CATALOG
        for (model in all) {
            assertNotEquals(
                DownloadNotifications.notificationId(model.archive),
                DownloadNotifications.resultNotificationId(model.archive)
            )
        }
    }
}
