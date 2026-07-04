package com.kafkasl.phonewhisper

import android.content.Context
import okhttp3.OkHttpClient
import okhttp3.Request
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.*
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

data class Model(
    val name: String,
    val archive: String,
    val sizeMb: Int,
    val quality: String,
    val recommended: Boolean = false,
    /**
     * Expected SHA-256 of the `<archive>.tar.bz2` asset, lowercase hex.
     * Verified against `checksum.txt` published alongside the sherpa-onnx
     * `asr-models` GitHub release (and independently re-hashed from the
     * downloaded assets) on 2026-07-02. Null means "not sourced yet" — the
     * download intentionally fails rather than silently skipping the check;
     * see [ModelDownloader.download].
     */
    val sha256: String? = null,
    /**
     * True for a streaming (sherpa-onnx OnlineRecognizer) model, as opposed to the batch
     * OfflineRecognizer models above (#29). Installed under a separate `streaming_models/`
     * directory (see [ModelDownloader.modelDir]) so it can never be picked up by
     * [LocalTranscriber.availableModels]'s scan of `models/` and mistaken for an offline model —
     * its encoder/decoder/joiner files match that offline auto-detection's file-name heuristics
     * but aren't compatible with the OfflineRecognizer graph shapes.
     */
    val isStreaming: Boolean = false,
    /**
     * True for the single curated on-device cleanup GGUF model (#37), as opposed to the
     * tar.bz2 ASR archives above. Installed under its own `cleanup_models/` directory (see
     * [ModelDownloader.modelDir]) -- separate from both `models/` and `streaming_models/`, and
     * never scanned by [LocalTranscriber]. Unlike the ASR archives, a local-cleanup model is
     * downloaded from Hugging Face ([sourceUrl]) as a single `.gguf` file rather than extracted
     * from a tar.bz2 -- see [ModelDownloader.download]/[ModelDownloader.installSingleFile].
     */
    val isLocalCleanup: Boolean = false,
    /** Full download URL, overriding the default sherpa-onnx-release URL pattern. Required when
     *  [isLocalCleanup] is true, since those models are hosted on Hugging Face, not GitHub Releases. */
    val sourceUrl: String? = null,
    /** File name the downloaded single file is installed as. Required when [isLocalCleanup] is true. */
    val fileName: String? = null,
)

val MODEL_CATALOG = listOf(
    Model("Parakeet 110M", "sherpa-onnx-nemo-parakeet_tdt_ctc_110m-en-36000-int8",
        100, "★★★ Best value", recommended = true,
        sha256 = "17f945007b52ccd8b7200ffc7c5652e9e8e961dfdf479cefcabd06cf5703630b"),
    Model("Whisper Base", "sherpa-onnx-whisper-base.en",
        199, "★★★",
        sha256 = "475bc7052ce299c007f6d5d5407ba8601f819a2867f6eecee510ed17df581542"),
    Model("Parakeet 0.6B", "sherpa-onnx-nemo-parakeet-tdt-0.6b-v3-int8",
        465, "★★★★ Best quality",
        sha256 = "5793d0fd397c5778d2cf2126994d58e9d56b1be7c04d13c7a15bb1b4eafb16bf"),
    Model("Moonshine Tiny", "sherpa-onnx-moonshine-tiny-en-int8",
        103, "★★☆ Fast",
        sha256 = "d5fe6ec4334fef36255b2a4010412cad4c007e33103fec62fb5d17cad88086f2"),
)

/**
 * Streaming zipformer (English), used only for live preview during recording (#29) — the final
 * injected transcript always still comes from the batch [MODEL_CATALOG] (or cloud) pipeline above,
 * unchanged. Kept in its own catalog/list rather than appended to [MODEL_CATALOG] so it's never
 * offered as a selectable *offline* model. Archive size and SHA-256 verified against `checksum.txt`
 * published alongside the sherpa-onnx `asr-models` GitHub release (and independently re-hashed from
 * the downloaded asset) on 2026-07-03.
 */
val STREAMING_MODEL = Model("Streaming Zipformer (EN)", "sherpa-onnx-streaming-zipformer-en-20M-2023-02-17",
    128, "★★☆ Live preview",
    sha256 = "9c559283e8498d3fe95913c79ca1cb454bb26281ac2b102b41306c7d752765d9",
    isStreaming = true)

val STREAMING_MODEL_CATALOG = listOf(STREAMING_MODEL)

/**
 * The one curated on-device cleanup model shipped for #37 -- no arbitrary user-supplied GGUF
 * support in this pass (see the issue's non-goals). Qwen2.5-0.5B-Instruct is small enough to run
 * acceptably CPU-only on modest Android hardware while still following cleanup instructions
 * well; Q4_K_M is the standard "good quality, small size" quantization used throughout the GGUF
 * ecosystem. Sourced from the real, verifiable `Qwen/Qwen2.5-0.5B-Instruct-GGUF` Hugging Face
 * repo (Apache-2.0 license) on 2026-07-03; [sha256] was computed by downloading the exact file
 * and hashing it locally (`shasum -a 256`), the same verification approach used for the sherpa-
 * onnx ASR models above -- not copied from a webpage.
 */
val LOCAL_CLEANUP_MODEL = Model(
    name = "Qwen2.5 0.5B Instruct (Q4_K_M)",
    archive = "qwen2.5-0.5b-instruct-q4_k_m",
    sizeMb = 492,
    quality = "★★☆ On-device cleanup",
    recommended = true,
    sha256 = "74a4da8c9fdbcd15bd1f6d01d621410d31c6fc00986f5eb687824e7b93d7a9db",
    isLocalCleanup = true,
    sourceUrl = "https://huggingface.co/Qwen/Qwen2.5-0.5B-Instruct-GGUF/resolve/main/qwen2.5-0.5b-instruct-q4_k_m.gguf",
    fileName = "qwen2.5-0.5b-instruct-q4_k_m.gguf",
)

val LOCAL_CLEANUP_MODEL_CATALOG = listOf(LOCAL_CLEANUP_MODEL)

sealed class DownloadState {
    data class Downloading(val progress: Float) : DownloadState()
    object Extracting : DownloadState()
    object Done : DownloadState()
    data class Error(val message: String) : DownloadState()
}

/** Thrown when there isn't enough free space to safely download and extract a
 *  model. Kept distinct from generic IOException so callers/UI can show a
 *  specific "not enough space" message instead of a generic network error. */
class NotEnoughSpaceException(requiredBytes: Long, availableBytes: Long) : IOException(
    "Not enough free space: need ~${requiredBytes / 1_000_000} MB, " +
        "have ~${availableBytes / 1_000_000} MB available"
)

object ModelDownloader {
    private const val BASE_URL =
        "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models"
    private val client = OkHttpClient.Builder()
        .readTimeout(60, TimeUnit.SECONDS).build()

    /** Multiplier applied to a model's compressed archive size to estimate the
     *  free space needed to safely download and extract it. We don't track
     *  exact uncompressed sizes per model, so this is a heuristic: 1x covers
     *  the compressed archive sitting in cache, and the remaining headroom
     *  covers the extracted files coexisting with it in staging before the
     *  atomic move to finalDir. These are bzip2-compressed ONNX weights,
     *  which don't expand dramatically, so 3x compressed size is comfortable
     *  headroom without being wasteful. */
    const val SPACE_HEADROOM_MULTIPLIER = 3L

    /** Bytes required to safely download and extract a model whose compressed
     *  archive is [sizeMb] megabytes. */
    fun requiredSpaceBytes(sizeMb: Int): Long =
        sizeMb.toLong() * 1_000_000L * SPACE_HEADROOM_MULTIPLIER

    /** True if [availableBytes] covers [sizeMb] of compressed archive plus
     *  extraction headroom (see [SPACE_HEADROOM_MULTIPLIER]). */
    fun hasEnoughSpace(availableBytes: Long, sizeMb: Int): Boolean =
        availableBytes >= requiredSpaceBytes(sizeMb)

    /** "models" for a batch/offline model, "streaming_models" for a streaming one (#29), or
     *  "cleanup_models" for the on-device cleanup GGUF model (#37) — kept out of files/models/
     *  entirely so it's structurally impossible for [LocalTranscriber.availableModels]'s
     *  directory scan to ever see it. */
    private fun kindDir(model: Model) = when {
        model.isLocalCleanup -> "cleanup_models"
        model.isStreaming -> "streaming_models"
        else -> "models"
    }

    /** The installed GGUF file for a local-cleanup [model], or null if it isn't fully installed.
     *  Only meaningful for [Model.isLocalCleanup] models. */
    fun localCleanupModelFile(ctx: Context, model: Model): File? {
        val dir = modelDir(ctx, model)
        if (!isInstalledDir(dir)) return null
        return File(dir, model.fileName ?: model.archive)
    }

    /** Pure path computation, exposed separately from [modelDir] so it's testable without a real
     *  Android [Context]. */
    fun modelDirPath(filesDir: File, model: Model): File =
        File(filesDir, "${kindDir(model)}/${model.archive}")

    fun modelDir(ctx: Context, model: Model) = modelDirPath(ctx.filesDir, model)

    /** Staging dir for an in-progress extraction. Lives outside files/models/ (and the streaming
     *  equivalent) so a half-extracted model never shows up as installed. */
    private fun stagingDir(ctx: Context, model: Model) =
        File(ctx.cacheDir, "model-extract/${kindDir(model)}/${model.archive}")

    /** Marker written only after extraction + atomic move both succeed. */
    fun completeMarker(dir: File): File = File(dir, ".complete")

    /** True only if [dir] is a fully-installed model: present *and* marked complete.
     *  A directory left behind by an interrupted extraction has no marker and
     *  reads as not-installed, so it's never auto-selected and the download
     *  button reappears for it. */
    fun isInstalledDir(dir: File): Boolean = dir.isDirectory && completeMarker(dir).isFile

    fun isInstalled(ctx: Context, model: Model) = isInstalledDir(modelDir(ctx, model))

    /**
     * Download and extract model, blocking the calling thread until done (or
     * failed). Callbacks fire synchronously on the calling thread. Callers
     * (currently [ModelDownloadWorker]) are responsible for running this off
     * the main thread; this function itself no longer spawns one, so a
     * single in-flight download can't race a second call writing the same
     * [tmpFile] -- that single-flight guarantee is enforced by the caller
     * (WorkManager unique work), not here.
     */
    fun download(ctx: Context, model: Model, onState: (DownloadState) -> Unit) {
        val url = model.sourceUrl ?: "$BASE_URL/${model.archive}.tar.bz2"
        val tmpFile = File(ctx.cacheDir, model.fileName ?: "${model.archive}.tar.bz2")
        val staging = stagingDir(ctx, model)
        val finalDir = modelDir(ctx, model)

        // Only cleared once downloadFile() returns successfully, i.e. the
        // archive on disk is complete. If a network error interrupts the
        // download partway, tmpFile is left in place so the next call can
        // resume it via HTTP Range instead of restarting from byte 0.
        var downloadComplete = false
        try {
            val availableBytes = minOf(ctx.cacheDir.usableSpace, ctx.filesDir.usableSpace)
            if (!hasEnoughSpace(availableBytes, model.sizeMb)) {
                throw NotEnoughSpaceException(requiredSpaceBytes(model.sizeMb), availableBytes)
            }
            downloadFile(url, tmpFile, onState)
            downloadComplete = true
            val expected = model.sha256
                ?: throw IOException("No checksum configured for ${model.archive}; refusing to install unverified")
            verifyChecksum(tmpFile, expected)
            if (model.isLocalCleanup) {
                onState(DownloadState.Extracting) // no real extraction, but keeps the UI phase consistent
                installSingleFile(tmpFile, finalDir, model.fileName ?: model.archive)
            } else {
                onState(DownloadState.Extracting)
                extractAndInstall(tmpFile, staging, finalDir, model.archive)
            }
            onState(DownloadState.Done)
        } catch (e: Exception) {
            onState(DownloadState.Error(e.message ?: "Unknown error"))
        } finally {
            // A checksum mismatch also lands here with downloadComplete == true:
            // the archive is fully present but wrong, so resuming it wouldn't
            // help -- delete it and let the next attempt start clean.
            if (downloadComplete) tmpFile.delete()
        }
    }

    fun delete(ctx: Context, model: Model) {
        modelDir(ctx, model).deleteRecursively()
        stagingDir(ctx, model).deleteRecursively()
    }

    /**
     * What the "model_name" preference (the offline-model selection, #51) should become after
     * [deletedArchive] is uninstalled, given [currentArchive] ("" if nothing was selected) and
     * [remainingInstalled] (archives from [MODEL_CATALOG] still installed after the delete). Left
     * untouched if the deleted model wasn't the selected one. Otherwise falls back to another
     * installed model when one exists, or "" ("no model selected") so a stale reference to
     * now-deleted files is never left behind.
     */
    fun resolveSelectionAfterDelete(
        currentArchive: String,
        deletedArchive: String,
        remainingInstalled: List<String>,
    ): String {
        if (currentArchive != deletedArchive) return currentArchive
        return remainingInstalled.firstOrNull() ?: ""
    }

    /** SHA-256 of [file] as lowercase hex. */
    fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { src ->
            val buf = ByteArray(16384)
            var n: Int
            while (src.read(buf).also { n = it } != -1) digest.update(buf, 0, n)
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /** Throws [IOException] if [file]'s SHA-256 doesn't match [expectedSha256]. */
    fun verifyChecksum(file: File, expectedSha256: String) {
        val actual = sha256(file)
        if (!actual.equals(expectedSha256, ignoreCase = true)) {
            throw IOException("Checksum mismatch: expected $expectedSha256, got $actual")
        }
    }

    /**
     * Extracts [archive] into a fresh [staging] directory, then atomically moves
     * the resulting `<archiveName>` directory into [finalDir] and writes the
     * completion marker last. Any prior contents of [finalDir] (e.g. a corrupt
     * install from before this fix) are replaced only once the new extraction
     * is verified good. On any failure, [staging] is removed and [finalDir] is
     * left untouched (or absent) — never left in a partially-written state.
     */
    fun extractAndInstall(archive: File, staging: File, finalDir: File, archiveName: String) {
        try {
            staging.deleteRecursively()
            staging.mkdirs()
            extractTarBz2(archive, staging)

            val extractedRoot = File(staging, archiveName)
            if (!extractedRoot.isDirectory) {
                throw IOException("Archive did not produce expected directory: $archiveName")
            }

            finalDir.parentFile?.mkdirs()
            finalDir.deleteRecursively()
            // renameTo is atomic when staging/ and files/models/ share a filesystem
            // (the normal case for app-private storage); fall back to copy+delete
            // for the rare cross-filesystem case.
            if (!extractedRoot.renameTo(finalDir)) {
                extractedRoot.copyRecursively(finalDir, overwrite = true)
                extractedRoot.deleteRecursively()
            }
            if (!completeMarker(finalDir).createNewFile()) {
                throw IOException("Failed to write completion marker")
            }
        } finally {
            staging.deleteRecursively()
        }
    }

    /**
     * Installs a single downloaded file (e.g. a `.gguf`) directly into [finalDir] under
     * [fileName], with no extraction step -- the local-cleanup-model counterpart to
     * [extractAndInstall] (#37). Same atomic-move-then-mark-complete shape: any prior contents of
     * [finalDir] are replaced only once the new file is in place, and the completion marker is
     * written last so a half-installed model never reads as installed.
     */
    fun installSingleFile(file: File, finalDir: File, fileName: String) {
        finalDir.parentFile?.mkdirs()
        finalDir.deleteRecursively()
        finalDir.mkdirs()
        val dest = File(finalDir, fileName)
        // renameTo is atomic when cacheDir and filesDir share a filesystem (the normal case for
        // app-private storage); fall back to copy+delete for the rare cross-filesystem case.
        if (!file.renameTo(dest)) {
            file.copyTo(dest, overwrite = true)
        }
        if (!completeMarker(finalDir).createNewFile()) {
            throw IOException("Failed to write completion marker")
        }
    }

    /** HTTP Range header value to resume a download whose partial file already
     *  has [existingLength] bytes on disk, or null if there's nothing to resume. */
    fun rangeHeaderFor(existingLength: Long): String? =
        if (existingLength > 0) "bytes=$existingLength-" else null

    /** Decides how to continue a download given what's already on disk
     *  ([existingLength]) and how the server responded to our Range request
     *  ([responseCode]). Only a 206 (Partial Content) confirms the server
     *  honored the range; any other code (e.g. 200) means it sent the whole
     *  file from scratch, so we must overwrite rather than append. */
    fun planResume(existingLength: Long, responseCode: Int): ResumePlan =
        if (existingLength > 0 && responseCode == 206) ResumePlan(offset = existingLength, append = true)
        else ResumePlan(offset = 0L, append = false)

    data class ResumePlan(val offset: Long, val append: Boolean)

    /** Total size of the full download, combining [offset] (bytes already on
     *  disk when resuming) with what the response reports. Prefers the
     *  authoritative total from a `Content-Range: bytes start-end/total`
     *  header when present, falling back to offset + Content-Length. */
    fun computeTotalBytes(offset: Long, contentLength: Long, contentRange: String?): Long {
        contentRange?.substringAfterLast('/')?.toLongOrNull()?.let { return it }
        return if (contentLength >= 0) offset + contentLength else -1L
    }

    private fun downloadFile(
        url: String, dest: File, onState: (DownloadState) -> Unit
    ) {
        val existingLength = if (dest.isFile) dest.length() else 0L
        val requestBuilder = Request.Builder().url(url)
        rangeHeaderFor(existingLength)?.let { requestBuilder.header("Range", it) }

        val response = client.newCall(requestBuilder.build()).execute()
        if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
        val body = response.body ?: throw IOException("Empty response")

        val plan = planResume(existingLength, response.code)
        val total = computeTotalBytes(plan.offset, body.contentLength(), response.header("Content-Range"))
        var downloaded = plan.offset

        body.byteStream().use { src ->
            FileOutputStream(dest, plan.append).use { dst ->
                val buf = ByteArray(16384)
                var n: Int
                while (src.read(buf).also { n = it } != -1) {
                    dst.write(buf, 0, n)
                    downloaded += n
                    if (total > 0)
                        onState(DownloadState.Downloading(downloaded.toFloat() / total))
                }
            }
        }
    }

    /** Extract tar.bz2 to outDir. Validates paths to prevent traversal. */
    fun extractTarBz2(archive: File, outDir: File) {
        outDir.mkdirs()
        val bzIn = BZip2CompressorInputStream(BufferedInputStream(FileInputStream(archive)))
        TarArchiveInputStream(bzIn).use { tar ->
            generateSequence { tar.nextEntry }.forEach { entry ->
                val dest = File(outDir, entry.name)
                require(dest.canonicalPath.startsWith(outDir.canonicalPath)) {
                    "Path traversal: ${entry.name}"
                }
                if (entry.isDirectory) dest.mkdirs()
                else {
                    dest.parentFile?.mkdirs()
                    FileOutputStream(dest).use { tar.copyTo(it) }
                }
            }
        }
    }
}
