package com.trevornk.ramblr

import org.junit.Assert.*
import org.junit.Test

/**
 * #132: a segmented decode produces one result per speech segment, so the join has to behave when
 * a segment transcribes to nothing (silence the VAD passed through, or a model that returned an
 * empty result) -- otherwise the user gets leading/doubled spaces injected into their text.
 */
class SegmentedTranscriptTest {

    @Test fun `joins segments with a single space`() {
        assertEquals("hello world", SegmentedTranscript.join(listOf("hello", "world")))
    }

    @Test fun `drops empty and whitespace-only segments`() {
        assertEquals("hello world", SegmentedTranscript.join(listOf("hello", "", "   ", "world")))
    }

    @Test fun `trims per-segment whitespace rather than concatenating it`() {
        assertEquals("hello world", SegmentedTranscript.join(listOf("  hello ", " world  ")))
    }

    @Test fun `an all-empty list joins to an empty string`() {
        assertEquals("", SegmentedTranscript.join(listOf("", "  ")))
    }

    @Test fun `an empty list joins to an empty string`() {
        assertEquals("", SegmentedTranscript.join(emptyList()))
    }

    @Test fun `a single segment is returned trimmed and unchanged otherwise`() {
        assertEquals("just one", SegmentedTranscript.join(listOf(" just one ")))
    }

    @Test fun `preserves interior spacing within a segment`() {
        assertEquals("a  b c", SegmentedTranscript.join(listOf("a  b", "c")))
    }
}
