package com.trevornk.ramblr

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

    /** The oldest queued completed speech segment's samples. Only valid when [isEmpty] is false. */
    fun front(): FloatArray

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
 * Not thread-safe: [accept] and [finish] must be called from one thread, matching the
 * single-threaded per-recording discipline [SilenceAutoStopSession] already documents.
 */
class SpeechSegmenter(
    private val vad: VadHandle,
    private val windowSize: Int = SilenceAutoStopSession.WINDOW_SIZE,
) {
    /** Samples left over from a chunk that wasn't an exact multiple of [windowSize], carried into
     *  the next [accept] call so every [VadHandle.acceptWaveform] call gets a full window. */
    private var pending = FloatArray(0)

    private var finished = false

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
            vad.acceptWaveform(buffered.copyOfRange(offset, offset + windowSize))
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
            vad.acceptWaveform(pending.copyOf(windowSize))
            pending = FloatArray(0)
            drain(onSegment)
        }

        vad.flush()
        drain(onSegment)
    }

    private fun drain(onSegment: (FloatArray) -> Unit) {
        while (!vad.isEmpty()) {
            val segment = vad.front()
            vad.pop()
            onSegment(segment)
        }
    }
}
