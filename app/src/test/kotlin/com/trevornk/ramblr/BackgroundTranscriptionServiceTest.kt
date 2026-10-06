package com.trevornk.ramblr

import android.app.Application
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Looper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.xmlpull.v1.XmlPullParser

/**
 * #284: wiring of the foreground service itself -- its declared type/permission, that it enters the
 * foreground with a dataSync notification, stops when nothing is held, and that the result
 * notifications live on their own channel and never leak transcript text.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BackgroundTranscriptionServiceTest {

    private lateinit var app: Application

    @Before fun setUp() {
        app = RuntimeEnvironment.getApplication()
        BackgroundTranscriptionService.resetForTest()
    }

    private fun startedServices(): List<Intent> {
        val out = mutableListOf<Intent>()
        while (true) out += shadowOf(app).nextStartedService ?: break
        return out
    }

    private fun nm() = app.getSystemService(NotificationManager::class.java)

    @Test fun `acquiring a hold requests exactly one service start, repeats do not duplicate it`() {
        val work = BackgroundTranscriptionService.work(app)
        work.begin(); work.begin()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1, startedServices().size)
        work.end(); work.end()
    }

    @Test fun `stopping under a live hold lets the next acquire start a fresh service, with no duplicate`() {
        val work = BackgroundTranscriptionService.work(app)
        work.begin() // dictation A
        shadowOf(Looper.getMainLooper()).idle()
        val a = Robolectric.buildService(BackgroundTranscriptionService::class.java, Intent(app, BackgroundTranscriptionService::class.java))
        val svc = a.create().startCommand(0, 1).get()
        startedServices() // drain A's start
        assertFalse(shadowOf(svc).isStoppedBySelf)

        svc.stopNow() // hard cap / platform timeout while A's hold is still counted
        assertEquals("A's hold is still counted, not stranded", 1, BackgroundTranscriptionService.heldCount())

        work.begin() // dictation B arrives
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals("B must get a fresh start", 1, startedServices().size)
        svc.onDestroy() // the platform's late destroy of the OLD instance must not clear B's pending request
        work.begin() // dictation C while B's start is still pending
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals("no duplicate start while one is pending", 0, startedServices().size)

        work.end(); work.end(); work.end()
    }

    @Test fun `a hold taken on a live instance refreshes its hard cap instead of starting a second service`() {
        val work = BackgroundTranscriptionService.work(app)
        work.begin()
        val ctrl = Robolectric.buildService(BackgroundTranscriptionService::class.java, Intent(app, BackgroundTranscriptionService::class.java))
        ctrl.create().startCommand(0, 1)
        startedServices()
        work.begin()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(0, startedServices().size)
        work.end(); work.end()
    }

    @Test fun `manifest declares the service non-exported with the dataSync type and its permission`() {
        val info = app.packageManager.getServiceInfo(
            android.content.ComponentName(app, BackgroundTranscriptionService::class.java),
            0,
        )
        assertFalse(info.exported)
        assertEquals(ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC, info.foregroundServiceType)
        val requested = app.packageManager.getPackageInfo(app.packageName, android.content.pm.PackageManager.GET_PERMISSIONS)
            .requestedPermissions.orEmpty().toSet()
        assertTrue(requested.contains("android.permission.FOREGROUND_SERVICE"))
        assertTrue(requested.contains("android.permission.FOREGROUND_SERVICE_DATA_SYNC"))
        assertTrue(requested.contains("android.permission.POST_NOTIFICATIONS"))
    }

    @Test fun `with a dictation held the service enters the foreground with an ongoing notification`() {
        val work = BackgroundTranscriptionService.work(app)
        work.begin()
        try {
            val ctrl = Robolectric.buildService(BackgroundTranscriptionService::class.java, Intent(app, BackgroundTranscriptionService::class.java))
            val svc = ctrl.create().startCommand(0, 1).get()
            val n: Notification = shadowOf(svc).lastForegroundNotification
            assertNotNull(n)
            assertTrue(n.flags and Notification.FLAG_ONGOING_EVENT != 0)
            assertEquals(BackgroundDictationNotifications.WORK_CHANNEL_ID, n.channelId)
            assertFalse("the ongoing notification must not contain transcript text",
                n.extras.getCharSequence(Notification.EXTRA_TEXT).toString().isBlank())
            assertFalse(shadowOf(svc).isStoppedBySelf)
        } finally {
            work.end()
        }
    }

    @Test fun `a service that starts after everything already finished stops itself`() {
        // begin()/end() both happened before onStartCommand ran (a very short dictation).
        val ctrl = Robolectric.buildService(BackgroundTranscriptionService::class.java, Intent(app, BackgroundTranscriptionService::class.java))
        val svc = ctrl.create().startCommand(0, 1).get()
        assertTrue("no held work => it must not linger", shadowOf(svc).isStoppedBySelf)
    }

    @Test fun `result and work notifications use separate channels so the routine one can be muted alone`() {
        BackgroundDictationNotifications.ensureChannels(app)
        val work = nm().getNotificationChannel(BackgroundDictationNotifications.WORK_CHANNEL_ID)
        val result = nm().getNotificationChannel(BackgroundDictationNotifications.RESULT_CHANNEL_ID)
        assertEquals(NotificationManager.IMPORTANCE_LOW, work.importance)
        assertEquals(NotificationManager.IMPORTANCE_DEFAULT, result.importance)
    }

    @Test fun `posted result uses one fixed id so repeats replace instead of stacking`() {
        shadowOf(nm()).setNotificationsEnabled(true)
        val notice = failureNoticeFor(BackgroundFailure.FAILED)
        BackgroundDictationNotifications.postResult(app, notice)
        BackgroundDictationNotifications.postResult(app, failureNoticeFor(BackgroundFailure.TIMED_OUT))
        val all = shadowOf(nm()).allNotifications
        assertEquals(1, all.size)
        assertEquals(failureNoticeFor(BackgroundFailure.TIMED_OUT).title, all.single().extras.getCharSequence(Notification.EXTRA_TITLE).toString())
        assertNotNull(all.single().contentIntent)
    }

    @Test fun `notifications disabled falls back to a toast instead of silence`() {
        shadowOf(nm()).setNotificationsEnabled(false)
        BackgroundDictationNotifications.postResult(app, failureNoticeFor(BackgroundFailure.FAILED))
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(shadowOf(nm()).allNotifications.isEmpty())
        assertEquals(
            "${failureNoticeFor(BackgroundFailure.FAILED).title}. ${failureNoticeFor(BackgroundFailure.FAILED).text}",
            org.robolectric.shadows.ShadowToast.getTextOfLatestToast(),
        )
    }

    @Test fun `a muted result channel falls back to a toast even with app notifications enabled`() {
        shadowOf(nm()).setNotificationsEnabled(true)
        BackgroundDictationNotifications.ensureChannels(app)
        val muted = NotificationChannel(
            BackgroundDictationNotifications.RESULT_CHANNEL_ID, "Dictation results", NotificationManager.IMPORTANCE_NONE,
        )
        nm().createNotificationChannel(muted)
        BackgroundDictationNotifications.postResult(app, failureNoticeFor(BackgroundFailure.FAILED))
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(shadowOf(nm()).allNotifications.isEmpty())
        assertEquals(
            "${failureNoticeFor(BackgroundFailure.FAILED).title}. ${failureNoticeFor(BackgroundFailure.FAILED).text}",
            org.robolectric.shadows.ShadowToast.getTextOfLatestToast(),
        )
    }

    @Test fun `history-opening notice targets the history viewer`() {
        val n = BackgroundDictationNotifications.result(
            app,
            deliveredNoticeFor(BackgroundDelivery.CLIPBOARD_AND_NOTIFY, historySaved = true, cleanupFailed = false)!!,
        )
        val intent = shadowOf(n.contentIntent).savedIntent
        assertEquals(DataLogsActivity::class.java.name, intent.component?.className)
        assertTrue(intent.getBooleanExtra(DataLogsActivity.EXTRA_SHOW_HISTORY, false))
    }
}
