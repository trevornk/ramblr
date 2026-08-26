package com.trevornk.ramblr

import android.content.Context
import android.util.Log
import com.k2fsa.sherpa.onnx.*
import java.io.File

/**
 * Local on-device transcription via sherpa-onnx.
 * Models are loaded from the app's external files dir.
 */
class LocalTranscriber private constructor(private val recognizer: OfflineRecognizer) {

    /** Transcribe raw PCM float samples. Blocking — call from background thread. */
    fun transcribe(samples: FloatArray, sampleRate: Int = 16000): String {
        val stream = recognizer.createStream()
        try {
            stream.acceptWaveform(samples, sampleRate)
            recognizer.decode(stream)
            return recognizer.getResult(stream).text.trim()
        } finally {
            stream.release()
        }
    }

    /**
     * Transcribes a whole PCM file by decoding one VAD-detected speech segment at a time (#132).
     *
     * Peak memory is bounded by the longest *segment* rather than the longest *recording*: each
     * segment gets its own `OfflineStream`, released before the next segment is decoded, and
     * [SherpaVadHandle.MAX_SPEECH_DURATION_SECONDS] caps how long a single segment can grow. The
     * previous single-shot path fed the entire take to one stream, which drove ~2GB RSS on a
     * 5:52 dictation and got the process OOM-killed with no text and no error reaching the user.
     *
     * The file is streamed through [PcmFileBuffer.forEachChunk], so the read side never holds the
     * whole recording either. Blocking — call from a background thread.
     */
    fun transcribeSegmented(
        file: File,
        vad: VadHandle,
        sampleRate: Int = 16000,
        chunkSamples: Int = SEGMENT_READ_CHUNK_SAMPLES,
    ): String {
        val results = mutableListOf<String>()
        val segmenter = SpeechSegmenter(vad)
        val onSegment: (FloatArray) -> Unit = { segment -> results += transcribe(segment, sampleRate) }

        PcmFileBuffer.forEachChunk(file, chunkSamples) { chunk -> segmenter.accept(chunk, onSegment) }
        segmenter.finish(onSegment)

        return SegmentedTranscript.join(results)
    }

    /** Release the native recognizer. Call once no in-flight [transcribe] can still be using it. */
    fun release() = recognizer.release()

    companion object {
        private const val TAG = "LocalTranscriber"

        /** ~1s of 16kHz audio per read in [transcribeSegmented] — small enough to keep the read
         *  side flat, large enough not to churn one allocation per 512-sample VAD window. */
        const val SEGMENT_READ_CHUNK_SAMPLES = 16000

        /** Find fully-installed model dirs under the app's files/models/ dir. Filters on the
         *  same `.complete` marker [ModelDownloader.isInstalledDir] requires (#88): a marker-less
         *  partial dir (e.g. from an interrupted copy fallback) was rejected by isInstalled()
         *  but still listed here -- and auto-selected by initLocalModel's blank-pref detection,
         *  which then failed to load and silently fell back to the cloud API. */
        fun availableModels(ctx: Context): List<String> {
            val modelsDir = File(ctx.filesDir, "models")
            if (!modelsDir.exists()) return emptyList()
            return modelsDir.listFiles()
                ?.filter { ModelDownloader.isInstalledDir(it) }
                ?.map { it.name }
                ?: emptyList()
        }

        /** Create a LocalTranscriber for the given model directory name. Returns null on failure. */
        fun create(ctx: Context, modelName: String): LocalTranscriber? {
            val modelDir = File(ctx.filesDir, "models/$modelName")
            if (!modelDir.exists()) {
                Log.e(TAG, "Model dir not found: $modelDir")
                return null
            }

            val numThreads = LocalTranscriptionThreads.threadsOrDefault(ctx)
            val canaryLanguage = CanaryLanguage.languageOrDefault(ctx)
            val config = detectModelConfig(modelDir, numThreads, canaryLanguage) ?: run {
                Log.e(TAG, "Could not detect model type in $modelDir")
                return null
            }

            return try {
                val recognizer = OfflineRecognizer(assetManager = null, config = config)
                Log.i(TAG, "Loaded model: $modelName")
                LocalTranscriber(recognizer)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load model: ${e.message}")
                null
            }
        }

        /** Auto-detect model type from files present in the directory. [numThreads] defaults to
         *  [LocalTranscriptionThreads.DEFAULT_THREADS] (2, unchanged shipped behavior) so every
         *  existing direct caller -- notably the detectModelConfig-only unit tests, which have no
         *  [Context] to read a setting from -- keeps working without change; [create] passes the
         *  user-configured value explicitly (#107). [canaryLanguage] follows the same pattern
         *  (#177): defaults to [CanaryLanguage.DEFAULT] ("en", the previously hardcoded value),
         *  with [create] passing the user's setting; only the canary branch consumes it. */
        fun detectModelConfig(
            dir: File,
            numThreads: Int = LocalTranscriptionThreads.DEFAULT_THREADS,
            canaryLanguage: String = CanaryLanguage.DEFAULT,
        ): OfflineRecognizerConfig? {
            val p = dir.absolutePath
            val tokens = findTokens(p) ?: return null

            // Moonshine (has preprocess.onnx)
            val preprocessor = findFile(p, "preprocess")
            if (preprocessor != null) {
                return OfflineRecognizerConfig(
                    modelConfig = OfflineModelConfig(
                        moonshine = OfflineMoonshineModelConfig(
                            preprocessor = preprocessor,
                            encoder = findFile(p, "encode") ?: return null,
                            uncachedDecoder = findFile(p, "uncached_decode") ?: return null,
                            cachedDecoder = findFile(p, "cached_decode") ?: return null,
                        ),
                        tokens = tokens,
                        numThreads = numThreads,
                    )
                )
            }

            // Whisper (has encoder + decoder, no joiner)
            //
            // NeMo Canary (#98) has the IDENTICAL file layout -- encoder + decoder, no joiner,
            // no distinguishing filename -- so it must be checked first by directory name before
            // falling through to the Whisper branch below, or every Canary install would silently
            // load as (garbage) Whisper. Curated-catalog-only (#37 non-goals: no arbitrary
            // user-supplied GGUF/ONNX), so matching on the known archive name is safe and exact,
            // unlike a heuristic that would need to hold for arbitrary future models.
            if (dir.name.contains("canary")) {
                val canaryEncoder = findFile(p, "encoder")
                val canaryDecoder = findFile(p, "decoder")
                if (canaryEncoder != null && canaryDecoder != null) {
                    return OfflineRecognizerConfig(
                        modelConfig = OfflineModelConfig(
                            canary = OfflineCanaryModelConfig(
                                encoder = canaryEncoder,
                                decoder = canaryDecoder,
                                // Canary decoding is prompted by a source-language token; the
                                // wrong one collapses output to garbage (#177: de.wav under
                                // srcLang="en" -> " E E E E…"), so this comes from the user's
                                // CanaryLanguage setting. src == tgt means transcription;
                                // differing values would mean translation, which is out of scope.
                                srcLang = canaryLanguage,
                                tgtLang = canaryLanguage,
                                usePnc = true,
                            ),
                            tokens = tokens,
                            numThreads = numThreads,
                        )
                    )
                }
            }

            val whisperEncoder = findFile(p, "encoder")
            val whisperDecoder = findFile(p, "decoder")
            if (whisperEncoder != null && whisperDecoder != null && findFile(p, "joiner") == null) {
                return OfflineRecognizerConfig(
                    modelConfig = OfflineModelConfig(
                        whisper = OfflineWhisperModelConfig(
                            encoder = whisperEncoder,
                            decoder = whisperDecoder,
                        ),
                        tokens = tokens,
                        numThreads = numThreads,
                        modelType = "whisper",
                    )
                )
            }

            // NeMo transducer / Parakeet TDT (has encoder + decoder + joiner)
            val encoder = findFile(p, "encoder")
            val decoder = findFile(p, "decoder")
            val joiner = findFile(p, "joiner")
            if (encoder != null && decoder != null && joiner != null) {
                return OfflineRecognizerConfig(
                    modelConfig = OfflineModelConfig(
                        transducer = OfflineTransducerModelConfig(
                            encoder = encoder,
                            decoder = decoder,
                            joiner = joiner,
                        ),
                        tokens = tokens,
                        numThreads = numThreads,
                        modelType = "nemo_transducer",
                    )
                )
            }

            // NeMo CTC (single model.onnx / model.int8.onnx)
            val ctcModel = findFile(p, "model")
            if (ctcModel != null) {
                return OfflineRecognizerConfig(
                    modelConfig = OfflineModelConfig(
                        nemo = OfflineNemoEncDecCtcModelConfig(model = ctcModel),
                        tokens = tokens,
                        numThreads = numThreads,
                    )
                )
            }

            return null
        }

        /** Find the tokens file, bare (`tokens.txt`) or model-name-prefixed (`<name>-tokens.txt`). */
        fun findTokens(dir: String): String? =
            File(dir).listFiles()?.firstOrNull { it.name == "tokens.txt" || it.name.endsWith("-tokens.txt") }
                ?.absolutePath

        /**
         * Find first file whose name contains [needle] (prefer int8 quantized). Matches by
         * `contains` rather than `startsWith` since sherpa-onnx archives (e.g. Whisper) prefix
         * every filename with the model name, e.g. `base.en-encoder.int8.onnx`.
         */
        fun findFile(dir: String, needle: String): String? {
            val d = File(dir)
            // Prefer int8 quantized
            d.listFiles()?.firstOrNull { it.name.contains(needle) && it.name.contains("int8") }
                ?.let { return it.absolutePath }
            // Fallback to any onnx/ort
            return d.listFiles()?.firstOrNull {
                it.name.contains(needle) && (it.name.endsWith(".onnx") || it.name.endsWith(".ort"))
            }?.absolutePath
        }
    }
}
