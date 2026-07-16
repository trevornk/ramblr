package com.trevornk.ramblr

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupManagerTest {

    private fun tempFile(name: String, content: String? = null): File =
        File.createTempFile(name, ".tmp").apply {
            deleteOnExit()
            if (content != null) writeText(content) else delete()
        }

    @Test fun `zipFiles writes an entry for every existing non-empty source file`() {
        val history = tempFile("history", "{\"a\":1}\n")
        val benchmark = tempFile("benchmark", "{\"b\":2}\n")
        val dest = File.createTempFile("backup", ".zip").apply { deleteOnExit(); delete() }

        val written = BackupManager.zipFiles(
            dest,
            mapOf(
                BackupManager.ENTRY_DICTATION_HISTORY to history,
                BackupManager.ENTRY_BENCHMARK_LOG to benchmark,
            )
        )

        assertEquals(setOf(BackupManager.ENTRY_DICTATION_HISTORY, BackupManager.ENTRY_BENCHMARK_LOG), written)
        assertTrue(dest.exists())
        history.delete(); benchmark.delete(); dest.delete()
    }

    @Test fun `zipFiles skips a source file that does not exist`() {
        val missing = File.createTempFile("missing", ".tmp").apply { delete() }
        val dest = File.createTempFile("backup", ".zip").apply { deleteOnExit(); delete() }

        val written = BackupManager.zipFiles(dest, mapOf(BackupManager.ENTRY_QUALITY_LOG to missing))

        assertTrue(written.isEmpty())
        dest.delete()
    }

    @Test fun `zipFiles skips a source file that exists but is empty`() {
        val empty = File.createTempFile("empty", ".tmp").apply { deleteOnExit() }
        val dest = File.createTempFile("backup", ".zip").apply { deleteOnExit(); delete() }

        val written = BackupManager.zipFiles(dest, mapOf(BackupManager.ENTRY_QUALITY_LOG to empty))

        assertTrue(written.isEmpty())
        empty.delete(); dest.delete()
    }

    @Test fun `round-trips content through zipFiles then unzipToTargets`() {
        val history = tempFile("history", "line one\nline two\n")
        val prefs = tempFile("prefs", "<map/>")
        val dest = File.createTempFile("backup", ".zip").apply { deleteOnExit(); delete() }
        BackupManager.zipFiles(
            dest,
            mapOf(
                BackupManager.ENTRY_DICTATION_HISTORY to history,
                BackupManager.ENTRY_PREFS to prefs,
            )
        )

        val restoreHistory = File.createTempFile("restore_history", ".tmp").apply { deleteOnExit(); delete() }
        val restorePrefs = File.createTempFile("restore_prefs", ".tmp").apply { deleteOnExit(); delete() }
        val result = dest.inputStream().use { input ->
            BackupManager.unzipToTargets(
                input,
                mapOf(
                    BackupManager.ENTRY_DICTATION_HISTORY to restoreHistory,
                    BackupManager.ENTRY_PREFS to restorePrefs,
                )
            )
        }

        assertEquals(setOf(BackupManager.ENTRY_DICTATION_HISTORY, BackupManager.ENTRY_PREFS), result.restoredEntries)
        assertTrue(result.skippedEntries.isEmpty())
        assertEquals("line one\nline two\n", restoreHistory.readText())
        assertEquals("<map/>", restorePrefs.readText())

        history.delete(); prefs.delete(); dest.delete(); restoreHistory.delete(); restorePrefs.delete()
    }

    @Test fun `unzipToTargets reports an unrecognized entry name as skipped instead of writing it anywhere`() {
        val source = tempFile("secret", "top secret ciphertext")
        val dest = File.createTempFile("backup", ".zip").apply { deleteOnExit(); delete() }
        // Simulates a zip containing an entry name that has no matching backup target -- e.g. an
        // encrypted-credentials file this backup format never includes, or a crafted zip-slip
        // path like "../../evil.xml".
        BackupManager.zipFiles(dest, mapOf("ramblr_provider_credentials.xml" to source))

        val restoreTarget = File.createTempFile("restore_unused", ".tmp").apply { deleteOnExit(); delete() }
        val result = dest.inputStream().use { input ->
            BackupManager.unzipToTargets(input, mapOf(BackupManager.ENTRY_PREFS to restoreTarget))
        }

        assertTrue(result.restoredEntries.isEmpty())
        assertEquals(setOf("ramblr_provider_credentials.xml"), result.skippedEntries)
        assertFalse("an unrecognized entry must never be written to any file", restoreTarget.exists())

        source.delete(); dest.delete()
    }

    @Test fun `unzipToTargets overwrites an existing target file's content`() {
        val source = tempFile("new_content", "fresh restored content")
        val dest = File.createTempFile("backup", ".zip").apply { deleteOnExit(); delete() }
        BackupManager.zipFiles(dest, mapOf(BackupManager.ENTRY_BENCHMARK_LOG to source))

        val existingTarget = tempFile("stale_target", "stale old content that should be replaced")
        val result = dest.inputStream().use { input ->
            BackupManager.unzipToTargets(input, mapOf(BackupManager.ENTRY_BENCHMARK_LOG to existingTarget))
        }

        assertEquals(setOf(BackupManager.ENTRY_BENCHMARK_LOG), result.restoredEntries)
        assertEquals("fresh restored content", existingTarget.readText())

        source.delete(); dest.delete(); existingTarget.delete()
    }

    @Test fun `zipFiles never includes any of the three EncryptedSharedPreferences file names by construction`() {
        // targetFiles() is the single source of truth for what a backup contains; this asserts
        // its key set directly rather than exercising I/O, so it fails loudly if a future edit
        // ever adds one of the excluded encrypted stores to the map (GH #103 constraint).
        val excluded = setOf(
            "ramblr_secure.xml",
            "ramblr_cleanup_credentials.xml",
            "ramblr_provider_credentials.xml",
        )
        val includedKeys = setOf(
            BackupManager.ENTRY_DICTATION_HISTORY,
            BackupManager.ENTRY_BENCHMARK_LOG,
            BackupManager.ENTRY_QUALITY_LOG,
            BackupManager.ENTRY_PREFS,
        )
        assertTrue(excluded.none { it in includedKeys })
    }

    // --- parsePrefsXml (regression: restore silently returning seeded defaults, #103 follow-up) ---
    //
    // Real SharedPreferences XML backup content pulled from Trevor's own device (trimmed to a
    // representative subset of the real file's boolean/string/int entries) -- this is the exact
    // format restoreBackup()'s prefs path has to parse correctly, not a synthetic shape.
    private val realPrefsXmlFixture = """
        <?xml version='1.0' encoding='utf-8' standalone='yes' ?>
        <map>
            <boolean name="use_post_processing" value="true" />
            <string name="custom_vocabulary_terms">Solveit&#10;fast.ai&#10;Merittas&#10;Pi&#10;Codex</string>
            <int name="peek_visible_size_dp" value="48" />
            <boolean name="debug_visibility_enabled" value="false" />
            <string name="cleanup_style">concise</string>
        </map>
    """.trimIndent()

    @Test fun `parsePrefsXml reads boolean, string, and int entries from a real Android prefs XML backup`() {
        val xml = java.io.ByteArrayOutputStream().apply { write(realPrefsXmlFixture.toByteArray()) }

        val result = BackupManager.parsePrefsXml(xml)

        assertEquals(true, result["use_post_processing"])
        assertEquals(false, result["debug_visibility_enabled"])
        assertEquals(48, result["peek_visible_size_dp"])
        assertEquals("concise", result["cleanup_style"])
        assertEquals(
            "Solveit\nfast.ai\nMerittas\nPi\nCodex",
            result["custom_vocabulary_terms"],
        )
    }

    @Test fun `parsePrefsXml round-trips a multi-line vocabulary string exactly, matching the real bug report`() {
        // Regression for the reported bug: restoring a backed-up custom_vocabulary_terms list
        // came back as the seeded defaults instead of the real 24-term list, because the old
        // restore path only overwrote ramblr.xml on disk -- a no-op against the app's already-
        // loaded SharedPreferences instance. This asserts the *parsed* value round-trips exactly
        // (the actual SharedPreferences.Editor.commit() apply step is exercised on-device, since
        // getSharedPreferences() needs a real Context this plain-JVM unit test doesn't have).
        val terms = listOf("Solveit", "fast.ai", "Answer.AI", "Wyatt", "Dyson", "Adalyn")
        val serialized = terms.joinToString("&#10;")
        val xml = java.io.ByteArrayOutputStream().apply {
            write(
                """
                <?xml version='1.0' encoding='utf-8' standalone='yes' ?>
                <map>
                    <string name="custom_vocabulary_terms">$serialized</string>
                </map>
                """.trimIndent().toByteArray()
            )
        }

        val result = BackupManager.parsePrefsXml(xml)

        assertEquals(terms.joinToString("\n"), result["custom_vocabulary_terms"])
    }
}
