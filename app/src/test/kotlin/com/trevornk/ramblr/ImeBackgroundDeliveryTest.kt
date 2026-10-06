package com.trevornk.ramblr

import android.text.InputType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #284, IME host: what [ImePanelController] does with a finished/failed dictation depending on
 * whether the editor it was started in is still the bound one. The first test group is the
 * protected daily-driver path: staying in the field must change nothing (no clipboard, no
 * notification).
 */
class ImeBackgroundDeliveryTest {

    private class Runtime : ImeRuntimeControl {
        override fun onTap() {}
        override fun invalidate() {}
        override fun teardownAsync() {}
    }

    private class Harness(
        excluded: Set<String> = emptySet(),
        retention: Boolean = true,
        historyOk: Boolean = true,
    ) {
        val clipboard = mutableListOf<String>()
        val notices = mutableListOf<BackgroundNotice>()
        val commits = mutableListOf<String>()
        val histories = mutableListOf<DictationHistoryEntry>()
        val messages = mutableListOf<String>()
        val states = mutableListOf<ImeUiState>()
        var connection: Any = Any()
        var identity = ImeEditorIdentity("com.chat", 7, InputType.TYPE_CLASS_TEXT, if (retention) 0 else android.view.inputmethod.EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING)
        var generation = 1L
        val controller = ImePanelController(
            runtime = Runtime(),
            renderState = { states += it },
            editorSnapshot = { Triple(generation, identity, connection) },
            commitText = { _, t -> commits += t; true },
            recordHistory = { histories += it; historyOk },
            userMessage = { messages += it },
            isPackageExcluded = { it in excluded },
            backgroundSink = BackgroundResultSink({ clipboard += it }, { notices += it }),
        )

        init {
            controller.onEditorChanged(generation, identity, connection)
            controller.listener.onRecordingStartRequested()
        }

        /** The user moves to a different app's editor. */
        fun switchApps(pkg: String = "com.mail") {
            generation++
            identity = ImeEditorIdentity(pkg, 9, InputType.TYPE_CLASS_TEXT)
            connection = Any()
            controller.onEditorChanged(generation, identity, connection)
        }

        fun deliver(text: String = "dictated text", cleanupError: String? = null) =
            controller.listener.deliverText(text, null, null, cleanupError, 2000)
    }

    // --- daily-driver path: nothing new ---

    @Test fun `staying in the field commits and adds no clipboard write or notification`() {
        val h = Harness()
        h.deliver("hello")
        assertEquals(listOf("hello"), h.commits)
        assertTrue(h.clipboard.isEmpty())
        assertTrue(h.notices.isEmpty())
        assertEquals(listOf("hello"), h.histories.map { it.rawText })
    }

    @Test fun `a failure while still in the field adds no notification`() {
        val h = Harness()
        h.controller.listener.onDictationFailed(BackgroundFailure.FAILED)
        h.controller.listener.onDictationFailed(BackgroundFailure.NO_SPEECH)
        h.controller.listener.onDictationFailed(BackgroundFailure.TIMED_OUT)
        assertTrue(h.notices.isEmpty())
    }

    // --- target gone ---

    @Test fun `switching apps mid-transcription never types into the new editor`() {
        val h = Harness()
        h.switchApps()
        h.deliver("secret dictation")
        assertTrue("must not commit into whatever is focused now", h.commits.isEmpty())
    }

    @Test fun `switching apps copies to clipboard, saves history once, and notifies once`() {
        val h = Harness()
        h.switchApps()
        h.deliver("keep me")
        assertEquals(listOf("keep me"), h.clipboard)
        assertEquals("history is saved exactly once, not re-saved by the fallback", 1, h.histories.size)
        assertEquals(1, h.notices.size)
        val n = h.notices.single()
        assertTrue(n.text.contains("clipboard") && n.text.contains("history"))
        assertTrue(n.opensHistory)
        assertFalse("no transcript in the notification", n.text.contains("keep me") || n.title.contains("keep me"))
    }

    @Test fun `target gone with history write failing does not claim history`() {
        val h = Harness(historyOk = false)
        h.switchApps()
        h.deliver("x")
        val n = h.notices.single()
        assertFalse(n.text.contains("history"))
        assertFalse(n.opensHistory)
        assertEquals(listOf("x"), h.clipboard)
    }

    @Test fun `target gone from a no-retention editor copies and saves nothing`() {
        val h = Harness(retention = false)
        h.switchApps()
        h.deliver("private")
        assertTrue(h.clipboard.isEmpty())
        assertTrue(h.histories.isEmpty())
        assertTrue(h.commits.isEmpty())
        assertEquals(1, h.notices.size)
        assertFalse(h.notices.single().text.contains("private"))
    }

    @Test fun `switching into an excluded app copies nothing but still notifies`() {
        // The exclusion follows the editor the user is in NOW (same as the accessibility host's
        // fresh foreground read): the clipboard is a hop into that app too.
        val h = Harness(excluded = setOf("com.bank"))
        h.switchApps("com.bank")
        h.deliver("bank stuff")
        assertTrue(h.clipboard.isEmpty())
        assertTrue(h.commits.isEmpty())
        assertEquals(1, h.notices.size)
        assertFalse(h.notices.single().text.contains("on the clipboard"))
        assertTrue("history still saved: it is a local record, not a write into the excluded app", h.histories.size == 1)
    }

    @Test fun `excluded app but dictation still bound adds no notification`() {
        // Mic is blocked in an excluded app, but if the exclusion list changed mid-dictation the
        // existing suppression applies and #284 adds nothing while the editor is still current.
        val h = Harness(excluded = setOf("com.chat"))
        h.deliver("bank stuff")
        assertTrue(h.notices.isEmpty())
        assertTrue(h.clipboard.isEmpty())
        assertTrue(h.commits.isEmpty())
    }

    @Test fun `cleanup failure is disclosed when the target is gone`() {
        val h = Harness()
        h.switchApps()
        h.deliver("raw text", cleanupError = "boom")
        assertTrue(h.notices.single().text.contains("raw transcript"))
    }

    @Test fun `a clipboard failure still posts the notification`() {
        val notices = mutableListOf<BackgroundNotice>()
        val controller = ImePanelController(
            runtime = Runtime(), renderState = {},
            editorSnapshot = { Triple(1L, ImeEditorIdentity("a", 1, 1), Any()) },
            commitText = { _, _ -> true }, recordHistory = { true },
            backgroundSink = BackgroundResultSink({ error("denied") }, { notices += it }),
        )
        controller.onEditorChanged(1L, ImeEditorIdentity("a", 1, 1), Any())
        controller.listener.onRecordingStartRequested()
        controller.onEditorChanged(2L, ImeEditorIdentity("b", 2, 1), Any())
        controller.listener.deliverText("t", null, null, null, 2000)
        assertEquals(1, notices.size)
    }

    // --- failures while gone ---

    @Test fun `failure after switching apps posts one failure notice`() {
        val h = Harness()
        h.switchApps()
        h.controller.listener.onDictationFailed(BackgroundFailure.FAILED)
        assertEquals(1, h.notices.size)
        assertEquals(failureNoticeFor(BackgroundFailure.FAILED), h.notices.single())
    }

    @Test fun `failure with no dictation ever bound posts nothing`() {
        val controller = ImePanelController(Runtime(), {}, backgroundSink = BackgroundResultSink({}, { error("must not post") }))
        controller.listener.onDictationFailed(BackgroundFailure.FAILED)
    }

    @Test fun `a controller whose lifecycle was torn down reports nothing`() {
        val h = Harness()
        h.controller.onLifecycleLost(ImeLifecycleLoss.DESTROYED)
        h.controller.listener.onDictationFailed(BackgroundFailure.FAILED)
        h.deliver("late")
        assertTrue(h.notices.isEmpty())
        assertTrue(h.clipboard.isEmpty())
    }

    // --- the guard ---

    @Test fun `isCurrent does not spend the ticket`() {
        val guard = ImeDestinationGuard()
        val id = ImeEditorIdentity("a", 1, 1)
        val conn = Any()
        guard.editorChanged(1, id, conn)
        val ticket = guard.bindDictation()
        assertTrue(guard.isCurrent(ticket))
        assertTrue(guard.isCurrent(ticket))
        assertEquals(ImeCommitResult.SUCCESS, guard.commitIfCurrent(ticket, "x") { _, _ -> true })
        guard.editorChanged(2, id, conn)
        assertFalse(guard.isCurrent(ticket))
        assertFalse(guard.isCurrent(null))
    }
}
