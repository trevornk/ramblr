package com.trevornk.ramblr

import android.text.InputType
import android.view.inputmethod.EditorInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * #284, IME host: the real [RamblrImeService] lifecycle with a dictation in each pipeline state.
 * Switching apps arrives as onFinishInput (+ onWindowHidden when the keyboard goes away): before
 * #284 that tore the runtime down and silently dropped any in-flight transcription.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RamblrImeBackgroundLifecycleTest {

    private fun editor(pkg: String, inputType: Int = InputType.TYPE_CLASS_TEXT) = EditorInfo().apply {
        this.inputType = inputType
        packageName = pkg
        fieldId = 1
    }

    private fun service(): RamblrImeService {
        val s = Robolectric.buildService(RamblrImeService::class.java).create().get()
        s.onCreateInputView()
        s.onStartInput(editor("com.chat"), false)
        s.onStartInputView(editor("com.chat"), false)
        return s
    }

    private fun runtimeOf(s: RamblrImeService): DictationRuntime =
        RamblrImeService::class.java.getDeclaredField("runtime").apply { isAccessible = true }.get(s) as DictationRuntime

    private fun stateMachineOf(r: DictationRuntime): RecordingStateMachine =
        DictationRuntime::class.java.getDeclaredField("stateMachine").apply { isAccessible = true }.get(r) as RecordingStateMachine

    private fun transcribing(s: RamblrImeService) {
        val sm = stateMachineOf(runtimeOf(s))
        assertTrue(sm.tryStartRecording())
        assertTrue(sm.tryStartTranscribing())
    }

    @Test fun `leaving the field while transcribing keeps the runtime and controller alive`() {
        val s = service()
        transcribing(s)
        val controller = s.panelControllerForTest()
        val runtime = runtimeOf(s)

        s.onFinishInput()
        s.onWindowHidden()

        assertNotNull("controller must survive so the in-flight result is still delivered", s.panelControllerForTest())
        assertTrue(controller === s.panelControllerForTest())
        assertTrue(runtime === runtimeOf(s))
        assertEquals(RecordingStateMachine.State.TRANSCRIBING, stateMachineOf(runtime).current())
    }

    @Test fun `leaving the field while recording still tears everything down, as before`() {
        val s = service()
        assertTrue(stateMachineOf(runtimeOf(s)).tryStartRecording())

        s.onFinishInput()

        assertNull(s.panelControllerForTest())
    }

    @Test fun `leaving the field while idle still tears everything down, as before`() {
        val s = service()
        s.onFinishInput()
        assertNull(s.panelControllerForTest())
    }

    @Test fun `destroying the service while transcribing still tears down`() {
        val s = service()
        transcribing(s)
        s.onDestroy()
        assertNull(s.panelControllerForTest())
    }

    @Test fun `moving into a secure editor while transcribing keeps the secure lock painted`() {
        val s = service()
        transcribing(s)

        s.onFinishInput()
        s.onStartInput(editor("com.bank", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD), false)
        // The detached dictation finishes and the runtime paints IDLE; the secure lock must win.
        s.panelControllerForTest()!!.listener.onIdleUi()

        assertEquals(ImeUiState.SECURE_FIELD, s.lastRenderedStateForTest())
    }

    @Test fun `a detached dictation finishing repaints idle in the next ordinary editor`() {
        val s = service()
        transcribing(s)
        s.onFinishInput()
        s.onStartInput(editor("com.mail"), false)
        s.onStartInputView(editor("com.mail"), false)

        s.panelControllerForTest()!!.listener.onIdleUi()

        assertEquals(ImeUiState.IDLE, s.lastRenderedStateForTest())
    }

    // --- lazy foreground-service hold (ADR-0002) ---

    /** Robolectric never binds a real InputConnection; give the service one so tickets exist. */
    private fun bindFakeConnection(s: RamblrImeService) {
        val f = android.inputmethodservice.InputMethodService::class.java.getDeclaredField("mInputConnection")
        f.isAccessible = true
        f.set(s, android.view.inputmethod.BaseInputConnection(android.view.View(s), true))
    }

    private fun app() = org.robolectric.RuntimeEnvironment.getApplication()

    private fun startedService() = org.robolectric.Shadows.shadowOf(app()).peekNextStartedService()

    private fun idleLooper() = org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()

    @org.junit.Before fun resetService() {
        BackgroundTranscriptionService.resetForTest()
        while (org.robolectric.Shadows.shadowOf(app()).nextStartedService != null) { /* drain */ }
    }

    @Test fun `transcribing while the user stays in the field never starts the service`() {
        val s = service()
        transcribing(s)
        // Everything a stay-in-field dictation does around the stop tap: panel callbacks, restart
        // of the same editor, repaint. None of it is a leave signal.
        s.panelControllerForTest()!!.listener.onEnterTranscribingUi()
        s.onStartInput(editor("com.chat"), true)
        idleLooper()
        assertNull("no foreground service on the stay-in-field path", startedService())
    }

    @Test fun `leaving the field while transcribing starts the hold exactly once across both leave signals`() {
        val s = service()
        transcribing(s)

        s.onFinishInput()
        s.onWindowHidden()
        idleLooper()

        assertNotNull("leaving while transcribing must hold the process", startedService())
        assertEquals(BackgroundTranscriptionService::class.java.name, startedService()!!.component!!.className)
        org.robolectric.Shadows.shadowOf(app()).nextStartedService
        assertNull("a second leave signal must not start a second service", startedService())
    }

    @Test fun `leaving the field while recording or idle never starts the service`() {
        val s = service()
        s.onFinishInput(); s.onWindowHidden()
        val r = service()
        assertTrue(stateMachineOf(runtimeOf(r)).tryStartRecording())
        r.onFinishInput(); r.onWindowHidden()
        idleLooper()
        assertNull(startedService())
    }

    @Test fun `destroying the service while detached and transcribing posts the failure notice`() {
        val nm = app().getSystemService(android.app.NotificationManager::class.java)
        org.robolectric.Shadows.shadowOf(nm).setNotificationsEnabled(true)
        val s = service()
        bindFakeConnection(s)
        s.onStartInput(editor("com.chat"), false) // re-bind now that a connection exists
        s.panelControllerForTest()!!.listener.onRecordingStartRequested() // binds the dictation's ticket
        transcribing(s)
        s.onFinishInput() // user left; editor ticket no longer current
        s.onDestroy()
        idleLooper()
        // The notice goes through the normal poster; either a notification or the toast fallback.
        val posted = org.robolectric.Shadows.shadowOf(nm).allNotifications.size +
            (if (org.robolectric.shadows.ShadowToast.getLatestToast() != null) 1 else 0)
        assertTrue("a result lost to onDestroy must not be silent", posted >= 1)
    }

    @Test fun `destroying the service while still in the field posts nothing extra`() {
        val s = service()
        bindFakeConnection(s)
        s.onStartInput(editor("com.chat"), false)
        s.panelControllerForTest()!!.listener.onRecordingStartRequested()
        transcribing(s)
        s.onDestroy() // never left: the in-panel state is the whole story, as before
        idleLooper()
        val nm = app().getSystemService(android.app.NotificationManager::class.java)
        assertTrue(org.robolectric.Shadows.shadowOf(nm).allNotifications.isEmpty())
        assertNull(org.robolectric.shadows.ShadowToast.getLatestToast())
    }
}
