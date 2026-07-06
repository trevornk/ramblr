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

    @Test fun `rejects traversal into a sibling dir whose name merely starts with the out dir's (#88)`() {
        withTempDir { tmp ->
            val archive = File(tmp, "evil.tar.bz2")
            // Canonicalizes to <tmp>/outevil/x.txt, which startsWith(<tmp>/out) as a plain
            // string -- the missing-separator bypass the prefix check now closes.
            writeTarBz2(archive, mapOf("../outevil/x.txt" to "gotcha"))

            assertThrows(IllegalArgumentException::class.java) {
                ModelDownloader.extractTarBz2(archive, File(tmp, "out"))
            }
            assertFalse(File(tmp, "outevil").exists())
        }
    }

    @Test fun `catalog has expected structure`() {
        // Reduced from 4 to 3 for #98 (Trevor's mislabeled-catalog cleanup follow-up): Moonshine
        // Tiny removed -- verified strictly dominated by Parakeet 110M on every axis (103MB vs.
        // 100MB on disk, ~12.66% WER vs. ~7.5%), so it was never a real choice, just confusion.
        assertEquals(3, MODEL_CATALOG.size)
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

    @Test fun `streaming catalog has one tier, marked and checksummed`() {
        // Collapsed from three tiers to one for #98 (Claude Fable 5 STT model consult): the
        // quality difference between them was invisible in a cosmetic live preview, while the
        // larger tiers cost real CPU during recording -- see STREAMING_MODEL's kdoc.
        assertEquals(1, STREAMING_MODEL_CATALOG.size)
        assertTrue(STREAMING_MODEL_CATALOG.all { it.isStreaming })
        assertTrue(STREAMING_MODEL_CATALOG.all { it.sha256 != null })
        assertTrue(STREAMING_MODEL_CATALOG.all { it.sha256!!.matches(Regex("[0-9a-f]{64}")) })
        assertTrue(STREAMING_MODEL_CATALOG.contains(STREAMING_MODEL))
        assertEquals(1, STREAMING_MODEL_CATALOG.count { it.recommended })
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

    // -- local cleanup model catalog (#37) --

    @Test fun `local cleanup catalog has real, distinct, checksummed tiers with exactly one default`() {
        // Collapsed from 3 to 1 for #98 (Trevor's direct request): Qwen2.5-1.5B ("best quality")
        // was a ~1.1GB download that would only compound the memory-pressure failures the
        // LFM2.5-350M swap was meant to fix; SmolLM2-360M ("smallest, still good") is
        // independently confirmed BROKEN for this exact task in #54 (falls back to generic
        // assistant chit-chat instead of cleaning the transcript).
        //
        // Reopened to 2 tiers for the mumble-cleanup A/B test (Trevor-requested, following a real
        // on-device LFM2.5+DEV_PROMPT failure and Trevor's explicit request to search for existing
        // prior art before building a fine-tuning pipeline from scratch): unlike the old rejected
        // tiers, MUMBLE_CLEANUP_MODEL is a real, independently-sourced, differently-architected
        // model (Qwen2.5-0.5B LoRA fine-tuned specifically for this task, not just a smaller/
        // larger instance of the same "prompt a generic instruct model" approach) -- a genuine
        // second option worth comparing, not a confusing non-choice like the old tiers were.
        assertEquals(2, LOCAL_CLEANUP_MODEL_CATALOG.size)
        assertTrue(LOCAL_CLEANUP_MODEL_CATALOG.all { it.isLocalCleanup })
        assertTrue(LOCAL_CLEANUP_MODEL_CATALOG.all { it.sha256 != null })
        assertTrue(LOCAL_CLEANUP_MODEL_CATALOG.all { it.sha256!!.matches(Regex("[0-9a-f]{64}")) })
        assertTrue(LOCAL_CLEANUP_MODEL_CATALOG.contains(LOCAL_CLEANUP_MODEL))
        assertTrue(LOCAL_CLEANUP_MODEL_CATALOG.contains(MUMBLE_CLEANUP_MODEL))
        // Exactly one default: installing the new A/B entry must not silently change what every
        // existing user's fresh install (or resolveActiveModel's recommended-fallback) resolves to.
        assertEquals(1, LOCAL_CLEANUP_MODEL_CATALOG.count { it.recommended })
        assertEquals(LOCAL_CLEANUP_MODEL, LOCAL_CLEANUP_MODEL_CATALOG.first { it.recommended })
    }

    @Test fun `mumble-cleanup model is sourced from a real Hugging Face URL with a distinct archive and file name`() {
        assertTrue(MUMBLE_CLEANUP_MODEL.sourceUrl!!.startsWith("https://huggingface.co/"))
        assertTrue(MUMBLE_CLEANUP_MODEL.sourceUrl!!.endsWith(".gguf"))
        assertEquals("mumble-cleanup-2stage-q4km.gguf", MUMBLE_CLEANUP_MODEL.fileName)
        assertFalse(MUMBLE_CLEANUP_MODEL.recommended)
        assertNotEquals(LOCAL_CLEANUP_MODEL.archive, MUMBLE_CLEANUP_MODEL.archive)
        assertNotEquals(LOCAL_CLEANUP_MODEL.fileName, MUMBLE_CLEANUP_MODEL.fileName)
    }

    @Test fun `local cleanup model is sourced from a real Hugging Face URL, not the sherpa-onnx release host`() {
        assertTrue(LOCAL_CLEANUP_MODEL.sourceUrl!!.startsWith("https://huggingface.co/"))
        assertTrue(LOCAL_CLEANUP_MODEL.sourceUrl!!.endsWith(".gguf"))
        assertEquals(LOCAL_CLEANUP_MODEL.fileName, "lfm2.5-350m-q4_0.gguf")
    }

    @Test fun `local cleanup model archive name never collides with the offline or streaming catalogs`() {
        val otherArchives = (MODEL_CATALOG + STREAMING_MODEL_CATALOG).map { it.archive }.toSet()
        assertTrue(LOCAL_CLEANUP_MODEL_CATALOG.none { it.archive in otherArchives })
    }

    @Test fun `local cleanup model installs under its own cleanup_models directory`() {
        withTempDir { tmp ->
            val dir = ModelDownloader.modelDirPath(tmp, LOCAL_CLEANUP_MODEL)
            assertTrue(dir.path.contains("/cleanup_models/"))
            assertFalse(dir.path.contains("/models/"))
            assertFalse(dir.path.contains("/streaming_models/"))
        }
    }

    // -- installSingleFile (#37): local-cleanup counterpart to extractAndInstall --

    @Test fun `installSingleFile moves the file into finalDir under fileName and marks it complete`() {
        withTempDir { tmp ->
            val downloaded = File(tmp, "download.tmp").apply { writeText("fake-gguf-bytes") }
            val finalDir = File(tmp, "cleanup_models/qwen2.5-0.5b-instruct-q4_k_m")

            ModelDownloader.installSingleFile(downloaded, finalDir, "qwen2.5-0.5b-instruct-q4_k_m.gguf")

            assertTrue(ModelDownloader.isInstalledDir(finalDir))
            assertEquals("fake-gguf-bytes", File(finalDir, "qwen2.5-0.5b-instruct-q4_k_m.gguf").readText())
            assertFalse(downloaded.exists()) // moved, not copied-and-left-behind
        }
    }

    @Test fun `installSingleFile replaces a prior corrupt install only after the new file is in place`() {
        withTempDir { tmp ->
            val downloaded = File(tmp, "download.tmp").apply { writeText("fresh-bytes") }
            val finalDir = File(tmp, "cleanup_models/mymodel").apply { mkdirs() }
            File(finalDir, "mymodel.gguf").writeText("stale-partial")
            // no .complete marker -- simulates a corrupt leftover install

            ModelDownloader.installSingleFile(downloaded, finalDir, "mymodel.gguf")

            assertTrue(ModelDownloader.isInstalledDir(finalDir))
            assertEquals("fresh-bytes", File(finalDir, "mymodel.gguf").readText())
        }
    }

    @Test fun `a failed install restores the previous good install instead of destroying it (#88)`() {
        withTempDir { tmp ->
            val finalDir = File(tmp, "cleanup_models/mymodel")
            val firstDownload = File(tmp, "first.tmp").apply { writeText("good-bytes") }
            ModelDownloader.installSingleFile(firstDownload, finalDir, "mymodel.gguf")
            assertTrue(ModelDownloader.isInstalledDir(finalDir))

            // A source file that no longer exists makes renameTo fail and copyTo throw --
            // the same shape as disk-full mid-copy. Previously finalDir had already been
            // deleted at this point, so a failed upgrade destroyed the working install.
            val vanishedDownload = File(tmp, "vanished.tmp")
            assertThrows(Exception::class.java) {
                ModelDownloader.installSingleFile(vanishedDownload, finalDir, "mymodel.gguf")
            }

            assertTrue(ModelDownloader.isInstalledDir(finalDir))
            assertEquals("good-bytes", File(finalDir, "mymodel.gguf").readText())
        }
    }

    @Test fun `a successful re-install replaces the previous one and leaves no swap residue`() {
        withTempDir { tmp ->
            val finalDir = File(tmp, "cleanup_models/mymodel")
            ModelDownloader.installSingleFile(File(tmp, "v1.tmp").apply { writeText("v1") }, finalDir, "mymodel.gguf")
            ModelDownloader.installSingleFile(File(tmp, "v2.tmp").apply { writeText("v2") }, finalDir, "mymodel.gguf")

            assertTrue(ModelDownloader.isInstalledDir(finalDir))
            assertEquals("v2", File(finalDir, "mymodel.gguf").readText())
            // The move-aside dir from the swap must not linger (it would waste a model's worth
            // of disk) -- and nothing else unexpected should appear next to the install.
            assertEquals(listOf("mymodel"), finalDir.parentFile!!.list()!!.toList())
        }
    }

    @Test fun `installSingleFile at the real local-cleanup model path reads as installed`() {
        withTempDir { tmp ->
            val downloaded = File(tmp, "download.tmp").apply { writeText("bytes") }
            val finalDir = ModelDownloader.modelDirPath(tmp, LOCAL_CLEANUP_MODEL)
            ModelDownloader.installSingleFile(downloaded, finalDir, LOCAL_CLEANUP_MODEL.fileName!!)

            assertTrue(ModelDownloader.isInstalledDir(finalDir))
            assertTrue(File(finalDir, LOCAL_CLEANUP_MODEL.fileName!!).isFile)
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

    @Test fun `single-file installs demand rename-in-place headroom, not the 3x extraction multiple`() {
        // The 1117 MB Qwen 1.5B GGUF is renamed in place, never extracted: demanding 3x
        // (~3.35 GB) was a false NotEnoughSpaceException on phones with real room (#88).
        assertEquals(1117L * 1_000_000L * 120L / 100L, ModelDownloader.requiredSpaceBytes(1117, singleFile = true))
        assertTrue(ModelDownloader.requiredSpaceBytes(1117, singleFile = true) < ModelDownloader.requiredSpaceBytes(1117))
    }

    @Test fun `a resumable partial on disk is credited against the space requirement`() {
        val full = ModelDownloader.requiredSpaceBytes(465)
        assertEquals(full - 100_000_000L, ModelDownloader.requiredSpaceBytes(465, alreadyDownloadedBytes = 100_000_000L))
        // And an over-complete partial floors at zero rather than going negative.
        assertEquals(0L, ModelDownloader.requiredSpaceBytes(465, alreadyDownloadedBytes = full + 1))
        assertTrue(ModelDownloader.hasEnoughSpace(0L, 465, alreadyDownloadedBytes = full))
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

    // -- resume: 416 Range Not Satisfiable (#68) --

    @Test fun `a 416 for a range we sent means the stale partial must be deleted and restarted`() {
        assertTrue(ModelDownloader.shouldRestartAfterRangeNotSatisfiable(existingLength = 1000, responseCode = 416))
    }

    @Test fun `a 416 with no partial on disk is a plain server error, not a restart loop`() {
        assertFalse(ModelDownloader.shouldRestartAfterRangeNotSatisfiable(existingLength = 0, responseCode = 416))
    }

    @Test fun `non-416 codes never trigger the stale-partial restart`() {
        assertFalse(ModelDownloader.shouldRestartAfterRangeNotSatisfiable(existingLength = 1000, responseCode = 206))
        assertFalse(ModelDownloader.shouldRestartAfterRangeNotSatisfiable(existingLength = 1000, responseCode = 200))
        assertFalse(ModelDownloader.shouldRestartAfterRangeNotSatisfiable(existingLength = 1000, responseCode = 500))
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

    // -- resolveSelectionAfterDelete (#51: uninstall-fallback logic) --

    @Test fun `resolveSelectionAfterDelete leaves an unrelated selection untouched`() {
        assertEquals(
            "sherpa-onnx-whisper-base.en",
            ModelDownloader.resolveSelectionAfterDelete(
                currentArchive = "sherpa-onnx-whisper-base.en",
                deletedArchive = "sherpa-onnx-moonshine-tiny-en-int8",
                remainingInstalled = listOf("sherpa-onnx-whisper-base.en"),
            )
        )
    }

    @Test fun `resolveSelectionAfterDelete falls back to another installed model when the selected one is deleted`() {
        assertEquals(
            "sherpa-onnx-whisper-base.en",
            ModelDownloader.resolveSelectionAfterDelete(
                currentArchive = "sherpa-onnx-moonshine-tiny-en-int8",
                deletedArchive = "sherpa-onnx-moonshine-tiny-en-int8",
                remainingInstalled = listOf("sherpa-onnx-whisper-base.en"),
            )
        )
    }

    @Test fun `resolveSelectionAfterDelete clears the selection when no other model remains installed`() {
        assertEquals(
            "",
            ModelDownloader.resolveSelectionAfterDelete(
                currentArchive = "sherpa-onnx-moonshine-tiny-en-int8",
                deletedArchive = "sherpa-onnx-moonshine-tiny-en-int8",
                remainingInstalled = emptyList(),
            )
        )
    }

    @Test fun `resolveSelectionAfterDelete is a no-op when nothing was ever selected`() {
        assertEquals(
            "",
            ModelDownloader.resolveSelectionAfterDelete(
                currentArchive = "",
                deletedArchive = "sherpa-onnx-moonshine-tiny-en-int8",
                remainingInstalled = listOf("sherpa-onnx-whisper-base.en"),
            )
        )
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
