package com.trevornk.ramblr

import android.content.Context
import java.io.File
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
)

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
     */
    fun log(
        context: Context,
        correlationId: String,
        transcription: BenchmarkStage? = null,
        cleanup: BenchmarkStage? = null,
        rawTextLength: Int? = null,
        cleanedTextLength: Int? = null,
        pipeline: PipelineStage? = null,
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
            )
            val file = logFile(context)
            rotateIfNeeded(file)
            file.appendText(line + "\n")
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
        return root.toString()
    }

    private fun BenchmarkStage.toJson(): JSONObject = JSONObject()
        .put("provider", provider)
        .put("model", model)
        .put("latencyMs", latencyMs)
        .put("success", success)

    private fun PipelineStage.toJson(): JSONObject = JSONObject()
        .put("stopToDrainMs", stopToDrainMs ?: JSONObject.NULL)
        .put("injectionAttemptMs", injectionAttemptMs ?: JSONObject.NULL)
        .put("injectMethod", injectMethod ?: JSONObject.NULL)
        .put("totalMs", totalMs ?: JSONObject.NULL)

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
