package com.trevornk.ramblr

import org.junit.Assert.*
import org.junit.Test

/**
 * Covers the VAD-driven segmentation/drain logic behind #132's segmented local decode, using a
 * scripted fake [VadHandle] so none of this needs the native sherpa-onnx library.
 *
 * The bug being guarded: a whole recording used to be decoded in one `OfflineStream`, so peak
 * memory scaled with take length and a ~6 minute dictation OOM-killed the process silently.
 * Segmenting bounds a decode to one utterance, which makes the correctness of *which* samples end
 * up in *which* segment -- and that none are dropped at the tail -- the thing worth testing.
 */
class SpeechSegmenterTest {

    /**
     * Records every window pushed, and emits queued segments on a caller-supplied schedule:
     * [segmentAfterWindows] maps a window index to the segment that becomes available once that
     * window has been accepted. [flushSegment], when set, is queued by [flush].
     */
    private class FakeVad(
        private val segmentAfterWindows: Map<Int, FloatArray> = emptyMap(),
        private val flushSegment: FloatArray? = null,
    ) : VadHandle {
        val windows = mutableListOf<FloatArray>()
        var flushed = false
            private set

        private val queue = ArrayDeque<FloatArray>()

        override fun acceptWaveform(samples: FloatArray) {
            windows += samples
            segmentAfterWindows[windows.size - 1]?.let { queue.addLast(it) }
        }

        override fun isEmpty(): Boolean = queue.isEmpty()
        override fun front(): FloatArray = queue.first()
        override fun pop() { queue.removeFirst() }
        override fun flush() {
            flushed = true
            flushSegment?.let { queue.addLast(it) }
        }
    }

    private fun ramp(size: Int, start: Int = 0) = FloatArray(size) { (start + it).toFloat() }

    // -- windowing --

    @Test fun `feeds the vad exact-size windows and carries the remainder to the next chunk`() {
        val vad = FakeVad()
        val segmenter = SpeechSegmenter(vad, windowSize = 4)

        segmenter.accept(ramp(6)) {}      // 1 full window, 2 left over
        assertEquals(1, vad.windows.size)
        assertArrayEquals(floatArrayOf(0f, 1f, 2f, 3f), vad.windows[0], 0f)

        segmenter.accept(ramp(6, start = 6)) {}  // 2 carried + 6 new = 8 -> 2 more windows
        assertEquals(3, vad.windows.size)
        assertArrayEquals(floatArrayOf(4f, 5f, 6f, 7f), vad.windows[1], 0f)
        assertArrayEquals(floatArrayOf(8f, 9f, 10f, 11f), vad.windows[2], 0f)
    }

    @Test fun `a chunk smaller than one window pushes nothing until enough samples accumulate`() {
        val vad = FakeVad()
        val segmenter = SpeechSegmenter(vad, windowSize = 4)

        segmenter.accept(ramp(3)) {}
        assertTrue(vad.windows.isEmpty())

        segmenter.accept(ramp(1, start = 3)) {}
        assertEquals(1, vad.windows.size)
        assertArrayEquals(floatArrayOf(0f, 1f, 2f, 3f), vad.windows[0], 0f)
    }

    // -- draining --

    @Test fun `emits each completed segment exactly once, in order`() {
        val first = floatArrayOf(1f, 1f)
        val second = floatArrayOf(2f, 2f)
        val vad = FakeVad(segmentAfterWindows = mapOf(0 to first, 2 to second))
        val segmenter = SpeechSegmenter(vad, windowSize = 2)

        val emitted = mutableListOf<FloatArray>()
        segmenter.accept(ramp(6)) { emitted += it }

        assertEquals(2, emitted.size)
        assertArrayEquals(first, emitted[0], 0f)
        assertArrayEquals(second, emitted[1], 0f)
    }

    @Test fun `drains multiple segments queued from a single window`() {
        // Two segments become available off the same window: the drain loop must not stop at one.
        val vad = object : VadHandle {
            private val queue = ArrayDeque<FloatArray>()
            override fun acceptWaveform(samples: FloatArray) {
                queue.addLast(floatArrayOf(1f))
                queue.addLast(floatArrayOf(2f))
            }
            override fun isEmpty() = queue.isEmpty()
            override fun front() = queue.first()
            override fun pop() { queue.removeFirst() }
            override fun flush() {}
        }
        val segmenter = SpeechSegmenter(vad, windowSize = 2)

        val emitted = mutableListOf<FloatArray>()
        segmenter.accept(ramp(2)) { emitted += it }

        assertEquals(2, emitted.size)
    }

    // -- finish --

    @Test fun `finish flushes the vad and emits the trailing segment`() {
        val trailing = floatArrayOf(9f, 9f)
        val vad = FakeVad(flushSegment = trailing)
        val segmenter = SpeechSegmenter(vad, windowSize = 4)

        segmenter.accept(ramp(4)) {}

        val emitted = mutableListOf<FloatArray>()
        segmenter.finish { emitted += it }

        assertTrue(vad.flushed)
        assertEquals(1, emitted.size)
        assertArrayEquals(trailing, emitted[0], 0f)
    }

    @Test fun `finish zero-pads a partial window instead of dropping trailing speech`() {
        val vad = FakeVad()
        val segmenter = SpeechSegmenter(vad, windowSize = 4)

        segmenter.accept(ramp(6)) {}   // 2 samples stranded below the window size
        segmenter.finish {}

        assertEquals(2, vad.windows.size)
        assertArrayEquals(floatArrayOf(4f, 5f, 0f, 0f), vad.windows[1], 0f)
    }

    @Test fun `finish on an empty recording flushes without pushing any window`() {
        val vad = FakeVad()
        val segmenter = SpeechSegmenter(vad, windowSize = 4)

        val emitted = mutableListOf<FloatArray>()
        segmenter.finish { emitted += it }

        assertTrue(vad.windows.isEmpty())
        assertTrue(vad.flushed)
        assertTrue(emitted.isEmpty())
    }

    @Test fun `finish is idempotent`() {
        val vad = FakeVad(flushSegment = floatArrayOf(1f))
        val segmenter = SpeechSegmenter(vad, windowSize = 4)

        val emitted = mutableListOf<FloatArray>()
        segmenter.finish { emitted += it }
        segmenter.finish { emitted += it }

        assertEquals(1, emitted.size)
    }

    @Test fun `accept after finish is rejected`() {
        val segmenter = SpeechSegmenter(FakeVad(), windowSize = 4)
        segmenter.finish {}
        assertThrows(IllegalStateException::class.java) { segmenter.accept(ramp(4)) {} }
    }

    // -- silence-only --

    @Test fun `a silence-only recording emits no segments`() {
        val vad = FakeVad()
        val segmenter = SpeechSegmenter(vad, windowSize = 4)

        val emitted = mutableListOf<FloatArray>()
        segmenter.accept(FloatArray(16)) { emitted += it }
        segmenter.finish { emitted += it }

        assertTrue(emitted.isEmpty())
    }
}
