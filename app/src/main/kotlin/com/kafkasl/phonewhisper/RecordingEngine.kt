package com.kafkasl.phonewhisper

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
        val errorMessage: String? = null
    )

    companion object {
        private const val TAG = "RecordingEngine"
        const val SAMPLE_RATE = 16000
        const val MAX_DURATION_MS = 10 * 60 * 1000L

        // Backstop independent of the elapsed-time cap: 16kHz mono 16-bit is 32,000 bytes/sec.
        const val MAX_BYTES = 32_000L * (MAX_DURATION_MS / 1000L)
    }

    @Volatile private var readerThread: Thread? = null

    /**
     * Validates the mic and starts the reader thread synchronously. Returns false with no side
     * effects if the AudioRecord could not be acquired (mic busy, permission race, bad
     * buffer size) — the caller should surface that as user feedback ("mic busy") rather than
     * crash. [onMaxDuration] and [onFinished] run on the reader thread; callers must hop back to
     * the main thread themselves before touching UI.
     */
    fun start(onMaxDuration: () -> Unit, onFinished: (Result) -> Unit): Boolean {
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

        val pcmFile = File.createTempFile("rec_", ".pcm", cacheDir)
        try {
            audioRecord.startRecording()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start recording", e)
            pcmFile.delete()
            audioRecord.release()
            stateMachine.reset()
            return false
        }

        readerThread = thread(name = "RecordingEngine-reader") {
            var stopReason = StopReason.USER
            var errorMessage: String? = null
            val buf = ByteArray(bufSize)
            val startedAt = System.currentTimeMillis()

            PcmFileBuffer(pcmFile, MAX_BYTES).use { buffer ->
                try {
                    while (stateMachine.isRecording()) {
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
                stateMachine.tryStartTranscribing()
                val discarded = stateMachine.current() != RecordingStateMachine.State.TRANSCRIBING
                if (!discarded && stopReason == StopReason.MAX_DURATION) onMaxDuration()

                val bytes = buffer.bytesWritten
                if (discarded || bytes <= 0L) {
                    buffer.deleteFile()
                    onFinished(Result(null, bytes, discarded, stopReason, errorMessage))
                } else {
                    onFinished(Result(pcmFile, bytes, discarded, stopReason, errorMessage))
                }
            }
        }
        return true
    }

    /** Blocks until the reader thread has stopped/released the recorder, or [timeoutMs] elapses. Safe to call from onDestroy. */
    fun awaitTeardown(timeoutMs: Long = 2000) {
        readerThread?.join(timeoutMs)
    }
}
