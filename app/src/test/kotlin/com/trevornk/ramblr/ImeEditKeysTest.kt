package com.trevornk.ramblr

import android.text.InputType
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnectionWrapper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * #312: the keyboard's quick edit keys (backspace, space, enter). The decisions are pure functions
 * ([resolveEnterKey], [imeEditKeyEnabled]) and the InputConnection work ([ImeEditActions]) runs
 * against a small in-memory editor so the resulting TEXT is asserted, not just which method was hit.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ImeEditKeysTest {

    // ---- Enter-key resolution (pure) ----

    private val singleLine = InputType.TYPE_CLASS_TEXT
    private val multiLine = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE

    @Test fun `single-line field with Send action performs Send`() {
        assertEquals(
            EnterKeyAction.PerformEditorAction(EditorInfo.IME_ACTION_SEND),
            resolveEnterKey(singleLine, EditorInfo.IME_ACTION_SEND),
        )
    }

    @Test fun `single-line Go Search and Done actions are performed`() {
        for (action in listOf(EditorInfo.IME_ACTION_GO, EditorInfo.IME_ACTION_SEARCH, EditorInfo.IME_ACTION_DONE)) {
            assertEquals(EnterKeyAction.PerformEditorAction(action), resolveEnterKey(singleLine, action))
        }
    }

    @Test fun `single-line Next and Previous move focus like any keyboard`() {
        assertEquals(
            EnterKeyAction.PerformEditorAction(EditorInfo.IME_ACTION_NEXT),
            resolveEnterKey(singleLine, EditorInfo.IME_ACTION_NEXT),
        )
        assertEquals(
            EnterKeyAction.PerformEditorAction(EditorInfo.IME_ACTION_PREVIOUS),
            resolveEnterKey(singleLine, EditorInfo.IME_ACTION_PREVIOUS),
        )
    }

    @Test fun `multi-line field inserts a newline even when it advertises Send`() {
        // A chat box is multi-line + actionSend. Enter here must never submit the message.
        assertEquals(EnterKeyAction.InsertNewline, resolveEnterKey(multiLine, EditorInfo.IME_ACTION_SEND))
        assertEquals(EnterKeyAction.InsertNewline, resolveEnterKey(multiLine, EditorInfo.IME_ACTION_DONE))
        assertEquals(EnterKeyAction.InsertNewline, resolveEnterKey(multiLine, EditorInfo.IME_ACTION_NONE))
    }

    @Test fun `single-line field with no action sends a plain Enter key press`() {
        assertEquals(EnterKeyAction.SendEnterKey, resolveEnterKey(singleLine, EditorInfo.IME_ACTION_NONE))
        assertEquals(EnterKeyAction.SendEnterKey, resolveEnterKey(singleLine, EditorInfo.IME_ACTION_UNSPECIFIED))
        assertEquals(EnterKeyAction.SendEnterKey, resolveEnterKey(0, 0)) // TYPE_NULL
    }

    @Test fun `NO_ENTER_ACTION flag suppresses the action`() {
        assertEquals(
            EnterKeyAction.SendEnterKey,
            resolveEnterKey(singleLine, EditorInfo.IME_ACTION_SEND or EditorInfo.IME_FLAG_NO_ENTER_ACTION),
        )
    }

    @Test fun `action id is read from the action mask not the whole imeOptions`() {
        // Real fields mix flags (e.g. no-personalized-learning, no-extract-ui) into imeOptions.
        val options = EditorInfo.IME_ACTION_SEARCH or EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING or
            EditorInfo.IME_FLAG_NO_EXTRACT_UI
        assertEquals(EnterKeyAction.PerformEditorAction(EditorInfo.IME_ACTION_SEARCH), resolveEnterKey(singleLine, options))
    }

    @Test fun `non-text classes follow the same single-line rule`() {
        assertEquals(
            EnterKeyAction.PerformEditorAction(EditorInfo.IME_ACTION_DONE),
            resolveEnterKey(InputType.TYPE_CLASS_NUMBER, EditorInfo.IME_ACTION_DONE),
        )
    }

    // ---- Enabled policy (pure) ----

    @Test fun `all edit keys are enabled when idle`() {
        for (key in ImeEditKey.values()) {
            assertTrue(key.name, imeEditKeyEnabled(key, ImeUiState.IDLE, commitPending = false, enter = EnterKeyAction.PerformEditorAction(EditorInfo.IME_ACTION_SEND)))
        }
    }

    @Test fun `all edit keys are disabled in a secure field`() {
        for (key in ImeEditKey.values()) {
            assertFalse(key.name, imeEditKeyEnabled(key, ImeUiState.SECURE_FIELD, commitPending = false, enter = EnterKeyAction.InsertNewline))
        }
    }

    @Test fun `backspace space and newline stay enabled while a dictation is in flight`() {
        for (state in listOf(ImeUiState.RECORDING, ImeUiState.TRANSCRIBING, ImeUiState.CLEANING)) {
            assertTrue(imeEditKeyEnabled(ImeEditKey.BACKSPACE, state, false, EnterKeyAction.InsertNewline))
            assertTrue(imeEditKeyEnabled(ImeEditKey.SPACE, state, false, EnterKeyAction.InsertNewline))
            assertTrue(imeEditKeyEnabled(ImeEditKey.ENTER, state, false, EnterKeyAction.InsertNewline))
        }
    }

    @Test fun `enter that would submit is blocked while a dictation is in flight or its commit pending`() {
        val send = EnterKeyAction.PerformEditorAction(EditorInfo.IME_ACTION_SEND)
        for (state in listOf(ImeUiState.RECORDING, ImeUiState.TRANSCRIBING, ImeUiState.CLEANING)) {
            assertFalse(state.name, imeEditKeyEnabled(ImeEditKey.ENTER, state, false, send))
            assertFalse(state.name, imeEditKeyEnabled(ImeEditKey.ENTER, state, false, EnterKeyAction.SendEnterKey))
        }
        // Idle UI but the guarded commit has not landed yet.
        assertFalse(imeEditKeyEnabled(ImeEditKey.ENTER, ImeUiState.IDLE, commitPending = true, enter = send))
        assertTrue(imeEditKeyEnabled(ImeEditKey.BACKSPACE, ImeUiState.IDLE, commitPending = true, enter = send))
    }

    @Test fun `style picker is only available when no dictation is in flight`() {
        assertTrue(imeStylePickerEnabled(ImeUiState.IDLE, commitPending = false))
        assertTrue(imeStylePickerEnabled(ImeUiState.ERROR, commitPending = false))
        for (state in listOf(ImeUiState.RECORDING, ImeUiState.TRANSCRIBING, ImeUiState.CLEANING, ImeUiState.SECURE_FIELD)) {
            assertFalse(state.name, imeStylePickerEnabled(state, commitPending = false))
        }
        assertFalse(imeStylePickerEnabled(ImeUiState.IDLE, commitPending = true))
    }

    // ---- InputConnection actions against an in-memory editor ----

    /** Minimal editor: text + selection, enough to observe what each call really did. */
    private class FakeEditor(text: String, var selStart: Int = text.length, var selEnd: Int = text.length) :
        InputConnectionWrapper(null, true) {
        val text = StringBuilder(text)
        val keyEvents = mutableListOf<Pair<Int, Int>>() // action to keyCode
        val editorActions = mutableListOf<Int>()
        var deleteSurroundingResult = true

        private fun lo() = minOf(selStart, selEnd)
        private fun hi() = maxOf(selStart, selEnd)

        override fun getSelectedText(flags: Int): CharSequence? =
            if (lo() == hi()) null else text.substring(lo(), hi())

        override fun commitText(t: CharSequence, newCursorPosition: Int): Boolean {
            text.replace(lo(), hi(), t.toString())
            selStart = lo() + t.length
            selEnd = selStart
            return true
        }

        override fun deleteSurroundingTextInCodePoints(beforeLength: Int, afterLength: Int): Boolean {
            if (!deleteSurroundingResult) return false
            var start = lo()
            repeat(beforeLength) { if (start > 0) start -= Character.charCount(Character.codePointBefore(text, start)) }
            text.delete(start, lo())
            selStart = start
            selEnd = start
            return true
        }

        override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean =
            throw AssertionError("backspace must delete by code point, not UTF-16 unit")

        override fun sendKeyEvent(event: KeyEvent): Boolean {
            keyEvents.add(event.action to event.keyCode)
            return true
        }

        override fun performEditorAction(editorAction: Int): Boolean {
            editorActions.add(editorAction)
            return true
        }
    }

    @Test fun `backspace with a caret deletes exactly one character`() {
        val ed = FakeEditor("hello")

        assertTrue(ImeEditActions.backspace(ed))

        assertEquals("hell", ed.text.toString())
    }

    @Test fun `backspace with a selection deletes the whole selection and nothing more`() {
        val ed = FakeEditor("hello world", selStart = 6, selEnd = 11)

        assertTrue(ImeEditActions.backspace(ed))

        assertEquals("hello ", ed.text.toString())
        assertEquals(6, ed.selStart)
    }

    @Test fun `backspace with a backwards selection also deletes only the selection`() {
        val ed = FakeEditor("hello world", selStart = 11, selEnd = 6)

        ImeEditActions.backspace(ed)

        assertEquals("hello ", ed.text.toString())
    }

    @Test fun `backspace removes a whole emoji not half a surrogate pair`() {
        val ed = FakeEditor("hi \uD83D\uDE00")

        ImeEditActions.backspace(ed)

        assertEquals("hi ", ed.text.toString())
    }

    @Test fun `backspace at the start of an empty field is harmless`() {
        val ed = FakeEditor("")

        ImeEditActions.backspace(ed)

        assertEquals("", ed.text.toString())
    }

    @Test fun `backspace falls back to a DEL key press when the editor refuses the delete`() {
        val ed = FakeEditor("hello").apply { deleteSurroundingResult = false }

        ImeEditActions.backspace(ed)

        assertEquals(
            listOf(KeyEvent.ACTION_DOWN to KeyEvent.KEYCODE_DEL, KeyEvent.ACTION_UP to KeyEvent.KEYCODE_DEL),
            ed.keyEvents,
        )
    }

    @Test fun `every edit action is a safe no-op without an InputConnection`() {
        assertFalse(ImeEditActions.backspace(null))
        assertFalse(ImeEditActions.space(null))
        assertFalse(ImeEditActions.enter(null, InputType.TYPE_CLASS_TEXT, EditorInfo.IME_ACTION_SEND))
    }

    @Test fun `space inserts a space at the caret and replaces a selection`() {
        val a = FakeEditor("ab", selStart = 1, selEnd = 1)
        ImeEditActions.space(a)
        assertEquals("a b", a.text.toString())

        val b = FakeEditor("hello world", selStart = 5, selEnd = 6)
        ImeEditActions.space(b)
        assertEquals("hello world", b.text.toString())
    }

    @Test fun `enter in a multi-line field inserts a newline`() {
        val ed = FakeEditor("line one")

        ImeEditActions.enter(ed, multiLine, EditorInfo.IME_ACTION_SEND)

        assertEquals("line one\n", ed.text.toString())
        assertTrue(ed.editorActions.isEmpty())
    }

    @Test fun `enter in a single-line Search field performs the search action and types nothing`() {
        val ed = FakeEditor("pizza")

        ImeEditActions.enter(ed, singleLine, EditorInfo.IME_ACTION_SEARCH)

        assertEquals(listOf(EditorInfo.IME_ACTION_SEARCH), ed.editorActions)
        assertEquals("pizza", ed.text.toString())
    }

    @Test fun `enter in a single-line field with no action presses the Enter key`() {
        val ed = FakeEditor("pizza")

        ImeEditActions.enter(ed, singleLine, EditorInfo.IME_ACTION_NONE)

        assertEquals(
            listOf(KeyEvent.ACTION_DOWN to KeyEvent.KEYCODE_ENTER, KeyEvent.ACTION_UP to KeyEvent.KEYCODE_ENTER),
            ed.keyEvents,
        )
        assertTrue(ed.editorActions.isEmpty())
    }

    // ---- Hold-to-repeat ----

    private class FakeClock {
        private var now = 0L
        private val queue = mutableListOf<Pair<Long, Runnable>>()
        fun post(r: Runnable, delay: Long) { queue.add(now + delay to r) }
        fun cancel(r: Runnable) { queue.removeAll { it.second === r } }
        fun advance(ms: Long) {
            val end = now + ms
            while (true) {
                val next = queue.filter { it.first <= end }.minByOrNull { it.first } ?: break
                queue.remove(next)
                now = next.first
                next.second.run()
            }
            now = end
        }
        fun pending() = queue.size
    }

    @Test fun `a tap deletes once and does not repeat`() {
        val clock = FakeClock()
        var count = 0
        val repeater = KeyRepeater(clock::post, clock::cancel, firstDelayMs = 400, intervalMs = 50) { count++; true }

        repeater.press()
        clock.advance(399)
        repeater.release()
        clock.advance(2_000)

        assertEquals(1, count)
        assertEquals(0, clock.pending())
    }

    @Test fun `holding repeats after the initial delay at the key-repeat interval`() {
        val clock = FakeClock()
        var count = 0
        val repeater = KeyRepeater(clock::post, clock::cancel, firstDelayMs = 400, intervalMs = 50) { count++; true }

        repeater.press()
        assertEquals(1, count)
        clock.advance(400)
        assertEquals(2, count) // first repeat lands at 400ms
        clock.advance(150)
        assertEquals(5, count) // 450, 500, 550
        repeater.release()
        clock.advance(1_000)
        assertEquals(5, count)
    }

    @Test fun `repeat stops when the action reports it can no longer act`() {
        val clock = FakeClock()
        var remaining = 3
        val repeater = KeyRepeater(clock::post, clock::cancel, firstDelayMs = 100, intervalMs = 50) { remaining-- > 0 }

        repeater.press()
        clock.advance(5_000)

        assertEquals(-1, remaining) // 3 successful + the one failure that ended it
        assertEquals(0, clock.pending())
    }

    @Test fun `cancel stops a running repeat and a second press restarts cleanly`() {
        val clock = FakeClock()
        var count = 0
        val repeater = KeyRepeater(clock::post, clock::cancel, firstDelayMs = 100, intervalMs = 50) { count++; true }

        repeater.press()
        clock.advance(100)
        repeater.release()
        val afterFirst = count
        repeater.press() // must not leave a duplicate runnable behind from the first hold
        clock.advance(100)
        repeater.release()

        assertEquals(afterFirst + 2, count)
        assertEquals(0, clock.pending())
    }
}
