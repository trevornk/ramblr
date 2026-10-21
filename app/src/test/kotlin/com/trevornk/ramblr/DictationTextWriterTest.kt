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
    var lastCaret = -1

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

    override fun placeCaretAtEnd(textLength: Int) {
        calls += "placeCaretAtEnd"
        lastCaret = textLength
    }
}


/**
 * A destination that behaves like the Compose `BasicTextField` in Hermes Relay's composer
 * (ramblr#TBD): `ACTION_SET_TEXT` reports success but the new value only becomes visible to a
 * reader after the field recomposes ([landsAfterReads] reads later); a caret move issued *before*
 * that happens re-applies the field's stale value and silently loses the write; and a target may
 * simply swallow a write while still reporting success ([dropWrites]; [Int.MAX_VALUE] = always).
 */
private class ComposeLikeDestination(
    var text: String,
    private val landsAfterReads: Int = 0,
    private var dropWrites: Int = 0,
    private val normalize: (String) -> String = { it },
    private val stale: Boolean = false,
) : TextDestination {
    var replaceCalls = 0
    var caretCalls = 0
    var caretPlacedAfterLanding = false
    private var pending: String? = null
    private var readsSincePending = 0

    private fun settle() {
        val p = pending ?: return
        if (readsSincePending >= landsAfterReads) { text = normalize(p); pending = null }
    }

    override fun acceptsDirectWrite() = true
    override fun refresh(): Boolean { readsSincePending++; return !stale }
    override fun readText(): String { settle(); return text }
    override fun selectionStart() = text.length
    override fun selectionEnd() = text.length
    override fun prepareForWrite() {}
    override fun replaceAllText(text: String): Boolean {
        replaceCalls++
        if (dropWrites > 0) { if (dropWrites != Int.MAX_VALUE) dropWrites--; return true }
        pending = text
        readsSincePending = 0
        settle()
        return true
    }
    override fun pasteFromClipboard() = false
    override fun placeCaretAtEnd(textLength: Int) {
        caretCalls++
        if (pending != null) { pending = null } else caretPlacedAfterLanding = true
    }
    /** What the user ends up looking at once the field has finished recomposing. */
    fun settled(): String { readsSincePending = Int.MAX_VALUE; settle(); return text }
}

private val NO_PAUSE = WritePause { }

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

    // --- selection overwritten by the first partial (#300) ---

    @Test
    fun `commitClosingStreamingSpan after a select-all leaves only the final text`() {
        val destination = FakeTextDestination(text = "Partial")
        val result = DictationTextWriter.commitClosingStreamingSpan(
            destination,
            StreamingSpan(insertionStart = 0, previousLength = 7, replacedText = "old draft"),
            "Final text",
        )
        assertEquals(InjectMethod.DIRECT, result.method)
        assertEquals("Final text", destination.writtenText)
    }

    @Test
    fun `clearStreamingSpan restores the selected text the first partial overwrote`() {
        val destination = FakeTextDestination(text = "note Partial end")
        DictationTextWriter.clearStreamingSpan(
            destination,
            StreamingSpan(insertionStart = 5, previousLength = 7, replacedText = "selected"),
        )
        assertEquals("note selected end", destination.writtenText)
    }

    // --- confirmed writes: a target can report ACTION_SET_TEXT success and still not apply it ---

    @Test
    fun `a final write the target silently swallows is retried once and lands`() {
        val destination = ComposeLikeDestination(text = "interim hypothesis text", dropWrites = 1)

        val result = DictationTextWriter.commitClosingStreamingSpan(
            destination, StreamingSpan(insertionStart = 0, previousLength = 23), "Final text.", NO_PAUSE,
        )

        assertEquals(InjectMethod.DIRECT, result.method)
        assertEquals("Final text.", destination.settled())
        assertEquals(2, destination.replaceCalls)
    }

    @Test
    fun `a final write the target keeps swallowing reports NONE instead of a phantom success`() {
        val destination = ComposeLikeDestination(text = "interim hypothesis text", dropWrites = Int.MAX_VALUE)

        val result = DictationTextWriter.commitClosingStreamingSpan(
            destination, StreamingSpan(insertionStart = 0, previousLength = 23), "Final text.", NO_PAUSE,
        )

        assertEquals(InjectMethod.NONE, result.method)
        assertEquals("exactly one retry, not a loop", 2, destination.replaceCalls)
        assertEquals("no caret move on a write that never landed", 0, destination.caretCalls)
    }

    @Test
    fun `the confirmation wait is bounded`() {
        val destination = ComposeLikeDestination(text = "interim", dropWrites = Int.MAX_VALUE)
        var slept = 0L

        DictationTextWriter.commitClosingStreamingSpan(
            destination, StreamingSpan(0, 7), "Final.", WritePause { slept += it },
        )

        assertTrue("slept ${slept}ms", slept in 1..1000)
    }

    @Test
    fun `a target that rewrites the text still counts as landed and is not written again`() {
        val destination = ComposeLikeDestination(text = "interim", normalize = { it.trim().uppercase() })

        val result = DictationTextWriter.commitClosingStreamingSpan(
            destination, StreamingSpan(0, 7), "final ", NO_PAUSE,
        )

        assertEquals(InjectMethod.DIRECT, result.method)
        assertEquals(1, destination.replaceCalls)
        assertEquals("FINAL", destination.settled())
    }

    @Test
    fun `the caret is only moved after the write is observed, never into a stale value`() {
        val destination = ComposeLikeDestination(text = "interim hypothesis", landsAfterReads = 3)

        val result = DictationTextWriter.commitClosingStreamingSpan(
            destination, StreamingSpan(0, 18), "Final text.", NO_PAUSE,
        )

        assertEquals(InjectMethod.DIRECT, result.method)
        assertEquals("Final text.", destination.settled())
        assertTrue("caret must follow a landed write", destination.caretPlacedAfterLanding)
    }

    @Test
    fun `a destination that goes stale right after the write is not treated as a failed write`() {
        val destination = ComposeLikeDestination(text = "interim", landsAfterReads = 0, stale = false)
        val result = DictationTextWriter.commitClosingStreamingSpan(
            destination, StreamingSpan(0, 7), "Final.", NO_PAUSE,
        )
        assertEquals(InjectMethod.DIRECT, result.method)
    }

    @Test
    fun `commit retries a swallowed one-shot write and lands it`() {
        val destination = ComposeLikeDestination(text = "", dropWrites = 1)

        val result = DictationTextWriter.commit(destination, "Hello there.", NO_PAUSE)

        assertEquals(InjectMethod.DIRECT, result.method)
        assertEquals("Hello there.", destination.settled())
    }

    @Test
    fun `commit never reports DIRECT for a write that never landed`() {
        val destination = ComposeLikeDestination(text = "draft", dropWrites = Int.MAX_VALUE)

        val result = DictationTextWriter.commit(destination, "Hello there.", NO_PAUSE)

        assertEquals(InjectMethod.NONE, result.method)
    }

    @Test
    fun `clearStreamingSpan retries a swallowed revert so no preview garbage is left behind`() {
        val destination = ComposeLikeDestination(text = "note Partial end", dropWrites = 1)

        DictationTextWriter.clearStreamingSpan(
            destination, StreamingSpan(insertionStart = 5, previousLength = 7, replacedText = "selected"), NO_PAUSE,
        )

        assertEquals("note selected end", destination.settled())
    }

    @Test
    fun `closing a span whose last partial write was reverted removes the whole leftover`() {
        // The field still holds the previous, longer partial because the newest (shorter) partial
        // never landed; the trace lets the close span cover what is really there.
        val destination = FakeTextDestination(text = "Alpha beta gamma delta. To you.")
        val span = StreamingSpan(
            insertionStart = 0, previousLength = 23,
            trace = StreamingWriteTrace(
                writtenText = "Alpha beta gamma delta.",
                priorText = "Alpha beta gamma delta. To you.", priorLength = 31,
            ),
        )

        val result = DictationTextWriter.commitClosingStreamingSpan(destination, span, "Final.")

        assertEquals(InjectMethod.DIRECT, result.method)
        assertEquals("Final.", destination.writtenText)
    }

    // --- replaceConfirmed (#343) ---

    @Test fun `replaceConfirmed writes the full text and puts the caret at the requested spot`() {
        val field = FakeTextDestination(text = "Hello Clean world")

        val ok = DictationTextWriter.replaceConfirmed(field, "Hello Clean world", "Hello raw um world", caret = 12, pause = NO_PAUSE)

        assertTrue(ok)
        assertEquals("Hello raw um world", field.text)
        assertEquals(12, field.lastCaret)
    }

    @Test fun `replaceConfirmed reports failure and leaves the caret alone when the write fails`() {
        val field = FakeTextDestination(text = "abc", writeSucceeds = false)

        val ok = DictationTextWriter.replaceConfirmed(field, "abc", "abd", caret = 3, pause = NO_PAUSE)

        assertFalse(ok)
        assertEquals(-1, field.lastCaret)
    }
}
