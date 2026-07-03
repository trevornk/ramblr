package com.kafkasl.phonewhisper

import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorOutputStream
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files

class ModelDownloaderTest {

    @Test fun `extracts tar bz2 with nested files`() {
        withTempDir { tmp ->
            val archive = File(tmp, "test.tar.bz2")
            val outDir = File(tmp, "out")

            writeTarBz2(archive, mapOf(
                "mymodel/tokens.txt" to "hello\nworld",
                "mymodel/encoder.onnx" to "fake-onnx-data",
            ))

            ModelDownloader.extractTarBz2(archive, outDir)

            assertTrue(File(outDir, "mymodel").isDirectory)
            assertEquals("hello\nworld", File(outDir, "mymodel/tokens.txt").readText())
            assertEquals("fake-onnx-data", File(outDir, "mymodel/encoder.onnx").readText())
        }
    }

    @Test fun `rejects path traversal`() {
        withTempDir { tmp ->
            val archive = File(tmp, "evil.tar.bz2")
            writeTarBz2(archive, mapOf("../evil.txt" to "gotcha"))

            assertThrows(IllegalArgumentException::class.java) {
                ModelDownloader.extractTarBz2(archive, File(tmp, "out"))
            }
        }
    }

    @Test fun `catalog has expected structure`() {
        assertEquals(4, MODEL_CATALOG.size)
        assertTrue(MODEL_CATALOG.any { it.recommended })
        assertTrue(MODEL_CATALOG.all { it.archive.startsWith("sherpa-onnx-") })
        assertTrue(MODEL_CATALOG.all { it.sizeMb > 0 })
    }

    @Test fun `catalog entries have a sourced sha256`() {
        // Every shipped model must carry a real checksum -- see Model.sha256 kdoc.
        // A null here isn't a bug, but it does mean download() refuses to install
        // that model until a real hash is sourced, so guard against forgetting one.
        assertTrue(MODEL_CATALOG.all { it.sha256 != null })
        assertTrue(MODEL_CATALOG.all { it.sha256!!.matches(Regex("[0-9a-f]{64}")) })
    }

    @Test fun `no offline catalog entry is marked streaming`() {
        // MODEL_CATALOG is the offline/batch list surfaced by MainActivity's "Local models"
        // radio-select UI; a streaming model there would be selectable as an offline model_name
        // and break LocalTranscriber's auto-detection (#29).
        assertTrue(MODEL_CATALOG.none { it.isStreaming })
    }

    // -- streaming model catalog (#29) --

    @Test fun `streaming catalog has exactly the one streaming model, marked and checksummed`() {
        assertEquals(1, STREAMING_MODEL_CATALOG.size)
        assertTrue(STREAMING_MODEL_CATALOG.all { it.isStreaming })
        assertTrue(STREAMING_MODEL_CATALOG.all { it.sha256 != null })
        assertTrue(STREAMING_MODEL_CATALOG.all { it.sha256!!.matches(Regex("[0-9a-f]{64}")) })
        assertEquals(STREAMING_MODEL, STREAMING_MODEL_CATALOG.single())
    }

    @Test fun `streaming and offline model archive names never collide`() {
        val offlineArchives = MODEL_CATALOG.map { it.archive }.toSet()
        assertTrue(STREAMING_MODEL_CATALOG.none { it.archive in offlineArchives })
    }

    @Test fun `streaming model installs under a separate streaming_models directory`() {
        withTempDir { tmp ->
            val dir = ModelDownloader.modelDirPath(tmp, STREAMING_MODEL)
            assertTrue(dir.path.contains("/streaming_models/"))
            assertFalse(dir.path.contains("/models/"))
        }
    }

    @Test fun `offline model install path is unchanged by the streaming addition`() {
        withTempDir { tmp ->
            val dir = ModelDownloader.modelDirPath(tmp, MODEL_CATALOG.first())
            assertEquals(File(tmp, "models/${MODEL_CATALOG.first().archive}").path, dir.path)
        }
    }

    // -- isInstalledDir / completion marker --

    @Test fun `isInstalledDir is false when directory is missing`() {
        withTempDir { tmp ->
            assertFalse(ModelDownloader.isInstalledDir(File(tmp, "nope")))
        }
    }

    @Test fun `isInstalledDir is false for a directory without a completion marker`() {
        withTempDir { tmp ->
            val dir = File(tmp, "mymodel").apply { mkdirs() }
            File(dir, "tokens.txt").writeText("hi")
            assertFalse(ModelDownloader.isInstalledDir(dir))
        }
    }

    @Test fun `isInstalledDir is true once the completion marker exists`() {
        withTempDir { tmp ->
            val dir = File(tmp, "mymodel").apply { mkdirs() }
            ModelDownloader.completeMarker(dir).createNewFile()
            assertTrue(ModelDownloader.isInstalledDir(dir))
        }
    }

    // -- extractAndInstall (atomic staging + rename) --

    @Test fun `extractAndInstall moves the extracted model into finalDir and marks it complete`() {
        withTempDir { tmp ->
            val archive = File(tmp, "test.tar.bz2")
            writeTarBz2(archive, mapOf(
                "mymodel/tokens.txt" to "hello",
                "mymodel/encoder.onnx" to "fake-onnx-data",
            ))
            val staging = File(tmp, "staging")
            val finalDir = File(tmp, "models/mymodel")

            ModelDownloader.extractAndInstall(archive, staging, finalDir, "mymodel")

            assertTrue(ModelDownloader.isInstalledDir(finalDir))
            assertEquals("hello", File(finalDir, "tokens.txt").readText())
            assertEquals("fake-onnx-data", File(finalDir, "encoder.onnx").readText())
            // staging is cleaned up, never left around and never mistaken for a model
            assertFalse(staging.exists())
        }
    }

    @Test fun `extractAndInstall replaces a prior corrupt install only after the new one verifies`() {
        withTempDir { tmp ->
            val archive = File(tmp, "test.tar.bz2")
            writeTarBz2(archive, mapOf("mymodel/tokens.txt" to "fresh"))
            val staging = File(tmp, "staging")
            val finalDir = File(tmp, "models/mymodel").apply { mkdirs() }
            File(finalDir, "tokens.txt").writeText("stale-partial")
            // no .complete marker -- this simulates a corrupt leftover install

            ModelDownloader.extractAndInstall(archive, staging, finalDir, "mymodel")

            assertTrue(ModelDownloader.isInstalledDir(finalDir))
            assertEquals("fresh", File(finalDir, "tokens.txt").readText())
        }
    }

    @Test fun `extractAndInstall leaves no installed-looking dir when the archive is missing the expected folder`() {
        withTempDir { tmp ->
            val archive = File(tmp, "test.tar.bz2")
            // top-level entry name doesn't match the archiveName we pass in
            writeTarBz2(archive, mapOf("wrong-folder/tokens.txt" to "hello"))
            val staging = File(tmp, "staging")
            val finalDir = File(tmp, "models/mymodel")

            assertThrows(IOException::class.java) {
                ModelDownloader.extractAndInstall(archive, staging, finalDir, "mymodel")
            }

            assertFalse(ModelDownloader.isInstalledDir(finalDir))
            assertFalse(staging.exists())
        }
    }

    @Test fun `extractAndInstall cleans up staging and leaves finalDir untouched when extraction throws`() {
        withTempDir { tmp ->
            val archive = File(tmp, "evil.tar.bz2")
            writeTarBz2(archive, mapOf("../evil.txt" to "gotcha"))
            val staging = File(tmp, "staging")
            val finalDir = File(tmp, "models/mymodel")

            assertThrows(IllegalArgumentException::class.java) {
                ModelDownloader.extractAndInstall(archive, staging, finalDir, "mymodel")
            }

            assertFalse(finalDir.exists())
            assertFalse(staging.exists())
        }
    }

    // -- checksum verification --

    @Test fun `sha256 matches a known vector`() {
        withTempDir { tmp ->
            val file = File(tmp, "data.bin").apply { writeText("hello") }
            // echo -n hello | sha256sum
            assertEquals(
                "2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824",
                ModelDownloader.sha256(file)
            )
        }
    }

    @Test fun `verifyChecksum passes silently for a matching hash`() {
        withTempDir { tmp ->
            val file = File(tmp, "data.bin").apply { writeText("hello") }
            ModelDownloader.verifyChecksum(
                file, "2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824"
            )
        }
    }

    @Test fun `verifyChecksum throws on mismatch, so a truncated download never reaches extraction`() {
        withTempDir { tmp ->
            val file = File(tmp, "data.bin").apply { writeText("hello") }
            assertThrows(IOException::class.java) {
                ModelDownloader.verifyChecksum(file, "0".repeat(64))
            }
        }
    }

    @Test fun `a checksum failure before extractAndInstall leaves finalDir absent`() {
        withTempDir { tmp ->
            val archive = File(tmp, "test.tar.bz2")
            writeTarBz2(archive, mapOf("mymodel/tokens.txt" to "hello"))
            val staging = File(tmp, "staging")
            val finalDir = File(tmp, "models/mymodel")

            assertThrows(IOException::class.java) {
                ModelDownloader.verifyChecksum(archive, "0".repeat(64))
                ModelDownloader.extractAndInstall(archive, staging, finalDir, "mymodel")
            }

            assertFalse(ModelDownloader.isInstalledDir(finalDir))
            assertFalse(finalDir.exists())
        }
    }

    // -- free space guard --

    @Test fun `requiredSpaceBytes applies the headroom multiplier to compressed size`() {
        assertEquals(465L * 1_000_000L * 3L, ModelDownloader.requiredSpaceBytes(465))
    }

    @Test fun `hasEnoughSpace is false just under the requirement`() {
        val required = ModelDownloader.requiredSpaceBytes(465)
        assertFalse(ModelDownloader.hasEnoughSpace(required - 1, 465))
    }

    @Test fun `hasEnoughSpace is true at exactly the requirement and above`() {
        val required = ModelDownloader.requiredSpaceBytes(465)
        assertTrue(ModelDownloader.hasEnoughSpace(required, 465))
        assertTrue(ModelDownloader.hasEnoughSpace(required + 1, 465))
    }

    @Test fun `NotEnoughSpaceException message reports required and available in MB`() {
        val e = NotEnoughSpaceException(1_395_000_000L, 500_000_000L)
        assertTrue(e.message!!.contains("1395"))
        assertTrue(e.message!!.contains("500"))
    }

    // -- resume: range header + offset/append planning --

    @Test fun `rangeHeaderFor is null when there is nothing on disk yet`() {
        assertNull(ModelDownloader.rangeHeaderFor(0))
    }

    @Test fun `rangeHeaderFor requests bytes from the existing offset`() {
        assertEquals("bytes=123456-", ModelDownloader.rangeHeaderFor(123456))
    }

    @Test fun `planResume resumes when the server honors the range with 206`() {
        val plan = ModelDownloader.planResume(existingLength = 1000, responseCode = 206)
        assertEquals(1000L, plan.offset)
        assertTrue(plan.append)
    }

    @Test fun `planResume restarts from zero when the server ignores the range and sends 200`() {
        val plan = ModelDownloader.planResume(existingLength = 1000, responseCode = 200)
        assertEquals(0L, plan.offset)
        assertFalse(plan.append)
    }

    @Test fun `planResume restarts from zero when there is nothing on disk, even if the code is 206`() {
        // Shouldn't happen in practice (no Range header was sent), but the offset
        // must never be trusted without existing bytes to back it up.
        val plan = ModelDownloader.planResume(existingLength = 0, responseCode = 206)
        assertEquals(0L, plan.offset)
        assertFalse(plan.append)
    }

    // -- resume: total size computation --

    @Test fun `computeTotalBytes prefers the authoritative total from Content-Range`() {
        val total = ModelDownloader.computeTotalBytes(
            offset = 1000, contentLength = 500, contentRange = "bytes 1000-1499/465000000"
        )
        assertEquals(465_000_000L, total)
    }

    @Test fun `computeTotalBytes falls back to offset plus Content-Length without a range header`() {
        val total = ModelDownloader.computeTotalBytes(offset = 1000, contentLength = 464999000, contentRange = null)
        assertEquals(465_000_000L, total)
    }

    @Test fun `computeTotalBytes is unknown when neither source has a length`() {
        assertEquals(-1L, ModelDownloader.computeTotalBytes(offset = 0, contentLength = -1, contentRange = null))
    }

    // -- helpers --

    private fun withTempDir(block: (File) -> Unit) {
        val tmp = Files.createTempDirectory("model-test").toFile()
        try { block(tmp) } finally { tmp.deleteRecursively() }
    }

    private fun writeTarBz2(file: File, entries: Map<String, String>) {
        TarArchiveOutputStream(BZip2CompressorOutputStream(FileOutputStream(file))).use { tar ->
            for ((name, content) in entries) {
                val bytes = content.toByteArray()
                tar.putArchiveEntry(TarArchiveEntry(name).apply { size = bytes.size.toLong() })
                tar.write(bytes)
                tar.closeArchiveEntry()
            }
        }
    }
}
