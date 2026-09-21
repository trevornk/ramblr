package com.trevornk.ramblr

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.DataInputStream
import java.io.File

/** Exercises the production file-backed SherpaVadHandle with real speech followed by silence. */
@RunWith(AndroidJUnit4::class)
class NativeProbeVadTest {
    @Test
    fun emitsSpeechSegmentFrom512SampleFrames() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val model = ModelDownloader.vadModelFile(ctx, SILERO_VAD_MODEL)
            ?: throw AssertionError("provisioning did not install VAD model")
        val wav = File(ctx.filesDir, "bench_models/$ASR_ARCHIVE/test_wavs").listFiles { f ->
            f.isFile && f.extension.equals("wav", ignoreCase = true)
        }?.sortedBy { it.name }?.firstOrNull()
            ?: throw AssertionError("provisioning did not install ASR speech fixture")

        val samples = readWavMono16(wav)
        val handle = SherpaVadHandle.create(model)
            ?: throw AssertionError("SherpaVadHandle.create returned null")
        val segments = JSONArray()
        handle.use { vad ->
            feed512(vad, samples)
            repeat(SILENCE_FRAMES) { vad.acceptWaveform(FloatArray(FRAME_SIZE)) }
            vad.flush()
            while (!vad.isEmpty()) {
                val segment = vad.front()
                segments.put(JSONObject().put("start", segment.start).put("samples", segment.samples.size))
                vad.pop()
            }
        }
        assertTrue("VAD emitted no speech segment for ${wav.name}", segments.length() > 0)
        assertTrue("VAD emitted only empty segments", (0 until segments.length()).any { segments.getJSONObject(it).getInt("samples") > 0 })
        File(ctx.filesDir, RESULTS_FILE).writeText(
            JSONObject().put("model", model.name).put("modelSha256", ModelDownloader.sha256(model))
                .put("wav", wav.name).put("frameSize", FRAME_SIZE).put("silenceFrames", SILENCE_FRAMES)
                .put("segments", segments).toString(2),
        )
        Log.i(TAG, "VAD_OK modelSha256=${ModelDownloader.sha256(model)} wav=${wav.name} segments=${segments}")
    }

    private fun feed512(vad: SherpaVadHandle, samples: FloatArray) {
        var offset = 0
        while (offset < samples.size) {
            val frame = FloatArray(FRAME_SIZE)
            val count = minOf(FRAME_SIZE, samples.size - offset)
            samples.copyInto(frame, 0, offset, offset + count)
            vad.acceptWaveform(frame)
            offset += count
        }
    }

    private fun readWavMono16(file: File): FloatArray = DataInputStream(file.inputStream().buffered()).use { input ->
        fun tag() = String(ByteArray(4).also { input.readFully(it) }, Charsets.US_ASCII)
        fun leInt() = input.read() or (input.read() shl 8) or (input.read() shl 16) or (input.read() shl 24)
        fun leShort() = input.read() or (input.read() shl 8)
        require(tag() == "RIFF"); leInt(); require(tag() == "WAVE")
        var channels = -1
        var bits = -1
        while (true) {
            val chunk = tag()
            val size = leInt()
            when (chunk) {
                "fmt " -> {
                    require(leShort() == 1) { "${file.name}: non-PCM" }
                    channels = leShort(); leInt(); leInt(); leShort(); bits = leShort()
                    input.skipBytes(size - 16)
                }
                "data" -> {
                    require(channels == 1 && bits == 16) { "${file.name}: expected mono s16" }
                    val bytes = ByteArray(size); input.readFully(bytes)
                    return@use FloatArray(size / 2) { i ->
                        (((bytes[2 * i + 1].toInt() shl 8) or (bytes[2 * i].toInt() and 0xff)).toShort().toInt() / 32768f)
                    }
                }
                else -> input.skipBytes(size + (size and 1))
            }
        }
        error("unreachable")
    }

    private companion object {
        const val TAG = "NativeProbeVAD"
        const val RESULTS_FILE = "native_probe_vad.json"
        const val ASR_ARCHIVE = "sherpa-onnx-nemo-parakeet_tdt_ctc_110m-en-36000-int8"
        const val FRAME_SIZE = 512
        const val SILENCE_FRAMES = 64
    }
}
