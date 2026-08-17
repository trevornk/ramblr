package com.trevornk.ramblr

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the privacy policy against drifting away from what the app's backup configuration
 * actually does.
 *
 * This exists because PRIVACY.md claimed "the app sets `allowBackup=false`" while
 * AndroidManifest.xml has always set `android:allowBackup="true"` with include-only rules. The
 * practical outcome the user cares about (dictation history and API keys are never backed up)
 * was correct, but the stated mechanism was not -- and for a Play Store submission the privacy
 * policy, the Data safety form, and runtime behavior have to agree literally, not just in
 * spirit.
 *
 * The assertions deliberately read the real manifest, the real backup rule XML, and the real
 * PRIVACY.md rather than a fixture: a fixture copy would keep passing after someone edits the
 * shipping files, which is precisely the failure being prevented.
 */
class PrivacyPolicyBackupClaimsTest {

    private val repoRoot: File by lazy {
        // Gradle runs unit tests with the module dir (app/) as the working directory, but that
        // isn't contractual, so walk up to whichever ancestor actually holds the files instead
        // of hardcoding "..".
        generateSequence(File("").absoluteFile) { it.parentFile }
            .firstOrNull { File(it, "PRIVACY.md").isFile && File(it, "app/src/main/AndroidManifest.xml").isFile }
            ?: error("could not locate repo root from ${File("").absolutePath}")
    }

    private fun read(relativePath: String): String {
        val file = File(repoRoot, relativePath)
        assertTrue("$relativePath should exist at ${file.absolutePath}", file.isFile)
        return file.readText()
    }

    private val manifest by lazy { read("app/src/main/AndroidManifest.xml") }
    private val backupRules by lazy { read("app/src/main/res/xml/backup_rules.xml") }
    private val dataExtractionRules by lazy { read("app/src/main/res/xml/data_extraction_rules.xml") }
    private val privacyPolicy by lazy { read("PRIVACY.md") }

    /** Strips XML comments so a filename merely *discussed* in a doc comment is never mistaken
     *  for an actual <include> element. backup_rules.xml documents the excluded credential files
     *  by name at length, so this distinction is load-bearing rather than theoretical. */
    private fun withoutComments(xml: String): String = xml.replace(Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL), "")

    @Test
    fun `manifest really does enable backup, so the policy must not claim allowBackup is false`() {
        assertTrue(
            "manifest is expected to opt into backup with include-only rules",
            manifest.contains("android:allowBackup=\"true\""),
        )
        assertFalse(
            "PRIVACY.md claims allowBackup=false while the manifest sets it true",
            privacyPolicy.contains("allowBackup=false") || privacyPolicy.contains("`allowBackup=false`"),
        )
    }

    @Test
    fun `manifest points at both backup rule files that this test verifies`() {
        assertTrue(manifest.contains("android:fullBackupContent=\"@xml/backup_rules\""))
        assertTrue(manifest.contains("android:dataExtractionRules=\"@xml/data_extraction_rules\""))
    }

    @Test
    fun `dictation history is never included in backup or device transfer`() {
        val historyFile = "dictation_history.jsonl"
        assertFalse(
            "backup_rules.xml must not include $historyFile",
            withoutComments(backupRules).contains(historyFile),
        )
        assertFalse(
            "data_extraction_rules.xml must not include $historyFile",
            withoutComments(dataExtractionRules).contains(historyFile),
        )
    }

    @Test
    fun `keystore-encrypted credential files are never included in backup or device transfer`() {
        val encryptedPrefs = listOf(
            "ramblr_provider_credentials",
            "ramblr_secure",
            "ramblr_cleanup_credentials",
        )
        for (name in encryptedPrefs) {
            assertFalse(
                "backup_rules.xml must not include $name (Keystore key cannot survive a restore)",
                withoutComments(backupRules).contains(name),
            )
            assertFalse(
                "data_extraction_rules.xml must not include $name",
                withoutComments(dataExtractionRules).contains(name),
            )
        }
    }

    @Test
    fun `both rule files include exactly the same paths, so cloud backup and device transfer agree`() {
        val includePattern = Regex("""<include\s+domain="([^"]+)"\s+path="([^"]+)"\s*/>""")
        fun includesIn(xml: String) = includePattern.findAll(withoutComments(xml))
            .map { it.groupValues[1] to it.groupValues[2] }
            .toList()

        val legacy = includesIn(backupRules)
        val modern = includesIn(dataExtractionRules)

        assertEquals(
            "backup_rules.xml should include exactly the plain prefs file and the overlay icon",
            listOf("sharedpref" to "ramblr.xml", "file" to "overlay_icon.png"),
            legacy,
        )
        // data_extraction_rules.xml declares the same set twice, once under <cloud-backup> and
        // once under <device-transfer>; an asymmetry between the two would mean a device swap
        // carried data a cloud restore did not, which is exactly the kind of silent divergence
        // the privacy policy would then be wrong about.
        assertEquals(
            "data_extraction_rules.xml should declare the same includes for cloud-backup and device-transfer",
            legacy + legacy,
            modern,
        )
    }

    @Test
    fun `privacy policy documents the include-only backup behavior it actually has`() {
        val section = privacyPolicy.substringAfter("## Android backup and device transfer", "")
        assertTrue("PRIVACY.md is missing the backup/device-transfer section", section.isNotBlank())
        assertTrue(
            "the backup section should name the rule files so the claim stays auditable",
            section.contains("backup_rules.xml") && section.contains("data_extraction_rules.xml"),
        )
    }
}
