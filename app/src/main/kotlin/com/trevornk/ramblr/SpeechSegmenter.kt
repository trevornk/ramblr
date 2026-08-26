package com.trevornk.ramblr

/**
 * One completed speech segment as drained from the VAD: [start] is the absolute sample index of
 * the segment's first sample, counted over everything fed to the VAD since construction, and
 * [samples] is the segment audio itself.
 *
 * Exists for the pre-roll fix (#196): the vendored [com.k2fsa.sherpa.onnx.Vad.front] natively
 * returns `SpeechSegment(start, samples)`, but [VadHandle.front] used to surface only the samples
 * and discard `start` -- and without the start index there is no way to know *which* recently-fed
 * samples immediately precede a segment, which is exactly what pre-roll padding needs.
 */
class VadSegment(val start: Int, val samples: FloatArray)

/**
 * The subset of [com.k2fsa.sherpa.onnx.Vad] that [SpeechSegmenter] needs, extracted as an
 * interface so the segmentation/drain logic is unit-testable with a fake -- the same way
 * [SilenceAutoStopDecision] extracts the pure auto-stop check away from the native VAD.
 *
 * Mirrors sherpa-onnx's own `vad-non-streaming-asr` calling convention: push fixed-size windows
 * with [acceptWaveform], then drain any completed speech segments with [isEmpty]/[front]/[pop].
 */
interface VadHandle {
    /** Push exactly one window of samples (see [SpeechSegmenter.windowSize]). */
    fun acceptWaveform(samples: FloatArray)

    /** True when no completed speech segment is queued. */
    fun isEmpty(): Boolean

    /** The oldest queued completed speech segment. Only valid when [isEmpty] is false. */
    fun front(): VadSegment

    /** Drop the segment returned by [front]. */
    fun pop()

    /** Force any in-progress speech to be queued as a completed segment (end of input). */
    fun flush()
}

/**
 * Splits a recording into speech segments so each one can be decoded in its own offline stream
 * (#132).
 *
 * The bug this exists for: [LocalTranscriber.transcribe] handed the entire recording to a single
 * `OfflineStream.acceptWaveform` + `decode`, so a non-streaming model materialised full-utterance
 * encoder activations for the whole take at once. A 5:52 dictation reached ~2GB RSS from ~22MB of
 * PCM and the process was OOM-killed with no text and no error surfaced. Decoding per segment
 * bounds peak memory by the longest *utterance* instead of the longest *take* --
 * `SileroVadModelConfig.maxSpeechDuration` (5s by default) caps a single segment even when the
 * speaker never pauses.
 *
 * Pre-roll padding (#196): Silero's detected speech start reliably clips the first phoneme(s) of
 * an utterance -- on-device this surfaced as dropped leading characters ("'ll just text you" for
 * "I'll just text you"). Every emitted segment is therefore prepended with up to [PRE_ROLL_MS] of
 * the audio that immediately *preceded* the VAD's detected start, taken from a rolling history of
 * samples already fed to the VAD, clamped so it never reaches before the recording's first sample
 * and never re-emits audio already handed out as part of the previous segment (a speaker resuming
 * quickly must not have the segment boundary's samples decoded twice).
 *
 * Not thread-safe: [accept] and [finish] must be called from one thread, matching the
 * single-threaded per-recording discipline [SilenceAutoStopSession] already documents.
 */
class SpeechSegmenter(
    private val vad: VadHandle,
    private val windowSize: Int = SilenceAutoStopSession.WINDOW_SIZE,
    /** Overridable only for tests; production callers always want [PRE_ROLL_SAMPLES]. */
    private val preRollSamples: Int = PRE_ROLL_SAMPLES,
    /** Overridable only for tests (to exercise the evicted-history guard without feeding 20s of
     *  audio); production callers always want [HISTORY_CAPACITY_SAMPLES]. */
    private val historyCapacitySamples: Int = HISTORY_CAPACITY_SAMPLES,
) {
    companion object {
        /**
         * How much pre-speech audio is prepended to each segment (#196). Targets Silero VAD
         * head-clipping: 200-300ms is the evidence-backed range from the 2026-08-25 audit of the
         * dropped-leading-characters failures, so the midpoint is used. The 16kHz sample-rate
         * assumption matches the rest of the pipeline ([LocalTranscriber.transcribeSegmented]'s
         * default sample rate, which is also what the recording path captures at).
         */
        const val PRE_ROLL_MS = 250
        private const val ASSUMED_SAMPLE_RATE = 16_000
        const val PRE_ROLL_SAMPLES = PRE_ROLL_MS * ASSUMED_SAMPLE_RATE / 1000

        /**
         * Rolling history capacity. A segment can be up to
         * [SherpaVadHandle.MAX_SPEECH_DURATION_SECONDS] (15s) long and is only emitted after the
         * trailing silence that closes it, so by emission time its *start* can lie well over 15s
         * behind the newest fed sample; 20s covers that plus comfortable margin for the closing
         * silence. At 16kHz that is 320k floats, ~1.25MB -- negligible next to the ~2GB
         * whole-take decode blow-up this class exists to prevent (#132), which is why holding the
         * history is an acceptable cost here at all.
         */
        const val HISTORY_CAPACITY_SAMPLES =
            (SherpaVadHandle.MAX_SPEECH_DURATION_SECONDS.toInt() + 5) * ASSUMED_SAMPLE_RATE
    }

    /** Samples left over from a chunk that wasn't an exact multiple of [windowSize], carried into
     *  the next [accept] call so every [VadHandle.acceptWaveform] call gets a full window. */
    private var pending = FloatArray(0)

    private var finished = false

    /**
     * Rolling history of windows already fed to the VAD, oldest first, holding at most (roughly)
     * [historyCapacitySamples] samples -- the source of each segment's pre-roll. Kept as the fed
     * windows themselves rather than one big ring buffer: appends stay O(1), eviction drops whole
     * windows from the front, and the only copying happens in [preRollFor], once per segment.
     * Note the zero-padded tail window [finish] pushes lands in here too -- correctly so, since
     * those zeros are real fed samples as far as the VAD's reported indices are concerned.
     */
    private val history = ArrayDeque<FloatArray>()

    /** Total samples currently held across [history]. */
    private var historySize = 0

    /** Absolute index (in samples fed to the VAD) of the oldest sample still in [history]. */
    private var historyStart = 0

    /** Absolute index one past the previous emitted segment's last sample ([VadSegment.start] +
     *  its length), so pre-roll never re-emits audio the previous segment already carried. */
    private var previousSegmentEnd = 0

    /**
     * Feeds one chunk of samples to the VAD and invokes [onSegment] for every speech segment that
     * completes as a result. Segments are handed over one at a time and are not retained here, so
     * the caller can decode and release each before the next arrives.
     */
    fun accept(samples: FloatArray, onSegment: (FloatArray) -> Unit) {
        check(!finished) { "accept() after finish()" }
        if (samples.isEmpty() && pending.isEmpty()) return

        val buffered = if (pending.isEmpty()) samples else pending + samples

        var offset = 0
        while (buffered.size - offset >= windowSize) {
            feed(buffered.copyOfRange(offset, offset + windowSize))
            offset += windowSize
            drain(onSegment)
        }

        pending = if (offset < buffered.size) buffered.copyOfRange(offset, buffered.size) else FloatArray(0)
    }

    /**
     * Ends the stream: any partial window left over is zero-padded to a full window and pushed
     * (dropping it would silently lose up to [windowSize] samples of trailing speech -- ~32ms at
     * 16kHz), then [VadHandle.flush] closes any still-open segment and the queue is drained.
     */
    fun finish(onSegment: (FloatArray) -> Unit) {
        if (finished) return
        finished = true

        if (pending.isNotEmpty()) {
            feed(pending.copyOf(windowSize))
            pending = FloatArray(0)
            drain(onSegment)
        }

        vad.flush()
        drain(onSegment)
    }

    /** Pushes one window to the VAD and records it in the pre-roll history (evicting from the
     *  front once the capacity is exceeded, so memory stays bounded on arbitrarily long takes). */
    private fun feed(window: FloatArray) {
        vad.acceptWaveform(window)
        history.addLast(window)
        historySize += window.size
        while (history.isNotEmpty() && historySize - history.first().size >= historyCapacitySamples) {
            val evicted = history.removeFirst()
            historySize -= evicted.size
            historyStart += evicted.size
        }
    }

    /**
     * The pre-roll samples for a segment starting at absolute index [segmentStart]: the fed audio
     * in `[segmentStart - preRollSamples, segmentStart)`, clamped to (a) the recording's first
     * sample, (b) the previous emitted segment's end (no duplicated audio when the speaker
     * resumes within the pre-roll span), and (c) the oldest sample still in [history]. Clamp (c)
     * is a never-crash guard: with [HISTORY_CAPACITY_SAMPLES] correctly sized it should be
     * unreachable, but a shorter prepend beats an exception mid-dictation if that reasoning ever
     * rots -- the pre-fix behavior was effectively an always-empty prepend anyway.
     */
    private fun preRollFor(segmentStart: Int): FloatArray {
        val from = maxOf(segmentStart - preRollSamples, 0, previousSegmentEnd, historyStart)
        if (from >= segmentStart) return FloatArray(0)

        val out = FloatArray(segmentStart - from)
        var absolute = historyStart
        var written = 0
        for (window in history) {
            val overlapFrom = maxOf(from, absolute)
            val overlapTo = minOf(segmentStart, absolute + window.size)
            if (overlapTo > overlapFrom) {
                val count = overlapTo - overlapFrom
                System.arraycopy(window, overlapFrom - absolute, out, written, count)
                written += count
            }
            absolute += window.size
            if (absolute >= segmentStart) break
        }
        // written == out.size whenever the range is fully inside history, which clamp (c)
        // guarantees; sized exactly, so no trimming step is needed.
        return out
    }

    private fun drain(onSegment: (FloatArray) -> Unit) {
        while (!vad.isEmpty()) {
            val segment = vad.front()
            vad.pop()
            val preRoll = preRollFor(segment.start)
            previousSegmentEnd = segment.start + segment.samples.size
            onSegment(if (preRoll.isEmpty()) segment.samples else preRoll + segment.samples)
        }
    }
}
