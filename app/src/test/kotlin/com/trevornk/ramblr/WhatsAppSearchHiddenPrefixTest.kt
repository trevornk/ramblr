package com.trevornk.ramblr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #373: WhatsApp's chat-list search field (`com.whatsapp:id/search_input`) prepends U+200B to every
 * piece of text it holds. Measured on a Pixel 10a: after ACTION_SET_TEXT "X" the field holds
 * U+200B + "X" and the selection is reported in those raw coordinates, one past what we wrote. The
 * destination boundary must hand the writer logical coordinates (the text as we wrote it).
 */
class WhatsAppSearchHiddenPrefixTest {

    private val zw = ZERO_WIDTH_SPACE
    private val searchId = WHATSAPP_SEARCH_INPUT_ID
    private val placeholder = "Ask Meta AI or Search"

    private fun resolved(raw: String?, viewId: String?, selStart: Int = 4, selEnd: Int = 4) = resolveRealText(
        rawText = raw,
        isShowingHintText = false,
        selectionStart = selStart,
        selectionEnd = selEnd,
        isEditable = true,
        isFocused = true,
        viewIdResourceName = viewId,
    )

    // --- pure helpers ---

    @Test fun `hidden prefix is stripped only for the WhatsApp search view id`() {
        assertEquals("abc", resolved("${zw}abc", searchId))
        assertEquals("${zw}abc", resolved("${zw}abc", "com.whatsapp:id/entry"))
        assertEquals("${zw}abc", resolved("${zw}abc", null))
        assertEquals("${zw}abc", resolved("${zw}abc", "com.example:id/search_input"))
    }

    @Test fun `only one leading ZWSP is the hidden prefix`() {
        assertEquals(1, hiddenPrefixLength(searchId, "${zw}${zw}x"))
        assertEquals("${zw}x", resolved("${zw}${zw}x", searchId))
    }

    @Test fun `hiddenPrefixLength is keyed on the view id and the raw text`() {
        assertEquals(1, hiddenPrefixLength(searchId, "${zw}abc"))
        assertEquals(0, hiddenPrefixLength(searchId, "abc"))
        assertEquals(0, hiddenPrefixLength(searchId, placeholder))
        assertEquals(0, hiddenPrefixLength(searchId, null))
        assertEquals(0, hiddenPrefixLength(null, "${zw}abc"))
        assertEquals(0, hiddenPrefixLength("com.whatsapp:id/entry", "${zw}abc"))
    }

    @Test fun `placeholder without ZWSP still resolves to empty`() {
        assertEquals("", resolved(placeholder, searchId, 1, 1))
    }

    @Test fun `ZWSP-only content resolves to empty`() {
        assertEquals("", resolved(zw, searchId, 1, 1))
    }

    @Test fun `selection conversion keeps unreported negatives and never goes below zero`() {
        assertEquals(3, logicalSelection(4, 1))
        assertEquals(0, logicalSelection(0, 1))
        assertEquals(-1, logicalSelection(-1, 1))
        assertEquals(1, logicalSelection(1, 0))
        assertEquals(4, rawCaret(3, 1))
        assertEquals(3, rawCaret(3, 0))
    }

    // --- WhatsApp-emulating destination, using the same helpers as AccessibilityTextDestination ---

    private class WhatsAppSearchFake(private val searchId: String) : TextDestination {
        var raw = "Ask Meta AI or Search"
        var rawSelStart = 1
        var rawSelEnd = 1

        private fun prefix() = hiddenPrefixLength(searchId, raw)

        override fun acceptsDirectWrite() = true
        override fun refresh() = true
        override fun readText() = resolveRealText(raw, false, rawSelStart, rawSelEnd, true, true, searchId)
        override fun selectionStart() = logicalSelection(rawSelStart, prefix())
        override fun selectionEnd() = logicalSelection(rawSelEnd, prefix())
        override fun prepareForWrite() {}
        override fun replaceAllText(text: String): Boolean {
            // The app re-adds its prefix and leaves the caret at the end of its (raw) text.
            raw = "​$text"
            rawSelStart = raw.length
            rawSelEnd = raw.length
            return true
        }
        override fun wholeFieldWriteIsLossy() = false
        override fun pasteFromClipboard(payload: String) = false
        override fun placeCaret(position: Int) {
            val p = rawCaret(position, prefix())
            rawSelStart = p
            rawSelEnd = p
        }
    }

    private val noPause = WritePause { }

    private fun field() = WhatsAppSearchFake(searchId)

    private fun assertCaretAtEnd(f: WhatsAppSearchFake) {
        assertEquals(f.raw.length, f.rawSelStart)
        assertEquals(f.raw.length, f.rawSelEnd)
    }

    @Test fun `one-shot commit into the empty field leaves the caret at the end`() {
        val f = field()
        DictationTextWriter.commit(f, "Testing one two three.", noPause)
        assertEquals("${zw}Testing one two three.", f.raw)
        assertCaretAtEnd(f)
    }

    @Test fun `one-shot commit appends after existing real text with a separator`() {
        val f = field()
        DictationTextWriter.commit(f, "hello", noPause)
        DictationTextWriter.commit(f, "world", noPause)
        assertEquals("${zw}hello world", f.raw)
        assertCaretAtEnd(f)
    }

    /** Drives the field the way WhisperAccessibilityService.maybeInjectPartial does. */
    private fun firstPartial(f: WhatsAppSearchFake, text: String): StreamingSpan {
        val write = composeFirstStreamingPartial(f.readText(), f.selectionStart(), f.selectionEnd(), text)
        assertTrue(f.replaceAllText(write.updatedText))
        // scheduleCaretNudgeAfterLanding: the nudge only fires when the field reads back as written.
        assertEquals(write.updatedText, f.readText())
        f.placeCaret(write.span.insertionStart + write.span.previousLength)
        return write.span
    }

    private fun nextPartial(f: WhatsAppSearchFake, span: StreamingSpan, text: String): StreamingSpan {
        val write = composeTracedStreamingPartial(f.readText(), span.insertionStart, span.previousLength, span.trace, text)
        assertTrue(f.replaceAllText(write.updatedText))
        assertEquals(write.updatedText, f.readText())
        f.placeCaret(span.insertionStart + write.trackedLength)
        return span.copy(previousLength = write.trackedLength, trace = write.trace)
    }

    @Test fun `streaming partials then final yield exactly the dictation with the caret at the end`() {
        val f = field()
        var span = firstPartial(f, "Testing one two three")
        assertEquals("${zw}Testing one two three", f.raw)
        assertCaretAtEnd(f)
        span = nextPartial(f, span, "Testing one two three four")
        assertEquals("${zw}Testing one two three four", f.raw)
        assertCaretAtEnd(f)
        val result = DictationTextWriter.commitClosingStreamingSpan(f, span, "Testing one two three four.", noPause)
        assertEquals(InjectMethod.DIRECT, result.method)
        assertEquals("${zw}Testing one two three four.", f.raw)
        assertCaretAtEnd(f)
    }

    @Test fun `commit priorText is logical so undo restores the empty field`() {
        val f = field()
        val result = DictationTextWriter.commit(f, "Testing", noPause)
        assertEquals("", result.priorText)
        assertEquals(UndoPlan.RestoreInPlace(""), planUndo(0, 60_000, true, result.priorText, null))
    }

    @Test fun `raw-text swap rewrites the injected text in place`() {
        val f = field()
        val result = DictationTextWriter.commit(f, "Cleaned text.", noPause)
        val current = f.readText()
        val swap = swapInjectedForRaw(result.priorText!!, current, "Cleaned text.", "raw text")!!
        assertTrue(DictationTextWriter.replaceConfirmed(f, current, swap.text, swap.caret, noPause))
        assertEquals("${zw}raw text", f.raw)
        assertCaretAtEnd(f)
    }

    @Test fun `the caret nudge landing check holds for text we just wrote`() {
        val f = field()
        val write = composeFirstStreamingPartial(f.readText(), f.selectionStart(), f.selectionEnd(), "abc")
        f.replaceAllText(write.updatedText)
        assertEquals(write.updatedText, f.readText())
    }
}
