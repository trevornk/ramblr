package com.trevornk.ramblr

import android.view.inputmethod.EditorInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #312: [ImePanelController.isCommitPending] is what stops the keyboard's Enter-that-submits from
 * firing in the window where the UI already says IDLE but the guarded, exactly-once insertion of
 * the dictated text has not run yet (history write is async, the commit hops back to main).
 */
class ImePanelControllerCommitPendingTest {

    private class Runtime : ImeRuntimeControl {
        override fun onTap() {}
        override fun invalidate() {}
        override fun teardownAsync() {}
    }

    private val identity = ImeEditorIdentity("com.example.app", 7, 1)
    private val committed = mutableListOf<String>()
    private val deferredHistory = mutableListOf<() -> Unit>()
    private val deferredMain = mutableListOf<() -> Unit>()
    private var settled = 0
    private val connection = Any()

    private fun controller(retention: Boolean = true): ImePanelController {
        val editor = if (retention) identity else identity.copy(imeOptions = EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING)
        val c = ImePanelController(
            runtime = Runtime(),
            renderState = {},
            editorSnapshot = { Triple(1L, editor, connection) },
            commitText = { _, text -> committed.add(text); true },
            recordHistory = { true },
            runHistoryWrite = { deferredHistory.add(it) },
            postToMain = { deferredMain.add(it) },
            onCommitSettled = { settled++ },
        )
        c.onEditorChanged(1L, editor, connection)
        return c
    }

    private fun deliver(c: ImePanelController, text: String = "hello") {
        c.listener.onRecordingStartRequested()
        c.listener.deliverText(text, rawText = null, paidFallbackGroup = null, cleanupError = null, feedbackDurationMs = 0)
    }

    @Test fun `nothing is pending before any dictation`() {
        assertFalse(controller().isCommitPending())
    }

    @Test fun `a delivered dictation is pending until its guarded commit lands`() {
        val c = controller()

        deliver(c)

        assertTrue("history write queued, commit not yet run", c.isCommitPending())
        assertEquals(emptyList<String>(), committed)

        deferredHistory.removeAt(0).invoke()
        assertTrue("history done but the main-thread commit has not run", c.isCommitPending())

        deferredMain.removeAt(0).invoke()
        assertFalse(c.isCommitPending())
        assertEquals(listOf("hello"), committed)
        assertEquals("settled callback fires once so the UI can re-enable keys", 1, settled)
    }

    @Test fun `with retention off the commit is immediate and never pending`() {
        val c = controller(retention = false)

        deliver(c)

        assertFalse(c.isCommitPending())
        assertEquals(listOf("hello"), committed)
        assertEquals(1, settled)
    }

    @Test fun `lifecycle loss clears a pending commit so keys are not stuck disabled`() {
        val c = controller()
        deliver(c)

        c.onLifecycleLost(ImeLifecycleLoss.HIDDEN)

        assertFalse(c.isCommitPending())
    }
}
