package com.trevornk.ramblr

import android.util.Log
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import com.k2fsa.sherpa.onnx.getVadModelConfig
import java.io.File

/**
 * Owns a single recording's [Vad] instance and the trailing-silence bookkeeping for silence-based
 * auto-stop (#108, mode 1). One instance is created per recording session in
 * [WhisperAccessibilityService.startRecording] when [SilenceAutoStopToggle] is on and the Silero
 * VAD model is installed, fed via [RecordingEngine]'s `onChunk` tee, and released exactly once
 * when the recording ends (auto-stop, manual stop, or error) -- mirroring the "one Vad per
 * recording, released on every exit path" discipline [RecordingEngine]'s own try/finally around
 * `AudioRecord` already follows for the mic.
 *
 * Silero's `windowSize` is 512 samples (see [getVadModelConfig]'s type-0 template); each
 * `AudioRecord.read` chunk this class receives is fed to [Vad.acceptWaveform] in 512-sample
 * slices (matching sherpa-onnx's own `SherpaOnnxVad` example calling convention -- see
 * `sherpa-onnx/android/SherpaOnnxVad`), buffering any samples left over from a chunk that isn't
 * an exact multiple of 512 until the next call. [Vad.isSpeechDetected] is checked after every
 * 512-sample slice, the same per-window cadence the example uses.
 *
 * Not thread-safe on its own -- callers must only ever call [onChunk] from the single reader
 * thread [RecordingEngine.start]'s `onChunk` tee already runs on, matching how the rest of this
 * feature is single-threaded per recording.
 */
class SilenceAutoStopSession(
    modelFile: File,
    private val thresholdMs: Long,
    private val onSilenceThresholdExceeded: () -> Unit,
    private val nowMs: () -> Long = { System.currentTimeMillis() },
) {
    companion object {
        private const val TAG = "SilenceAutoStopSession"

        /** Silero's expected samples-per-`acceptWaveform()` call (see [getVadModelConfig] type 0). */
        const val WINDOW_SIZE = 512
    }

    private val vad: Vad = run {
        val template = getVadModelConfig(0) ?: throw IllegalStateException("No VAD config template")
        val config = template.copy(
            sileroVadModelConfig = template.sileroVadModelConfig.copy(model = modelFile.absolutePath)
        )
        // assetManager = null routes through Vad's newFromFile(config) branch (verified in
        // Vad.kt's init block) since the model lives in filesDir, not as a bundled asset.
        Vad(assetManager = null, config = config)
    }

    /** Leftover samples from a chunk that wasn't an exact multiple of [WINDOW_SIZE], carried into
     *  the next [onChunk] call so every [Vad.acceptWaveform] call gets a full window. */
    private var pending = FloatArray(0)

    /** Wall-clock time speech was last detected; seeded to "now" at construction so silence from
     *  the very start of a recording (nobody has spoken yet) still counts toward the threshold --
     *  see [SilenceAutoStopDecision]'s kdoc. */
    private var lastSpeechAtMs: Long = nowMs()

    /** Guards against firing [onSilenceThresholdExceeded] more than once per recording -- a second
     *  silence window after the stop signal has already fired must be a no-op. */
    @Volatile private var triggered = false

    private var released = false

    /** Feeds one raw PCM16 chunk (as delivered by [RecordingEngine]'s `onChunk` tee) to the VAD in
     *  [WINDOW_SIZE]-sample slices, and fires [onSilenceThresholdExceeded] exactly once when
     *  trailing silence first exceeds [thresholdMs]. */
    fun onChunk(buf: ByteArray, len: Int) {
        if (released || triggered) return
        val newSamples = PcmFileBuffer.bytesToFloatArray(buf, len)
        var samples = if (pending.isEmpty()) newSamples else pending + newSamples

        var offset = 0
        while (samples.size - offset >= WINDOW_SIZE) {
            val window = samples.copyOfRange(offset, offset + WINDOW_SIZE)
            vad.acceptWaveform(window)
            if (vad.isSpeechDetected()) {
                lastSpeechAtMs = nowMs()
            }
            offset += WINDOW_SIZE

            if (SilenceAutoStopDecision.shouldTrigger(lastSpeechAtMs, nowMs(), thresholdMs)) {
                triggered = true
                onSilenceThresholdExceeded()
                return
            }
        }
        pending = if (offset < samples.size) samples.copyOfRange(offset, samples.size) else FloatArray(0)
    }

    /** Releases the native VAD instance. Safe to call more than once (e.g. from both an explicit
     *  teardown call and a finally block) -- only the first call does real work. */
    fun release() {
        if (released) return
        released = true
        try {
            vad.release()
        } catch (e: Exception) {
            Log.e(TAG, "Vad.release() failed", e)
        }
    }
}
