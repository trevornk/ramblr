package com.trevornk.ramblr

import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import com.k2fsa.sherpa.onnx.getVadModelConfig
import java.io.File

/**
 * Adapts the vendored native [Vad] binding to [VadHandle] so [SpeechSegmenter] can drive it
 * without depending on the native library (#132).
 *
 * Configured from the same Silero template [SilenceAutoStopSession] uses, with one deliberate
 * difference: [SileroVadModelConfig.maxSpeechDuration] is what actually bounds peak decode memory
 * here. It forces a segment boundary after that many seconds of continuous speech even when the
 * speaker never pauses, so a monologue can't reconstruct the very unbounded-decode problem this
 * change exists to fix.
 */
class SherpaVadHandle private constructor(private val vad: Vad) : VadHandle, AutoCloseable {

    override fun acceptWaveform(samples: FloatArray) = vad.acceptWaveform(samples)

    override fun isEmpty(): Boolean = vad.empty()

    // Maps the native SpeechSegment 1:1 -- the start index was always available natively; the
    // interface just used to discard it before the pre-roll fix (#196) needed it.
    override fun front(): VadSegment = vad.front().let { VadSegment(it.start, it.samples) }

    override fun pop() = vad.pop()

    override fun flush() = vad.flush()

    override fun close() = vad.release()

    companion object {
        /** Hard ceiling on a single segment's length, and therefore on a single decode's memory. */
        const val MAX_SPEECH_DURATION_SECONDS = 15.0F

        /**
         * Builds a handle from the on-disk Silero model, or returns null if the model file is
         * missing or the native constructor fails -- callers fall back to the unsegmented path
         * rather than failing the dictation.
         */
        fun create(modelFile: File): SherpaVadHandle? {
            if (!modelFile.exists()) return null
            val template = getVadModelConfig(0) ?: return null
            val config: VadModelConfig = template.copy(
                sileroVadModelConfig = template.sileroVadModelConfig.copy(
                    model = modelFile.absolutePath,
                    maxSpeechDuration = MAX_SPEECH_DURATION_SECONDS,
                )
            )
            return try {
                // assetManager = null routes through Vad's newFromFile(config) branch, matching
                // SilenceAutoStopSession -- the model lives in filesDir, not as a bundled asset.
                SherpaVadHandle(Vad(assetManager = null, config = config))
            } catch (e: Exception) {
                null
            }
        }
    }
}
