package com.trevornk.ramblr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * In-memory [TextDestination] standing in for a real editable field. Deliberately models the parts
 * of an `AccessibilityNodeInfo` the write path actually reads -- content, caret, editability,
 * staleness, paste support -- and records the exact call order so ordering guarantees
 * (focus-before-read, direct-before-paste) can be asserted rather than assumed.
 */
private class FakeTextDestination(
    var text: String = "",
    var selStart: Int = -1,
    var selEnd: Int = -1,
    private val direct: Boolean = true,
    private val paste: Boolean = false,
    private val writeSucceeds: Boolean = true,
    private val stale: Boolean = false,
) : TextDestination {
    val calls = mutableListOf<String>()
    var writtenText: String? = null
    var focusCount = 0

    override fun acceptsDirectWrite(): Boolean {
        calls += "acceptsDirectWrite"
        return direct
    }

    override fun refresh(): Boolean {
        calls += "refresh"
        return !stale
    }

    override fun readText(): String {
        calls += "readText"
        return text
    }

    override fun selectionStart(): Int = selStart

    override fun selectionEnd(): Int = selEnd

    override fun prepareForWrite() {
        calls += "prepareForWrite"
        focusCount++
    }

    override fun replaceAllText(text: String): Boolean {
        calls += "replaceAllText"
        if (!writeSucceeds) return false
        writtenText = text
        this.text = text
        return true
    }

    override fun pasteFromClipboard(): Boolean {
        calls += "pasteFromClipboard"
        return paste
    }
}

class DictationTextWriterTest {

    // --- commit(): routing ---

    @Test
    fun `commit prefers direct write over paste when the destination accepts it`() {
        val destination = FakeTextDestination(direct = true, paste = true)

        val result = DictationTextWriter.commit(destination, "hello")

        assertEquals(InjectMethod.DIRECT, result.method)
        assertFalse("paste must not run once a direct write succeeded", destination.calls.contains("pasteFromClipboard"))
    }

    @Test
    fun `commit falls back to paste when the destination refuses direct writes`() {
        val destination = FakeTextDestination(direct = false, paste = true)

        val result = DictationTextWriter.commit(destination, "hello")

        assertEquals(InjectMethod.FROM_CLIPBOARD, result.method)
        assertNull("paste never captures prior text; undo can't restore it", result.priorText)
        assertFalse("no direct write may be attempted on a non-editable destination", destination.calls.contains("replaceAllText"))
    }

    @Test
    fun `commit falls back to paste when an accepted direct write fails`() {
        val destination = FakeTextDestination(direct = true, writeSucceeds = false, paste = true)

        val result = DictationTextWriter.commit(destination, "hello")

        assertEquals(InjectMethod.FROM_CLIPBOARD, result.method)
        assertTrue(destination.calls.contains("replaceAllText"))
        assertTrue(destination.calls.contains("pasteFromClipboard"))
    }

    @Test
    fun `commit reports NONE when neither direct write nor paste works`() {
        val destination = FakeTextDestination(direct = true, writeSucceeds = false, paste = false)

        val result = DictationTextWriter.commit(destination, "hello")

        assertEquals(InjectMethod.NONE, result.method)
        assertNull(result.priorText)
    }

    @Test
    fun `commit takes focus before reading the destination's text and caret`() {
        val destination = FakeTextDestination(text = "draft", selStart = 5, selEnd = 5)

        DictationTextWriter.commit(destination, "hello")

        assertEquals(1, destination.focusCount)
        assertTrue(
            "focus must precede the content read; a focus change can move the caret",
            destination.calls.indexOf("prepareForWrite") < destination.calls.indexOf("readText"),
        )
    }

    // --- commit(): composition, preserving #42 / #140 / #144 behaviour ---

    @Test
    fun `commit appends after an existing draft that reports a zero caret`() {
        // #140: WhatsApp's restored draft reports (0,0) with the caret visibly at the end.
        // Prepending here is the exact regression that produced " worlddraft".
        val destination = FakeTextDestination(text = "draft", selStart = 0, selEnd = 0)

        DictationTextWriter.commit(destination, "hello")

        assertEquals("draft hello", destination.writtenText)
    }

    @Test
    fun `commit writes dictated text alone into an empty destination`() {
        val destination = FakeTextDestination(text = "", selStart = -1, selEnd = -1)

        DictationTextWriter.commit(destination, "hello")

        assertEquals("hello", destination.writtenText)
    }

    @Test
    fun `commit separates a draft from dictated text instead of gluing them together`() {
        // #144: "Hello john" + "How old is Tom?" must not become "Hello johnHow old is Tom?".
        val destination = FakeTextDestination(text = "Hello john", selStart = 10, selEnd = 10)

        DictationTextWriter.commit(destination, "How old is Tom?")

        assertEquals("Hello john How old is Tom?", destination.writtenText)
    }

    @Test
    fun `commit captures prior text on the direct path so undo can restore it`() {
        val destination = FakeTextDestination(text = "before", selStart = 6, selEnd = 6)

        val result = DictationTextWriter.commit(destination, "after")

        assertEquals("before", result.priorText)
    }

    // --- commitClosingStreamingSpan(): #45 ---

    @Test
    fun `commitClosingStreamingSpan replaces the tracked partial rather than appending beside it`() {
        val destination = FakeTextDestination(text = "note partial", selStart = 12, selEnd = 12)

        val result = DictationTextWriter.commitClosingStreamingSpan(
            destination,
            StreamingSpan(insertionStart = 4, previousLength = 8),
            "final text",
        )

        assertEquals(InjectMethod.DIRECT, result.method)
        assertEquals("note final text", destination.writtenText)
    }

    @Test
    fun `commitClosingStreamingSpan reports NONE for a stale destination without writing`() {
        val destination = FakeTextDestination(text = "note partial", stale = true)

        val result = DictationTextWriter.commitClosingStreamingSpan(
            destination,
            StreamingSpan(insertionStart = 4, previousLength = 8),
            "final text",
        )

        assertEquals(InjectMethod.NONE, result.method)
        assertNull("a stale destination must never be written to", destination.writtenText)
    }

    @Test
    fun `commitClosingStreamingSpan reports NONE when the write is rejected`() {
        val destination = FakeTextDestination(text = "note partial", writeSucceeds = false)

        val result = DictationTextWriter.commitClosingStreamingSpan(
            destination,
            StreamingSpan(insertionStart = 4, previousLength = 8),
            "final text",
        )

        assertEquals(InjectMethod.NONE, result.method)
    }

    @Test
    fun `commitClosingStreamingSpan never pastes because paste would append beside the leftover`() {
        val destination = FakeTextDestination(text = "note partial", writeSucceeds = false, paste = true)

        val result = DictationTextWriter.commitClosingStreamingSpan(
            destination,
            StreamingSpan(insertionStart = 4, previousLength = 8),
            "final text",
        )

        assertEquals(InjectMethod.NONE, result.method)
        assertFalse(destination.calls.contains("pasteFromClipboard"))
    }

    // --- clearStreamingSpan(): #45 ---

    @Test
    fun `clearStreamingSpan reverts an orphaned partial when the final text landed elsewhere`() {
        val destination = FakeTextDestination(text = "note partial")

        DictationTextWriter.clearStreamingSpan(
            destination,
            StreamingSpan(insertionStart = 4, previousLength = 8),
        )

        assertEquals("note", destination.writtenText)
    }

    @Test
    fun `clearStreamingSpan leaves a stale destination untouched`() {
        val destination = FakeTextDestination(text = "note partial", stale = true)

        DictationTextWriter.clearStreamingSpan(
            destination,
            StreamingSpan(insertionStart = 4, previousLength = 8),
        )

        assertNull(destination.writtenText)
    }
}
