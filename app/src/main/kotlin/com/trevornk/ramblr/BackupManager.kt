package com.trevornk.ramblr

import android.content.Context
import java.io.File
import java.io.InputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Manual, on-demand backup/restore of the on-device data that's NOT already covered by Android's
 * automatic backup mechanism (see backup_rules.xml / data_extraction_rules.xml, which only ever
 * carry `ramblr.xml` + `overlay_icon.png`) -- specifically `dictation_history.jsonl`,
 * `benchmark_log.jsonl`, and `quality_log.jsonl` (GH #103). Prompted by a real scenario: a
 * signature-key change (debug -> release signed APK) forces an uninstall, which would otherwise
 * silently lose all three.
 *
 * Deliberately excludes the three EncryptedSharedPreferences files (`ramblr_secure.xml`,
 * `ramblr_cleanup_credentials.xml`, `ramblr_provider_credentials.xml`) -- same reasoning already
 * documented in backup_rules.xml's doc comment: their ciphertext is bound to a key generated in,
 * and never leaving, this specific device's Android Keystore. Restoring that ciphertext anywhere
 * else (a different device, or even this same app after a fresh install/Keystore reset) can't
 * produce a working key, so every read would come back empty or throw. Re-entering API key(s)
 * once is the correct, safe behavior -- this class simply never touches those three files.
 *
 * [zipFiles]/[unzipToTargets] are pure `File`-based logic with no [Context] dependency, so
 * they're directly unit-testable against real temp files without Robolectric. [createBackup]/
 * [restoreBackup] are the thin [Context]-aware wrappers real call sites use, mirroring the
 * forFile/forContext split already used by [DictationHistoryStore].
 *
 * Restore safety (zip-slip prevention): [unzipToTargets] never derives a filesystem path from a
 * zip entry's name. Every entry name is looked up in the caller-supplied [targets] map -- built
 * from [targetFiles], a fixed, known set of on-device files -- and only written if that lookup
 * succeeds. An entry with an unrecognized name (including a path-traversal attempt like
 * `"../../evil"`) simply has no matching key and is reported in [BackupManager.RestoreResult.skippedEntries]
 * instead of being written anywhere.
 */
object BackupManager {
    const val ENTRY_DICTATION_HISTORY = "dictation_history.jsonl"
    const val ENTRY_BENCHMARK_LOG = "benchmark_log.jsonl"
    const val ENTRY_QUALITY_LOG = "quality_log.jsonl"
    const val ENTRY_PREFS = "ramblr_prefs.xml"

    /** Real on-disk name of the plain (non-encrypted) SharedPreferences file backing
     *  [BaseSettingsActivity.prefs] -- see that file's `getSharedPreferences("ramblr", ...)` call. */
    private const val PREFS_FILE_NAME = "ramblr.xml"

    /** Maps each known backup entry name to its real on-device [File] location. Shared by both
     *  [createBackup] (source files to zip) and [restoreBackup] (destination files to overwrite)
     *  so the two are always in sync by construction -- adding a new backed-up file only ever
     *  requires changing this one map. */
    fun targetFiles(context: Context): Map<String, File> = mapOf(
        ENTRY_DICTATION_HISTORY to File(context.filesDir, ENTRY_DICTATION_HISTORY),
        ENTRY_BENCHMARK_LOG to BenchmarkLogger.logFile(context),
        ENTRY_QUALITY_LOG to QualityLogger.logFile(context),
        // SharedPreferences XML files live in a fixed sibling of filesDir, one directory up:
        // <filesDir>/../shared_prefs/<name>.xml. There's no public Context accessor for this
        // path (only for the parsed SharedPreferences object itself), but the location is a
        // stable, documented part of Android's per-app storage layout.
        ENTRY_PREFS to File(File(context.filesDir.parentFile, "shared_prefs"), PREFS_FILE_NAME),
    )

    /** Zips every existing, non-empty file in [sources] into [destination], keyed by its map key
     *  as the zip entry name. Returns the set of entry names actually written -- a fresh install
     *  with, say, no quality log yet simply omits that entry, the same "nothing there yet" spirit
     *  as [AdvancedActivity]'s existing shareBenchmarkLog/shareQualityLog empty-file guard. */
    fun zipFiles(destination: File, sources: Map<String, File>): Set<String> {
        val written = mutableSetOf<String>()
        ZipOutputStream(destination.outputStream().buffered()).use { zipOut ->
            sources.forEach { (entryName, file) ->
                if (!file.exists() || file.length() == 0L) return@forEach
                zipOut.putNextEntry(ZipEntry(entryName))
                file.inputStream().use { it.copyTo(zipOut) }
                zipOut.closeEntry()
                written += entryName
            }
        }
        return written
    }

    fun createBackup(context: Context, destination: File): Set<String> =
        zipFiles(destination, targetFiles(context))

    /** [restoredEntries]: entry names successfully written to their matching target file.
     *  [skippedEntries]: entry names present in the zip that had no matching [targets] key, and
     *  were therefore never written anywhere -- see this class's kdoc for why that's the safety
     *  mechanism, not an edge case to "fix". */
    data class RestoreResult(val restoredEntries: Set<String>, val skippedEntries: Set<String>)

    /** Restores every entry in [source] whose name matches a key in [targets], overwriting that
     *  file in place. See this class's kdoc for the zip-slip-safety contract this relies on. */
    fun unzipToTargets(source: InputStream, targets: Map<String, File>): RestoreResult {
        val restored = mutableSetOf<String>()
        val skipped = mutableSetOf<String>()
        ZipInputStream(source).use { zipIn ->
            var entry: ZipEntry? = zipIn.nextEntry
            while (entry != null) {
                val target = targets[entry.name]
                if (target == null) {
                    skipped += entry.name
                } else {
                    target.parentFile?.mkdirs()
                    target.outputStream().use { out -> zipIn.copyTo(out) }
                    restored += entry.name
                }
                zipIn.closeEntry()
                entry = zipIn.nextEntry
            }
        }
        return RestoreResult(restored, skipped)
    }

    fun restoreBackup(context: Context, source: InputStream): RestoreResult =
        unzipToTargets(source, targetFiles(context))
}
