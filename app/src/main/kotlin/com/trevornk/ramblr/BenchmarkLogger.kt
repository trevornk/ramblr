package com.trevornk.ramblr

import android.content.Context
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong
import org.json.JSONObject

/**
 * One transcription or cleanup outcome, as attached to a [BenchmarkLogger.log] call. `model` is
 * the actual model id that served the request (not just the provider kind) so a JSONL consumer
 * can tell e.g. "gpt-4o-transcribe" apart from "whisper-1" on the same [ProviderKind.OPENAI].
 */
data class BenchmarkStage(
    val provider: String,
    val model: String,
    val latencyMs: Long,
    val success: Boolean,
    /** #109 benchmarking follow-up: whether this cloud transcription upload used the opt-in
     *  compressed AAC/M4A path (true) or the original raw-WAV path (false). Null for local
     *  transcription, which never uploads anything and has no compression concept. Lets a JSONL
     *  consumer actually A/B whether the compressed-upload toggle affects latency/quality on real
     *  usage data, instead of only being able to infer it indirectly from wall-clock timing next
     *  to a separately-remembered "I had it on" recollection. */
    val compressedUpload: Boolean? = null,
    /**
     * Why this stage failed, or null on success (#138). The provider's own message is the whole
     * point: before this existed a failure was recorded as a bare `"success": false`, so 21
     * failures across 910 real records carried zero diagnostic signal and it was impossible to
     * tell a bad key from a rate limit from a genuine timeout after the fact. The detail already
     * existed on [CleanupStepOutcome] and was written to logcat -- which is a ring buffer that
     * rotates within hours, while THIS file is the durable artifact. Exactly backwards.
     *
     * Always pass this through [sanitizeError]: provider error envelopes can echo request
     * content, and this log is deliberately length-only (see [BenchmarkLogger]'s privacy note).
     */
    val error: String? = null,
)

/**
 * Maximum length of a stored [BenchmarkStage.error]. Long enough for a status line plus a real
 * provider message, short enough that a response body echoing a long dictation can't be
 * reconstructed from it.
 */
const val MAX_ERROR_DETAIL_CHARS = 200

/**
 * Scrubs a provider failure message for the length-only benchmark log (#138).
 *
 * [BenchmarkLogger] stores lengths, timings and model ids -- never transcript content -- which is
 * why raw/cleaned text pairs live in the separate [QualityLogger] instead, itself gated behind an
 * off-by-default toggle (`quality_log_enabled`, #191). A provider's
 * error body can quote the request that caused it.
 *
 * **What this does and does not guarantee.** Collapsing whitespace is a correctness guarantee:
 * JSONL is line-oriented, so an embedded newline would split one record into two unparseable
 * fragments. Truncation, however, only *bounds* content exposure to [MAX_ERROR_DETAIL_CHARS] --
 * it does not eliminate it, because a body that echoes the request still leaks its first
 * [MAX_ERROR_DETAIL_CHARS] characters. There is no cap that is simultaneously useful for
 * diagnosis and provably content-free, since the useful part ("invalid x-api-key", "rate limit
 * exceeded") and the risky part (an echoed prompt) arrive in the same string.
 *
 * That tradeoff is acceptable here because this file is app-private and never uploaded, but it
 * is a real consideration before sharing a benchmark log. If zero content risk is ever required,
 * the fix is to log the status code and exception class only and drop provider prose entirely --
 * strictly less diagnostic value, which is the whole reason it isn't the default.
 */
fun sanitizeError(raw: String?): String? {
    val collapsed = raw?.replace(Regex("\\s+"), " ")?.trim().orEmpty()
    if (collapsed.isEmpty()) return null
    return if (collapsed.length <= MAX_ERROR_DETAIL_CHARS) collapsed
    else collapsed.take(MAX_ERROR_DETAIL_CHARS) + "..."
}

/**
 * End-to-end, user-perceived pipeline timing for one dictation (#115). Unlike [BenchmarkStage]
 * (which times a single provider round-trip), this is the "speech ended -> text appears in the
 * focused field" timeline Trevor actually cares about optimizing before any #107/#109 tuning
 * decision -- every field here is elapsed milliseconds *from the stop tap*, not a duration of one
 * sub-stage, so they're directly comparable to each other and to [BenchmarkStage.latencyMs]
 * without a reader needing to reconstruct absolute timestamps first.
 *
 * All fields are individually optional: a given dictation may fail or short-circuit before later
 * stages run (e.g. no speech detected never reaches injection), and this must degrade gracefully
 * to partial data rather than requiring a caller fabricate a value for a stage that never
 * happened.
 *
 * Transcription/cleanup stage start/end are already covered by [BenchmarkStage.latencyMs] on the
 * existing `transcription`/`cleanup` entries sharing this line's correlationId -- deliberately
 * not duplicated here.
 */
data class PipelineStage(
    /** Stop tap -> the recording reader thread finished draining/releasing the AudioRecord and
     *  handed off the captured PCM (i.e. [WhisperAccessibilityService.onRecordingFinished]). */
    val stopToDrainMs: Long? = null,
    /** Stop tap -> the final [WhisperAccessibilityService.injectText] attempt started (i.e. the
     *  cleaned/raw text is decided and injection into the focused field begins). */
    val injectionAttemptMs: Long? = null,
    /** Which [InjectMethod] this dictation actually resolved to -- DIRECT (typed straight into the
     *  node), FROM_CLIPBOARD (pasted), or NONE (clipboard-only fallback, user must paste manually).
     *  Stored as the enum's plain name string so this schema never takes a hard dependency on
     *  [InjectMethod] living in this module. */
    val injectMethod: String? = null,
    /** Stop tap -> injection fully resolved (success or clipboard fallback) -- the real
     *  end-to-end number this whole stage exists to measure. */
    val totalMs: Long? = null,
)

/**
 * One cloud-live transcription attempt's timing + outcome (#233 Phase 1 item 10).
 *
 * Exists because the live->batch fallback is deliberately LOSSLESS: when a live attempt fails,
 * the preserved local recording runs the ordinary batch chain and the user gets the same text
 * delivered the same way. Correct product behavior, but it makes a live failure *invisible* --
 * on a real device there is otherwise no way to tell whether live actually served a dictation or
 * whether it silently fell back on every single utterance. Without this block the whole device
 * acceptance gate ("prove post-stop latency, setup timing, interim behavior, mid-stream
 * network-drop fallback") is unfalsifiable, because no evidence survives the session.
 *
 * Every field is a derived DURATION or an enum name, never a wall-clock absolute: the raw
 * [CloudLiveTiming] marks are only useful after a reader subtracts them, and the same
 * "directly comparable elapsed ms" convention [PipelineStage] established is what makes a live
 * line readable next to a batch one.
 *
 * Deliberately carries no transcript content of any kind -- not the interim text, not the final
 * text, not a text sample. See [BenchmarkLogger]'s privacy note; raw/cleaned pairs live in
 * [QualityLogger] behind its own off-by-default toggle.
 *
 * Durations are NOT clamped to zero. A negative value means the device's wall clock moved
 * backwards mid-attempt, which is real signal a reader should see rather than have hidden.
 */
data class CloudLiveStage(
    /** [CloudLiveOutcome]'s enum name -- how this attempt actually ended. Stored as a plain
     *  string so this schema never takes a hard dependency on the enum living in this module,
     *  mirroring [PipelineStage.injectMethod]. */
    val outcome: String,
    /** True when the preserved recording went to the unchanged batch pipeline instead of live
     *  text being delivered. The single field that answers "did live actually serve this?". */
    val fellBackToBatch: Boolean,
    /** [CloudLiveFailureReason]'s enum name when the live session reported a terminal failure,
     *  else null -- so `NETWORK_ERROR` is distinguishable from `SETUP_TIMEOUT` from a live
     *  success whose final was simply unusable. */
    val failureReason: String? = null,
    /** Connection setup: `connectStartedAtMs` -> `setupCompletedAtMs`. Null when setup never
     *  completed (e.g. SETUP_TIMEOUT), which is itself the answer. */
    val setupMs: Long? = null,
    /** Time to first interim: `connectStartedAtMs` -> `firstInterimAtMs`. Null when the session
     *  never emitted an interim. */
    val firstInterimMs: Long? = null,
    /** End of audio to final: `activityEndedAtMs` -> `finalAtMs`. The headline number the whole
     *  live feature exists to reduce -- how long after the user stops talking the text lands. */
    val endOfAudioToFinalMs: Long? = null,
    /** The live session's own failure message, always routed through [sanitizeError] first
     *  (same bounded-exposure tradeoff [BenchmarkStage.error] documents). Null on success. */
    val error: String? = null,
)

/**
 * Durable, append-only JSONL benchmark log for A/B testing transcription/cleanup provider+model
 * combinations across real-world dictation usage (#100). One line per completed dictation, each
 * line a self-contained JSON object -- deliberately NOT a JSON array, so a crash or a concurrent
 * read mid-write can never corrupt lines already flushed, and a consumer can stream-parse it.
 *
 * Lives in app-private storage ([Context.getFilesDir]), never external storage: no storage
 * permission is required and the file survives with the rest of app data across the exact
 * lifetime Trevor cares about (multiple real dictation sessions across a day), but is still fully
 * removed on uninstall.
 *
 * Privacy: only lengths/timings/model ids are ever logged here, never the actual transcribed or
 * cleaned text -- see [rawTextLength]/[cleanedTextLength] on [log].
 *
 * Every public entry point wraps its file I/O in `runCatching` (see [log]'s kdoc for why) so a
 * full disk, a concurrent-write hiccup, or any other I/O failure here can never crash or block
 * the real dictation/cleanup path this is only ever meant to observe.
 */
object BenchmarkLogger {

    private const val FILE_NAME = "benchmark_log.jsonl"

    /**
     * All file I/O ([log]'s append + [rotateIfNeeded]'s up-to-[ROTATE_AT_BYTES] read/rewrite)
     * runs here instead of on the caller's thread (M1 audit, 2026-08-26): finishInjection calls
     * [log] on the MAIN thread, and a rotation there synchronously read 3MB and rewrote the file
     * mid-injection. Logging is fire-and-forget, so callers never need the result -- but write
     * ORDER matters for a line-oriented log, which is why this is a single thread and not a
     * pool: submissions execute strictly in submission order. Daemon so a lingering queued write
     * can never hold the process alive.
     */
    private val ioExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "BenchmarkLogger-io").apply { isDaemon = true }
    }

    /** Once the log file exceeds this size, it's rotated down to [KEEP_BYTES_AFTER_ROTATION] of
     *  its newest (tail) content rather than growing unbounded across many real-world sessions. */
    const val ROTATE_AT_BYTES = 3 * 1024 * 1024L

    /** How much of the file (from the end, i.e. newest entries) survives a rotation. Comfortably
     *  under [ROTATE_AT_BYTES] so a rotation doesn't just immediately re-trigger on the next line. */
    const val KEEP_BYTES_AFTER_ROTATION = 2 * 1024 * 1024L

    // Monotonically increasing fallback correlation id for call sites with no existing per-
    // dictation identifier to reuse. Callers that already have one (e.g. WhisperAccessibilityService's
    // per-dictation `token`) should pass that instead so a transcription line and its cleanup line
    // share the same correlationId.
    private val counter = AtomicLong(0)

    /** Generates a fallback correlationId. Prefer reusing an existing per-dictation identifier
     *  (e.g. the `token: Int` already threaded through WhisperAccessibilityService) over calling
     *  this, so a single dictation's transcription and cleanup log lines correlate. */
    fun nextCorrelationId(): String = "bm-${System.currentTimeMillis()}-${counter.incrementAndGet()}"

    /**
     * Appends one JSONL line describing a completed dictation's transcription and/or cleanup
     * outcome. Either [transcription] or [cleanup] (or both) may be null -- e.g. a pure local
     * flow that never ran cleanup, or a cleanup-only call site that has no transcription
     * timing of its own to report.
     *
     * All file I/O is wrapped in `runCatching`: this is a diagnostics-only side channel for
     * later analysis, and must never be allowed to crash or block Trevor's actual dictation --
     * a full disk or a transient I/O error here should be silently swallowed, not surfaced.
     * Since M1 the I/O also runs asynchronously on [ioExecutor], so a caller on the main thread
     * (finishInjection's pipeline line) pays only for building the JSON line, never for the
     * append or a 3MB rotation. Timestamp and line content are captured synchronously at call
     * time; only the disk write is deferred.
     */
    fun log(
        context: Context,
        correlationId: String,
        transcription: BenchmarkStage? = null,
        cleanup: BenchmarkStage? = null,
        rawTextLength: Int? = null,
        cleanedTextLength: Int? = null,
        pipeline: PipelineStage? = null,
        cloudLive: CloudLiveStage? = null,
    ) {
        runCatching {
            val line = buildLine(
                timestamp = System.currentTimeMillis(),
                correlationId = correlationId,
                transcription = transcription,
                cleanup = cleanup,
                rawTextLength = rawTextLength,
                cleanedTextLength = cleanedTextLength,
                pipeline = pipeline,
                cloudLive = cloudLive,
            )
            val file = logFile(context)
            ioExecutor.execute {
                runCatching {
                    rotateIfNeeded(file)
                    file.appendText(line + "\n")
                }
            }
        }
    }

    /** Absolute on-device path of the log file, e.g. for the Advanced screen's share action. */
    fun logFile(context: Context): File = File(context.filesDir, FILE_NAME)

    /** Pure JSONL-line construction, split out from [log] so it's directly unit-testable without
     *  a real [Context]/filesystem. */
    fun buildLine(
        timestamp: Long,
        correlationId: String,
        transcription: BenchmarkStage?,
        cleanup: BenchmarkStage?,
        rawTextLength: Int?,
        cleanedTextLength: Int?,
        pipeline: PipelineStage? = null,
        cloudLive: CloudLiveStage? = null,
    ): String {
        val root = JSONObject()
        root.put("timestamp", timestamp)
        root.put("correlationId", correlationId)
        root.put("transcription", transcription?.toJson() ?: JSONObject.NULL)
        root.put("cleanup", cleanup?.toJson() ?: JSONObject.NULL)
        root.put("rawTextLength", rawTextLength ?: JSONObject.NULL)
        root.put("cleanedTextLength", cleanedTextLength ?: JSONObject.NULL)
        // Additive (#115): older consumers that don't know this key simply never look at it;
        // JSONObject.NULL (not an omitted key) mirrors transcription/cleanup's existing
        // null-vs-missing convention above so every line has a stable, predictable key set.
        root.put("pipeline", pipeline?.toJson() ?: JSONObject.NULL)
        // Additive (#233 item 10), same convention again: a live attempt is rare (opt-in, and
        // only on the IME host), so the overwhelming majority of lines carry a null here.
        root.put("cloudLive", cloudLive?.toJson() ?: JSONObject.NULL)
        return root.toString()
    }

    private fun BenchmarkStage.toJson(): JSONObject = JSONObject()
        .put("provider", provider)
        .put("model", model)
        .put("latencyMs", latencyMs)
        .put("success", success)
        .put("compressedUpload", compressedUpload ?: JSONObject.NULL)
        // Additive (#138), same null-vs-missing convention as the keys above so the 910 existing
        // records stay parseable by any consumer that doesn't know this key.
        .put("error", error ?: JSONObject.NULL)

    private fun PipelineStage.toJson(): JSONObject = JSONObject()
        .put("stopToDrainMs", stopToDrainMs ?: JSONObject.NULL)
        .put("injectionAttemptMs", injectionAttemptMs ?: JSONObject.NULL)
        .put("injectMethod", injectMethod ?: JSONObject.NULL)
        .put("totalMs", totalMs ?: JSONObject.NULL)

    private fun CloudLiveStage.toJson(): JSONObject = JSONObject()
        .put("outcome", outcome)
        .put("fellBackToBatch", fellBackToBatch)
        .put("failureReason", failureReason ?: JSONObject.NULL)
        .put("setupMs", setupMs ?: JSONObject.NULL)
        .put("firstInterimMs", firstInterimMs ?: JSONObject.NULL)
        .put("endOfAudioToFinalMs", endOfAudioToFinalMs ?: JSONObject.NULL)
        .put("error", error ?: JSONObject.NULL)

    /** If [file] is at/over [ROTATE_AT_BYTES], truncates it down to its newest
     *  [KEEP_BYTES_AFTER_ROTATION] bytes, dropping any partial first line left over from the
     *  byte-offset cut so every remaining line is still valid, independently parseable JSON. */
    fun rotateIfNeeded(file: File) {
        if (!file.exists() || file.length() < ROTATE_AT_BYTES) return
        val bytes = file.readBytes()
        val tail = bytes.copyOfRange((bytes.size - KEEP_BYTES_AFTER_ROTATION).coerceAtLeast(0).toInt(), bytes.size)
        val text = String(tail, Charsets.UTF_8)
        val firstNewline = text.indexOf('\n')
        val trimmed = if (firstNewline >= 0 && firstNewline < text.length - 1) text.substring(firstNewline + 1) else text
        file.writeText(trimmed)
    }
}
