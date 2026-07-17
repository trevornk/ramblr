package com.trevornk.ramblr

import android.content.Context
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element

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

    /** How many of the most recent backup files to keep in a backups directory; see
     *  [pruneOldBackups]. GH #123: "Backup all data" wrote a fresh timestamped zip on every tap
     *  and only ever deleted it if the result was empty, so non-empty backups accumulated
     *  forever. 5 is a reasonable default -- enough recent history to recover from a bad restore
     *  or a few days of mistaken taps, without unbounded growth eating device storage. */
    const val MAX_RETAINED_BACKUPS = 5

    /** Pure, [File]-based retention logic split out for direct unit-testing (same reasoning as
     *  [zipFiles]/[unzipToTargets] above): deletes every file in [files] beyond the
     *  [keep] most recently modified, keeping the newest [keep] intact. Returns the files that
     *  were actually deleted. Sorts by [File.lastModified] (not filename) so it stays correct
     *  even if a future caller's naming scheme changes; the current
     *  `ramblr_backup_<timestamp>.zip` naming also happens to sort identically either way. */
    fun pruneOldBackups(files: List<File>, keep: Int = MAX_RETAINED_BACKUPS): List<File> {
        val toDelete = files.sortedByDescending { it.lastModified() }.drop(keep)
        toDelete.forEach { it.delete() }
        return toDelete
    }

    /** [pruneOldBackups] wrapper for a real backup directory on disk: lists its immediate zip
     *  files and prunes down to [keep]. Safe to call whether or not [backupsDir] exists yet. */
    fun pruneOldBackups(backupsDir: File, keep: Int = MAX_RETAINED_BACKUPS): List<File> {
        val existing = backupsDir.listFiles { f -> f.isFile && f.name.endsWith(".zip") }?.toList() ?: return emptyList()
        return pruneOldBackups(existing, keep)
    }

    /** [restoredEntries]: entry names successfully written to their matching target file.
     *  [skippedEntries]: entry names present in the zip that had no matching [targets] key, and
     *  were therefore never written anywhere -- see this class's kdoc for why that's the safety
     *  mechanism, not an edge case to "fix". */
    data class RestoreResult(val restoredEntries: Set<String>, val skippedEntries: Set<String>)

    /** Restores every entry in [source] whose name matches a key in [targets], overwriting that
     *  file in place. See this class's kdoc for the zip-slip-safety contract this relies on.
     *
     *  Plain [File] overwrite is correct for the JSONL log files: every read site
     *  ([DictationHistoryStore], [BenchmarkLogger], [QualityLogger]) opens the file fresh each
     *  call, no long-lived in-memory cache to go stale. It is NOT correct for [ENTRY_PREFS] --
     *  see [restoreBackup]'s kdoc for why that entry is deliberately excluded here and handled
     *  separately. */
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

    /** Parses a `SharedPreferences` XML backup entry (Android's own on-disk format: `<map>`
     *  containing `<boolean>`/`<string>`/`<int>`/`<long>`/`<float>` children with `name`/`value`
     *  attributes) and returns it as a plain key-to-typed-value map ready to apply through a
     *  real [android.content.SharedPreferences.Editor].
     *
     *  This exists because restoring [ENTRY_PREFS] by overwriting `ramblr.xml` on disk (the
     *  same way the JSONL files are restored) silently does nothing while the app is running:
     *  Android's [Context.getSharedPreferences] returns a single process-wide cached instance
     *  per file name that's loaded once and never re-read from disk afterward. [BaseSettingsActivity.prefs]
     *  is called from the very screen that triggers the restore, so that cached instance is
     *  already loaded -- overwriting the XML file underneath it changes what's on disk but not
     *  what every prefs read in the running process actually sees. (Confirmed against a real
     *  restore: a vocabulary list backed up from a device, restored on that same running app
     *  instance, came back as the seeded defaults -- prefs() still had its original in-memory
     *  values, only a relaunch would have picked up the raw file swap.) Applying through the
     *  real `Editor` updates that same cached instance in place, so [BaseSettingsActivity.prefs]
     *  reads the restored values immediately, no relaunch required.
     *
     *  Uses `javax.xml.parsers` (real JDK, not `org.xmlpull.v1`) deliberately: XmlPullParser's
     *  actual implementation is provided by the Android runtime, so calling it from a plain-JVM
     *  `testGithubDebugUnitTest` run (no Robolectric/instrumentation in this module) would throw
     *  at runtime against the SDK's unimplemented-stub jar. `javax.xml.parsers` is real JDK, so
     *  it works identically on-device and in a host-JVM unit test -- see [BackupManagerTest] for
     *  a fixture parsed with the real Android XML backup format Trevor's own device produced. */
    internal fun parsePrefsXml(xml: ByteArrayOutputStream): Map<String, Any> {
        val result = mutableMapOf<String, Any>()
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(xml.toByteArray().inputStream())
        val children = doc.documentElement.childNodes
        for (i in 0 until children.length) {
            val node = children.item(i) as? Element ?: continue
            val name = node.getAttribute("name")
            if (name.isEmpty()) continue
            when (node.tagName) {
                "boolean" -> result[name] = node.getAttribute("value").toBoolean()
                "int" -> node.getAttribute("value").toIntOrNull()?.let { result[name] = it }
                "long" -> node.getAttribute("value").toLongOrNull()?.let { result[name] = it }
                "float" -> node.getAttribute("value").toFloatOrNull()?.let { result[name] = it }
                // <string name="...">value</string> -- the text is nested element content, not a
                // "value" attribute.
                "string" -> result[name] = node.textContent ?: ""
            }
        }
        return result
    }

    /** Restores every file-backed entry ([ENTRY_DICTATION_HISTORY]/[ENTRY_BENCHMARK_LOG]/
     *  [ENTRY_QUALITY_LOG]) via plain [File] overwrite, and -- if present -- [ENTRY_PREFS]
     *  separately through a real `SharedPreferences.Editor.commit()` against the app's actual
     *  "ramblr" prefs instance. See [parsePrefsXml]'s kdoc for why the prefs entry can't take
     *  the same file-overwrite path as the others. `commit()` (not `apply()`) so a caller that
     *  immediately re-reads prefs right after this call (e.g. the restore screen's own refresh())
     *  is guaranteed to see the new values, not a possibly-still-in-flight async write. */
    fun restoreBackup(context: Context, source: InputStream): RestoreResult {
        val zipBytes = source.readBytes()
        val fileTargets = targetFiles(context) - ENTRY_PREFS
        val fileResult = unzipToTargets(zipBytes.inputStream(), fileTargets)

        val restored = fileResult.restoredEntries.toMutableSet()
        val skipped = fileResult.skippedEntries.toMutableSet()

        var prefsXml: ByteArrayOutputStream? = null
        ZipInputStream(zipBytes.inputStream()).use { zipIn ->
            var entry: ZipEntry? = zipIn.nextEntry
            while (entry != null) {
                if (entry.name == ENTRY_PREFS) {
                    prefsXml = ByteArrayOutputStream().apply { zipIn.copyTo(this) }
                }
                zipIn.closeEntry()
                entry = zipIn.nextEntry
            }
        }
        prefsXml?.let { xml ->
            val values = parsePrefsXml(xml)
            val editor = context.getSharedPreferences("ramblr", Context.MODE_PRIVATE).edit().clear()
            values.forEach { (key, value) ->
                when (value) {
                    is Boolean -> editor.putBoolean(key, value)
                    is Int -> editor.putInt(key, value)
                    is Long -> editor.putLong(key, value)
                    is Float -> editor.putFloat(key, value)
                    is String -> editor.putString(key, value)
                }
            }
            editor.commit()
            restored += ENTRY_PREFS
            // unzipToTargets's first pass above never had ENTRY_PREFS in its targets map (it's
            // deliberately excluded so that pass doesn't raw-overwrite ramblr.xml), so it always
            // reports this entry as "skipped" -- correct that now that it's actually been
            // restored through the real Editor above.
            skipped -= ENTRY_PREFS
        }

        return RestoreResult(restored, skipped)
    }
}
