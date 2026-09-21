package com.trevornk.ramblr

import android.content.Context
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Emits small synthetic-result summaries, then removes only this probe's model fixtures. */
@RunWith(AndroidJUnit4::class)
class NativeProbeEvidenceAndCleanupTest {
    @Test
    fun reportsNativeResultsAndRemovesProbeModels() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val expected = listOf(
            "native_probe_provisioning.json",
            "bench_results.json",
            "native_probe_vad.json",
            "numeric_cleanup_results.json",
        )
        expected.forEach { name ->
            val file = File(ctx.filesDir, name)
            assertTrue("missing native evidence $name", file.isFile)
            Log.i(TAG, "EVIDENCE $name=${file.readText()}")
        }
        listOf(SILERO_VAD_MODEL, MODEL_CATALOG.first { it.archive == ASR_ARCHIVE }, MUMBLE_CLEANUP_Q4_0_MODEL)
            .forEach { ModelDownloader.delete(ctx, it) }
        File(ctx.filesDir, "bench_models").deleteRecursively()
        ctx.getSharedPreferences("ramblr", Context.MODE_PRIVATE).edit().remove("local_cleanup_model_name").apply()
        val remnants = listOf("models", "vad_models", "cleanup_models", "bench_models")
            .flatMap { dir -> File(ctx.filesDir, dir).walkTopDown().filter { it.isFile && it.name != ".complete" }.map { it.absolutePath }.toList() }
        assertTrue("probe fixture cleanup left payloads: $remnants", remnants.isEmpty())
        Log.i(TAG, "PROBE_MODEL_CLEANUP=OK package=${ctx.packageName}")
    }

    private companion object {
        const val TAG = "NativeProbeEvidence"
        const val ASR_ARCHIVE = "sherpa-onnx-nemo-parakeet_tdt_ctc_110m-en-36000-int8"
    }
}
