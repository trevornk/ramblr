package com.trevornk.ramblr

import android.app.Application
import android.app.NotificationManager
import android.content.ClipboardManager
import android.content.Context
import android.os.Looper
import android.text.InputType
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
import org.robolectric.shadows.ShadowToast

/**
 * The floating icon never dictates into a password field: drives the REAL
 * [WhisperAccessibilityService] with the focused-field read substituted, and observes the real
 * side effects (toast, clipboard, history, vocabulary counters, notifications). The accessibility
 * tree itself can't exist under Robolectric, so the tree read is the on-device checklist.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AccessibilityPasswordFieldTest {

    class TestService : WhisperAccessibilityService() {
        @Volatile var passwordFocused = false
        var capturedNode: AccessibilityNodeInfo? = null
        override fun focusedInputIsPassword(): Boolean = passwordFocused
        override fun readCaptureTarget(): Pair<String, AccessibilityNodeInfo?>? = "com.chat" to capturedNode
        override fun readDeliveryProbe(capturedPackage: String, capturedNode: AccessibilityNodeInfo?) =
            DeliveryProbe(capturedPackage, capturedPackage, capturedPackage, deviceLocked = false, fieldState = CapturedFieldState.FOCUSED)
    }

    private lateinit var app: Application

    @Before fun setUp() {
        app = RuntimeEnvironment.getApplication()
        prefs().edit().clear().apply()
        PreviewBeforeInjectToggle.setEnabled(app, false)
        VocabularySuggestionsToggle.setEnabled(app, true)
        ClipboardUtil.copy(app, "PRIOR")
        DictationHistoryStore.forContext(app).clear()
        ShadowToast.reset()
    }

    @After fun tearDown() { WhisperAccessibilityService.instance = null; prefs().edit().clear().apply() }

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

    private fun history() = DictationHistoryStore.forContext(app).all()

    private fun candidates() = VocabularySuggestionStore.loadCandidates(prefs())

    private fun retryScheduled(service: WhisperAccessibilityService): Boolean =
        WhisperAccessibilityService::class.java.getDeclaredField("pendingInjectionRetry")
            .apply { isAccessible = true }.get(service) != null

    private fun tap(service: WhisperAccessibilityService) {
        WhisperAccessibilityService::class.java.getDeclaredMethod("onTap")
            .apply { isAccessible = true }.invoke(service)
        idle()
    }

    private fun tapFeedback(service: WhisperAccessibilityService) {
        WhisperAccessibilityService::class.java.getDeclaredMethod("onFeedbackTapped")
            .apply { isAccessible = true }.invoke(service)
    }

    /** History and vocabulary writes happen on worker threads; give them time to (not) land. */
    private fun settle() { Thread.sleep(400); idle() }

    private fun passwordNode(configure: AccessibilityNodeInfo.() -> Unit): AccessibilityNodeInfo =
        AccessibilityNodeInfo.obtain().apply(configure)

    private val spoken = "we deployed it on Zorblax today"

    // --- node predicate ---------------------------------------------------------------------------

    @Test fun `a node flagged isPassword is refused`() {
        assertTrue(passwordNode { isPassword = true }.isPasswordField())
    }

    @Test fun `a node with a password input type is refused even when not flagged`() {
        listOf(
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD,
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD,
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
            InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD,
        ).forEach { type ->
            assertTrue("inputType=$type", passwordNode { inputType = type }.isPasswordField())
        }
    }

    @Test fun `an ordinary text field is allowed`() {
        assertFalse(passwordNode { inputType = InputType.TYPE_CLASS_TEXT; isEditable = true }.isPasswordField())
    }

    @Test fun `writes and pastes into a password node are refused and never stage the clipboard`() {
        var staged = false
        val destination = AccessibilityTextDestination(passwordNode { isPassword = true; isEditable = true }) { staged = true }

        assertFalse(destination.replaceAllText("hunter2"))
        assertFalse(destination.pasteFromClipboard())
        assertFalse("the dictation must not reach the clipboard for a password node", staged)
    }

    // --- start entry point ------------------------------------------------------------------------

    @Test fun `tapping the ring over a password field refuses to record and says why`() {
        val service = build()
        service.passwordFocused = true

        tap(service)

        assertEquals(RecordingStateMachine.State.IDLE, service.runtime.currentState())
        assertEquals(PASSWORD_FIELD_REFUSAL_MESSAGE, ShadowToast.getTextOfLatestToast())
    }

    @Test fun `tapping the ring over an ordinary field is not refused on password grounds`() {
        val service = build()
        service.passwordFocused = false

        tap(service)

        assertNotRefused()
    }

    @Test fun `the quick settings tile and accessibility button route through the same refusal`() {
        val service = build()
        WhisperAccessibilityService.instance = service
        service.passwordFocused = true

        assertTrue(WhisperAccessibilityService.requestToggleRecording())
        idle()

        assertEquals(RecordingStateMachine.State.IDLE, service.runtime.currentState())
        assertEquals(PASSWORD_FIELD_REFUSAL_MESSAGE, ShadowToast.getTextOfLatestToast())
    }

    private fun assertNotRefused() {
        assertTrue(
            "unexpected refusal: ${ShadowToast.getTextOfLatestToast()}",
            ShadowToast.getTextOfLatestToast() != PASSWORD_FIELD_REFUSAL_MESSAGE,
        )
    }

    // --- injection-time guard ---------------------------------------------------------------------

    @Test fun `control - a dictation into an ordinary field is recorded and counted`() {
        val service = build()
        val listener = listenerOf(service)
        listener.onEnterTranscribingUi()

        listener.deliverText(spoken, null, null, null, 2000)
        settle()

        assertEquals(1, history().size)
        assertTrue("vocabulary collector must see the accepted text", candidates().containsKey("zorblax"))
        assertTrue("normal path still reaches the injection funnel", retryScheduled(service))
    }

    @Test fun `a result for a password field is not inserted recorded or counted`() {
        val service = build()
        val listener = listenerOf(service)
        listener.onEnterTranscribingUi()
        service.passwordFocused = true // focus moved there while the dictation was in flight

        listener.deliverText(spoken, null, null, null, 2000)
        settle()

        assertTrue("no history", history().isEmpty())
        assertTrue("no vocabulary counters", candidates().isEmpty())
        assertEquals("not on the clipboard", "PRIOR", clipboardText())
        assertFalse("no insertion attempt", retryScheduled(service))
        assertTrue("no background notification either", notifications().isEmpty())
    }

    @Test fun `a result captured in a password field is discarded even after focus moves away`() {
        val service = build()
        service.capturedNode = passwordNode { isPassword = true }
        val listener = listenerOf(service)
        listener.onEnterTranscribingUi()
        service.passwordFocused = false

        listener.deliverText(spoken, "raw $spoken", null, null, 2000)
        settle()

        assertTrue(history().isEmpty())
        assertTrue(candidates().isEmpty())
        assertEquals("PRIOR", clipboardText())
        assertFalse(retryScheduled(service))
        assertTrue(notifications().isEmpty())
    }

    @Test fun `with preview-before-insert a password result never begins a preview or history row`() {
        PreviewBeforeInjectToggle.setEnabled(app, true)
        val service = build()
        val listener = listenerOf(service)
        listener.onEnterTranscribingUi()
        service.passwordFocused = true

        listener.deliverText("clean candidate", "raw words", null, null, 2000)
        settle()

        assertTrue(history().isEmpty())
        assertNull(WhisperAccessibilityService::class.java.getDeclaredField("pendingPreview")
            .apply { isAccessible = true }.get(service))
    }

    @Test fun `a preview committed after focus reached a password field is discarded`() {
        PreviewBeforeInjectToggle.setEnabled(app, true)
        val service = build()
        val listener = listenerOf(service)
        listener.onEnterTranscribingUi()
        listener.deliverText(spoken, "raw $spoken", null, null, 2000)
        listener.onIdleUi()
        idle()
        service.passwordFocused = true

        tapFeedback(service)
        settle()

        assertEquals("PRIOR", clipboardText())
        assertFalse(retryScheduled(service))
        assertTrue(notifications().isEmpty())
        assertTrue("never offered as vocabulary", candidates().isEmpty())
    }

    @Test fun `the empty-scan retry discards when focus reached a password field during the wait`() {
        val service = build()
        val listener = listenerOf(service)
        listener.onEnterTranscribingUi()
        listener.deliverText(spoken, null, null, null, 2000) // ordinary field: schedules the retry
        listener.onIdleUi()
        assertTrue(retryScheduled(service))

        service.passwordFocused = true
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(400))

        assertEquals("PRIOR", clipboardText())
        assertTrue(notifications().isEmpty())
        assertFalse(retryScheduled(service))
    }

    @Test fun `tap to copy again offers nothing after a password result`() {
        val service = build()
        val listener = listenerOf(service)
        listener.onEnterTranscribingUi()
        service.passwordFocused = true
        listener.deliverText(spoken, null, null, null, 2000)

        tapFeedback(service)
        idle()

        assertEquals("PRIOR", clipboardText())
    }

    @Test fun `a live partial is not written while a password field is focused`() {
        val service = build()
        service.passwordFocused = true

        service.maybeInjectPartial("hunter two", requireRecording = false)

        val session = WhisperAccessibilityService::class.java.getDeclaredField("streamingSession")
            .apply { isAccessible = true }.get(service)
        assertNull(session)
    }
}
