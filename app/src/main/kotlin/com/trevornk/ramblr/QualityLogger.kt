package com.trevornk.ramblr

import android.content.Context
import java.io.File
import org.json.JSONObject

/**
 * One transcription or cleanup provider/model attribution, as attached to a [QualityLogger.log]
 * call. Deliberately a subset of [BenchmarkStage] -- no `latencyMs`/`success` here, since HOW FAST
 * a stage ran is [BenchmarkLogger]'s job; this log exists purely to say WHAT produced a given
 * piece of text.
 */
data class QualityStage(
    val provider: String,
    val model: String,
)

/**
 * Durable, append-only JSONL quality log for manually reviewing actual transcription/cleanup
 * *output* (not just speed) across the provider+model combinations Trevor is A/B testing on his
 * device over several days (GH #105). One line per completed dictation stage, each line a
 * self-contained JSON object -- deliberately NOT a JSON array, so a crash or a concurrent read
 * mid-write can never corrupt lines already flushed, and a consumer can stream-parse it.
 *
 * This is [BenchmarkLogger]'s deliberate complement, not a replacement or a merge target:
 * [BenchmarkLogger] intentionally never logs actual dictated text (a considered privacy decision
 * from its original build) and only records lengths/timings. [QualityLogger] is the one place
 * that captures the real raw-transcript-to-cleaned-text pairs Trevor needs to judge output
 * *quality*, correlated with the exact provider+model that produced each one via the same
 * `"tok-$token"` correlation id [BenchmarkLogger] already uses -- so a quality-log line and its
 * corresponding benchmark-log line for the same dictation can be joined by id. The two logs stay
 * fully independent files/formats/classes; this is purely additive.
 *
 * Lives in app-private storage ([Context.getFilesDir]), never external storage: no storage
 * permission is required and the file survives with the rest of app data across the exact
 * lifetime Trevor cares about (several days of active manual A/B testing), but is still fully
 * removed on uninstall.
 *
 * Every public entry point wraps its file I/O in `runCatching` (mirrors [BenchmarkLogger]) so a
 * full disk, a concurrent-write hiccup, or any other I/O failure here can never crash or block
 * the real dictation/cleanup path this is only ever meant to observe.
 */
object QualityLogger {

    private const val FILE_NAME = "quality_log.jsonl"

    /** Once the log file exceeds this size, it's rotated down to [KEEP_BYTES_AFTER_ROTATION] of
     *  its newest (tail) content rather than growing unbounded across many real-world sessions.
     *  Set generously larger than [BenchmarkLogger.ROTATE_AT_BYTES]: this log stores real (but
     *  still small, text-only) raw/cleaned dictation content rather than lengths/timings, and the
     *  goal is Trevor should not lose early-in-the-week entries by day 3 or 4 of a multi-day
     *  manual A/B review -- typical dictation text is short enough that even this generous
     *  MB-scale cap should comfortably hold hundreds to thousands of entries. */
    const val ROTATE_AT_BYTES = 10 * 1024 * 1024L

    /** How much of the file (from the end, i.e. newest entries) survives a rotation. Comfortably
     *  under [ROTATE_AT_BYTES] so a rotation doesn't just immediately re-trigger on the next line. */
    const val KEEP_BYTES_AFTER_ROTATION = 8 * 1024 * 1024L

    /**
     * Appends one JSONL line describing a completed dictation's raw transcription and/or cleaned
     * output. Either [transcription]/[rawText] or [cleanup]/[cleanedText] (or both) may be null --
     * e.g. a transcription-only call site that has no cleanup outcome yet, or a cleanup-only call
     * site logging just the cleaned side of an already-logged transcription.
     *
     * All file I/O is wrapped in `runCatching`: this is a diagnostics-only side channel for
     * later manual review, and must never be allowed to crash or block Trevor's actual dictation
     * -- a full disk or a transient I/O error here should be silently swallowed, not surfaced.
     */
    fun log(
        context: Context,
        correlationId: String,
        transcription: QualityStage? = null,
        cleanup: QualityStage? = null,
        rawText: String? = null,
        cleanedText: String? = null,
    ) {
        runCatching {
            val line = buildLine(
                timestamp = System.currentTimeMillis(),
                correlationId = correlationId,
                transcription = transcription,
                cleanup = cleanup,
                rawText = rawText,
                cleanedText = cleanedText,
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
        transcription: QualityStage?,
        cleanup: QualityStage?,
        rawText: String?,
        cleanedText: String?,
    ): String {
        val root = JSONObject()
        root.put("timestamp", timestamp)
        root.put("correlationId", correlationId)
        root.put("transcription", transcription?.toJson() ?: JSONObject.NULL)
        root.put("cleanup", cleanup?.toJson() ?: JSONObject.NULL)
        root.put("rawText", rawText ?: JSONObject.NULL)
        root.put("cleanedText", cleanedText ?: JSONObject.NULL)
        return root.toString()
    }

    private fun QualityStage.toJson(): JSONObject = JSONObject()
        .put("provider", provider)
        .put("model", model)

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
