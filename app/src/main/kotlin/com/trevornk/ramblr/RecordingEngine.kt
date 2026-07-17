package com.trevornk.ramblr

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import java.io.File
import kotlin.concurrent.thread

/**
 * Owns the AudioRecord lifecycle end-to-end on a single reader thread: validate -> record ->
 * drain -> stop -> release. Callers (the UI thread, or `onDestroy`) only ever flip [stateMachine]
 * away from RECORDING; the reader thread is the sole owner of the `AudioRecord` instance and the
 * only thread that stops or releases it, so a released recorder is never read from cross-thread.
 *
 * PCM is streamed straight to a temp file in `cacheDir` instead of an in-memory buffer, so heap
 * stays flat for the duration of a recording, and a max recording duration auto-stops into
 * TRANSCRIBING with user feedback instead of growing unbounded.
 */
class RecordingEngine(
    private val cacheDir: File,
    private val stateMachine: RecordingStateMachine
) {
    enum class StopReason { USER, MAX_DURATION, ERROR }

    /**
     * [pcmFile] is non-null only when there is audio to hand off to transcription; the caller
     * owns deleting it once read. [discarded] is true when the recording was torn down without
     * ever reaching TRANSCRIBING (e.g. the service was destroyed mid-recording) — callers must
     * not transcribe in that case.
     */
    data class Result(
        val pcmFile: File?,
        val bytesRecorded: Long,
        val discarded: Boolean,
        val stopReason: StopReason,
        val errorMessage: String? = null,
        /**
         * The finalized `.m4a` produced by an [AacEncoderSession] tee'd through this recording's
         * `onChunk` (#109), when the caller opted into compressed cloud uploads and the encode
         * actually produced usable audio. Always null when that opt-in is off (see
         * [CompressedUploadToggle]) -- [RecordingEngine] itself has no knowledge of compression,
         * this field only exists so the caller (which does own the encoder session, same as it
         * owns [SilenceAutoStopSession]) can attach the finished file onto the [Result] it
         * receives via `onFinished`, keeping the compressed file's lifetime attached 1:1 to
         * [pcmFile]'s from that point on -- both travel together and are deleted together by
         * every downstream call site, mirroring [pcmFile]'s own discard/delete discipline.
         */
        val compressedFile: File? = null
    )

    companion object {
        private const val TAG = "RecordingEngine"
        const val SAMPLE_RATE = 16000
        const val MAX_DURATION_MS = 10 * 60 * 1000L

        // Backstop independent of the elapsed-time cap: 16kHz mono 16-bit is 32,000 bytes/sec.
        const val MAX_BYTES = 32_000L * (MAX_DURATION_MS / 1000L)

        /** Monotonic id of the current recording session, shared across ALL RecordingEngine
         *  instances (#88 item 6 / H2). The service creates a fresh engine per recording, so an
         *  *instance*-private counter never changed across instances: an old reader stalled in
         *  onChunk/read across a stop->cancel->new-tap sequence saw its own generation as still
         *  current forever, then observed the shared state machine back in RECORDING (the NEW
         *  session) and resumed feeding audio concurrently with the new reader (mic contention,
         *  and it could CAS the new session's RECORDING->TRANSCRIBING, killing a live dictation).
         *  A process-wide counter makes every new recording's start() supersede all prior readers,
         *  so a resurrected reader sees generation != myGeneration and exits at its next check. */
        private val sessionGeneration = java.util.concurrent.atomic.AtomicInteger(0)
    }

    @Volatile private var readerThread: Thread? = null

    /**
     * Validates the mic and starts the reader thread synchronously. Returns false with no side
     * effects if the AudioRecord could not be acquired (mic busy, permission race, bad
     * buffer size) — the caller should surface that as user feedback ("mic busy") rather than
     * crash. [onFinished] runs on the reader thread; callers must hop back to the main thread
     * themselves before touching UI (a MAX_DURATION stop is signalled via [Result.stopReason], not
     * a separate callback). [onChunk] is an optional tee (#29): called
     * with each freshly-read buffer and its valid length right after every successful
     * `AudioRecord.read`, before it's written to [PcmFileBuffer] — e.g. to feed live audio to a
     * streaming recognizer. Defaults to a no-op so callers that don't need it (the common case)
     * are unaffected. A throwing [onChunk] is caught and logged rather than tearing down the
     * recording, since a bug in an optional preview feature must never break base recording.
     */
    fun start(
        onFinished: (Result) -> Unit,
        onChunk: (ByteArray, Int) -> Unit = { _, _ -> }
    ): Boolean {
        val bufSize = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        if (bufSize <= 0) {
            Log.e(TAG, "getMinBufferSize failed: $bufSize")
            return false
        }

        val audioRecord = try {
            AudioRecord(
                MediaRecorder.AudioSource.MIC, SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufSize
            )
        } catch (_: SecurityException) {
            return false
        }

        if (audioRecord.state != AudioRecord.STATE_INITIALIZED) {
            audioRecord.release()
            Log.e(TAG, "AudioRecord failed to initialize (mic busy?)")
            return false
        }

        if (!stateMachine.tryStartRecording()) {
            audioRecord.release()
            return false
        }

        // Temp-file creation can fail under cache-dir/disk pressure (plausible on a phone
        // carrying multi-GB models). We already hold the RECORDING claim and the mic here, so a
        // throw must unwind both -- otherwise the state machine is wedged in RECORDING forever
        // and the mic handle leaks (#85).
        val pcmFile = try {
            File.createTempFile("rec_", ".pcm", cacheDir)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create PCM temp file", e)
            audioRecord.release()
            stateMachine.reset()
            return false
        }
        try {
            audioRecord.startRecording()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start recording", e)
            pcmFile.delete()
            audioRecord.release()
            stateMachine.reset()
            return false
        }

        val myGeneration = sessionGeneration.incrementAndGet()
        readerThread = thread(name = "RecordingEngine-reader") {
            var stopReason = StopReason.USER
            var errorMessage: String? = null
            val buf = ByteArray(bufSize)
            val startedAt = System.currentTimeMillis()

            // Opening the file-backed buffer can also fail under disk pressure. It must not
            // throw out of this bare thread (process death) with the recorder unreleased --
            // funnel the failure through the same ERROR result path as a mid-recording
            // exception (#85).
            val fileBuffer = try {
                PcmFileBuffer(pcmFile, MAX_BYTES)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to open PCM buffer", e)
                try { audioRecord.stop() } catch (_: Exception) {}
                audioRecord.release()
                val superseded = sessionGeneration.get() != myGeneration
                if (RecorderHandoff.shouldClaimTranscribing(superseded)) stateMachine.tryStartTranscribing()
                val discarded = RecorderHandoff.discarded(superseded, stateMachine.current())
                pcmFile.delete()
                onFinished(Result(null, 0L, discarded, StopReason.ERROR, e.message))
                return@thread
            }

            val result = fileBuffer.use { buffer ->
                try {
                    while (stateMachine.isRecording() && sessionGeneration.get() == myGeneration) {
                        if (RecordingDurationCap.exceeded(startedAt, System.currentTimeMillis(), MAX_DURATION_MS)) {
                            stopReason = StopReason.MAX_DURATION
                            break
                        }
                        val n = audioRecord.read(buf, 0, buf.size)
                        if (n <= 0) {
                            if (n < 0) {
                                errorMessage = "AudioRecord.read returned $n"
                                stopReason = StopReason.ERROR
                            }
                            break
                        }
                        try { onChunk(buf, n) } catch (e: Exception) { Log.e(TAG, "onChunk failed", e) }
                        if (!buffer.write(buf, 0, n)) {
                            stopReason = StopReason.MAX_DURATION
                            break
                        }
                    }
                } catch (e: Exception) {
                    errorMessage = e.message
                    stopReason = StopReason.ERROR
                } finally {
                    try { audioRecord.stop() } catch (_: Exception) {}
                    audioRecord.release()
                }

                // Claim the RECORDING -> TRANSCRIBING transition ourselves if nobody else has
                // (max-duration/error paths, where we're the one detecting the stop condition).
                // This is a no-op if the caller already made this transition (user-initiated
                // stop) — checking the *current* state afterwards, rather than this call's
                // return value, is what lets both callers agree on the outcome instead of
                // whoever loses the race being treated as "discarded".
                //
                // BUT a superseded reader (a newer recording has bumped the shared generation
                // counter past ours) must NOT claim: that transition now belongs to the NEW
                // session's RECORDING, and stealing it would discard the user's in-progress second
                // dictation (H2). A superseded reader always discards its own audio instead.
                val superseded = sessionGeneration.get() != myGeneration
                if (RecorderHandoff.shouldClaimTranscribing(superseded)) stateMachine.tryStartTranscribing()
                val discarded = RecorderHandoff.discarded(superseded, stateMachine.current())
                val bytes = buffer.bytesWritten
                Result(
                    pcmFile = if (!discarded && bytes > 0L) pcmFile else null,
                    bytesRecorded = bytes,
                    discarded = discarded,
                    stopReason = stopReason,
                    errorMessage = errorMessage
                )
            }

            // The file-backed buffer must be closed before the handoff reads the file; otherwise
            // the reader thread can synchronously re-open the PCM file while its FileOutputStream
            // is still open.
            if (result.pcmFile == null) pcmFile.delete()
            onFinished(result)
        }
        return true
    }

    /** Blocks until the reader thread has stopped/released the recorder, or [timeoutMs] elapses. Safe to call from onDestroy. */
    fun awaitTeardown(timeoutMs: Long = 2000) {
        readerThread?.join(timeoutMs)
    }
}
