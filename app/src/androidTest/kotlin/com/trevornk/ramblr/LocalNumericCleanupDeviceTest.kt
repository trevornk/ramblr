package com.trevornk.ramblr

import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * On-device verification for #155: real number-bearing transcripts through the REAL local
 * cleanup path -- [SpokenNumberNormalizer] -> [RealLocalInferenceEngine] (installed GGUF via
 * the production JNI/dlopen path) -> [NumericPreservationVerifier] -- exactly mirroring the
 * LOCAL_LLM branch of [CleanupWaterfallExecutor.performStep].
 *
 * This is the hardware evidence the issue's merge comment (#167) explicitly left open: every
 * prior number was host-side. It runs against whatever cleanup model the device actually has
 * installed and SKIPS (assumeTrue) when none is present, so an empty device can never
 * masquerade as a green verification.
 *
 * The #155 contract under test is NOT "the model never errs" -- it is that the pipeline never
 * lets a numeric corruption through silently:
 *   - ACCEPTED output must carry the exact ordered numeric values of the normalized input.
 *   - Output that diverges numerically must be REJECTED (falls through the waterfall in prod).
 *   - The deterministic normalizer, not the model, performs spoken-numeral -> digit conversion.
 *
 * Results are written to filesDir/numeric_cleanup_results.json for adb retrieval. Test inputs
 * are synthetic (the issue's own failure examples plus category coverage); no user data is
 * read or logged.
 */
class LocalNumericCleanupDeviceTest {

    private data class Case(val name: String, val raw: String)

    /** The issue's three measured real-world failures first, then one per major category. */
    private val cases = listOf(
        Case("issue_million_dollars", "we raised one point two million dollars last quarter"),
        Case("issue_percent", "we saw like a twenty three percent increase"),
        Case("issue_phone", "call me at five five five one two three four five six seven"),
        Case("currency", "the invoice total is four hundred fifty dollars"),
        Case("time", "the meeting got moved to four thirty pm"),
        Case("ordinal", "she finished in twenty first place"),
        Case("cardinal_compound", "there were three hundred forty two people at the event"),
        Case("date_year", "the contract expires in twenty twenty six"),
        Case("decimal", "the board is two point five inches thick"),
        Case("plain_prose_control", "let's grab coffee tomorrow and talk about the project"),
    )

    @Test
    fun numericPreservationThroughRealLocalModel() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val model = LocalCleanupProvider.selectedModel(ctx)
        val modelFile = ModelDownloader.localCleanupModelFile(ctx, model)
        assumeTrue(
            "no installed local cleanup model; install one before running this verification",
            modelFile != null && modelFile.exists(),
        )
        val systemPrompt = LocalCleanupProvider.selectedSystemPrompt(ctx)

        val results = JSONArray()
        var corrupted = 0
        var accepted = 0
        var rejected = 0

        for (case in cases) {
            val normalization = SpokenNumberNormalizer.normalize(case.raw)
            val deadline = System.currentTimeMillis() + PER_CASE_BUDGET_MS
            val startMs = System.currentTimeMillis()
            val result = RealLocalInferenceEngine.complete(
                systemPrompt, normalization.text, modelFile!!.absolutePath, deadline,
            ) { false }
            val latencyMs = System.currentTimeMillis() - startMs

            val entry = JSONObject()
                .put("case", case.name)
                .put("raw", case.raw)
                .put("normalized", normalization.text)
                .put("latencyMs", latencyMs)

            when (result) {
                is LocalInferenceResult.Success -> {
                    val trimmed = result.text.trim()
                    val verdict = NumericPreservationVerifier.verify(normalization, trimmed)
                    val diverged = verdict is NumericPreservation.Rejected
                    if (diverged) rejected++ else accepted++
                    // Mirror of the production branch: divergence -> StepFailed (fall-through).
                    // A corruption only "escapes" if the verifier ACCEPTS diverged output, which
                    // is precisely what checkAccepted() below asserts never happens.
                    entry.put("outcome", if (diverged) "REJECTED_NUMERIC_DIVERGENCE" else "ACCEPTED")
                        .put("output", trimmed)
                    if (!diverged && !numbersSurvived(normalization.text, trimmed)) {
                        corrupted++
                        entry.put("escapedCorruption", true)
                    }
                }
                is LocalInferenceResult.Failure -> {
                    rejected++
                    entry.put("outcome", "STEP_FAILED").put("detail", result.message)
                }
                is LocalInferenceResult.TimedOut -> {
                    rejected++
                    entry.put("outcome", "TIMED_OUT")
                }
                is LocalInferenceResult.Cancelled -> entry.put("outcome", "CANCELLED")
            }
            Log.i(TAG, "case=${case.name} -> ${entry.optString("outcome")} (${latencyMs}ms)")
            results.put(entry)
        }

        val summary = JSONObject()
            .put("model", modelFile!!.name)
            .put("cases", cases.size)
            .put("accepted", accepted)
            .put("rejectedOrFailed", rejected)
            .put("escapedCorruptions", corrupted)
            .put("results", results)
        File(ctx.filesDir, RESULTS_FILE).writeText(summary.toString(2))
        Log.i(TAG, "accepted=$accepted rejected=$rejected escapedCorruptions=$corrupted")

        // The #155 contract: zero silent numeric corruptions may reach accepted output.
        assertTrue(
            "silently corrupted numeric output escaped the verifier in $corrupted case(s) -- see $RESULTS_FILE",
            corrupted == 0,
        )
        // And the pipeline must actually work: an install where every single case fails/times
        // out proves nothing about preservation. Require at least one real accepted cleanup.
        assertTrue("no case produced accepted local cleanup output; pipeline never exercised", accepted > 0)
    }

    /** Belt-and-braces re-check independent of the verifier: every digit-run the normalizer
     *  produced must appear in accepted output (ignoring commas/spacing), in order. */
    private fun numbersSurvived(normalized: String, output: String): Boolean {
        val wanted = DIGIT_RUN.findAll(normalized).map { it.value.replace(",", "") }.toList()
        if (wanted.isEmpty()) return true
        val got = DIGIT_RUN.findAll(output).map { it.value.replace(",", "") }.toList()
        var i = 0
        for (g in got) {
            if (i < wanted.size && g == wanted[i]) i++
        }
        return i == wanted.size
    }

    private companion object {
        const val TAG = "LocalNumericCleanupDeviceTest"
        const val RESULTS_FILE = "numeric_cleanup_results.json"
        /** Generous per-case budget: first case pays the full GGUF load. */
        const val PER_CASE_BUDGET_MS = 60_000L
        val DIGIT_RUN = Regex("[0-9][0-9,]*(?:\\.[0-9]+)?")
    }
}
