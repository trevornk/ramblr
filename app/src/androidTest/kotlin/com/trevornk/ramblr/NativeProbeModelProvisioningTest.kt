package com.trevornk.ramblr

import android.content.Context
import android.content.pm.ApplicationInfo
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Downloads the exact catalog artifacts into the isolated runtime-probe package. This test is
 * deliberately separate from native execution so every download/install checksum gate is visible
 * before ASR, VAD, or cleanup runs.
 */
@RunWith(AndroidJUnit4::class)
class NativeProbeModelProvisioningTest {
    @Test
    fun downloadsVerifiedModelsOnlyInsideNonDebuggableProbe() {
        val ctx = ProbeRuntimeContext.targetContext
        assertEquals("com.trevornk.ramblr.r8probe6", ctx.packageName)
        assertFalse(
            "runtime probe must remain non-debuggable so AGP/R8 keeps release optimization enabled",
            ctx.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0,
        )

        val records = JSONArray()
        listOf(SILERO_VAD_MODEL, MODEL_CATALOG.first { it.archive == ASR_ARCHIVE }, MUMBLE_CLEANUP_Q4_0_MODEL)
            .forEach { model ->
                val states = mutableListOf<String>()
                ModelDownloader.download(ctx, model, onState = { state ->
                    states += state.javaClass.simpleName
                    if (state is DownloadState.Error) {
                        throw AssertionError("${model.archive} download/install failed: ${state.message}", state.cause)
                    }
                })
                assertTrue("${model.archive} did not reach Done: $states", states.any { it == "Done" })
                val installedDir = ModelDownloader.modelDir(ctx, model)
                assertTrue("${model.archive} missing completion marker", ModelDownloader.isInstalledDir(installedDir))
                val payloadHashes = JSONArray()
                installedDir.walkTopDown().filter { it.isFile && it.name != ".complete" }.sortedBy { it.relativeTo(installedDir).path }
                    .forEach { file ->
                        payloadHashes.put(JSONObject()
                            .put("path", file.relativeTo(installedDir).path)
                            .put("sha256", ModelDownloader.sha256(file)))
                    }
                records.put(JSONObject()
                    .put("archive", model.archive)
                    .put("catalogSha256", model.sha256)
                    .put("installedDir", installedDir.absolutePath)
                    .put("payloadHashes", payloadHashes))
                Log.i(TAG, "MODEL_VERIFIED archive=${model.archive} catalogSha256=${model.sha256} payloadFiles=${payloadHashes.length()}")
            }

        val asr = MODEL_CATALOG.first { it.archive == ASR_ARCHIVE }
        val source = ModelDownloader.modelDir(ctx, asr)
        val benchTarget = File(ctx.filesDir, "bench_models/${asr.archive}")
        benchTarget.deleteRecursively()
        assertTrue("failed copying ASR archive to isolated benchmark sibling", source.copyRecursively(benchTarget, overwrite = true))
        assertTrue("ASR fixture archive has no test WAVs", File(benchTarget, "test_wavs").listFiles { f -> f.extension.equals("wav", true) }?.isNotEmpty() == true)

        ctx.getSharedPreferences("ramblr", Context.MODE_PRIVATE).edit()
            .putString("local_cleanup_model_name", MUMBLE_CLEANUP_Q4_0_MODEL.archive)
            .apply()
        assertEquals(MUMBLE_CLEANUP_Q4_0_MODEL.archive, LocalCleanupProvider.selectedModel(ctx).archive)

        File(ctx.filesDir, RESULTS_FILE).writeText(
            JSONObject()
                .put("package", ctx.packageName)
                .put("debuggable", false)
                .put("models", records)
                .put("cleanupSelection", MUMBLE_CLEANUP_Q4_0_MODEL.archive)
                .toString(2),
        )
        Log.i(TAG, "PROVISIONED package=${ctx.packageName} models=${records.length()} cleanup=${MUMBLE_CLEANUP_Q4_0_MODEL.archive}")
    }

    private companion object {
        const val TAG = "NativeProbeProvision"
        const val RESULTS_FILE = "native_probe_provisioning.json"
        const val ASR_ARCHIVE = "sherpa-onnx-nemo-parakeet_tdt_ctc_110m-en-36000-int8"
    }
}
