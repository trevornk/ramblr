package com.trevornk.ramblr

import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputConnectionWrapper
import android.widget.EditText
import android.widget.ImageButton
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** The keyboard's delete key. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ImeDeleteKeyTest {

    /** A real EditText behind the connection its own editor hands a keyboard. */
    private fun field(text: String, selStart: Int = text.length, selEnd: Int = selStart): Pair<EditText, InputConnection> {
        val edit = EditText(RuntimeEnvironment.getApplication())
        edit.setText(text)
        edit.setSelection(selStart, selEnd)
        return edit to edit.onCreateInputConnection(EditorInfo())!!
    }

    @Test fun `a press deletes the character before the cursor`() {
        val (edit, ic) = field("hello")
        ImeDeleteKey.deleteOnce(ic)
        assertEquals("hell", edit.text.toString())
    }

    @Test fun `an emoji made of several code points goes in one press`() {
        val family = "👨‍👩‍👧"
        val (edit, ic) = field("hi $family")
        ImeDeleteKey.deleteOnce(ic)
        assertEquals("hi ", edit.text.toString())
    }

    @Test fun `a selection is deleted as a whole`() {
        val (edit, ic) = field("keep this gone", 9, 14)
        ImeDeleteKey.deleteOnce(ic)
        assertEquals("keep this", edit.text.toString())
    }

    @Test fun `word mode deletes the last word and the spaces after it`() {
        val (edit, ic) = field("one two three  ")
        ImeDeleteKey.deleteOnce(ic, word = true)
        assertEquals("one two ", edit.text.toString())
        ImeDeleteKey.deleteOnce(ic, word = true)
        assertEquals("one ", edit.text.toString())
    }

    @Test fun `only text after the cursor is left alone`() {
        val (edit, ic) = field("abc def", 3)
        ImeDeleteKey.deleteOnce(ic)
        assertEquals("ab def", edit.text.toString())
    }

    @Test fun `with nothing before the cursor a delete key still reaches the app`() {
        val keys = mutableListOf<Int>()
        val (_, real) = field("")
        val ic = object : InputConnectionWrapper(real, false) {
            override fun sendKeyEvent(event: KeyEvent): Boolean {
                if (event.action == KeyEvent.ACTION_DOWN) keys.add(event.keyCode)
                return true
            }
        }
        ImeDeleteKey.deleteOnce(ic)
        assertEquals(listOf(KeyEvent.KEYCODE_DEL), keys)
    }

    @Test fun `holding switches from characters to words after about a second`() {
        assertTrue(!ImeDeleteKey.wordsAfter(0))
        assertTrue(!ImeDeleteKey.wordsAfter(ImeDeleteKey.CHARS_BEFORE_WORDS - 1))
        assertTrue(ImeDeleteKey.wordsAfter(ImeDeleteKey.CHARS_BEFORE_WORDS))
        val holdMs = ImeDeleteKey.REPEAT_START_MS + ImeDeleteKey.CHARS_BEFORE_WORDS * ImeDeleteKey.REPEAT_CHAR_MS
        assertTrue("$holdMs", holdMs in 1_000L..1_600L)
    }

    @Test fun `the panel has a labelled delete key`() {
        val service = Robolectric.buildService(RamblrImeService::class.java).create().get()
        val root = service.onCreateInputView()
        fun View.all(): Sequence<View> = sequence {
            yield(this@all)
            if (this@all is ViewGroup) for (i in 0 until childCount) yieldAll(getChildAt(i).all())
        }
        val delete = root.all().filterIsInstance<ImageButton>().single { it.tag == RamblrImeService.DELETE_TAG }
        assertEquals("Delete", delete.contentDescription)
    }
}
