package com.trevornk.ramblr

import android.app.Application
import android.app.NotificationManager
import android.content.ClipboardManager
import android.content.Context
import android.os.Looper
import android.view.accessibility.AccessibilityNodeInfo
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * #284, accessibility host: drives the REAL [WhisperAccessibilityService] runtime listener with the
 * two platform reads (capture, probe) substituted, and observes the real side effects: clipboard,
 * notifications, history. The accessibility tree itself can't exist under Robolectric, so what is
 * proven here is the host's decision wiring; the tree reads are the on-device checklist.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AccessibilityBackgroundDeliveryTest {

    class TestService : WhisperAccessibilityService() {
        var capturePkg: String? = "com.chat"
        internal var probe: (String) -> DeliveryProbe = { pkg ->
            DeliveryProbe(pkg, pkg, pkg, deviceLocked = false, fieldState = CapturedFieldState.FOCUSED)
        }
        override fun readCaptureTarget(): Pair<String, AccessibilityNodeInfo?>? = capturePkg?.let { it to null }
        override fun readDeliveryProbe(capturedPackage: String, capturedNode: AccessibilityNodeInfo?) = probe(capturedPackage)
    }

    private lateinit var app: Application

    @Before fun setUp() {
        app = RuntimeEnvironment.getApplication()
        prefs().edit().clear().apply()
        PreviewBeforeInjectToggle.setEnabled(app, false)
        ClipboardUtil.copy(app, "PRIOR")
        DictationHistoryStore.forContext(app).clear()
    }

    @After fun tearDown() { prefs().edit().clear().apply() }

    private fun prefs() = app.getSharedPreferences("ramblr", Context.MODE_PRIVATE)

    private fun build(): TestService {
        val service = Robolectric.buildService(TestService::class.java, null).create().get()
        WhisperAccessibilityService::class.java.getDeclaredMethod("showOverlay").apply { isAccessible = true }.invoke(service)
        return service
    }

    private fun listenerOf(service: WhisperAccessibilityService): RuntimeListener =
        WhisperAccessibilityService::class.java.getDeclaredField("runtimeListener")
            .apply { isAccessible = true }.get(service) as RuntimeListener

    private fun clipboardText(): String? =
        (app.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).primaryClip?.getItemAt(0)?.text?.toString()

    private fun notifications() =
        shadowOf(app.getSystemService(NotificationManager::class.java)).allNotifications

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun gone(): (String) -> DeliveryProbe = { pkg ->
        DeliveryProbe(pkg, "com.mail", "com.mail", deviceLocked = false, fieldState = CapturedFieldState.UNKNOWN)
    }

    // --- target gone ---

    @Test fun `target gone puts text on the clipboard and notifies, without attempting insertion`() {
        val service = build()
        val listener = listenerOf(service)
        listener.onEnterTranscribingUi()
        service.probe = gone()

        listener.deliverText("hello from the background", null, null, null, 2000)
        idle()

        assertEquals("hello from the background", clipboardText())
        val n = notifications().single()
        val text = n.extras.getCharSequence("android.text").toString()
        assertTrue(text.contains("clipboard"))
        assertFalse("transcript must not be in the notification", text.contains("hello from the background"))
        val retry = WhisperAccessibilityService::class.java.getDeclaredField("pendingInjectionRetry")
            .apply { isAccessible = true }.get(service)
        assertNull("the empty-scan injection retry means an insertion was attempted", retry)
    }

    @Test fun `target gone records history exactly once`() {
        val service = build()
        val listener = listenerOf(service)
        listener.onEnterTranscribingUi()
        service.probe = gone()

        listener.deliverText("once only", "raw once only", null, null, 2000)
        Thread.sleep(300) // recordHistory writes on a worker thread
        idle()

        val entries = DictationHistoryStore.forContext(app).all()
        assertEquals(1, entries.size)
        assertEquals("raw once only", entries.single().rawText)
        assertEquals("once only", entries.single().cleanedText)
    }

    @Test fun `device locked counts as gone`() {
        val service = build()
        val listener = listenerOf(service)
        listener.onEnterTranscribingUi()
        service.probe = { pkg -> DeliveryProbe(pkg, pkg, pkg, deviceLocked = true, fieldState = CapturedFieldState.FOCUSED) }

        listener.deliverText("locked", null, null, null, 2000)
        idle()

        assertEquals("locked", clipboardText())
        assertEquals(1, notifications().size)
    }

    @Test fun `failure with the field gone posts a failure notice`() {
        val service = build()
        val listener = listenerOf(service)
        listener.onEnterTranscribingUi()
        service.probe = gone()

        listener.onDictationFailed(BackgroundFailure.FAILED)
        idle()

        val n = notifications().single()
        assertEquals(failureNoticeFor(BackgroundFailure.FAILED).title, n.extras.getCharSequence("android.title").toString())
        assertEquals("a failure never touches the clipboard", "PRIOR", clipboardText())
    }

    // --- daily-driver path: zero change ---

    @Test fun `failure while the field is current posts nothing`() {
        val service = build()
        val listener = listenerOf(service)
        listener.onEnterTranscribingUi()

        listener.onDictationFailed(BackgroundFailure.FAILED)
        listener.onDictationFailed(BackgroundFailure.TIMED_OUT)
        idle()

        assertTrue(notifications().isEmpty())
    }

    @Test fun `delivery while the field is current takes the normal injection path and notifies nothing`() {
        val service = build()
        val listener = listenerOf(service)
        listener.onEnterTranscribingUi()

        listener.deliverText("normal path", null, null, null, 2000)
        idle()

        assertTrue("no new notification on the protected path", notifications().isEmpty())
        // The normal funnel was entered: with no accessibility tree it schedules its empty-scan retry.
        val retry = WhisperAccessibilityService::class.java.getDeclaredField("pendingInjectionRetry")
            .apply { isAccessible = true }.get(service)
        assertTrue("injectText() must still be the path taken", retry != null)
    }

    @Test fun `nothing captured fails open to the normal path`() {
        val service = build()
        service.capturePkg = null
        val listener = listenerOf(service)
        listener.onEnterTranscribingUi()
        service.probe = gone()

        listener.deliverText("no target known", null, null, null, 2000)
        idle()

        assertTrue(notifications().isEmpty())
    }

    @Test fun `a throwing probe fails open to the normal path`() {
        val service = build()
        val listener = listenerOf(service)
        listener.onEnterTranscribingUi()
        service.probe = { error("a11y connection died") }

        listener.deliverText("probe broke", null, null, null, 2000)
        idle()

        assertTrue(notifications().isEmpty())
    }

    @Test fun `the target is forgotten when the pipeline goes idle`() {
        val service = build()
        val listener = listenerOf(service)
        listener.onEnterTranscribingUi()
        assertTrue(service.dictationTarget != null)
        listener.onIdleUi()
        assertNull(service.dictationTarget)
    }
}
