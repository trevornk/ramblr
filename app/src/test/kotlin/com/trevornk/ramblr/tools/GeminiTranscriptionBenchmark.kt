package com.trevornk.ramblr.tools

import com.trevornk.ramblr.GeminiTranscriberClient
import com.trevornk.ramblr.InFlightCall
import com.trevornk.ramblr.VocabularyTerms
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.system.exitProcess

/**
 * Manual dev tool for issue #129 — NOT a JUnit test and NOT run by `make test` / CI.
 *
 * Measures how well the **production transcription path** actually transcribes audio, by calling
 * the real shipped [GeminiTranscriberClient.transcribe] (same request shape, same headers, same
 * OkHttp client, same async callback contract) for every fixture x model x repetition in the
 * corpus under `app/src/test/resources/transcription_eval/`, then scoring the results with
 * [TranscriptionMetrics] and writing a Markdown + JSON report to `eval-reports/` (gitignored).
 *
 * Mirrors [EvalHarness]'s structure deliberately — same "declare only main(), so Gradle compiles
 * it but JUnit never discovers it" trick, same manual JavaExec task, same gitignored report
 * directory — but is a separate tool because it benchmarks a completely different stage of the
 * pipeline (audio -> text) with completely different metrics (WER/CER, not human-judged cleanup
 * quality) and completely different cost and privacy characteristics (it uploads audio).
 *
 * **This tool calls the real Gemini API, spends real credits, and uploads audio to Google.**
 * See the "Transcription benchmark" section of README.md.
 *
 * Run via: `./gradlew runGeminiTranscriptionBenchmark`
 *
 * ## Known limitation: no cost accounting
 * [GeminiTranscriberClient.Result] carries only `text`/`error` — the production client discards
 * the response's `usageMetadata`, so token counts are simply not observable from this path. This
 * report therefore records latency and accuracy but **no billed-cost figures**. Estimating cost
 * from clip duration would be a fabrication; if real cost accounting is needed, the production
 * Result type has to surface usage metadata first.
 */

/** Env var holding the API key. Its *value* is never printed, logged, or written to a report. */
private const val API_KEY_ENV = "GEMINI_API_KEY"

/** Comma-separated model id override. No default substitution happens if it's set but empty. */
private const val MODELS_ENV = "GEMINI_TRANSCRIPTION_MODELS"

/** Repetition count override — >1 exposes run-to-run nondeterminism in the model's output. */
private const val REPETITIONS_ENV = "GEMINI_TRANSCRIPTION_REPETITIONS"

/** Corpus directory override, for keeping a private fixture corpus outside the repo. */
private const val EVAL_DIR_ENV = "TRANSCRIPTION_EVAL_DIR"

/**
 * Comma-separated personal-vocabulary override. Production interpolates the user's vocabulary
 * into the transcription prompt itself ([GeminiTranscriberClient.transcribePrompt], #114 part 2),
 * so a benchmark that omitted it would measure a prompt no shipped build ever sends.
 *
 * Unset  -> [VocabularyTerms.DEFAULTS], the exact list prefs are seeded with on first run
 *           ([VocabularyTerms.DEFAULT_SERIALIZED]), i.e. what a real user actually has.
 * Set to "" -> explicitly no terms, for measuring what vocabulary biasing is worth.
 *
 * Both are explicit and both are recorded in the report header; neither is a silent substitution.
 */
private const val VOCABULARY_ENV = "GEMINI_TRANSCRIPTION_VOCABULARY"

private const val DEFAULT_EVAL_DIR = "app/src/test/resources/transcription_eval"

/**
 * Default candidate models: the two Gemini entries the live model catalog
 * ([com.trevornk.ramblr.ModelCatalogStore.CATALOG_URL]) currently offers — RECOMMENDED and GOOD.
 * These are what a user can actually pick in the app, which is the only list worth benchmarking.
 */
private val DEFAULT_MODELS = listOf("gemini-3.1-flash-lite", "gemini-3.5-flash")

/** Per-call ceiling. The production OkHttp client has its own timeouts; this latch bound only
 *  guarantees the benchmark can never wedge forever waiting on a callback that never fires. */
private const val CALL_TIMEOUT_SECONDS = 180L

// ---------------------------------------------------------------------- pure configuration

/** A fully validated, ready-to-execute benchmark configuration. Contains no secret. */
data class BenchmarkConfig(
    val evalDir: File,
    val manifestFile: File,
    val manifestChecksum: String,
    val fixtures: List<TranscriptionEvalManifest.Fixture>,
    val modelIds: List<String>,
    val repetitions: Int,
    val callTimeoutSeconds: Long,
    /** Vocabulary terms interpolated into the production transcription prompt (see [VOCABULARY_ENV]). */
    val vocabularyTerms: List<String> = emptyList(),
) {
    val totalCalls: Int get() = fixtures.size * modelIds.size * repetitions
}

/**
 * Raw, unvalidated inputs — deliberately a plain data class of strings so unit tests can drive
 * [resolveConfig] without setting process environment variables or touching the network.
 * [apiKeyPresent] is a boolean, not the key: the key never enters this layer at all.
 */
data class BenchmarkArgs(
    val apiKeyPresent: Boolean,
    val modelsRaw: String?,
    val repetitionsRaw: String?,
    val evalDirRaw: String?,
    val vocabularyRaw: String? = null,
)

/**
 * The entire "should this run start, and with what?" decision as a **pure function**: no network,
 * no environment reads, no output. Returns the config or throws
 * [TranscriptionEvalManifest.ManifestException] listing every problem at once.
 */
fun resolveConfig(args: BenchmarkArgs): BenchmarkConfig {
    val problems = mutableListOf<String>()

    if (!args.apiKeyPresent) {
        problems.add("$API_KEY_ENV is not set — this tool calls the real Gemini API and needs a key")
    }

    val repetitions = when (val raw = args.repetitionsRaw?.trim()?.takeIf { it.isNotEmpty() }) {
        null -> 1
        else -> raw.toIntOrNull().also {
            if (it == null) problems.add("$REPETITIONS_ENV must be an integer, got '$raw'")
            else if (it < 1) problems.add("$REPETITIONS_ENV must be at least 1, got $it")
        } ?: 1
    }

    // An explicitly-set-but-empty override is an error, not a cue to fall back to the defaults:
    // "GEMINI_TRANSCRIPTION_MODELS=" almost certainly means a broken shell expansion, and
    // silently benchmarking a different model set than the operator believes they configured
    // produces results that look fine and mean nothing.
    val modelIds: List<String> = when (val raw = args.modelsRaw) {
        null -> DEFAULT_MODELS
        else -> raw.split(",").map { it.trim() }.filter { it.isNotEmpty() }
    }
    problems.addAll(TranscriptionEvalManifest.validateModelIds(modelIds))

    // Production always sends the user's personal vocabulary in the transcription prompt when
    // prefs hold any terms, and prefs are seeded with VocabularyTerms.DEFAULTS on first run. An
    // unset override therefore means "what a real user actually has", not "no terms" — omitting
    // them would benchmark a prompt no shipped build ever sends. The comma-separated env value is
    // routed through the production parser (via its newline wire format) so trimming, blank-drop
    // and case-insensitive dedupe match the app exactly rather than being reimplemented here.
    val vocabularyTerms: List<String> = when (val raw = args.vocabularyRaw) {
        null -> VocabularyTerms.DEFAULTS
        else -> VocabularyTerms.parse(raw.split(",").joinToString("\n"))
    }

    val evalDir = File(args.evalDirRaw?.trim()?.takeIf { it.isNotEmpty() } ?: DEFAULT_EVAL_DIR)
    val manifestFile = File(evalDir, "manifest.json")
    var fixtures: List<TranscriptionEvalManifest.Fixture> = emptyList()
    var checksum = ""

    if (!manifestFile.isFile) {
        problems.add("Manifest not found at ${manifestFile.path} (run this from the repo root, or set $EVAL_DIR_ENV)")
    } else {
        val document = manifestFile.readText()
        checksum = TranscriptionEvalManifest.checksum(document)
        try {
            fixtures = TranscriptionEvalManifest.parse(document).fixtures
            problems.addAll(TranscriptionEvalManifest.validateFixtures(fixtures, evalDir))
            if (fixtures.isEmpty()) {
                problems.add(
                    "Manifest at ${manifestFile.path} declares no fixtures. The checked-in manifest is a " +
                        "placeholder — add your own audio fixtures first (see ${evalDir.path}/README.md)."
                )
            }
        } catch (e: TranscriptionEvalManifest.ManifestException) {
            problems.add(e.message ?: "Manifest failed to parse")
        }
    }

    if (problems.isNotEmpty()) {
        throw TranscriptionEvalManifest.ManifestException(
            "Cannot start the transcription benchmark (${problems.size} problem(s)):\n" +
                problems.joinToString("\n") { "  - $it" }
        )
    }

    return BenchmarkConfig(
        evalDir = evalDir,
        manifestFile = manifestFile,
        manifestChecksum = checksum,
        fixtures = fixtures,
        modelIds = modelIds,
        repetitions = repetitions,
        callTimeoutSeconds = CALL_TIMEOUT_SECONDS,
        vocabularyTerms = vocabularyTerms,
    )
}

// ---------------------------------------------------------------------- per-call results

/** One (fixture, model, repetition) attempt. Exactly one of [transcript]/[error] is non-null. */
data class CallOutcome(
    val fixtureId: String,
    val modelId: String,
    val repetition: Int,
    val transcript: String?,
    val error: String?,
    val latencyMs: Long,
) {
    val succeeded: Boolean get() = transcript != null
}

/** Coarse failure buckets for the report's failure-category table. Pattern-matched on the error
 *  text because that's all the production Result type exposes — no status code survives it. */
fun categorizeFailure(error: String?): String = when {
    error == null -> "none"
    // Checked before the generic buckets: this one is produced locally by the production size
    // gate, not by the API, so it must never be lumped in with a transport failure.
    error.contains("too large for Gemini", ignoreCase = true) -> "skipped: exceeds inline audio limit"
    error.contains("timeout", ignoreCase = true) || error.contains("timed out", ignoreCase = true) -> "timeout"
    error.contains("quota", ignoreCase = true) || error.contains("rate limit", ignoreCase = true) ||
        error.contains("RESOURCE_EXHAUSTED", ignoreCase = true) -> "quota/rate-limit"
    error.contains("permission", ignoreCase = true) || error.contains("API key", ignoreCase = true) ||
        error.contains("UNAUTHENTICATED", ignoreCase = true) -> "auth"
    error.contains("No text content", ignoreCase = true) || error.contains("No candidates", ignoreCase = true) ||
        error.contains("safety", ignoreCase = true) -> "empty/blocked response"
    error.contains("Invalid Gemini endpoint", ignoreCase = true) || error.contains("not found", ignoreCase = true) -> "bad model/endpoint"
    error.contains("Unable to resolve host", ignoreCase = true) || error.contains("connect", ignoreCase = true) -> "network"
    else -> "other"
}

/**
 * Mirrors production's pre-flight inline-audio size gate
 * ([WhisperAccessibilityService]'s `ProviderKind.GEMINI` branch, which calls
 * [GeminiTranscriberClient.canInlineAudio] on the PCM length and falls through to the next
 * transcription candidate above the threshold). A fixture larger than
 * [GeminiTranscriberClient.MAX_INLINE_PCM_BYTES] would never reach Gemini on a real device, so
 * scoring it here would report accuracy for a call production refuses to make. Returns the
 * production-shaped rejection reason, or null when the clip is within the inline budget.
 */
fun inlineAudioRejection(pcmBytes: Long): String? =
    if (GeminiTranscriberClient.canInlineAudio(pcmBytes)) null
    else "Recording too large for Gemini transcription ($pcmBytes bytes > " +
        "${GeminiTranscriberClient.MAX_INLINE_PCM_BYTES} inline limit); production would fall " +
        "through to the next transcription candidate rather than call Gemini"

/** Blocking wrapper around the async production client: enqueues the real call and waits on a
 *  latch with a hard bound, so one hung callback can't stall a whole benchmark run. */
private fun transcribeBlocking(
    pcmFile: File,
    apiKey: String,
    model: String,
    timeoutSeconds: Long,
    vocabularyTerms: List<String>,
): Pair<GeminiTranscriberClient.Result, Long> {
    val latch = CountDownLatch(1)
    // AtomicReference rather than a plain var: the callback runs on an OkHttp dispatcher thread,
    // so the write must be safely published to the thread awaiting the latch.
    val result = java.util.concurrent.atomic.AtomicReference<GeminiTranscriberClient.Result?>(null)
    val holder = InFlightCall()
    val start = System.nanoTime()

    GeminiTranscriberClient.transcribe(
        pcmFile = pcmFile,
        apiKey = apiKey,
        model = model,
        cancelHolder = holder,
        // Production interpolates the user's vocabulary into the transcription prompt (#114
        // part 2); passing it here keeps the benchmarked prompt byte-identical to the shipped one.
        vocabularyTerms = vocabularyTerms,
        callback = { r ->
            result.set(r)
            latch.countDown()
        },
    )

    val completed = latch.await(timeoutSeconds, TimeUnit.SECONDS)
    val latencyMs = (System.nanoTime() - start) / 1_000_000
    if (!completed) {
        holder.cancel()
        return GeminiTranscriberClient.Result(null, "Timed out after ${timeoutSeconds}s waiting for the transcription callback") to latencyMs
    }
    val settled = result.get()
        ?: GeminiTranscriberClient.Result(null, "Callback fired without producing a Result")
    return settled to latencyMs
}

// ---------------------------------------------------------------------- reporting

/** Everything needed to render a report, computed offline from [CallOutcome]s. */
data class BenchmarkReport(
    val commitSha: String,
    val timestampUtc: String,
    val config: BenchmarkConfig,
    val outcomes: List<CallOutcome>,
)

private fun currentCommitSha(): String = try {
    val process = ProcessBuilder("git", "rev-parse", "HEAD").redirectErrorStream(true).start()
    val out = process.inputStream.bufferedReader().readText().trim()
    if (process.waitFor() == 0 && out.isNotEmpty()) out else "unknown"
} catch (e: Exception) {
    "unknown (${e.message})"
}

/** Scores a model's successful calls against the fixture references. */
private fun scoresFor(
    model: String,
    outcomes: List<CallOutcome>,
    fixturesById: Map<String, TranscriptionEvalManifest.Fixture>,
): List<TranscriptionMetrics.ClipScore> =
    outcomes.filter { it.modelId == model && it.succeeded }.mapNotNull { outcome ->
        fixturesById[outcome.fixtureId]?.let { fixture ->
            TranscriptionMetrics.score(fixture.referenceText, outcome.transcript!!)
        }
    }

fun renderMarkdown(report: BenchmarkReport): String {
    val cfg = report.config
    val fixturesById = cfg.fixtures.associateBy { it.id }
    val sb = StringBuilder()

    sb.append("# Gemini transcription benchmark (#129)\n\n")
    sb.append("- Commit: `${report.commitSha}`\n")
    sb.append("- Run at (UTC): `${report.timestampUtc}`\n")
    sb.append("- Models: ${cfg.modelIds.joinToString(", ") { "`$it`" }}\n")
    sb.append("- Repetitions per fixture/model: ${cfg.repetitions}\n")
    sb.append("- Per-call timeout: ${cfg.callTimeoutSeconds}s (plus the production OkHttp client's own timeouts)\n")
    sb.append("- Manifest: `${cfg.manifestFile.path}` (sha256 `${cfg.manifestChecksum}`)\n")
    sb.append("- Fixtures: ${cfg.fixtures.size}, total calls: ${cfg.totalCalls}\n")
    sb.append(
        "- Vocabulary terms in prompt: ${cfg.vocabularyTerms.size}" +
            if (cfg.vocabularyTerms.isEmpty()) " (explicitly disabled)\n"
            else " — ${cfg.vocabularyTerms.joinToString(", ")}\n"
    )
    sb.append("- Inline-audio gate: fixtures over ${GeminiTranscriberClient.MAX_INLINE_PCM_BYTES} PCM bytes are skipped, as production does\n\n")
    sb.append(
        "> Cost accounting is unavailable: `GeminiTranscriberClient.Result` discards the response's " +
            "`usageMetadata`, so token counts are not observable from the production path. No cost " +
            "figures are estimated here — that would be fabrication.\n\n"
    )

    sb.append("## Summary by model\n\n")
    sb.append("| Model | Calls | Success | micro-WER | micro-CER | macro-WER | Strict exact | Norm exact | p50 ms | p95 ms | mean ms |\n")
    sb.append("|---|---|---|---|---|---|---|---|---|---|---|\n")
    for (model in cfg.modelIds) {
        val modelOutcomes = report.outcomes.filter { it.modelId == model }
        val agg = TranscriptionMetrics.aggregate(scoresFor(model, report.outcomes, fixturesById))
        val latency = TranscriptionMetrics.latencyStats(modelOutcomes.filter { it.succeeded }.map { it.latencyMs })
        val successRate = if (modelOutcomes.isEmpty()) 0.0
        else modelOutcomes.count { it.succeeded }.toDouble() / modelOutcomes.size
        sb.append(
            "| `$model` | ${modelOutcomes.size} | ${"%.1f%%".format(successRate * 100)} | " +
                "${"%.4f".format(agg.microWer)} | ${"%.4f".format(agg.microCer)} | ${"%.4f".format(agg.macroWer)} | " +
                "${"%.1f%%".format(agg.strictExactMatchRate * 100)} | ${"%.1f%%".format(agg.normalizedExactMatchRate * 100)} | " +
                "${latency.p50Ms ?: "-"} | ${latency.p95Ms ?: "-"} | ${latency.meanMs?.let { "%.0f".format(it) } ?: "-"} |\n"
        )
    }
    sb.append("\nmicro-WER is the headline figure (summed edits / summed reference words). macro-WER ")
    sb.append("(the unweighted mean of per-clip rates) is shown only so corpus skew is visible.\n\n")

    sb.append("## Edit breakdown by model\n\n")
    sb.append("| Model | Substitutions | Deletions | Insertions | Ref words | Ref chars |\n|---|---|---|---|---|---|\n")
    for (model in cfg.modelIds) {
        val agg = TranscriptionMetrics.aggregate(scoresFor(model, report.outcomes, fixturesById))
        sb.append("| `$model` | ${agg.totalSubstitutions} | ${agg.totalDeletions} | ${agg.totalInsertions} | ${agg.totalReferenceWords} | ${agg.totalReferenceChars} |\n")
    }
    sb.append("\n")

    val failures = report.outcomes.filter { !it.succeeded }
    sb.append("## Failure categories\n\n")
    if (failures.isEmpty()) {
        sb.append("No failed calls.\n\n")
    } else {
        sb.append("| Category | Count |\n|---|---|\n")
        failures.groupingBy { categorizeFailure(it.error) }.eachCount()
            .toList().sortedByDescending { it.second }
            .forEach { (category, count) -> sb.append("| $category | $count |\n") }
        sb.append("\n")
    }

    sb.append("## Per-call detail\n\n")
    for (fixture in cfg.fixtures) {
        sb.append("### `${fixture.id}` — ${fixture.scenarios.joinToString(", ")} (${fixture.durationMs} ms, ${fixture.language})\n\n")
        sb.append("**Reference:**\n\n```\n${fixture.referenceText}\n```\n\n")
        report.outcomes.filter { it.fixtureId == fixture.id }.forEach { outcome ->
            sb.append("**`${outcome.modelId}` rep ${outcome.repetition}** — ${outcome.latencyMs} ms\n\n")
            if (outcome.transcript != null) {
                val score = TranscriptionMetrics.score(fixture.referenceText, outcome.transcript)
                sb.append("```\n${outcome.transcript}\n```\n\n")
                sb.append(
                    "_WER ${"%.4f".format(score.wer)} (S${score.wordEdits.substitutions}/D${score.wordEdits.deletions}/I${score.wordEdits.insertions}), " +
                        "CER ${"%.4f".format(score.cer)}, strict exact: ${score.strictExactMatch}, normalized exact: ${score.normalizedExactMatch}_\n\n"
                )
            } else {
                sb.append("_Error (${categorizeFailure(outcome.error)}): ${outcome.error}_\n\n")
            }
        }
        sb.append("---\n\n")
    }
    return sb.toString()
}

fun renderJson(report: BenchmarkReport): String {
    val cfg = report.config
    val fixturesById = cfg.fixtures.associateBy { it.id }
    val root = JSONObject()
    root.put("commitSha", report.commitSha)
    root.put("timestampUtc", report.timestampUtc)
    root.put("models", JSONArray(cfg.modelIds))
    root.put("repetitions", cfg.repetitions)
    root.put("callTimeoutSeconds", cfg.callTimeoutSeconds)
    root.put("manifestPath", cfg.manifestFile.path)
    root.put("manifestSha256", cfg.manifestChecksum)
    root.put("fixtureCount", cfg.fixtures.size)
    root.put("totalCalls", cfg.totalCalls)
    root.put("vocabularyTerms", JSONArray(cfg.vocabularyTerms))
    root.put("inlineAudioLimitBytes", GeminiTranscriberClient.MAX_INLINE_PCM_BYTES)
    root.put("costAccounting", "unavailable: GeminiTranscriberClient.Result discards usageMetadata")

    val perModel = JSONArray()
    for (model in cfg.modelIds) {
        val modelOutcomes = report.outcomes.filter { it.modelId == model }
        val agg = TranscriptionMetrics.aggregate(scoresFor(model, report.outcomes, fixturesById))
        val latency = TranscriptionMetrics.latencyStats(modelOutcomes.filter { it.succeeded }.map { it.latencyMs })
        perModel.put(
            JSONObject()
                .put("model", model)
                .put("calls", modelOutcomes.size)
                .put("successes", modelOutcomes.count { it.succeeded })
                .put("successRate", if (modelOutcomes.isEmpty()) 0.0 else modelOutcomes.count { it.succeeded }.toDouble() / modelOutcomes.size)
                .put("microWer", agg.microWer)
                .put("microCer", agg.microCer)
                .put("macroWer", agg.macroWer)
                .put("macroCer", agg.macroCer)
                .put("substitutions", agg.totalSubstitutions)
                .put("deletions", agg.totalDeletions)
                .put("insertions", agg.totalInsertions)
                .put("referenceWords", agg.totalReferenceWords)
                .put("referenceChars", agg.totalReferenceChars)
                .put("strictExactMatchRate", agg.strictExactMatchRate)
                .put("normalizedExactMatchRate", agg.normalizedExactMatchRate)
                .put("latencyP50Ms", latency.p50Ms ?: JSONObject.NULL)
                .put("latencyP95Ms", latency.p95Ms ?: JSONObject.NULL)
                .put("latencyMeanMs", latency.meanMs ?: JSONObject.NULL)
                .put("latencyMinMs", latency.minMs ?: JSONObject.NULL)
                .put("latencyMaxMs", latency.maxMs ?: JSONObject.NULL)
        )
    }
    root.put("perModel", perModel)

    val failureCategories = JSONObject()
    report.outcomes.filter { !it.succeeded }.groupingBy { categorizeFailure(it.error) }.eachCount()
        .forEach { (category, count) -> failureCategories.put(category, count) }
    root.put("failureCategories", failureCategories)

    val calls = JSONArray()
    report.outcomes.forEach { outcome ->
        val obj = JSONObject()
            .put("fixtureId", outcome.fixtureId)
            .put("model", outcome.modelId)
            .put("repetition", outcome.repetition)
            .put("latencyMs", outcome.latencyMs)
            .put("transcript", outcome.transcript ?: JSONObject.NULL)
            .put("error", outcome.error ?: JSONObject.NULL)
            .put("failureCategory", categorizeFailure(outcome.error))
        fixturesById[outcome.fixtureId]?.let { fixture ->
            obj.put("reference", fixture.referenceText)
            if (outcome.transcript != null) {
                val score = TranscriptionMetrics.score(fixture.referenceText, outcome.transcript)
                obj.put("wer", score.wer)
                obj.put("cer", score.cer)
                obj.put("substitutions", score.wordEdits.substitutions)
                obj.put("deletions", score.wordEdits.deletions)
                obj.put("insertions", score.wordEdits.insertions)
                obj.put("strictExactMatch", score.strictExactMatch)
                obj.put("normalizedExactMatch", score.normalizedExactMatch)
            }
        }
        calls.put(obj)
    }
    root.put("calls", calls)
    return root.toString(2)
}

// ---------------------------------------------------------------------- entry point

fun main() {
    val apiKey = System.getenv(API_KEY_ENV)

    val config = try {
        resolveConfig(
            BenchmarkArgs(
                // Only presence is passed in; the key itself never reaches the pure config layer,
                // so it can never end up in a config-derived log line or report field.
                apiKeyPresent = !apiKey.isNullOrBlank(),
                modelsRaw = System.getenv(MODELS_ENV),
                repetitionsRaw = System.getenv(REPETITIONS_ENV),
                evalDirRaw = System.getenv(EVAL_DIR_ENV),
                vocabularyRaw = System.getenv(VOCABULARY_ENV),
            )
        )
    } catch (e: TranscriptionEvalManifest.ManifestException) {
        System.err.println(e.message)
        exitProcess(1)
    }
    // Non-null by construction: resolveConfig rejects a missing key before returning.
    val key = apiKey!!

    println(
        "Running ${config.fixtures.size} fixture(s) x ${config.modelIds.size} model(s) x " +
            "${config.repetitions} repetition(s) = ${config.totalCalls} call(s) against the real " +
            "Gemini API. This uploads audio to Google and spends real credits."
    )
    println(
        "Prompt vocabulary: ${config.vocabularyTerms.size} term(s)" +
            if (config.vocabularyTerms.isEmpty()) " (explicitly disabled via $VOCABULARY_ENV)."
            else " — ${config.vocabularyTerms.joinToString(", ")}"
    )

    val outcomes = mutableListOf<CallOutcome>()
    for (fixture in config.fixtures) {
        val wavFile = File(config.evalDir, fixture.audioPath)
        val pcmFile = try {
            WavPcm.extractPcmToTempFile(wavFile)
        } catch (e: WavPcm.UnsupportedWavException) {
            System.err.println("Skipping fixture '${fixture.id}': ${e.message}")
            config.modelIds.forEach { model ->
                for (rep in 1..config.repetitions) {
                    outcomes.add(CallOutcome(fixture.id, model, rep, null, "WAV decode failed: ${e.message}", 0))
                }
            }
            continue
        }
        try {
            // Production's size gate runs on the raw PCM, before any Gemini call is made.
            val rejection = inlineAudioRejection(pcmFile.length())
            if (rejection != null) {
                System.err.println("Skipping fixture '${fixture.id}': $rejection")
                config.modelIds.forEach { model ->
                    for (rep in 1..config.repetitions) {
                        outcomes.add(CallOutcome(fixture.id, model, rep, null, rejection, 0))
                    }
                }
                continue
            }
            for (model in config.modelIds) {
                for (rep in 1..config.repetitions) {
                    print("  ${fixture.id} x $model rep $rep ... ")
                    val (result, latencyMs) =
                        transcribeBlocking(pcmFile, key, model, config.callTimeoutSeconds, config.vocabularyTerms)
                    println(if (result.error == null) "ok (${latencyMs}ms)" else "error: ${result.error} (${latencyMs}ms)")
                    outcomes.add(CallOutcome(fixture.id, model, rep, result.text, result.error, latencyMs))
                }
            }
        } finally {
            pcmFile.delete()
        }
    }

    val report = BenchmarkReport(
        commitSha = currentCommitSha(),
        timestampUtc = Instant.now().toString(),
        config = config,
        outcomes = outcomes,
    )

    val stamp = report.timestampUtc.replace(":", "-").replace(".", "-")
    val outDir = File("eval-reports")
    outDir.mkdirs()
    val mdFile = File(outDir, "transcription-benchmark-$stamp.md")
    val jsonFile = File(outDir, "transcription-benchmark-$stamp.json")
    mdFile.writeText(renderMarkdown(report))
    jsonFile.writeText(renderJson(report))
    println("Report written to ${mdFile.path} and ${jsonFile.path}")
}
