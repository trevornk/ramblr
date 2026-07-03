package com.kafkasl.phonewhisper

import android.content.Context
import org.json.JSONObject
import java.io.File

/**
 * One past dictation: the raw transcript, and the cleaned version if cleanup ran and succeeded.
 * [paidFallbackGroup] is only ever set when cleanup was served by a pay-per-token direct
 * provider (see [CleanupStepGroup.isPaidFallback], issue #33) -- null for LEGACY/OMNIROUTE/
 * LOCAL_LLM and for entries with no cleanup at all, so the common case and existing history
 * entries (predating this field) stay indistinguishable from each other.
 */
data class DictationHistoryEntry(
    val timestamp: Long,
    val rawText: String,
    val cleanedText: String?,
    val paidFallbackGroup: CleanupStepGroup? = null
)

/** Whether one history row's "paid fallback" badge (#33) should render: the debug/visibility
 *  toggle must be on, and the entry must actually have been served by a paid group. */
fun shouldShowPaidFallbackBadge(debugVisibilityEnabled: Boolean, entry: DictationHistoryEntry): Boolean =
    debugVisibilityEnabled && entry.paidFallbackGroup != null

/**
 * Local-only, file-backed log of past dictations (see #25) so a transcript survives even if
 * injection fails and the user misses the clipboard-fallback toast. Stored as JSON-lines in
 * app-private storage (excluded from backup, `allowBackup` is false); capped at [maxEntries] with
 * the oldest entries evicted on write. Plain [File] I/O, no Android framework dependency, so this
 * is unit-testable against a temp file.
 */
class DictationHistoryStore(private val file: File, private val maxEntries: Int = DEFAULT_MAX_ENTRIES) {

    companion object {
        const val DEFAULT_MAX_ENTRIES = 50
        private const val FILE_NAME = "dictation_history.jsonl"

        fun forContext(context: Context, maxEntries: Int = DEFAULT_MAX_ENTRIES) =
            DictationHistoryStore(File(context.filesDir, FILE_NAME), maxEntries)
    }

    /** Appends [entry], evicting the oldest entries beyond [maxEntries]. */
    @Synchronized
    fun add(entry: DictationHistoryEntry) {
        val entries = (readAll() + entry).takeLast(maxEntries)
        writeAll(entries)
    }

    /** All entries, newest first. */
    @Synchronized
    fun all(): List<DictationHistoryEntry> = readAll().asReversed()

    @Synchronized
    fun clear() {
        if (file.exists()) file.writeText("")
    }

    /** Removes every entry with the given [timestamp], which is unique per dictation and doubles
     *  as the entry's identity since there's no separate id field. */
    @Synchronized
    fun delete(timestamp: Long) {
        writeAll(readAll().filterNot { it.timestamp == timestamp })
    }

    private fun readAll(): List<DictationHistoryEntry> {
        if (!file.exists()) return emptyList()
        return file.readLines()
            .filter { it.isNotBlank() }
            // A corrupt/partial line (e.g. from a killed process mid-write) is skipped rather
            // than losing every other entry in the file.
            .mapNotNull { line -> runCatching { parse(line) }.getOrNull() }
    }

    private fun writeAll(entries: List<DictationHistoryEntry>) {
        file.parentFile?.mkdirs()
        val body = entries.joinToString("") { serialize(it) + "\n" }
        file.writeText(body)
    }

    private fun serialize(entry: DictationHistoryEntry): String =
        JSONObject().apply {
            put("timestamp", entry.timestamp)
            put("rawText", entry.rawText)
            put("cleanedText", entry.cleanedText ?: JSONObject.NULL)
            put("paidFallbackGroup", entry.paidFallbackGroup?.name ?: JSONObject.NULL)
        }.toString()

    private fun parse(line: String): DictationHistoryEntry {
        val json = JSONObject(line)
        return DictationHistoryEntry(
            timestamp = json.getLong("timestamp"),
            rawText = json.getString("rawText"),
            cleanedText = if (json.isNull("cleanedText")) null else json.getString("cleanedText"),
            // json.isNull returns true both for an explicit JSON null and for a missing key
            // (Android's org.json), so a pre-#33 history line with no "paidFallbackGroup" key at
            // all parses to null here rather than throwing.
            paidFallbackGroup = if (json.isNull("paidFallbackGroup")) null
                else runCatching { CleanupStepGroup.valueOf(json.getString("paidFallbackGroup")) }.getOrNull()
        )
    }
}
