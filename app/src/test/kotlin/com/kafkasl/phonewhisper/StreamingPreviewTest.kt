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
}
