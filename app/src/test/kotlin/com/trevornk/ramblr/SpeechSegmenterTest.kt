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
 *
 * The pre-roll section (#196) guards the head-clipping fix: Silero's detected speech start clips
 * the first phoneme(s), so each emitted segment must be prepended with the audio that immediately
 * preceded its detected start -- from the right history region, clamped at the recording start
 * and at the previous segment's end, and never crashing when history has been evicted.
 */
class SpeechSegmenterTest {

    /**
     * Records every window pushed, and emits queued segments on a caller-supplied schedule:
     * [segmentAfterWindows] maps a window index to the segment that becomes available once that
     * window has been accepted. [flushSegment], when set, is queued by [flush].
     */
    private class FakeVad(
        private val segmentAfterWindows: Map<Int, VadSegment> = emptyMap(),
        private val flushSegment: VadSegment? = null,
    ) : VadHandle {
        val windows = mutableListOf<FloatArray>()
        var flushed = false
            private set

        private val queue = ArrayDeque<VadSegment>()

        override fun acceptWaveform(samples: FloatArray) {
            windows += samples
            segmentAfterWindows[windows.size - 1]?.let { queue.addLast(it) }
        }

        override fun isEmpty(): Boolean = queue.isEmpty()
        override fun front(): VadSegment = queue.first()
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
        // start = 0 keeps both segments pre-roll-free: this test pins the base drain contract,
        // unchanged by #196 (a segment starting at the recording's first sample has nothing
        // before it to prepend).
        val first = VadSegment(0, floatArrayOf(1f, 1f))
        val second = VadSegment(0, floatArrayOf(2f, 2f))
        val vad = FakeVad(segmentAfterWindows = mapOf(0 to first, 2 to second))
        val segmenter = SpeechSegmenter(vad, windowSize = 2)

        val emitted = mutableListOf<FloatArray>()
        segmenter.accept(ramp(6)) { emitted += it }

        assertEquals(2, emitted.size)
        assertArrayEquals(first.samples, emitted[0], 0f)
        assertArrayEquals(second.samples, emitted[1], 0f)
    }

    @Test fun `drains multiple segments queued from a single window`() {
        // Two segments become available off the same window: the drain loop must not stop at one.
        val vad = object : VadHandle {
            private val queue = ArrayDeque<VadSegment>()
            override fun acceptWaveform(samples: FloatArray) {
                queue.addLast(VadSegment(0, floatArrayOf(1f)))
                queue.addLast(VadSegment(0, floatArrayOf(2f)))
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
        val trailing = VadSegment(0, floatArrayOf(9f, 9f))
        val vad = FakeVad(flushSegment = trailing)
        val segmenter = SpeechSegmenter(vad, windowSize = 4)

        segmenter.accept(ramp(4)) {}

        val emitted = mutableListOf<FloatArray>()
        segmenter.finish { emitted += it }

        assertTrue(vad.flushed)
        assertEquals(1, emitted.size)
        assertArrayEquals(trailing.samples, emitted[0], 0f)
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
        val vad = FakeVad(flushSegment = VadSegment(0, floatArrayOf(1f)))
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

    // -- pre-roll padding (#196) --

    @Test fun `prepends the pre-roll span immediately preceding the segment start`() {
        // Segment detected at absolute sample 8; with a 2-sample pre-roll the prepend must be
        // exactly fed samples [6, 8) -- the audio right before the VAD's clipped speech start.
        val segment = VadSegment(8, floatArrayOf(100f, 101f))
        val vad = FakeVad(segmentAfterWindows = mapOf(2 to segment))
        val segmenter = SpeechSegmenter(vad, windowSize = 4, preRollSamples = 2)

        val emitted = mutableListOf<FloatArray>()
        segmenter.accept(ramp(12)) { emitted += it }

        assertEquals(1, emitted.size)
        assertArrayEquals(floatArrayOf(6f, 7f, 100f, 101f), emitted[0], 0f)
    }

    @Test fun `pre-roll is clamped at the recording start`() {
        // Speech starting at sample 1 with a 4-sample pre-roll can only reach back to sample 0:
        // a 1-sample prepend, never negative indices or fabricated leading audio.
        val segment = VadSegment(1, floatArrayOf(100f))
        val vad = FakeVad(segmentAfterWindows = mapOf(0 to segment))
        val segmenter = SpeechSegmenter(vad, windowSize = 4, preRollSamples = 4)

        val emitted = mutableListOf<FloatArray>()
        segmenter.accept(ramp(4)) { emitted += it }

        assertEquals(1, emitted.size)
        assertArrayEquals(floatArrayOf(0f, 100f), emitted[0], 0f)
    }

    @Test fun `pre-roll is clamped at the previous segment's end so no audio is emitted twice`() {
        // Segment A covers [4, 8); segment B starts at 9 with a 4-sample pre-roll that would
        // naively reach back to 5 -- inside A. The prepend must start at A's end (8) instead:
        // a fast speech resumption never gets the boundary samples decoded in both segments.
        val a = VadSegment(4, floatArrayOf(200f, 201f, 202f, 203f))
        val b = VadSegment(9, floatArrayOf(300f))
        val vad = FakeVad(segmentAfterWindows = mapOf(1 to a, 3 to b))
        val segmenter = SpeechSegmenter(vad, windowSize = 4, preRollSamples = 4)

        val emitted = mutableListOf<FloatArray>()
        segmenter.accept(ramp(16)) { emitted += it }

        assertEquals(2, emitted.size)
        assertArrayEquals(floatArrayOf(0f, 1f, 2f, 3f, 200f, 201f, 202f, 203f), emitted[0], 0f)
        assertArrayEquals(floatArrayOf(8f, 300f), emitted[1], 0f)
    }

    @Test fun `pre-roll history is built correctly across multi-chunk accepts`() {
        // Chunks of 6 against a window size of 4 exercise the pending-remainder path: the
        // pre-roll span [7, 10) crosses both a chunk boundary and a window boundary, so this
        // fails if history were per-chunk or per-window instead of a continuous absolute index.
        val segment = VadSegment(10, floatArrayOf(100f))
        val vad = FakeVad(segmentAfterWindows = mapOf(2 to segment))
        val segmenter = SpeechSegmenter(vad, windowSize = 4, preRollSamples = 3)

        val emitted = mutableListOf<FloatArray>()
        segmenter.accept(ramp(6)) { emitted += it }
        segmenter.accept(ramp(6, start = 6)) { emitted += it }

        assertEquals(1, emitted.size)
        assertArrayEquals(floatArrayOf(7f, 8f, 9f, 100f), emitted[0], 0f)
    }

    @Test fun `a pre-roll reaching into evicted history is truncated, never a crash`() {
        // With capacity 4 the first two windows are evicted by the time the segment lands, so
        // only [8, 10) of the requested [2, 10) span still exists. Shouldn't happen with the
        // production capacity (sized past MAX_SPEECH_DURATION_SECONDS), but the guard must
        // degrade to a shorter prepend rather than throwing mid-dictation.
        val segment = VadSegment(10, floatArrayOf(100f))
        val vad = FakeVad(segmentAfterWindows = mapOf(2 to segment))
        val segmenter = SpeechSegmenter(
            vad, windowSize = 4, preRollSamples = 8, historyCapacitySamples = 4,
        )

        val emitted = mutableListOf<FloatArray>()
        segmenter.accept(ramp(12)) { emitted += it }

        assertEquals(1, emitted.size)
        assertArrayEquals(floatArrayOf(8f, 9f, 100f), emitted[0], 0f)
    }

    @Test fun `finish's zero-padded tail window is real history for a flush segment's pre-roll`() {
        // accept(6) leaves [4, 5] pending; finish pushes [4, 5, 0, 0], so absolute samples
        // [6, 8) are the padding zeros -- and a flush segment starting at 8 must see exactly
        // those zeros as its pre-roll, because that's what the VAD was actually fed.
        val vad = FakeVad(flushSegment = VadSegment(8, floatArrayOf(100f)))
        val segmenter = SpeechSegmenter(vad, windowSize = 4, preRollSamples = 2)

        val emitted = mutableListOf<FloatArray>()
        segmenter.accept(ramp(6)) { emitted += it }
        segmenter.finish { emitted += it }

        assertEquals(1, emitted.size)
        assertArrayEquals(floatArrayOf(0f, 0f, 100f), emitted[0], 0f)
    }

    @Test fun `production pre-roll and history constants match their documented sizing`() {
        // 250ms at 16kHz; see PRE_ROLL_MS's kdoc for why 250 (2026-08-25 audit range 200-300ms).
        assertEquals(4000, SpeechSegmenter.PRE_ROLL_SAMPLES)
        // History must out-span the longest possible segment (15s) plus its closing silence.
        assertTrue(
            SpeechSegmenter.HISTORY_CAPACITY_SAMPLES >=
                SherpaVadHandle.MAX_SPEECH_DURATION_SECONDS.toInt() * 16_000 +
                SpeechSegmenter.PRE_ROLL_SAMPLES,
        )
    }
}
