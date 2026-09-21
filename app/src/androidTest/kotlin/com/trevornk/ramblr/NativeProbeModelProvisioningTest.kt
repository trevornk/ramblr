package com.trevornk.ramblr

import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Downloads the exact catalog artifacts into the isolated runtime-probe package. Each model has a
 * deadline and throttled byte-progress log; cached payloads are accepted only when a manifest from
 * a previously checksum-verified probe download still matches every payload hash.
 */
@RunWith(AndroidJUnit4::class)
class NativeProbeModelProvisioningTest {
    @Test
    fun downloadsVerifiedModelsOnlyInsideNonDebuggableProbe() {
        val ctx = ProbeRuntimeContext.targetContext
        assertEquals("com.trevornk.ramblr.r8probe7", ctx.packageName)
        assertFalse(
            "runtime probe must remain non-debuggable so AGP/R8 keeps release optimization enabled",
            ctx.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0,
        )

        val records = JSONArray()
        listOf(SILERO_VAD_MODEL, MODEL_CATALOG.first { it.archive == ASR_ARCHIVE }, MUMBLE_CLEANUP_Q4_0_MODEL)
            .forEach { model ->
                val source = provisionBounded(ctx, model)
                val installedDir = ModelDownloader.modelDir(ctx, model)
                assertTrue("${model.archive} missing completion marker", ModelDownloader.isInstalledDir(installedDir))
                val payloadHashes = payloadHashes(installedDir)
                records.put(JSONObject()
                    .put("archive", model.archive)
                    .put("catalogSha256", model.sha256)
                    .put("source", source)
                    .put("installedDir", installedDir.absolutePath)
                    .put("payloadHashes", payloadHashes))
                Log.i(TAG, "MODEL_VERIFIED archive=${model.archive} source=$source catalogSha256=${model.sha256} payloadFiles=${payloadHashes.length()}")
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

    private fun provisionBounded(ctx: Context, model: Model): String {
        if (hasValidProbeIntegrity(ctx, model)) {
            Log.i(TAG, "MODEL_CACHE_VALID archive=${model.archive}")
            return "validated-cache"
        }
        // A marker alone is not integrity. The isolated probe may discard only its own stale data.
        ModelDownloader.delete(ctx, model)
        val deadline = SystemClock.elapsedRealtime() + modelBudgetMs(model)
        var done = false
        var nextProgressPercent = PROGRESS_STEP_PERCENT
        Log.i(TAG, "MODEL_DOWNLOAD_START archive=${model.archive} deadlineMs=${modelBudgetMs(model)}")
        ModelDownloader.download(ctx, model, isCancelled = { SystemClock.elapsedRealtime() >= deadline }) { state ->
            when (state) {
                is DownloadState.Downloading -> {
                    val percent = (state.progress * 100).toInt().coerceIn(0, 100)
                    if (percent >= nextProgressPercent || percent == 100) {
                        Log.i(TAG, "MODEL_DOWNLOAD_PROGRESS archive=${model.archive} percent=$percent")
                        nextProgressPercent = ((percent / PROGRESS_STEP_PERCENT) + 1) * PROGRESS_STEP_PERCENT
                    }
                }
                DownloadState.Extracting -> Log.i(TAG, "MODEL_EXTRACT_START archive=${model.archive}")
                DownloadState.Done -> done = true
                is DownloadState.Error -> throw AssertionError(
                    "${model.archive} download/install failed before deadline: ${state.message}", state.cause,
                )
            }
        }
        assertTrue("${model.archive} did not reach Done before deadline", done)
        writeProbeIntegrity(ctx, model)
        return "downloaded"
    }

    private fun hasValidProbeIntegrity(ctx: Context, model: Model): Boolean {
        return try {
            val installedDir = ModelDownloader.modelDir(ctx, model)
            val manifest = integrityFile(ctx, model)
            if (!ModelDownloader.isInstalledDir(installedDir) || !manifest.isFile) return false
            val parsed = JSONObject(manifest.readText())
            if (parsed.optString("catalogSha256") != model.sha256) return false
            val expected = parsed.getJSONArray("payloadHashes")
            if (expected.toString() != payloadHashes(installedDir).toString()) return false
            if (model.isSingleFile) {
                val file = if (model.isVadModel) ModelDownloader.vadModelFile(ctx, model)
                else ModelDownloader.localCleanupModelFile(ctx, model)
                if (file == null || ModelDownloader.sha256(file) != model.sha256) return false
            }
            true
        } catch (t: Throwable) {
            Log.w(TAG, "MODEL_CACHE_INVALID archive=${model.archive}", t)
            false
        }
    }

    private fun writeProbeIntegrity(ctx: Context, model: Model) {
        val installedDir = ModelDownloader.modelDir(ctx, model)
        integrityFile(ctx, model).apply {
            parentFile?.mkdirs()
            writeText(JSONObject()
                .put("catalogSha256", model.sha256)
                .put("payloadHashes", payloadHashes(installedDir))
                .toString())
        }
    }

    private fun integrityFile(ctx: Context, model: Model) =
        File(ctx.filesDir, "native_probe_integrity/${model.archive}.json")

    private fun payloadHashes(installedDir: File): JSONArray = JSONArray().also { hashes ->
        installedDir.walkTopDown().filter { it.isFile && it.name != ".complete" }
            .sortedBy { it.relativeTo(installedDir).path }
            .forEach { file -> hashes.put(JSONObject()
                .put("path", file.relativeTo(installedDir).path)
                .put("sha256", ModelDownloader.sha256(file))) }
    }

    private fun modelBudgetMs(model: Model): Long = when (model.archive) {
        SILERO_VAD_MODEL.archive -> VAD_DOWNLOAD_BUDGET_MS
        ASR_ARCHIVE -> ASR_DOWNLOAD_BUDGET_MS
        MUMBLE_CLEANUP_Q4_0_MODEL.archive -> CLEANUP_DOWNLOAD_BUDGET_MS
        else -> error("unbounded probe model ${model.archive}")
    }

    private companion object {
        const val TAG = "NativeProbeProvision"
        const val RESULTS_FILE = "native_probe_provisioning.json"
        const val ASR_ARCHIVE = "sherpa-onnx-nemo-parakeet_tdt_ctc_110m-en-36000-int8"
        const val PROGRESS_STEP_PERCENT = 5
        const val VAD_DOWNLOAD_BUDGET_MS = 120_000L
        const val ASR_DOWNLOAD_BUDGET_MS = 420_000L
        const val CLEANUP_DOWNLOAD_BUDGET_MS = 840_000L
    }
}
