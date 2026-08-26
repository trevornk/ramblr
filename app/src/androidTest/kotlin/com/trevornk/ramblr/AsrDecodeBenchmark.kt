package com.trevornk.ramblr

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.DataInputStream
import java.io.File
import java.io.IOException

/**
 * On-device local ASR decode benchmark (#198) -- the evidence gate that decides whether a big
 * catalog entry (concretely: Parakeet Unified 0.6B, #197) ever earns `recommended` status. Unit
 * tests can pin catalog metadata, but only a real decode on real hardware answers "is a 0.6B
 * model's latency acceptable on this phone", so this is an instrumented test, deliberately the
 * only one in the repo (the androidTest source set and runner config exist for it alone).
 *
 * NOT part of any build/check gate, and never run in CI -- it needs model files pushed to the
 * device first. Invocation shape (after `adb push`ing model dirs, each with its `test_wavs/`,
 * into the app's `filesDir/bench_models/` via run-as):
 *
 * ```
 * adb shell am instrument -w \
 *   -e class com.trevornk.ramblr.AsrDecodeBenchmark \
 *   [-e benchDir bench_models] [-e threads 2] [-e repeats 3] \
 *   com.trevornk.ramblr.test/androidx.test.runner.AndroidJUnitRunner
 * adb shell run-as com.trevornk.ramblr cat files/bench_results.json
 * ```
 *
 * Isolation contract: models are read from `filesDir/<benchDir>` ("bench_models" by default), a
 * SIBLING of the real "models" directory, and SharedPreferences are never touched -- running the
 * benchmark must not disturb the user's installed models or selected-model configuration in any
 * way. Configs are built with [LocalTranscriber.detectModelConfig] (public static, prefs-free by
 * design) and the [com.k2fsa.sherpa.onnx.OfflineRecognizer] is constructed directly rather than
 * through [LocalTranscriber.create], which reads the thread-count preference.
 *
 * The native lib needs no special handling: instrumentation runs in the app's own process, and
 * OfflineRecognizer's companion init block does `System.loadLibrary("sherpa-onnx-jni")` on first
 * touch -- the exact path production takes (verified against the vendored OfflineRecognizer.kt).
 */
@RunWith(AndroidJUnit4::class)
class AsrDecodeBenchmark {

    private companion object {
        const val TAG = "AsrBench"
        const val DEFAULT_BENCH_DIR = "bench_models"
        const val DEFAULT_THREADS = 2
        const val DEFAULT_REPEATS = 3
        const val RESULTS_FILE = "bench_results.json"
    }

    @Test
    fun benchmarkDecode() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val args = InstrumentationRegistry.getArguments()
        val ctx = instrumentation.targetContext

        val benchDirName = args.getString("benchDir") ?: DEFAULT_BENCH_DIR
        val threads = args.getString("threads")?.toIntOrNull() ?: DEFAULT_THREADS
        val repeats = args.getString("repeats")?.toIntOrNull() ?: DEFAULT_REPEATS

        val benchRoot = File(ctx.filesDir, benchDirName)
        val modelDirs = benchRoot.listFiles { f -> f.isDirectory }?.sortedBy { it.name }.orEmpty()

        // assumeTrue, not a bare return or a fake-green assert: an empty device reports the run
        // as SKIPPED with this exact message, so a missing adb push can never masquerade as a
        // passing benchmark -- while also not failing a run that legitimately targets one model.
        Log.i(TAG, "benchRoot=${benchRoot.absolutePath} modelDirs=${modelDirs.map { it.name }} " +
            "threads=$threads repeats=$repeats")
        assumeTrue(
            "no model dirs under ${benchRoot.absolutePath}; adb push models (each with " +
                "test_wavs/) into filesDir/$benchDirName before running the benchmark",
            modelDirs.isNotEmpty(),
        )

        val modelResults = JSONArray()
        for (modelDir in modelDirs) {
            val decodes = benchmarkModel(modelDir, threads, repeats)
            modelResults.put(
                JSONObject()
                    .put("model", modelDir.name)
                    .put("threads", threads)
                    .put("repeats", repeats)
                    .put("decodes", decodes)
            )
        }

        val summary = JSONObject()
            .put("benchDir", benchDirName)
            .put("threads", threads)
            .put("repeats", repeats)
            .put("timestampMs", System.currentTimeMillis())
            .put("models", modelResults)

        // Overwrite on every run: the parent pulls this one file via run-as; historic runs
        // belong in the pulled copies, not accumulating inside the app's private storage.
        File(ctx.filesDir, RESULTS_FILE).writeText(summary.toString(2))
        Log.i(TAG, "wrote ${File(ctx.filesDir, RESULTS_FILE).absolutePath}")
    }

    /** Decodes every wav under [modelDir]/test_wavs (every sherpa archive ships them) [repeats]
     *  times, logging one line per decode and returning the per-decode records. */
    private fun benchmarkModel(modelDir: File, threads: Int, repeats: Int): JSONArray {
        val decodes = JSONArray()

        val config = LocalTranscriber.detectModelConfig(modelDir, threads)
        if (config == null) {
            Log.w(TAG, "model=${modelDir.name} SKIP: detectModelConfig found no known layout")
            return decodes
        }

        val wavs = File(modelDir, "test_wavs")
            .listFiles { f -> f.isFile && f.name.endsWith(".wav", ignoreCase = true) }
            ?.sortedBy { it.name }
            .orEmpty()
        if (wavs.isEmpty()) {
            Log.w(TAG, "model=${modelDir.name} SKIP: no wavs under test_wavs/")
            return decodes
        }

        val recognizer = com.k2fsa.sherpa.onnx.OfflineRecognizer(assetManager = null, config = config)
        try {
            for (wav in wavs) {
                val samples = try {
                    readWavMono16(wav)
                } catch (e: IOException) {
                    Log.w(TAG, "model=${modelDir.name} wav=${wav.name} SKIP: ${e.message}")
                    continue
                }
                val audioMs = samples.size * 1000L / 16_000
                repeat(repeats) { i ->
                    val stream = recognizer.createStream()
                    val startNs = System.nanoTime()
                    val text: String
                    try {
                        stream.acceptWaveform(samples, sampleRate = 16_000)
                        recognizer.decode(stream)
                        text = recognizer.getResult(stream).text
                    } finally {
                        stream.release()
                    }
                    val decodeMs = (System.nanoTime() - startNs) / 1_000_000
                    val rtf = if (audioMs > 0) decodeMs.toDouble() / audioMs else 0.0
                    Log.i(
                        TAG,
                        "model=${modelDir.name} threads=$threads wav=${wav.name} run=$i " +
                            "audioMs=$audioMs decodeMs=$decodeMs rtf=${"%.3f".format(rtf)}"
                    )
                    decodes.put(
                        JSONObject()
                            .put("wav", wav.name)
                            .put("run", i)
                            .put("audioMs", audioMs)
                            .put("decodeMs", decodeMs)
                            .put("rtf", rtf)
                            .put("text", text)
                    )
                }
            }
        } finally {
            recognizer.release()
        }
        return decodes
    }

    /**
     * Minimal 16-bit PCM mono WAV reader: RIFF header check, then a chunk walk to `fmt ` and
     * `data`, converting to the [-1, 1) FloatArray sherpa-onnx expects (/32768f, matching the
     * PCM conversion the production capture path uses). Inline because the vendored
     * com.k2fsa.sherpa.onnx sources ship no WaveReader (checked before writing this) and the
     * sherpa test_wavs are all plain 16kHz s16le mono -- anything else is rejected loudly
     * rather than decoded as garbage.
     */
    private fun readWavMono16(file: File): FloatArray {
        DataInputStream(file.inputStream().buffered()).use { input ->
            fun readTag() = String(ByteArray(4).also { input.readFully(it) }, Charsets.US_ASCII)
            fun readLeInt(): Int =
                input.read() or (input.read() shl 8) or (input.read() shl 16) or (input.read() shl 24)
            fun readLeShort(): Int = input.read() or (input.read() shl 8)

            if (readTag() != "RIFF") throw IOException("${file.name}: not a RIFF file")
            readLeInt() // RIFF chunk size, unused
            if (readTag() != "WAVE") throw IOException("${file.name}: not a WAVE file")

            var channels = -1
            var bitsPerSample = -1
            while (true) {
                val tag = readTag()
                val size = readLeInt()
                when (tag) {
                    "fmt " -> {
                        val audioFormat = readLeShort()
                        channels = readLeShort()
                        readLeInt() // sample rate; test_wavs are 16k, and audioMs assumes it
                        readLeInt() // byte rate
                        readLeShort() // block align
                        bitsPerSample = readLeShort()
                        input.skipBytes(size - 16)
                        if (audioFormat != 1) throw IOException("${file.name}: not PCM (format $audioFormat)")
                    }
                    "data" -> {
                        if (channels != 1 || bitsPerSample != 16) {
                            throw IOException(
                                "${file.name}: want 16-bit mono, got ${bitsPerSample}-bit ${channels}ch"
                            )
                        }
                        val bytes = ByteArray(size)
                        input.readFully(bytes)
                        return FloatArray(size / 2) { i ->
                            val lo = bytes[2 * i].toInt() and 0xFF
                            val hi = bytes[2 * i + 1].toInt()
                            ((hi shl 8) or lo).toShort().toInt() / 32768f
                        }
                    }
                    else -> input.skipBytes(size + (size and 1)) // chunks are word-aligned
                }
            }
        }
    }
}
