package com.trevornk.ramblr

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
        // Back to 4 for #197: Parakeet Unified 0.6B added as the best-English tier -- unlike
        // Moonshine it earns its slot with a real, measured quality edge (~5.9% vs ~7.5% WER).
        assertEquals(4, MODEL_CATALOG.size)
        assertTrue(MODEL_CATALOG.any { it.recommended })
        assertTrue(MODEL_CATALOG.all { it.archive.startsWith("sherpa-onnx-") })
        assertTrue(MODEL_CATALOG.all { it.sizeMb > 0 })
    }

    @Test fun `the recommended ASR default is Parakeet 0dot6B v3, flipped from the 110M (#177)`() {
        // Exactly one entry may carry `recommended` -- it's what MainActivity's onboarding
        // auto-downloads and what resolveActiveModel's no-selection fallback keys off; two would
        // make "the default" ambiguous, zero would fall through to list order (same single-default
        // invariant ModelLicenseTest pins for the cleanup catalog).
        assertEquals(1, MODEL_CATALOG.count { it.recommended })
        // Since #177 (2026-08-26) the default is Parakeet 0.6B v3: best freely-licensed model on
        // the measured eval (2.38% WER vs the 110M's 3.02%, LibriSpeech test-clean 300 utts, and
        // the 110M uniquely produced garbage-token errors). Parakeet Unified 0.6B measured better
        // (1.67%) but is consent-gated (NVIDIA license) and must never be the default -- that
        // half of the invariant is pinned in ModelLicenseTest.
        assertEquals(
            "sherpa-onnx-nemo-parakeet-tdt-0.6b-v3-int8",
            MODEL_CATALOG.first { it.recommended }.archive,
        )
        // The flip is a fresh-install-only change: an existing device's explicit "model_name"
        // pick and the blank-pref first-installed fallback never consult `recommended`, so the
        // 110M must stay in the catalog for the users who already have it installed.
        assertTrue(MODEL_CATALOG.any { it.archive == "sherpa-onnx-nemo-parakeet_tdt_ctc_110m-en-36000-int8" })
    }

    @Test fun `ASR and streaming catalog sizeMb is decimal MB matching each model's real byte size`() {
        // Same contract, and the same failure mode, as the local-cleanup test below: sizeMb is
        // consumed by requiredSpaceBytes as DECIMAL MB. These three entries were authored from
        // MiB figures (100/147/465 instead of 104/153/487), so every ASR install reserved ~4.9%
        // less disk than it needed. The cleanup test above it existed and passed the whole time
        // -- it just never covered MODEL_CATALOG or STREAMING_MODEL_CATALOG, which is exactly how
        // the drift survived. Pin every downloadable catalog, not a subset.
        val realBytes = mapOf(
            // Verified 2026-08-25 by downloading the exact tar.bz2 from the sherpa-onnx
            // asr-models GitHub release and hashing/sizing it locally (#197).
            "sherpa-onnx-nemo-parakeet-unified-en-0.6b-int8-non-streaming" to 501_350_460L,
            "sherpa-onnx-nemo-parakeet-tdt-0.6b-v3-int8" to 487_170_055L,
            "sherpa-onnx-nemo-parakeet_tdt_ctc_110m-en-36000-int8" to 104_337_827L,
            "sherpa-onnx-nemo-canary-180m-flash-en-es-de-fr-int8" to 153_692_328L,
            "sherpa-onnx-streaming-zipformer-en-kroko-2025-08-06" to 57_267_600L,
        )

        for (model in MODEL_CATALOG + STREAMING_MODEL_CATALOG) {
            val bytes = realBytes[model.archive]
                ?: fail("no recorded byte size for ${model.archive}; add it when adding a catalog entry")
            val expectedDecimalMb = ((bytes as Long) / 1_000_000L).toInt()
            assertEquals(
                "${model.archive} sizeMb should be decimal MB ($bytes bytes), not MiB",
                expectedDecimalMb,
                model.sizeMb,
            )
            assertTrue(
                "${model.archive}: requiredSpaceBytes must exceed the real download size",
                ModelDownloader.requiredSpaceBytes(model.sizeMb, model.isSingleFile) > bytes,
            )
        }
    }

    @Test fun `local cleanup catalog sizeMb is decimal MB matching each model's real byte size`() {
        // requiredSpaceBytes multiplies sizeMb by 1_000_000, so these entries have to be decimal
        // MB. MUMBLE_CLEANUP_Q4_0_MODEL was authored from the MiB figure (336 instead of 352),
        // silently reserving ~19 MB less than the install needs. Pinning against the real
        // published byte counts is what makes that class of mistake fail loudly instead of only
        // showing up as a mid-download out-of-space error on someone's nearly-full phone.
        val realBytes = mapOf(
            // sha256 85e32858daafad55b7bcd6b97a1343ee0661188e8036f9862d14d6b563142f50
            LOCAL_CLEANUP_MODEL.archive to 219_309_792L,
            // sha256 000efc700d74636bc3885afe1d8f32dbb3fe813b8198dea79d8fd73efcc2c711
            MUMBLE_CLEANUP_Q4_0_MODEL.archive to 352_154_912L,
        )

        for (model in LOCAL_CLEANUP_MODEL_CATALOG) {
            val bytes = realBytes[model.archive]
                ?: fail("no recorded byte size for ${model.archive}; add it when adding a catalog entry")
            val expectedDecimalMb = ((bytes as Long) / 1_000_000L).toInt()
            assertEquals(
                "${model.archive} sizeMb should be decimal MB ($bytes bytes), not MiB",
                expectedDecimalMb,
                model.sizeMb,
            )
            // The reservation must actually cover the file it is reserving for.
            assertTrue(
                "${model.archive}: requiredSpaceBytes must exceed the real download size",
                ModelDownloader.requiredSpaceBytes(model.sizeMb, model.isSingleFile) > bytes,
            )
        }
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
        // Reopened for the mumble-cleanup A/B test (Trevor-requested, following a real on-device
        // LFM2.5+DEV_PROMPT failure and Trevor's explicit request to search for existing prior art
        // before building a fine-tuning pipeline from scratch): MUMBLE_CLEANUP_Q4_0_MODEL is a
        // real, independently-sourced, differently-architected model (Qwen2.5-0.5B LoRA
        // fine-tuned specifically for this task, not just a smaller/larger instance of the same
        // "prompt a generic instruct model" approach) -- a genuine option worth comparing, not a
        // confusing non-choice like the old tiers were. The catalog originally also had a prebuilt
        // Q4_K_M build, but that blew through the full 15s waterfall hard cap on-device and got
        // aborted mid-decode -- confirmed not usable on Trevor's device at all -- so it was
        // removed entirely and replaced with this self-quantized Q4_0 build (quantized locally via
        // the vendored llama-quantize tool, no prebuilt Q4_0 GGUF exists upstream), which came in
        // at ~2.9s on-device.
        assertEquals(2, LOCAL_CLEANUP_MODEL_CATALOG.size)
        assertTrue(LOCAL_CLEANUP_MODEL_CATALOG.all { it.isLocalCleanup })
        assertTrue(LOCAL_CLEANUP_MODEL_CATALOG.all { it.sha256 != null })
        assertTrue(LOCAL_CLEANUP_MODEL_CATALOG.all { it.sha256!!.matches(Regex("[0-9a-f]{64}")) })
        assertTrue(LOCAL_CLEANUP_MODEL_CATALOG.contains(LOCAL_CLEANUP_MODEL))
        assertTrue(LOCAL_CLEANUP_MODEL_CATALOG.contains(MUMBLE_CLEANUP_Q4_0_MODEL))
        // Exactly one default. Since #134 (2026-08-26) that default is the Apache-2.0 mumble
        // fine-tune: the A/B this entry was added for scored it ahead overall (80.6% vs 66.3%,
        // n=35 -- see MUMBLE_CLEANUP_Q4_0_MODEL's kdoc), and a free-licensed default is the #134
        // acceptance criterion (the licensing side is pinned in ModelLicenseTest).
        assertEquals(1, LOCAL_CLEANUP_MODEL_CATALOG.count { it.recommended })
        assertEquals(MUMBLE_CLEANUP_Q4_0_MODEL, LOCAL_CLEANUP_MODEL_CATALOG.first { it.recommended })
    }

    @Test fun `mumble-cleanup Q4_0 speed-test model is downloadable from a pinned HF URL and checksummed`() {
        // No prebuilt Q4_0 GGUF exists on amitashwini/mumble-cleanup-2stage (only f16 and Q4_K_M
        // are published), so this entry was quantized locally and then re-hosted under an account
        // Trevor controls. It downloads like every other entry now -- see the kdoc on
        // MUMBLE_CLEANUP_Q4_0_MODEL for the upload's verification trail.
        val url = MUMBLE_CLEANUP_Q4_0_MODEL.sourceUrl
        assertNotNull("re-hosted: must have a real download URL, not sideload-only", url)
        assertTrue(url!!.startsWith("https://huggingface.co/"))
        assertTrue(url.endsWith(".gguf"))
        // The URL must serve the exact file this entry is checksummed against; a mismatch between
        // fileName and the URL's basename would download the right bytes to the wrong path.
        assertTrue(url.endsWith("/${MUMBLE_CLEANUP_Q4_0_MODEL.fileName}"))
        // Recommended since the #134 flip (2026-08-26) -- the catalog invariants test above pins
        // the single-default property; this pins that it's THIS entry that carries it.
        assertTrue(MUMBLE_CLEANUP_Q4_0_MODEL.recommended)
        assertNotEquals(LOCAL_CLEANUP_MODEL.archive, MUMBLE_CLEANUP_Q4_0_MODEL.archive)
        assertNotEquals(LOCAL_CLEANUP_MODEL.fileName, MUMBLE_CLEANUP_Q4_0_MODEL.fileName)
        assertTrue(MUMBLE_CLEANUP_Q4_0_MODEL.sha256!!.matches(Regex("[0-9a-f]{64}")))
    }

    // -- sideload-only classification (#H7) --

    @Test fun `a local-cleanup model with no sourceUrl is sideload-only`() {
        // Uses a synthetic entry rather than a catalog one: every shipping local-cleanup model is
        // now hosted, but the classifier still has to hold for any future locally-quantized entry
        // that lands in the catalog before it has somewhere to be downloaded from.
        val sideloaded = LOCAL_CLEANUP_MODEL.copy(sourceUrl = null)
        assertTrue(ModelDownloader.isSideloadOnly(sideloaded))
    }

    @Test fun `every shipping local-cleanup model is downloadable, not sideload-only`() {
        // Guards the #134 regression directly: a local-cleanup entry with no host silently becomes
        // un-installable for anyone who cannot adb push it.
        LOCAL_CLEANUP_MODEL_CATALOG.forEach {
            assertFalse("${it.name} has no sourceUrl", ModelDownloader.isSideloadOnly(it))
        }
    }

    @Test fun `a local-cleanup model with a real sourceUrl is downloadable, not sideload-only`() {
        assertFalse(ModelDownloader.isSideloadOnly(LOCAL_CLEANUP_MODEL))
    }

    @Test fun `an ASR model (not local-cleanup) is never sideload-only even though its sourceUrl is null`() {
        // Offline/streaming models legitimately have sourceUrl == null -- they resolve to the
        // sherpa-onnx release BASE_URL. isSideloadOnly must apply only to local-cleanup entries.
        val asr = MODEL_CATALOG.first { it.sourceUrl == null }
        assertFalse(ModelDownloader.isSideloadOnly(asr))
    }

    @Test fun `a sideload-only download failure is classified terminal, not a 404 retry loop`() {
        // download() short-circuits a sideload-only model to DownloadState.Error with no
        // IOException cause; shouldRetry must then treat it as terminal (no retries) rather than
        // the transient-IO 404 loop the old BASE_URL fallback produced (#H7).
        val sideloadError = DownloadState.Error("sideload-only", cause = null)
        assertFalse(ModelDownloadWorker.shouldRetry(sideloadError.cause, runAttemptCount = 0))
    }

    // -- orphaned model dir pruning (post-Q4_K_M-removal cleanup) --

    @Test fun `orphanedArchives flags installed dirs no longer in the catalog`() {
        val installed = listOf("lfm2.5-350m-q4_0", "mumble-cleanup-2stage-q4km", "mumble-cleanup-2stage-q4_0")
        val catalog = setOf("lfm2.5-350m-q4_0", "mumble-cleanup-2stage-q4_0")
        assertEquals(listOf("mumble-cleanup-2stage-q4km"), ModelDownloader.orphanedArchives(installed, catalog))
    }

    @Test fun `orphanedArchives returns nothing when every installed dir is still cataloged`() {
        val installed = listOf("lfm2.5-350m-q4_0", "mumble-cleanup-2stage-q4_0")
        val catalog = setOf("lfm2.5-350m-q4_0", "mumble-cleanup-2stage-q4_0")
        assertTrue(ModelDownloader.orphanedArchives(installed, catalog).isEmpty())
    }

    @Test fun `orphanedArchives returns nothing when nothing is installed`() {
        assertTrue(ModelDownloader.orphanedArchives(emptyList(), setOf("lfm2.5-350m-q4_0")).isEmpty())
    }

    @Test fun `pruneOrphanedModelDirs deletes a stale cleanup-model dir no longer in the catalog`() {
        withTempDir { filesDir ->
            val cleanupModelsDir = File(filesDir, "cleanup_models")
            val staleDir = File(cleanupModelsDir, "mumble-cleanup-2stage-q4km").apply { mkdirs() }
            File(staleDir, "mumble-cleanup-2stage-q4km.gguf").writeText("fake-gguf-bytes")
            File(staleDir, ".complete").writeText("")
            val liveDir = File(cleanupModelsDir, MUMBLE_CLEANUP_Q4_0_MODEL.archive).apply { mkdirs() }
            File(liveDir, ".complete").writeText("")

            ModelDownloader.pruneOrphanedModelDirs(filesDir)

            assertFalse("orphaned Q4_K_M dir should be deleted", staleDir.exists())
            assertTrue("still-cataloged Q4_0 dir must survive pruning", liveDir.exists())
        }
    }

    @Test fun `pruneOrphanedModelDirs is a no-op when a kind dir doesn't exist yet`() {
        withTempDir { filesDir ->
            // Fresh install: none of "models"/"streaming_models"/"cleanup_models" exist yet.
            ModelDownloader.pruneOrphanedModelDirs(filesDir)
            // No exception, nothing to assert beyond "didn't crash".
        }
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

    @Test fun `a checksum mismatch after resuming a partial triggers one clean restart (M16)`() {
        assertTrue(ModelDownloader.shouldCleanRetryAfterChecksumMismatch(resumedBytes = 1000))
    }

    @Test fun `a checksum mismatch on a from-scratch download stays terminal (M16)`() {
        assertFalse(ModelDownloader.shouldCleanRetryAfterChecksumMismatch(resumedBytes = 0))
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

    // -- installed-aware active-model resolution (#134) --
    //
    // The default flip (LFM2.5 -> mumble-cleanup) is only safe because every caller resolves
    // through this overload. The scenario that forced it: a device with LFM2.5 installed and the
    // "local_cleanup_model_name" preference never written (Trevor's Fold). Before centralizing,
    // the service fell back to a hardcoded LOCAL_CLEANUP_MODEL constant while the Settings picker
    // fell back to the recommended entry -- both landed on LFM only by coincidence of the old
    // default. After the flip, a recommended-entry fallback would resolve to a model that isn't
    // on disk, localCleanupModelFile would return null, and local cleanup would silently die on a
    // device where it worked the day before.

    @Test fun `an explicit selection wins even when that model is not installed`() {
        // Pre-existing semantic, preserved: a deliberate pick with the download still pending (or
        // the file deleted out from under it) must not silently become a different model.
        val resolved = ModelDownloader.resolveActiveModel(
            LOCAL_CLEANUP_MODEL_CATALOG, LOCAL_CLEANUP_MODEL.archive
        ) { false }
        assertEquals(LOCAL_CLEANUP_MODEL, resolved)
    }

    @Test fun `no selection resolves to the recommended entry when it is installed`() {
        val resolved = ModelDownloader.resolveActiveModel(
            LOCAL_CLEANUP_MODEL_CATALOG, ""
        ) { it == MUMBLE_CLEANUP_Q4_0_MODEL }
        assertEquals(MUMBLE_CLEANUP_Q4_0_MODEL, resolved)
    }

    @Test fun `no selection prefers the one installed model over a not-installed recommended entry`() {
        // The existing-user case the overload exists for: only LFM2.5 is on disk, pref unset --
        // the flip must not strand this device on the not-yet-downloaded new default.
        val resolved = ModelDownloader.resolveActiveModel(
            LOCAL_CLEANUP_MODEL_CATALOG, ""
        ) { it == LOCAL_CLEANUP_MODEL }
        assertEquals(LOCAL_CLEANUP_MODEL, resolved)
    }

    @Test fun `no selection and nothing installed resolves to the recommended entry`() {
        // Fresh install: nothing on disk yet, and the recommended entry is what onboarding will
        // download -- resolving to it is what makes that download the active model afterwards.
        val resolved = ModelDownloader.resolveActiveModel(
            LOCAL_CLEANUP_MODEL_CATALOG, ""
        ) { false }
        assertEquals(MUMBLE_CLEANUP_Q4_0_MODEL, resolved)
    }

    @Test fun `a stale selection no longer in the catalog resolves installed-aware, not catalog-blind`() {
        // A pref naming a removed archive (like the pruned Q4_K_M mumble build) must degrade the
        // same way "never set" does: keep whatever is actually installed.
        val resolved = ModelDownloader.resolveActiveModel(
            LOCAL_CLEANUP_MODEL_CATALOG, "mumble-cleanup-2stage-q4_k_m"
        ) { it == LOCAL_CLEANUP_MODEL }
        assertEquals(LOCAL_CLEANUP_MODEL, resolved)
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
