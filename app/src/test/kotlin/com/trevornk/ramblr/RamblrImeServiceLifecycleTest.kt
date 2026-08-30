package com.trevornk.ramblr

import android.text.InputType
import android.view.inputmethod.EditorInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * #241 regression coverage at the layer where the defect actually lived.
 *
 * [ImePanelController] was never at fault: it recomputes its policy on every editor change and its
 * `active` latch is never tripped by the secure path, because `loseLifecycle` returns early once
 * `panelController` is already null. The failure was service-scoped -- a secure editor tears the
 * panel down, and Android can then restart input for an ordinary editor **without** re-showing the
 * input view (a WebView field switch calls `onStartInput(restarting = true)` and no
 * `onStartInputView`), leaving `panelController` null, the mic dead, and a stale secure status.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RamblrImeServiceLifecycleTest {

    private fun editorInfo(inputType: Int, imeOptions: Int = 0): EditorInfo = EditorInfo().apply {
        this.inputType = inputType
        this.imeOptions = imeOptions
        this.packageName = "harness.example"
        this.fieldId = 42
    }

    private fun secureEditor() = editorInfo(
        InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD,
    )

    private fun normalEditor() = editorInfo(InputType.TYPE_CLASS_TEXT)

    private fun serviceWithInputViewShown(): RamblrImeService {
        val service = Robolectric.buildService(RamblrImeService::class.java).create().get()
        service.onCreateInputView()
        service.onStartInput(normalEditor(), false)
        service.onStartInputView(normalEditor(), false)
        return service
    }

    @Test
    fun `panel survives a secure editor and recovers on a restarting normal editor`() {
        val service = serviceWithInputViewShown()
        assertNotNull("baseline: an ordinary editor must arm the panel", service.panelControllerForTest())

        service.onStartInput(secureEditor(), false)
        assertFalse(
            "secure editor must block dictation",
            service.editorPolicyForTest().allowsDictation,
        )

        // The exact real-world sequence: a WebView field switch restarts input without re-showing
        // the input view. Before the fix this left the panel torn down and the mic permanently dead.
        service.onStartInput(normalEditor(), true)

        assertTrue(
            "ordinary editor must allow dictation again",
            service.editorPolicyForTest().allowsDictation,
        )
        assertNotNull(
            "panel must be re-armed after a secure field, or the mic stays dead",
            service.panelControllerForTest(),
        )
        assertEquals(
            "stale secure-field status must be cleared",
            ImeUiState.IDLE,
            service.lastRenderedStateForTest(),
        )
    }

    @Test
    fun `secure editor still blocks and reports the secure status`() {
        val service = serviceWithInputViewShown()

        service.onStartInput(secureEditor(), false)

        assertEquals(ImeUiState.SECURE_FIELD, service.lastRenderedStateForTest())
        assertFalse(service.editorPolicyForTest().allowsDictation)
        assertFalse(imeMicEnabled(ImeUiState.SECURE_FIELD))
    }

    @Test
    fun `hidden input view does not build a runtime for a restarting editor`() {
        // onWindowHidden tears down; a restart while hidden must not resurrect native resources.
        val service = serviceWithInputViewShown()
        service.onWindowHidden()

        service.onStartInput(normalEditor(), true)

        assertTrue(
            "policy still tracks the editor while hidden",
            service.editorPolicyForTest().allowsDictation,
        )
    }
}
