package com.trevornk.ramblr.tools

import com.trevornk.ramblr.AnthropicCleanupProvider
import com.trevornk.ramblr.GeminiCleanupProvider
import com.trevornk.ramblr.NetworkClients
import com.trevornk.ramblr.PostProcessor
import com.trevornk.ramblr.VocabularyTerms
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
 * more [PostProcessor] cleanup prompts against the real OpenAI, Anthropic, and Gemini APIs (#97),
 * reusing each provider's real request/response contract already shipped in the app
 * ([PostProcessor] for OpenAI-compatible chat completions, [AnthropicCleanupProvider] for
 * `/v1/messages`, [GeminiCleanupProvider] for `generateContent`) rather than hand-rolling a
 * second HTTP path per provider, and writes a before/after markdown report for manual review.
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

private const val DEFAULT_SAMPLES_DIR = "app/src/test/resources/eval_samples"

/**
 * One cloud provider EvalHarness knows how to call, keyed by the env var holding its API key and
 * the env var that overrides its default candidate model list (#97). [defaultModels] is the
 * "cheap-tier, still top-notch" short-list used when the override env var isn't set — see
 * eval-reports/#97-provider-model-benchmark.md for how these were picked from current provider
 * pricing pages, not assumed from memory.
 */
private enum class Provider(val label: String, val apiKeyEnv: String, val modelsEnvOverride: String, val defaultModels: List<String>) {
    OPENAI("openai", "OPENAI_API_KEY", "OPENAI_EVAL_MODELS", listOf("gpt-5.4-nano", "gpt-5.4-mini")),
    ANTHROPIC("anthropic", "ANTHROPIC_API_KEY", "ANTHROPIC_EVAL_MODELS", listOf("claude-haiku-4-5-20251001", "claude-sonnet-4-6")),
    GEMINI("gemini", "GEMINI_API_KEY", "GEMINI_EVAL_MODELS", listOf("gemini-2.5-flash-lite", "gemini-2.5-flash")),
}

/** Calls OpenAI's real `/v1/chat/completions`, reusing [PostProcessor]'s request/response shape. */
private fun callOpenAi(apiKey: String, model: String, prompt: String, text: String): PostProcessor.Result {
    val body = PostProcessor.buildRequestBody(text, prompt, model).toString().toRequestBody("application/json".toMediaType())
    val request = Request.Builder()
        .url(PostProcessor.ENDPOINT_URL)
        .header("Authorization", "Bearer $apiKey")
        .post(body)
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

/** Calls Anthropic's real `/v1/messages`, reusing [AnthropicCleanupProvider]'s request/response shape. */
private fun callAnthropic(apiKey: String, model: String, prompt: String, text: String): PostProcessor.Result {
    val body = AnthropicCleanupProvider.buildRequestBody(text, prompt, model).toString()
        .toRequestBody("application/json".toMediaType())
    val requestBuilder = Request.Builder().url(AnthropicCleanupProvider.ENDPOINT_URL).post(body)
    AnthropicCleanupProvider.headers(apiKey).forEach { (name, value) -> requestBuilder.header(name, value) }

    return try {
        NetworkClients.shared.newCall(requestBuilder.build()).execute().use { response ->
            val responseBody = response.body?.string() ?: ""
            if (!response.isSuccessful && responseBody.isBlank()) {
                PostProcessor.Result(null, "HTTP ${response.code}")
            } else {
                AnthropicCleanupProvider.parseResponse(responseBody)
            }
        }
    } catch (e: IOException) {
        PostProcessor.Result(null, e.message ?: "network error")
    }
}

/** Calls Gemini's real `generateContent`, reusing [GeminiCleanupProvider]'s request/response shape. */
private fun callGemini(apiKey: String, model: String, prompt: String, text: String): PostProcessor.Result {
    val body = GeminiCleanupProvider.buildRequestBody(text, prompt).toString()
        .toRequestBody("application/json".toMediaType())
    val request = Request.Builder()
        .url(GeminiCleanupProvider.endpointUrl(model, apiKey))
        .post(body)
        .build()

    return try {
        NetworkClients.shared.newCall(request).execute().use { response ->
            val responseBody = response.body?.string() ?: ""
            if (!response.isSuccessful && responseBody.isBlank()) {
                PostProcessor.Result(null, "HTTP ${response.code}")
            } else {
                GeminiCleanupProvider.parseResponse(responseBody)
            }
        }
    } catch (e: IOException) {
        PostProcessor.Result(null, e.message ?: "network error")
    }
}

private fun callProvider(provider: Provider, apiKey: String, model: String, prompt: String, text: String): PostProcessor.Result =
    when (provider) {
        Provider.OPENAI -> callOpenAi(apiKey, model, prompt, text)
        Provider.ANTHROPIC -> callAnthropic(apiKey, model, prompt, text)
        Provider.GEMINI -> callGemini(apiKey, model, prompt, text)
    }

fun main(args: Array<String>) {
    val available = Provider.entries.mapNotNull { provider ->
        val key = System.getenv(provider.apiKeyEnv)
        if (key.isNullOrBlank()) null else provider to key
    }
    if (available.isEmpty()) {
        System.err.println(
            "None of ${Provider.entries.joinToString { it.apiKeyEnv }} are set. This tool calls real " +
                "provider APIs and spends real credits — export at least one before running. See README.md."
        )
        exitProcess(1)
    }
    val availableProviders = available.map { it.first }.toSet()
    Provider.entries.filterNot { it in availableProviders }.forEach { missing ->
        System.err.println("${missing.apiKeyEnv} not set — skipping ${missing.label}.")
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

    // (provider, model) pairs to run, in a stable order: each available provider's candidate
    // models, overridden per-provider by <PROVIDER>_EVAL_MODELS (comma-separated) if set.
    val runs: List<Pair<Provider, String>> = available.flatMap { (provider, _) ->
        val models = System.getenv(provider.modelsEnvOverride)?.takeIf { it.isNotBlank() }
            ?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }
            ?: provider.defaultModels
        models.map { model -> provider to model }
    }
    val apiKeys = available.toMap()

    val outputFile = File(args.getOrNull(1) ?: "eval-reports/${promptNames.joinToString("_vs_")}.md")
    outputFile.parentFile?.mkdirs()

    println(
        "Running ${samples.size} sample(s) x ${promptNames.size} prompt(s) x ${runs.size} model(s) " +
            "(${runs.joinToString { "${it.first.label}/${it.second}" }}). " +
            "This calls real provider APIs and costs credits."
    )

    val report = StringBuilder()
    report.append("# Prompt eval report\n\n")
    report.append("Models: ${runs.joinToString(", ") { "`${it.first.label}/${it.second}`" }}\n\n")
    report.append("Prompts compared: ${promptNames.joinToString(", ") { "`$it`" }}\n\n")
    report.append("Samples: ${samples.size} (from `${samplesDir.path}`)\n\n")
    report.append("---\n\n")

    for (sample in samples) {
        val rawText = sample.readText().trim()
        report.append("## ${sample.name}\n\n")
        report.append("**Before (raw transcript):**\n\n")
        report.append("```\n$rawText\n```\n\n")

        for (promptName in promptNames) {
            report.append("### Persona/prompt: `$promptName`\n\n")
            for ((provider, model) in runs) {
                print("  ${sample.name} x $promptName x ${provider.label}/$model ... ")
                val result = callProvider(provider, apiKeys.getValue(provider), model, PROMPT_REGISTRY.getValue(promptName), rawText)
                println(if (result.error == null) "ok" else "error: ${result.error}")

                report.append("**After — `${provider.label}/$model`:**\n\n")
                if (result.text != null) {
                    report.append("```\n${result.text}\n```\n\n")
                } else {
                    report.append("_Error: ${result.error}_\n\n")
                }
            }
        }
        report.append("---\n\n")
    }

    outputFile.writeText(report.toString())
    println("Report written to ${outputFile.path}")
}
