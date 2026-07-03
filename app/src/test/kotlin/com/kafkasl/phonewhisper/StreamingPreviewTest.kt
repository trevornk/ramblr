package com.kafkasl.phonewhisper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamingPreviewTest {

    // --- shouldInjectPartial (throttling) ---

    @Test fun `first partial of a recording injects immediately, bypassing the interval`() {
        assertTrue(shouldInjectPartial("hello", previousText = null, lastInjectedAtMs = 0, nowMs = 0, minIntervalMs = 400))
    }

    @Test fun `a second partial before the interval elapses is held back`() {
        assertFalse(shouldInjectPartial("hello there", previousText = "hello", lastInjectedAtMs = 1000, nowMs = 1200, minIntervalMs = 400))
    }

    @Test fun `a second partial once the interval elapses is injected`() {
        assertTrue(shouldInjectPartial("hello there", previousText = "hello", lastInjectedAtMs = 1000, nowMs = 1400, minIntervalMs = 400))
    }

    @Test fun `right at the interval boundary is injected`() {
        assertTrue(shouldInjectPartial("hello there", previousText = "hello", lastInjectedAtMs = 1000, nowMs = 1399, minIntervalMs = 399))
    }

    @Test fun `unchanged text never re-injects, even well past the interval`() {
        assertFalse(shouldInjectPartial("hello", previousText = "hello", lastInjectedAtMs = 0, nowMs = 100_000, minIntervalMs = 400))
    }

    @Test fun `blank text never injects, even as the first partial`() {
        assertFalse(shouldInjectPartial("", previousText = null, lastInjectedAtMs = 0, nowMs = 0, minIntervalMs = 400))
        assertFalse(shouldInjectPartial("   ", previousText = null, lastInjectedAtMs = 0, nowMs = 0, minIntervalMs = 400))
    }

    // --- replacePartialInField (replace-not-append) ---

    @Test fun `replaces the previous partial span with the new text, not appending after it`() {
        // "Hello wrld" -> user said "world", replacing the 4-char "wrld" starting at index 6
        val updated = replacePartialInField(current = "Hello wrld", insertionStart = 6, previousLength = 4, newText = "world")
        assertEquals("Hello world", updated)
    }

    @Test fun `first partial of a session has previousLength zero, so it inserts rather than replaces`() {
        val updated = replacePartialInField(current = "Hello ", insertionStart = 6, previousLength = 0, newText = "world")
        assertEquals("Hello world", updated)
    }

    @Test fun `a shorter revision shrinks the field instead of leaving stray characters`() {
        val updated = replacePartialInField(current = "Hello world today", insertionStart = 6, previousLength = 5, newText = "there")
        assertEquals("Hello there today", updated)
    }

    @Test fun `preserves text before and after the insertion point untouched`() {
        val updated = replacePartialInField(current = "Subject: hello line two", insertionStart = 9, previousLength = 5, newText = "howdy")
        assertEquals("Subject: howdy line two", updated)
    }

    @Test fun `clamps an insertion point past the end of a field that shrank out from under the session`() {
        val updated = replacePartialInField(current = "short", insertionStart = 100, previousLength = 4, newText = "world")
        assertEquals("shortworld", updated)
    }

    @Test fun `clamps a replacement span that would run past the end of the field`() {
        val updated = replacePartialInField(current = "Hello wr", insertionStart = 6, previousLength = 40, newText = "world")
        assertEquals("Hello world", updated)
    }

    // --- reconcileStreamingSpan (final-injection handoff, #45) ---

    @Test fun `streaming session tracking the final injection's own target node has its span replaced with the final text`() {
        // "S awright now..." bug repro: the tracked 1-char leftover "S" must be fully replaced by
        // the final transcript, not left concatenated alongside it.
        val updated = reconcileStreamingSpan(
            current = "S",
            session = StreamingSpan(insertionStart = 0, previousLength = 1),
            finalText = "awright now so this is what it looks like",
            isFinalInjectionTarget = true
        )
        assertEquals("awright now so this is what it looks like", updated)
    }

    @Test fun `streaming session tracking a different node than the final injection has its leftover span cleared, not left orphaned`() {
        val updated = reconcileStreamingSpan(
            current = "Draft: S",
            session = StreamingSpan(insertionStart = 7, previousLength = 1),
            finalText = "irrelevant -- lands elsewhere",
            isFinalInjectionTarget = false
        )
        assertEquals("Draft: ", updated)
    }

    @Test fun `no streaming session at all is completely unaffected`() {
        assertEquals(
            null,
            reconcileStreamingSpan(
                current = "whatever is currently in the field",
                session = null,
                finalText = "final transcript",
                isFinalInjectionTarget = true
            )
        )
    }

    // --- shouldUseStreamingPreview (streaming-vs-offline gating) ---

    @Test fun `streaming preview requires both the setting and an installed model`() {
        assertTrue(shouldUseStreamingPreview(settingEnabled = true, streamingModelInstalled = true))
    }

    @Test fun `streaming preview is off when the setting is off, even with the model installed`() {
        assertFalse(shouldUseStreamingPreview(settingEnabled = false, streamingModelInstalled = true))
    }

    @Test fun `streaming preview is off when the model isn't installed, even with the setting on`() {
        // Covers the "cleanly disabled" acceptance criterion: a model deleted after being
        // enabled must fall back to no preview, not crash on a missing file.
        assertFalse(shouldUseStreamingPreview(settingEnabled = true, streamingModelInstalled = false))
    }

    @Test fun `streaming preview is off when neither the setting nor the model are present`() {
        assertFalse(shouldUseStreamingPreview(settingEnabled = false, streamingModelInstalled = false))
    }

    // --- smartCapitalize (display-only sentence case, #42) ---

    @Test fun `empty string stays empty`() {
        assertEquals("", smartCapitalize(""))
    }

    @Test fun `single sentence gets only its first letter capitalized`() {
        assertEquals("Hello world", smartCapitalize("HELLO WORLD"))
    }

    @Test fun `multiple sentences are capitalized after period, exclamation and question mark`() {
        assertEquals(
            "Hello world. How are you! Fine then? Great",
            smartCapitalize("HELLO WORLD. HOW ARE YOU! FINE THEN? GREAT")
        )
    }

    @Test fun `all-caps input produces correctly cased output throughout, not just the first word`() {
        assertEquals("This is a longer sentence with many words", smartCapitalize("THIS IS A LONGER SENTENCE WITH MANY WORDS"))
    }

    @Test fun `a trailing partial word with no sentence terminator is left as a normal lowercase continuation`() {
        // Streaming hypotheses are incomplete -- the last word may be a mid-utterance fragment.
        assertEquals("Hello world this is a tes", smartCapitalize("HELLO WORLD THIS IS A TES"))
    }

    // --- resolveInsertionStart (first-partial insertion point, #42) ---

    @Test fun `negative selection falls back to the end of the existing text`() {
        assertEquals(12, resolveInsertionStart(selStart = -1, selEnd = -1, currentTextLength = 12))
    }

    @Test fun `a genuine non-zero selection is trusted as intentional cursor placement`() {
        assertEquals(3, resolveInsertionStart(selStart = 3, selEnd = 7, currentTextLength = 20))
    }

    @Test fun `an ambiguous (0, 0) report against non-empty existing text falls back to the end`() {
        // Many EditText/keyboard implementations report a stale (0, 0) selection before any real
        // selection-changed event has fired for the field -- not a genuine cursor-at-start.
        assertEquals(9, resolveInsertionStart(selStart = 0, selEnd = 0, currentTextLength = 9))
    }

    @Test fun `a genuine (0, 0) report against empty existing text is trusted`() {
        assertEquals(0, resolveInsertionStart(selStart = 0, selEnd = 0, currentTextLength = 0))
    }
}
