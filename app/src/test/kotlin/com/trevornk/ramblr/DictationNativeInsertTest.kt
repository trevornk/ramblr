package com.trevornk.ramblr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/** #367: WebView text fields get a real insert at the caret; everything else is unchanged. */
private class NativeInsertDestination(
    var text: String,
    private val selStart: Int,
    private val selEnd: Int,
    private val offersInsert: Boolean = true,
    private val insertLands: Boolean = true,
) : TextDestination {
    var inserted: String? = null
    var replaceCalls = 0
    var caret = -1

    override fun acceptsDirectWrite() = true
    override fun refresh() = true
    override fun readText() = text
    override fun selectionStart() = selStart
    override fun selectionEnd() = selEnd
    override fun prepareForWrite() {}
    override fun replaceAllText(text: String): Boolean {
        replaceCalls++
        this.text = text
        return true
    }
    override fun placeCaret(position: Int) { caret = position }
    override fun pasteFromClipboard() = false
    override fun insertAtCaretNatively(text: String): Boolean {
        if (!offersInsert) return false
        inserted = text
        if (insertLands) {
            val span = resolveReplacementSpan(selStart, selEnd, this.text.length)
            this.text = this.text.replaceRange(span.start, span.endExclusive, text)
        }
        return true
    }
}

class DictationNativeInsertTest {
    private val noWait = WritePause { }

    @Test fun midFieldCaretInsertsSeparatedTextWithoutRewritingTheField() {
        val d = NativeInsertDestination("hello world", 5, 5)
        val r = DictationTextWriter.commit(d, "there", noWait)
        assertEquals(InjectMethod.DIRECT, r.method)
        assertEquals("hello world", r.priorText)
        assertEquals(" there", d.inserted)
        assertEquals("hello there world", d.text)
        assertEquals(0, d.replaceCalls)
        assertEquals(-1, d.caret)
    }

    @Test fun selectionIsReplacedByTheCommit() {
        val d = NativeInsertDestination("one two three", 4, 7)
        val r = DictationTextWriter.commit(d, "2", noWait)
        assertEquals(InjectMethod.DIRECT, r.method)
        assertEquals("2", d.inserted)
        assertEquals("one 2 three", d.text)
        assertEquals(0, d.replaceCalls)
    }

    @Test fun insertIntoEmptyFieldAddsNoSeparator() {
        val d = NativeInsertDestination("", 0, 0)
        DictationTextWriter.commit(d, "x", noWait)
        assertEquals("x", d.inserted)
    }

    @Test fun notOfferedFallsBackToWholeFieldWriteAndCaret() {
        val d = NativeInsertDestination("hello world", 5, 5, offersInsert = false)
        val r = DictationTextWriter.commit(d, "there", noWait)
        assertEquals(InjectMethod.DIRECT, r.method)
        assertNull(d.inserted)
        assertEquals(1, d.replaceCalls)
        assertEquals("hello there world", d.text)
        assertEquals(11, d.caret)
    }

    @Test fun commitThatNeverLandsFallsBackToWholeFieldWrite() {
        val d = NativeInsertDestination("hello world", 5, 5, insertLands = false)
        val r = DictationTextWriter.commit(d, "there", noWait)
        assertEquals(InjectMethod.DIRECT, r.method)
        assertEquals(1, d.replaceCalls)
        assertEquals("hello there world", d.text)
    }

    @Test fun defaultDestinationNeverInsertsNatively() {
        val d = object : TextDestination {
            override fun acceptsDirectWrite() = true
            override fun refresh() = true
            override fun readText() = ""
            override fun selectionStart() = 0
            override fun selectionEnd() = 0
            override fun prepareForWrite() {}
            override fun replaceAllText(text: String) = true
            override fun placeCaret(position: Int) {}
            override fun pasteFromClipboard() = false
        }
        assertFalse(d.insertAtCaretNatively("x"))
    }
}
