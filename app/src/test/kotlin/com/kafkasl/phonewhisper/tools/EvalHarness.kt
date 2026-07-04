package com.kafkasl.phonewhisper.tools

import com.kafkasl.phonewhisper.NetworkClients
import com.kafkasl.phonewhisper.PostProcessor
import com.kafkasl.phonewhisper.VocabularyTerms
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import kotlin.system.exitProcess

/**
 * Manual dev tool (see issue #2) — NOT a JUnit test and NOT run by `make test` / CI.
 *
 * Runs the checked-in transcripts under `app/src/test/resources/eval_samples/` through one or
 * more [PostProcessor] cleanup prompts against the real OpenAI chat completions API, and writes
 * a before/after markdown report for manual review.
 *
 * This costs real API credits. See the "Prompt eval harness" section of README.md for setup
 * and usage. Because this file only declares `main()` (no `@Test` methods), Gradle's test task
 * compiles it like any other test-source file but JUnit never discovers or runs it — nothing
 * here executes as part of `./gradlew testDebugUnitTest` / `make test`.
 *
 * Run via: `./gradlew runEvalHarness` (see the `runEvalHarness` task in app/build.gradle.kts),
 * or directly from an IDE.
 */

/** Known prompt variants, by name, that [main] can run. Add new PostProcessor prompt constants
 *  here as they're introduced. Each is interpolated with [VocabularyTerms.DEFAULTS] — the same
 *  seed used for a fresh install — so the report reflects real output instead of a dangling
 *  `{{vocabulary}}` placeholder (see #26).
 *
 *  The FORMAL/CASUAL/NOTES personas (#40) reuse DEV_PROMPT/SIMPLE_PROMPT/STRUCTURED_PROMPT
 *  byte-for-byte, so they're already covered above under those names — only the tone-filter
 *  personas with genuinely distinct prompt text (GANGSTER/SMART/TEACHER) are registered
 *  separately. */
private val PROMPT_REGISTRY: Map<String, String> = mapOf(
    "SIMPLE_PROMPT" to PostProcessor.interpolateVocabulary(PostProcessor.SIMPLE_PROMPT, VocabularyTerms.DEFAULTS),
    "DEV_PROMPT" to PostProcessor.interpolateVocabulary(PostProcessor.DEV_PROMPT, VocabularyTerms.DEFAULTS),
    "STRUCTURED_PROMPT" to PostProcessor.interpolateVocabulary(PostProcessor.STRUCTURED_PROMPT, VocabularyTerms.DEFAULTS),
    "GANGSTER_PROMPT" to PostProcessor.interpolateVocabulary(PostProcessor.GANGSTER_PROMPT, VocabularyTerms.DEFAULTS),
    "SMART_PROMPT" to PostProcessor.interpolateVocabulary(PostProcessor.SMART_PROMPT, VocabularyTerms.DEFAULTS),
    "TEACHER_PROMPT" to PostProcessor.interpolateVocabulary(PostProcessor.TEACHER_PROMPT, VocabularyTerms.DEFAULTS),
)

private const val DEFAULT_MODEL = "gpt-4o-mini"
private const val DEFAULT_SAMPLES_DIR = "app/src/test/resources/eval_samples"

private fun callOpenAi(apiKey: String, model: String, prompt: String, text: String): PostProcessor.Result {
    val messages = JSONArray().apply {
        put(JSONObject().apply {
            put("role", "system")
            put("content", prompt)
        })
        put(JSONObject().apply {
            put("role", "user")
            put("content", text)
        })
    }

    val bodyJson = JSONObject().apply {
        put("model", model)
        put("messages", messages)
        put("temperature", 0.0)
    }

    val request = Request.Builder()
        .url("https://api.openai.com/v1/chat/completions")
        .header("Authorization", "Bearer $apiKey")
        .post(bodyJson.toString().toRequestBody("application/json".toMediaType()))
        .build()

    return try {
        NetworkClients.shared.newCall(request).execute().use { response ->
            val responseBody = response.body?.string() ?: ""
            if (!response.isSuccessful && responseBody.isBlank()) {
                PostProcessor.Result(null, "HTTP ${response.code}")
            } else {
                PostProcessor.parseResponse(responseBody)
            }
        }
    } catch (e: IOException) {
        PostProcessor.Result(null, e.message ?: "network error")
    }
}

fun main(args: Array<String>) {
    val apiKey = System.getenv("OPENAI_API_KEY")
    if (apiKey.isNullOrBlank()) {
        System.err.println(
            "OPENAI_API_KEY is not set. This tool calls the real OpenAI API and spends real " +
                "credits — export OPENAI_API_KEY (never hardcode it) before running. See README.md."
        )
        exitProcess(1)
    }

    val promptNames = (args.getOrNull(0) ?: "SIMPLE_PROMPT,DEV_PROMPT")
        .split(",").map { it.trim() }.filter { it.isNotEmpty() }
    val unknown = promptNames.filter { it !in PROMPT_REGISTRY }
    if (unknown.isNotEmpty()) {
        System.err.println(
            "Unknown prompt name(s): ${unknown.joinToString()}. " +
                "Known prompts: ${PROMPT_REGISTRY.keys.joinToString()}"
        )
        exitProcess(1)
    }

    val samplesDir = File(args.getOrNull(2) ?: DEFAULT_SAMPLES_DIR)
    if (!samplesDir.isDirectory) {
        System.err.println("Samples directory not found: ${samplesDir.path} (run this from the repo root)")
        exitProcess(1)
    }
    val samples = samplesDir.listFiles { f -> f.isFile && f.extension == "txt" }
        ?.sortedBy { it.name }
        ?: emptyList()
    if (samples.isEmpty()) {
        System.err.println("No .txt samples found in ${samplesDir.path}")
        exitProcess(1)
    }

    val model = System.getenv("OPENAI_EVAL_MODEL")?.takeIf { it.isNotBlank() } ?: DEFAULT_MODEL
    val outputFile = File(args.getOrNull(1) ?: "eval-reports/${promptNames.joinToString("_vs_")}.md")
    outputFile.parentFile?.mkdirs()

    println(
        "Running ${samples.size} sample(s) x ${promptNames.size} prompt(s) against $model. " +
            "This calls the real OpenAI API and costs credits."
    )

    val report = StringBuilder()
    report.append("# Prompt eval report\n\n")
    report.append("Model: `$model`\n\n")
    report.append("Prompts compared: ${promptNames.joinToString(", ") { "`$it`" }}\n\n")
    report.append("Samples: ${samples.size} (from `${samplesDir.path}`)\n\n")
    report.append("---\n\n")

    for (sample in samples) {
        val rawText = sample.readText().trim()
        report.append("## ${sample.name}\n\n")
        report.append("**Before (raw transcript):**\n\n")
        report.append("```\n$rawText\n```\n\n")

        for (promptName in promptNames) {
            print("  ${sample.name} x $promptName ... ")
            val result = callOpenAi(apiKey, model, PROMPT_REGISTRY.getValue(promptName), rawText)
            println(if (result.error == null) "ok" else "error: ${result.error}")

            report.append("**After — `$promptName`:**\n\n")
            if (result.text != null) {
                report.append("```\n${result.text}\n```\n\n")
            } else {
                report.append("_Error: ${result.error}_\n\n")
            }
        }
        report.append("---\n\n")
    }

    outputFile.writeText(report.toString())
    println("Report written to ${outputFile.path}")
}
