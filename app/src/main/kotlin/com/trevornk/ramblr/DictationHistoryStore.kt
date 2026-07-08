package com.trevornk.ramblr

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
 * app-private storage; capped at [maxEntries] with the oldest entries evicted on write. Plain
 * [File] I/O, no Android framework dependency, so this is unit-testable against a temp file.
 *
 * Excluded from backup even though the manifest sets `allowBackup="true"`: backup_rules.xml /
 * data_extraction_rules.xml use include-only semantics (only ramblr.xml + overlay_icon.png), so
 * dictation_history.jsonl is never backed up (security audit L-6).
 */
class DictationHistoryStore(private val file: File, private val maxEntries: Int = DEFAULT_MAX_ENTRIES) {

    companion object {
        const val DEFAULT_MAX_ENTRIES = 50
        private const val FILE_NAME = "dictation_history.jsonl"

        // One store instance per backing file (#71): add/all/clear/delete are @Synchronized,
        // i.e. locked on the instance — the service's background history writes and
        // MainActivity's history dialog previously each held their *own* instance, so their
        // read-modify-write cycles didn't exclude each other and a race could drop or
        // resurrect entries. First caller's maxEntries wins for a given path; every production
        // caller uses the default.
        private val instances = mutableMapOf<String, DictationHistoryStore>()

        /** The shared store for [file], creating it on first use. */
        fun forFile(file: File, maxEntries: Int = DEFAULT_MAX_ENTRIES): DictationHistoryStore =
            synchronized(instances) {
                instances.getOrPut(file.absolutePath) { DictationHistoryStore(file, maxEntries) }
            }

        fun forContext(context: Context, maxEntries: Int = DEFAULT_MAX_ENTRIES) =
            forFile(File(context.filesDir, FILE_NAME), maxEntries)
    }

    /** Appends [entry], evicting the oldest entries beyond [maxEntries]. */
    @Synchronized
    fun add(entry: DictationHistoryEntry) {
        val entries = (readAll() + entry).takeLast(maxEntries)
        writeAll(entries)
    }

    /** Inserts [entry], or replaces the existing entry with the same [DictationHistoryEntry.timestamp]
     *  in place if one exists (#73): lets a caller record a dictation as soon as its transcript+
     *  cleanup result exist, then update that same row later (e.g. once injection actually
     *  happens, or the user picks raw text over the cleaned candidate) without creating a second,
     *  duplicate entry for one dictation. Falls back to [add]'s ordering when no existing entry
     *  matches -- an update-in-place preserves the original entry's position rather than moving
     *  it to the end, since [timestamp] (the true dictation time) is unchanged either way. */
    @Synchronized
    fun upsert(entry: DictationHistoryEntry) {
        val existing = readAll()
        val replaced = existing.map { if (it.timestamp == entry.timestamp) entry else it }
        val entries = if (replaced != existing) {
            replaced
        } else {
            (existing + entry).takeLast(maxEntries)
        }
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
        // Write to a temp sibling then atomically rename over the target (bugs audit L8): a plain
        // truncate-then-write loses the whole history if the process dies mid-write, whereas a
        // rename either fully replaces the file or leaves the previous version intact.
        val tmp = File(file.parentFile, "${file.name}.tmp")
        tmp.writeText(body)
        if (!tmp.renameTo(file)) {
            // renameTo can fail across some filesystems even on the same dir; fall back to a
            // direct write so a rename quirk never silently drops the update.
            file.writeText(body)
            tmp.delete()
        }
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
