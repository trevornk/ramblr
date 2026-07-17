package com.trevornk.ramblr

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.util.Log
import java.io.File

/**
 * Owns a single recording's AAC-LC [MediaCodec] encoder and the [MediaMuxer] that finalizes its
 * output into a standalone `.m4a` file (#109), the compressed-upload counterpart to #108's
 * [SilenceAutoStopSession]: one instance is created per recording in
 * [WhisperAccessibilityService.startRecording] when [CompressedUploadToggle] is on, fed via
 * [RecordingEngine]'s `onChunk` tee, and released exactly once when the recording ends --
 * mirroring the same "one session per recording, released on every exit path" discipline
 * [SilenceAutoStopSession] already follows.
 *
 * Unlike #108's VAD, AAC-LC hardware/software encoding is a built-in Android platform codec --
 * no model download or extra precondition needed, so this session can be created unconditionally
 * whenever the toggle is on.
 *
 * The constructor mirrors [RecordingEngine]'s own PCM temp-file convention: [outputFile] is a
 * fresh `File.createTempFile(..., ".m4a", cacheDir)` inside the caller-provided `cacheDir`, owned
 * by this session until [finish] hands it off or [release] deletes it as an unfinished/orphaned
 * partial encode.
 *
 * MediaCodec/MediaMuxer handshake this class enforces (the most common source of bugs in this
 * kind of integration): [MediaMuxer.addTrack] only happens after the encoder's first
 * [MediaCodec.INFO_OUTPUT_FORMAT_CHANGED] output, [MediaMuxer.start] only happens immediately
 * after that `addTrack`, and no [MediaMuxer.writeSampleData] call happens before `start()`. Uses
 * the synchronous dequeue/queue buffer-queue API (not the async callback API) since encoding here
 * always happens on the same non-UI reader thread that already calls `onChunk`, not on a thread
 * where blocking briefly on a `dequeueInputBuffer`/`dequeueOutputBuffer` timeout is a concern.
 *
 * Not thread-safe on its own -- callers must only ever call [onChunk]/[finish]/[release] from the
 * single reader thread [RecordingEngine.start]'s `onChunk` tee already runs on, matching how
 * [SilenceAutoStopSession] documents the same assumption for the same reader thread.
 */
class AacEncoderSession(cacheDir: File) {
    companion object {
        private const val TAG = "AacEncoderSession"
        private const val MIME_TYPE = MediaFormat.MIMETYPE_AUDIO_AAC
        private const val SAMPLE_RATE = RecordingEngine.SAMPLE_RATE
        private const val CHANNEL_COUNT = 1
        private const val BIT_RATE = 64_000
        private const val BYTES_PER_SAMPLE = 2

        // Short, non-blocking-in-practice timeouts for the synchronous dequeue calls: long enough
        // that a momentarily-busy encoder doesn't spuriously starve, short enough that a genuinely
        // stuck encoder never stalls the shared reader thread for long.
        private const val DEQUEUE_TIMEOUT_US = 10_000L

        // Safety cap on the end-of-stream drain loop in [finish] so a codec that never emits its
        // EOS-flagged output buffer (broken driver, etc.) can't spin this thread forever instead
        // of eventually giving up and reporting failure.
        private const val MAX_EOS_DRAIN_ITERATIONS = 1000
    }

    /** Fresh `.m4a` temp file this session encodes into, in the same `cacheDir` convention
     *  [RecordingEngine] uses for its own PCM temp file. Owned by this session: deleted by
     *  [release] unless [finish] already completed successfully. */
    val outputFile: File = File.createTempFile("rec_", ".m4a", cacheDir)

    private val codec: MediaCodec = MediaCodec.createEncoderByType(MIME_TYPE)
    private val muxer: MediaMuxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

    init {
        // Letting configure()/start() throw out of the constructor (rather than swallowing here)
        // mirrors SilenceAutoStopSession's init block: the caller (WhisperAccessibilityService)
        // already wraps session construction in a try/catch and treats a thrown constructor as
        // "feature unavailable this recording", not a reason to fail the recording itself.
        val format = MediaFormat.createAudioFormat(MIME_TYPE, SAMPLE_RATE, CHANNEL_COUNT).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, BIT_RATE)
        }
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.start()
    }

    private var audioTrackIndex = -1
    private var muxerStarted = false
    private var wroteAnySamples = false
    private var bytesFed = 0L

    /** True once a runtime (post-construction) error makes this session unusable; further
     *  [onChunk] calls become no-ops and [finish] reports failure rather than risking a corrupt
     *  or partial `.m4a`. */
    private var broken = false

    @Volatile private var released = false
    private var finishSucceeded = false

    /**
     * Feeds one raw PCM16 chunk (as delivered by [RecordingEngine]'s `onChunk` tee) into the
     * encoder's input buffers, splitting it across as many `dequeueInputBuffer`/
     * `queueInputBuffer` calls as the codec's own input buffer capacity requires, then drains
     * whatever output the encoder has produced so far into the muxer. Never throws: any
     * unexpected [MediaCodec]/[MediaMuxer] failure marks this session [broken] and is logged,
     * rather than propagating into [RecordingEngine]'s onChunk tee (which does catch/log as a
     * safety net, but this class is designed not to rely on that net for the common case).
     */
    fun onChunk(buf: ByteArray, len: Int) {
        if (released || broken || len <= 0) return
        try {
            var offset = 0
            while (offset < len) {
                val inputIndex = codec.dequeueInputBuffer(DEQUEUE_TIMEOUT_US)
                if (inputIndex < 0) {
                    // Backpressure with no input buffer available even after a short wait: drop
                    // the remainder of this chunk rather than block the shared reader thread
                    // indefinitely. A rare dropped/delayed chunk under backpressure is an
                    // acceptable degradation for this opt-in feature (see class kdoc).
                    Log.w(TAG, "dequeueInputBuffer starved, dropping ${len - offset} bytes")
                    break
                }
                val inputBuffer = codec.getInputBuffer(inputIndex) ?: break
                val chunkLen = minOf(len - offset, inputBuffer.remaining())
                inputBuffer.put(buf, offset, chunkLen)
                codec.queueInputBuffer(inputIndex, 0, chunkLen, presentationTimeUsFor(bytesFed), 0)
                bytesFed += chunkLen
                offset += chunkLen
            }
            drainEncoder(endOfStream = false)
        } catch (e: Exception) {
            Log.e(TAG, "onChunk failed, disabling further encoding for this recording", e)
            broken = true
        }
    }

    /**
     * Signals end-of-stream to the encoder, drains all remaining output into the muxer, then
     * stops it -- the exact MediaCodec+MediaMuxer handshake order from the class kdoc. Always
     * calls [release] before returning (idempotent, so a caller that also calls [release]
     * separately afterwards is a safe no-op). Returns [outputFile] only when the encode actually
     * produced usable muxed audio (EOS was queued, the muxer was started via a real
     * `INFO_OUTPUT_FORMAT_CHANGED`, at least one sample was written, and the resulting file is
     * non-empty); returns null and deletes the partial file otherwise (e.g. a zero-byte
     * recording, or any failure along the way).
     */
    fun finish(): File? {
        if (released) return null
        if (broken) {
            release()
            return null
        }
        try {
            var eosQueued = false
            var attempts = 0
            while (!eosQueued && attempts < MAX_EOS_DRAIN_ITERATIONS) {
                val inputIndex = codec.dequeueInputBuffer(DEQUEUE_TIMEOUT_US)
                if (inputIndex >= 0) {
                    codec.queueInputBuffer(
                        inputIndex, 0, 0, presentationTimeUsFor(bytesFed), MediaCodec.BUFFER_FLAG_END_OF_STREAM
                    )
                    eosQueued = true
                } else {
                    attempts++
                }
            }
            if (!eosQueued) {
                Log.e(TAG, "Could not queue end-of-stream buffer, giving up on this encode")
            } else {
                drainEncoder(endOfStream = true)
            }
            // finishSucceeded must be computed BEFORE muxerStarted is cleared below -- it
            // reflects whether the muxer was actually started and received samples, not whether
            // stop() happened to succeed synchronously here.
            val startedWithSamples = muxerStarted && wroteAnySamples
            if (muxerStarted) {
                try {
                    muxer.stop()
                } catch (e: Exception) {
                    Log.e(TAG, "muxer.stop() failed", e)
                }
                // Stopped exactly once here regardless of success/failure above -- release()'s
                // own `if (muxerStarted) muxer.stop()` must never run a second stop() on a muxer
                // this method already stopped (Android throws IllegalStateException on a
                // double-stop). release() still unconditionally calls muxer.release() afterwards.
                muxerStarted = false
            }
            finishSucceeded = eosQueued && startedWithSamples && outputFile.length() > 0L
        } catch (e: Exception) {
            Log.e(TAG, "finish() failed", e)
            finishSucceeded = false
        } finally {
            release()
        }
        return if (finishSucceeded) outputFile else null
    }

    /** Drains all currently-available encoder output into the muxer. When [endOfStream] is
     *  false (the [onChunk] case), returns as soon as the encoder has no more output ready
     *  ([MediaCodec.INFO_TRY_AGAIN_LATER]) -- non-blocking in practice. When true (the [finish]
     *  case), keeps polling (bounded by [MAX_EOS_DRAIN_ITERATIONS]) until the EOS-flagged output
     *  buffer arrives, since that buffer is guaranteed to eventually follow a queued EOS input. */
    private fun drainEncoder(endOfStream: Boolean) {
        val bufferInfo = MediaCodec.BufferInfo()
        var iterations = 0
        while (true) {
            val outputIndex = codec.dequeueOutputBuffer(bufferInfo, DEQUEUE_TIMEOUT_US)
            when {
                outputIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    if (!endOfStream) return
                    iterations++
                    if (iterations >= MAX_EOS_DRAIN_ITERATIONS) {
                        Log.e(TAG, "Timed out waiting for end-of-stream output buffer")
                        return
                    }
                }
                outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    if (muxerStarted) {
                        Log.w(TAG, "Unexpected second INFO_OUTPUT_FORMAT_CHANGED, ignoring")
                    } else {
                        audioTrackIndex = muxer.addTrack(codec.outputFormat)
                        muxer.start()
                        muxerStarted = true
                    }
                }
                outputIndex >= 0 -> {
                    val outputBuffer = codec.getOutputBuffer(outputIndex)
                    val isCodecConfig = (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0
                    if (outputBuffer != null && bufferInfo.size > 0 && !isCodecConfig && muxerStarted) {
                        outputBuffer.position(bufferInfo.offset)
                        outputBuffer.limit(bufferInfo.offset + bufferInfo.size)
                        muxer.writeSampleData(audioTrackIndex, outputBuffer, bufferInfo)
                        wroteAnySamples = true
                    }
                    codec.releaseOutputBuffer(outputIndex, false)
                    if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        return
                    }
                }
                // Other negative info codes (e.g. INFO_OUTPUT_BUFFERS_CHANGED on old API levels)
                // need no handling here -- loop again.
            }
        }
    }

    private fun presentationTimeUsFor(bytes: Long): Long =
        bytes * 1_000_000L / (SAMPLE_RATE.toLong() * CHANNEL_COUNT * BYTES_PER_SAMPLE)

    /**
     * Releases the [MediaCodec] and [MediaMuxer]. Safe to call more than once (e.g. from both
     * [finish]'s own cleanup and an explicit belt-and-suspenders call at a failed-start/onDestroy
     * exit path) -- only the first call does real work, matching [SilenceAutoStopSession.release]
     * exactly. Deletes [outputFile] unless [finish] already completed successfully, so a
     * cancelled or errored recording never leaves a corrupt/orphaned `.m4a` behind.
     */
    fun release() {
        if (released) return
        released = true
        try {
            codec.stop()
        } catch (e: Exception) {
            Log.e(TAG, "codec.stop() failed", e)
        }
        try {
            codec.release()
        } catch (e: Exception) {
            Log.e(TAG, "codec.release() failed", e)
        }
        try {
            if (muxerStarted) muxer.stop()
        } catch (e: Exception) {
            Log.e(TAG, "muxer.stop() failed during release()", e)
        }
        try {
            muxer.release()
        } catch (e: Exception) {
            Log.e(TAG, "muxer.release() failed", e)
        }
        if (!finishSucceeded) {
            outputFile.delete()
        }
    }
}
