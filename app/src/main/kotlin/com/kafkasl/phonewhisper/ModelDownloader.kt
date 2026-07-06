package com.kafkasl.phonewhisper

import android.content.Context
import android.util.Log
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
    /** sherpa-onnx `OnlineModelConfig.modelType` for a streaming model (#29/#50) -- "zipformer" and
     *  "zipformer2" are distinct architectures with incompatible graphs, so this must match what
     *  the model was actually exported as (see sherpa-onnx's own bundled recognizer configs) rather
     *  than being hardcoded per catalog. Meaningless when [isStreaming] is false. */
    val streamingModelType: String = "zipformer",
    /**
     * Overrides [CleanupWaterfallExecutor]'s default `PostProcessor.SIMPLE_PROMPT` system message
     * for this specific local-cleanup model, when non-null. Only meaningful when [isLocalCleanup]
     * is true.
     *
     * Exists because SIMPLE_PROMPT's "plain-language instruction, no few-shot examples" design
     * (see its own kdoc) assumes a *general-purpose* small instruct model with enough instruction-
     * following margin to tolerate a system prompt it wasn't trained on verbatim. A model
     * *fine-tuned* for this exact task -- like `mumble-cleanup-2stage`, a 0.5B LoRA fine-tune
     * trained with completion-only masking against one fixed system prompt (see its model card at
     * https://huggingface.co/amitashwini/mumble-cleanup-2stage) -- has no such margin: it was never
     * shown any other instruction during training, so an unfamiliar prompt makes it echo/garble
     * instead of clean (confirmed live 2026-07-06: Trevor got the system prompt itself injected as
     * "cleaned" text). This field lets a fine-tuned catalog entry carry its own required prompt so
     * [LocalCleanupProvider.selectedSystemPrompt] can pick it over SIMPLE_PROMPT.
     */
    val localSystemPrompt: String? = null,
)

// Listed best-quality-first (#54-followup): Trevor asked for models to read in that order at a
// glance rather than requiring a mental sort by the inline quality label. Canary 180M Flash is
// the one non-tiered "alternative architecture" entry (multilingual, punctuated) and is
// interleaved by its own real quality standing, not pinned to the bottom.
val MODEL_CATALOG = listOf(
    Model("Parakeet 0.6B", "sherpa-onnx-nemo-parakeet-tdt-0.6b-v3-int8",
        465, "★★★★ Best quality",
        sha256 = "5793d0fd397c5778d2cf2126994d58e9d56b1be7c04d13c7a15bb1b4eafb16bf"),
    // Smallest AND best-value entry in the catalog (100MB, ~7.5% avg WER on the Open ASR
    // Leaderboard) -- recommended default.
    Model("Parakeet 110M", "sherpa-onnx-nemo-parakeet_tdt_ctc_110m-en-36000-int8",
        100, "★★★ Best value · Smallest", recommended = true,
        sha256 = "17f945007b52ccd8b7200ffc7c5652e9e8e961dfdf479cefcabd06cf5703630b"),
    // Replaces Whisper Base.en for #98 (Claude Fable 5 STT model consult): Canary-180m-flash is
    // strictly better in the same size class -- 7.12% avg WER vs. Whisper Base.en's 10.32%
    // (Open ASR Leaderboard), plus real punctuation/capitalization and en/es/de/fr support,
    // where Whisper Base.en also pads every utterance to a fixed 30-second window regardless of
    // actual dictation length. Real attention-decoder architecture (a bit slower per token than
    // Parakeet's CTC/TDT decoders, but batch transcription -- not live-critical the way streaming
    // preview is). CC-BY-4.0 licensed. URL/sha256 verified 2026-07-05 by downloading the exact
    // 153,692,328-byte asset from the real sherpa-onnx GitHub release and hashing it locally
    // (`shasum -a 256`), same discipline as every other entry in this file.
    Model("Canary 180M Flash", "sherpa-onnx-nemo-canary-180m-flash-en-es-de-fr-int8",
        147, "★★★★ Multilingual, punctuated",
        sha256 = "7a38ed8b13f014ad632b09ff8d22e0c6f1359dd046af9235d281dfae841b9ab9"),
    // Moonshine Tiny REMOVED (#98, follow-up to Trevor's request to clean up mislabeled/confusing
    // catalog entries): verified against Useful Sensors' own published benchmarks and the Open ASR
    // Leaderboard, "English Tiny" (the non-streaming variant shipped here) has ~12.66% WER --
    // nearly double Parakeet 110M's ~7.5% -- while ALSO being larger on disk (103MB vs. 100MB).
    // It was labeled "Fast alternative" and described in comments as "the smallest genuinely-good
    // English option," neither of which was true: it's strictly dominated by Parakeet 110M on
    // every axis (size, WER, and it's the existing recommended default already installed for most
    // users) with no redeeming tradeoff left to offer. A tier that loses on every axis isn't a
    // real choice, just confusion.
    // NeMo Conformer CTC Small REMOVED for #98 (Claude Fable 5 STT model consult): despite being
    // the smallest entry by raw size (76MB), it outputs lowercase text with no punctuation at
    // all -- disqualifying for a dictation app regardless of WER, since every other model here
    // produces real capitalization and punctuation. Better to have one fewer, genuinely usable
    // tier than a technically-smaller one that silently produces worse-looking output for every
    // single dictation.
)

/**
 * Streaming zipformer2 (English), used only for live preview during recording (#29) -- the final
 * injected transcript always still comes from the batch [MODEL_CATALOG] (or cloud) pipeline
 * above, unchanged. Kept in its own catalog/list rather than appended to [MODEL_CATALOG] so it's
 * never offered as a selectable *offline* model.
 *
 * Collapsed to a single tier for #98 (Claude Fable 5 STT model consult): the prior 3-tier catalog
 * (310MB "best quality" / 128MB "best value" / 57MB "smallest") spread a quality difference of
 * only 0.9-2.0pp LibriSpeech WER across a 5.4x size range -- invisible in a cosmetic live preview
 * whose every word gets replaced by the real batch transcript, while the larger tiers burned
 * proportionally more CPU *during recording*, competing directly with the local cleanup model's
 * record-start pre-warm (see [LocalCleanupModelHolder.warmUpAsync]) for the same performance
 * cores. This is now the Kroko community model (Zipformer2, Aug 2025 release from Banafo) --
 * already the smallest of the three prior tiers, now the only one. `modelType = "zipformer2"`,
 * NOT "zipformer" -- confirmed against sherpa-onnx's own bundled example recognizer config for
 * this exact archive (`OnlineRecognizer.kt` in this repo's vendored sherpa-onnx sources), since
 * the two architectures aren't interchangeable. License is CC-BY-SA (Banafo/Kroko-ASR) --
 * verified against the model card. URL/sha256 verified 2026-07-04 by downloading the exact asset
 * and hashing it locally.
 */
val STREAMING_MODEL = Model("Streaming Zipformer2 (EN)", "sherpa-onnx-streaming-zipformer-en-kroko-2025-08-06",
    57, "★★★ Live preview", recommended = true,
    sha256 = "c8676e5ff9ac2a85296e53ee0fd4d5fb1db6770e7a7647166eeafe349ade6834",
    isStreaming = true, streamingModelType = "zipformer2")

val STREAMING_MODEL_CATALOG = listOf(STREAMING_MODEL)

/**
 * The one curated on-device cleanup model shipped for #37 -- no arbitrary user-supplied GGUF
 * support in this pass (see the issue's non-goals).
 *
 * Swapped from Qwen2.5-0.5B-Instruct to LFM2.5-350M for #98, following a dedicated Claude
 * Fable 5 architecture consult after the #92/#95/#96/#97 native-hang investigation: the real
 * problem was never the engine (llama.cpp is still the right choice on this hardware -- no
 * third-party NPU/GPU acceleration exists for ANY engine on a Pixel-class Tensor chip as of this
 * consult) or the timeout plumbing -- it was that Qwen2.5-0.5B is both larger AND measurably
 * worse at instruction-following than newer small models in its size class. LFM2.5-350M is
 * Liquid AI's general-purpose instruction-tuned checkpoint (not a separately-named "-Instruct"
 * variant -- `LiquidAI/LFM2.5-350M` itself is the post-trained model; `-Base` is the separate
 * pre-trained-only checkpoint) with IFEval 71.7 (instruction-following -- precisely the "output
 * ONLY the cleaned text" skill this task lives on) vs. Qwen2.5-0.5B's ~31-42, while being
 * smaller (219MB Q4_0 vs. 492MB Q4_K_M) and reportedly ~2.5x faster to decode on CPU. Uses a
 * ChatML-like template (`<|im_start|>`/`<|im_end|>`, embedded in the GGUF, same as Qwen2.5 --
 * [LlamaCppInference]'s `chatTemplate = ""` already falls back to whatever template is embedded,
 * so no binding change was needed) and [SpecialTokenSanitizer]'s existing ChatML-token sanitizing
 * (#78) applies unchanged. Sourced from the real, verifiable `LiquidAI/LFM2.5-350M-GGUF` Hugging
 * Face repo (LFM Open License v1.0 -- free for commercial use under $10M annual revenue, verified
 * against the license text on 2026-07-05) on 2026-07-05; [sha256] was computed by downloading the
 * exact file and hashing it locally (`shasum -a 256`), the same verification approach used for
 * every other catalog entry -- not copied from a webpage.
 *
 * ([SpecialTokenSanitizer]'s `<|...|>` shape-based stripping (#78) is model-family-agnostic and
 * needed no update for this swap.)
 *
 * Q4_0 (not Q4_K_M) is deliberately used here, also per the Fable consult: Q4_0 is the quantization
 * llama.cpp's ARM i8mm/dotprod kernels are specifically optimized for, making it the faster choice
 * on real Android CPU hardware even though Q4_K_M is nominally higher quality at rest.
 *
 * This is now the ONLY local-cleanup catalog entry (collapsed from 3 tiers for #98, Trevor's
 * direct request after the model swap): the other two tiers were actively misleading rather than
 * genuine choices. Qwen2.5-1.5B ("best quality") is a ~1.1GB download that would only compound
 * the memory-pressure failures the LFM2.5-350M swap was meant to fix -- it was never going to
 * work reliably on a phone already struggling to run the much smaller model. SmolLM2-360M
 * ("smallest, still good") is independently confirmed BROKEN for this exact task in #54: verified
 * via a standalone host probe against the real production prompt, it replies with generic
 * assistant chit-chat ("I'd be happy to help you refine your text...") instead of cleaning the
 * transcript, reproducibly and deterministically. Both tiers also displayed the same "★★★★"
 * rating as this entry despite being either unusable-on-this-hardware or flat-out broken --
 * confusing, not a real choice. One real, working, benchmarked-against-alternatives model beats
 * three options where two don't actually work.
 */
val LOCAL_CLEANUP_MODEL = Model(
    name = "LFM2.5 350M (Q4_0)",
    archive = "lfm2.5-350m-q4_0",
    sizeMb = 219,
    quality = "On-device cleanup",
    recommended = true,
    sha256 = "85e32858daafad55b7bcd6b97a1343ee0661188e8036f9862d14d6b563142f50",
    isLocalCleanup = true,
    sourceUrl = "https://huggingface.co/LiquidAI/LFM2.5-350M-GGUF/resolve/main/LFM2.5-350M-Q4_0.gguf",
    fileName = "lfm2.5-350m-q4_0.gguf",
)

/**
 * Exact system prompt `mumble-cleanup-2stage` was fine-tuned against (completion-only masked,
 * see [Model.localSystemPrompt]'s kdoc) -- copied verbatim from the model card's `llama-cli`
 * usage example at https://huggingface.co/amitashwini/mumble-cleanup-2stage, not paraphrased.
 * Deliberately NOT run through [PostProcessor.interpolateVocabulary]: this model was never
 * trained with a vocabulary clause, so splicing one in would itself be an unfamiliar-prompt
 * regression of the exact kind this override exists to avoid.
 */
const val MUMBLE_CLEANUP_SYSTEM_PROMPT = "You are a transcript cleanup tool. You receive raw " +
    "speech to text output and return a cleaned version. Remove filler words and disfluencies " +
    "(um, uh, er, ah, like as filler, you know), remove repeated words and false starts, and " +
    "fix punctuation and capitalization. Do not reword, do not add anything the speaker did " +
    "not say, and do not answer questions in the text. Output only the cleaned text."

/**
 * Q4_0 self-quantized mumble-cleanup catalog entry (Trevor-requested A/B test, following the real
 * on-device failure where LFM2.5-350M + Formal persona's DEV_PROMPT drifted into answering/
 * chatting instead of restyling the transcript -- see CleanupWaterfallExecutor's localPrompt fix).
 * Unlike [LOCAL_CLEANUP_MODEL] (a general-purpose instruct model being *prompted* to do cleanup),
 * `mumble-cleanup-2stage` is a LoRA fine-tune of Qwen2.5-0.5B-Instruct *specifically trained* on
 * this exact task: Stage 1 pretrains on 50,000 synthetic (raw, clean) transcript pairs, Stage 2
 * fine-tunes on 638 hand-curated real-style pairs at a 10x lower learning rate to preserve a
 * "no-reword, no-hallucination" contract -- the same failure mode Trevor hit live. Its own
 * DictationQuality golden-corpus eval claims 10/10 (self-reported, not independently verified
 * here; that's exactly what this A/B test is for).
 *
 * Real prior art found via direct web research (not built from scratch) at Trevor's explicit
 * request to search first: `adikuma/mumble-cleanup` (the original) and
 * `amitashwini/mumble-cleanup-2stage` (source of this entry, the improved two-stage version) are
 * both real, Apache-2.0-licensed Hugging Face models. Apache-2.0 covers both the LoRA fine-tune
 * and the base Qwen2.5-0.5B-Instruct model -- free for any use, no revenue-cap license terms
 * unlike LFM2.5.
 *
 * `recommended = false`: LFM2.5-350M stays the default so installing this is purely additive for
 * Trevor's A/B test, not a silent swap of what every existing user gets. Whichever wins gets
 * promoted to `recommended` in a follow-up once real-world testing confirms it.
 *
 * History: the upstream model only publishes f16 and Q4_K_M GGUFs. The original catalog entry was
 * the prebuilt Q4_K_M asset (URL/sha256 verified 2026-07-06 by downloading the exact asset from
 * the real `amitashwini/mumble-cleanup-2stage` Hugging Face repo and hashing it locally --
 * 397,807,904 bytes, matching the model card's documented ~379 MB Q4_K_M size). That build blew
 * through the full 15s [CLEANUP_WATERFALL_HARD_CAP_MS] and got aborted mid-decode on Trevor's
 * device -- confirmed not usable there at all -- so it was removed from the catalog entirely and
 * replaced with this self-quantized Q4_0 build, which came in at ~2.9s on-device. No prebuilt
 * Q4_0 GGUF exists upstream, so this was produced locally with the *same* llama-quantize tool
 * vendored in this repo (llama.cpp/tools/quantize/quantize.cpp) that any GGUF publisher would use
 * -- not a hand-rolled format, just a different target quant of the same upstream f16 weights,
 * chosen because [LOCAL_CLEANUP_MODEL]'s own kdoc already documents Q4_0 hitting the faster ARM
 * i8mm/dotprod dot-product kernels llama.cpp ships for this legacy quant type, where Q4_K_M does
 * not benefit from the same path.
 *
 * `sourceUrl = null`: unlike every other catalog entry, this file has no direct HF download URL --
 * it exists only because it was quantized locally from `mumble-cleanup-2stage-f16.gguf`
 * (verified via the same shasum -a 256 discipline before quantizing: f16 source hash
 * `7659e5dc4df164b50be3dce70d80b191fe7ac378a9e8e44b92e5e4313ef9ff82`). If this ends up being kept
 * long-term, it should be re-hosted (e.g. uploaded back to a HF repo Trevor controls) so
 * [sourceUrl]/[fileName] work like every other entry instead of requiring manual `adb push`.
 */
val MUMBLE_CLEANUP_Q4_0_MODEL = Model(
    name = "Mumble Cleanup 2-Stage (Q4_0, local speed test)",
    archive = "mumble-cleanup-2stage-q4_0",
    sizeMb = 336,
    quality = "On-device cleanup · fine-tuned, Q4_0 speed test",
    recommended = false,
    sha256 = "000efc700d74636bc3885afe1d8f32dbb3fe813b8198dea79d8fd73efcc2c711",
    isLocalCleanup = true,
    sourceUrl = null,
    fileName = "mumble-cleanup-2stage-q4_0.gguf",
    localSystemPrompt = MUMBLE_CLEANUP_SYSTEM_PROMPT,
)

val LOCAL_CLEANUP_MODEL_CATALOG = listOf(LOCAL_CLEANUP_MODEL, MUMBLE_CLEANUP_Q4_0_MODEL)

sealed class DownloadState {
    data class Downloading(val progress: Float) : DownloadState()
    object Extracting : DownloadState()
    object Done : DownloadState()
    /** [cause] carries the original exception so [ModelDownloadWorker] can tell transient
     *  network failures (worth a WorkManager retry, #86) from terminal ones. */
    data class Error(val message: String, val cause: Exception? = null) : DownloadState()
}

/** Thrown when there isn't enough free space to safely download and extract a
 *  model. Kept distinct from generic IOException so callers/UI can show a
 *  specific "not enough space" message instead of a generic network error. */
class NotEnoughSpaceException(requiredBytes: Long, availableBytes: Long) : IOException(
    "Not enough free space: need ~${requiredBytes / 1_000_000} MB, " +
        "have ~${availableBytes / 1_000_000} MB available"
)

/** Thrown when the downloaded archive's SHA-256 doesn't match the catalog's pinned hash. A
 *  distinct type (not a bare IOException) because it's a *terminal* failure: the bytes on disk
 *  are complete-but-wrong, so retrying the download can't help until upstream changes (#86). */
class ChecksumMismatchException(expected: String, actual: String) : IOException(
    "Checksum mismatch: expected $expected, got $actual"
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

    /** Headroom for single-file (GGUF) installs, in percent (#88): they are renamed in place,
     *  never extracted, so demanding 3x was a false NotEnoughSpaceException on phones that
     *  genuinely had room -- the 1117 MB Qwen 1.5B demanded ~3.35 GB free. 120% covers the file
     *  itself plus filesystem slack. */
    const val SINGLE_FILE_HEADROOM_PERCENT = 120L

    /**
     * Bytes still required to safely finish downloading and installing a model whose compressed
     * size is [sizeMb] megabytes. [singleFile] selects the rename-in-place headroom over the
     * extraction headroom, and [alreadyDownloadedBytes] credits a resumable partial file on disk
     * (previously a 90%-downloaded model still demanded the full multiple, #88).
     */
    fun requiredSpaceBytes(sizeMb: Int, singleFile: Boolean = false, alreadyDownloadedBytes: Long = 0L): Long {
        val base = sizeMb.toLong() * 1_000_000L
        val withHeadroom = if (singleFile) base * SINGLE_FILE_HEADROOM_PERCENT / 100L
        else base * SPACE_HEADROOM_MULTIPLIER
        return (withHeadroom - alreadyDownloadedBytes).coerceAtLeast(0L)
    }

    /** True if [availableBytes] covers what [requiredSpaceBytes] says is still needed. */
    fun hasEnoughSpace(
        availableBytes: Long,
        sizeMb: Int,
        singleFile: Boolean = false,
        alreadyDownloadedBytes: Long = 0L,
    ): Boolean = availableBytes >= requiredSpaceBytes(sizeMb, singleFile, alreadyDownloadedBytes)

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
            val resumedBytes = if (tmpFile.isFile) tmpFile.length() else 0L
            if (!hasEnoughSpace(availableBytes, model.sizeMb, model.isLocalCleanup, resumedBytes)) {
                throw NotEnoughSpaceException(
                    requiredSpaceBytes(model.sizeMb, model.isLocalCleanup, resumedBytes),
                    availableBytes,
                )
            }
            downloadFile(url, tmpFile, onState)
            downloadComplete = true
            // IllegalStateException, not IOException: a missing pinned hash is a catalog bug,
            // terminal for retry-classification purposes (#86), not a network condition.
            val expected = model.sha256
                ?: throw IllegalStateException("No checksum configured for ${model.archive}; refusing to install unverified")
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
            onState(DownloadState.Error(e.message ?: "Unknown error", e))
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
     * Pure helper: given the archive-directory names actually present on disk under one
     * `kindDir()` (e.g. "cleanup_models") and the archive names the current app version's catalog
     * still recognizes for that kind, returns which installed archive names are orphaned -- present
     * on disk but no longer in any catalog, e.g. because a model was removed after being found
     * incompatible (like the Q4_K_M mumble-cleanup build blowing the 15s deadline on Trevor's
     * device). Split out from the [Context]-based [pruneOrphanedModelDirs] so the actual pruning
     * decision is unit-testable without touching real files.
     */
    fun orphanedArchives(installedArchiveDirNames: List<String>, catalogArchiveNames: Set<String>): List<String> =
        installedArchiveDirNames.filter { it !in catalogArchiveNames }

    /**
     * Deletes any installed model directory under "models", "streaming_models", or
     * "cleanup_models" whose archive name no longer appears in [MODEL_CATALOG],
     * [STREAMING_MODEL_CATALOG], or [LOCAL_CLEANUP_MODEL_CATALOG] respectively -- leftovers from a
     * model that was removed from the catalog after an app update (e.g. found incompatible on some
     * devices), which would otherwise sit on disk forever taking up space with no UI path to
     * discover or remove them. Pure [File]-based core (testable without a real [Context], same
     * split as [modelDirPath] vs [modelDir]); swallows individual delete failures per-directory so
     * one locked/busy directory can't block pruning the rest.
     */
    fun pruneOrphanedModelDirs(filesDir: File) {
        val catalogsByKindDir = mapOf(
            "models" to MODEL_CATALOG.map { it.archive }.toSet(),
            "streaming_models" to STREAMING_MODEL_CATALOG.map { it.archive }.toSet(),
            "cleanup_models" to LOCAL_CLEANUP_MODEL_CATALOG.map { it.archive }.toSet(),
        )
        for ((kindDirName, catalogArchives) in catalogsByKindDir) {
            val kindDir = File(filesDir, kindDirName)
            val installedDirNames = kindDir.listFiles { f -> f.isDirectory }?.map { it.name } ?: continue
            for (orphan in orphanedArchives(installedDirNames, catalogArchives)) {
                try {
                    File(kindDir, orphan).deleteRecursively()
                } catch (e: Exception) {
                    Log.w("ModelDownloader", "Failed to prune orphaned model dir $kindDirName/$orphan", e)
                }
            }
        }
    }

    /** Safe to call unconditionally on every app/service start: an up-to-date install with
     *  nothing orphaned is a no-op scan. See [pruneOrphanedModelDirs] (File overload) for the
     *  actual logic. */
    fun pruneOrphanedModelDirs(ctx: Context) = pruneOrphanedModelDirs(ctx.filesDir)

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

    /**
     * Resolves the "active" entry of a multi-tier catalog ([STREAMING_MODEL_CATALOG] or
     * [LOCAL_CLEANUP_MODEL_CATALOG], #50) given the archive persisted in that catalog's
     * "*_model_name" preference: the entry whose archive matches, or the catalog's `recommended`
     * entry if nothing matches (preference never set, or names an archive no longer in the
     * catalog) -- mirroring the offline model selection's existing fallback-to-installed/first
     * behavior in MainActivity, just pure and testable here.
     */
    fun resolveActiveModel(catalog: List<Model>, selectedArchive: String): Model =
        catalog.firstOrNull { it.archive == selectedArchive }
            ?: catalog.firstOrNull { it.recommended }
            ?: catalog.first()

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
            throw ChecksumMismatchException(expectedSha256, actual)
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
            installOverPrevious(finalDir) {
                // renameTo is atomic when staging/ and files/models/ share a filesystem
                // (the normal case for app-private storage); fall back to copy+delete
                // for the rare cross-filesystem case.
                if (!extractedRoot.renameTo(finalDir)) {
                    extractedRoot.copyRecursively(finalDir, overwrite = true)
                    extractedRoot.deleteRecursively()
                }
            }
        } finally {
            staging.deleteRecursively()
        }
    }

    /**
     * Swap-preserving install (#88): moves any existing [finalDir] aside (marker removed first
     * so it can never read as installed), runs [placeNewInstall] to put the new content at
     * [finalDir], writes the completion marker, and only then discards the old install. If
     * placing the new install throws (disk full mid-copy, cross-filesystem fallback failure),
     * the previous good install is restored -- previously it had already been deleted, so a
     * failed upgrade destroyed a working model.
     */
    private fun installOverPrevious(finalDir: File, placeNewInstall: () -> Unit) {
        val previous = File(finalDir.parentFile, ".previous-${finalDir.name}")
        previous.deleteRecursively()
        val previousWasComplete = isInstalledDir(finalDir)
        if (finalDir.isDirectory) {
            completeMarker(finalDir).delete()
            if (!finalDir.renameTo(previous)) {
                // Can't move it aside (odd filesystem state) -- fall back to the old
                // delete-first behavior rather than failing the install outright.
                finalDir.deleteRecursively()
            }
        }
        try {
            placeNewInstall()
            if (!completeMarker(finalDir).createNewFile()) {
                throw IOException("Failed to write completion marker")
            }
            previous.deleteRecursively()
        } catch (e: Exception) {
            finalDir.deleteRecursively()
            if (previous.isDirectory && previous.renameTo(finalDir) && previousWasComplete) {
                // Reinstate the marker only for an install that was genuinely complete before --
                // restoring it for a corrupt leftover would upgrade garbage to "installed".
                completeMarker(finalDir).createNewFile()
            }
            throw e
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
        installOverPrevious(finalDir) {
            finalDir.mkdirs()
            val dest = File(finalDir, fileName)
            // renameTo is atomic when cacheDir and filesDir share a filesystem (the normal case
            // for app-private storage); fall back to copy+delete for the rare cross-filesystem
            // case.
            if (!file.renameTo(dest)) {
                file.copyTo(dest, overwrite = true)
            }
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

    /** True when a resume attempt got 416 Range Not Satisfiable for a Range we actually sent:
     *  the partial file on disk is at/past the asset's full length (process death after the
     *  download completed but before checksum/install, or the asset changed upstream). Resuming
     *  that file can never succeed — every retry would 416 forever — so the partial must be
     *  deleted and the download restarted from byte 0 (#68). False when no Range was sent
     *  ([existingLength] == 0): a 416 with no Range is just a broken server response, and
     *  restarting on it would loop. */
    fun shouldRestartAfterRangeNotSatisfiable(existingLength: Long, responseCode: Int): Boolean =
        existingLength > 0 && responseCode == 416

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

        // use{} guarantees the response is closed on every exit, including the HTTP-error and
        // empty-body throws below -- those paths leaked the connection before (#88), which
        // combined with the (since-fixed) 416 wedge to leak one connection per retry tap.
        client.newCall(requestBuilder.build()).execute().use { response ->
            if (shouldRestartAfterRangeNotSatisfiable(existingLength, response.code)) {
                // Without this, download()'s keep-partial-for-resume logic retains the stale file
                // forever and every future attempt fails with "HTTP 416" (#68).
                response.close()
                dest.delete()
                downloadFile(url, dest, onState) // recurses at most once: no partial -> no Range header
                return
            }
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
    }

    /** Extract tar.bz2 to outDir. Validates paths to prevent traversal. */
    fun extractTarBz2(archive: File, outDir: File) {
        outDir.mkdirs()
        // The outer FileInputStream is in its own use{} so it still closes if the BZip2
        // constructor throws on a corrupt file (#88) -- previously only `tar` was managed.
        FileInputStream(archive).use { fileIn ->
            TarArchiveInputStream(BZip2CompressorInputStream(BufferedInputStream(fileIn))).use { tar ->
                // Compare against the canonical path *with* a trailing separator: a bare
                // startsWith let an entry canonicalizing to a sibling directory whose name
                // merely starts with outDir's string pass (#88; defense-in-depth -- catalog
                // downloads are SHA-256-pinned before extraction).
                val outRoot = outDir.canonicalPath + File.separator
                generateSequence { tar.nextEntry }.forEach { entry ->
                    val dest = File(outDir, entry.name)
                    val destPath = dest.canonicalPath
                    require(destPath == outDir.canonicalPath || destPath.startsWith(outRoot)) {
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
}
