package com.trevornk.ramblr

import android.content.Context
import android.util.Log
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineStream
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig

/**
 * Live-preview transcription via sherpa-onnx's streaming (online) recognizer (#29). Parallel to
 * [LocalTranscriber], not a replacement for it: this only ever produces *partial* hypotheses shown
 * while recording, never the final injected transcript -- that still comes from the batch
 * offline/cloud pipeline exactly as before.
 *
 * Unlike [LocalTranscriber] (one-shot: create a stream, decode a whole utterance, release), the
 * native [OnlineRecognizer] is loaded once and reused across many recordings, but its
 * [com.k2fsa.sherpa.onnx.OnlineStream] is per-recording -- [beginSession]/[endSession] bracket one
 * dictation.
 */
class StreamingTranscriber private constructor(private val recognizer: OnlineRecognizer) {

    /** Serializes every native stream operation (#77): [acceptChunk] runs on the RecordingEngine
     *  reader thread while [endSession]/[beginSession] run on the main thread, and
     *  `OnlineStream.release()` deletes the native object -- without mutual exclusion a release
     *  can interleave an in-flight decode and dereference freed native memory (SIGSEGV). Chunks
     *  arrive ~25/s and each decode is short, so holding one lock across a chunk is cheap. */
    private val lock = Any()

    private var stream: OnlineStream? = null

    /** Starts a fresh decode session for one recording. Releases any stream left over from a
     *  previous session first, in case [endSession] was never reached (e.g. the service was torn
     *  down mid-recording). */
    fun beginSession() {
        synchronized(lock) {
            stream?.release()
            stream = recognizer.createStream()
        }
    }

    /**
     * Feeds one chunk of PCM float samples and returns the recognizer's current best hypothesis
     * for the whole utterance so far, or null if [beginSession] hasn't been called (or the session
     * already ended) -- callers should treat null as "nothing to show", not an error. Safe to call
     * repeatedly from a background thread; sherpa-onnx's streaming API is designed for exactly this
     * incremental-chunk usage, decoding synchronously as far as the buffered audio allows.
     */
    fun acceptChunk(samples: FloatArray, sampleRate: Int): String? {
        synchronized(lock) {
            val s = stream ?: return null
            s.acceptWaveform(samples, sampleRate)
            while (recognizer.isReady(s)) recognizer.decode(s)
            return recognizer.getResult(s).text.trim()
        }
    }

    /** Ends the current decode session, releasing the native stream. Safe to call even when no
     *  session is active. */
    fun endSession() {
        synchronized(lock) {
            stream?.release()
            stream = null
        }
    }

    /** Releases the native recognizer. Safe to call while a straggling [acceptChunk] is in
     *  flight -- the internal lock serializes the release behind it. */
    fun release() {
        synchronized(lock) {
            stream?.release()
            stream = null
            recognizer.release()
        }
    }

    companion object {
        private const val TAG = "StreamingTranscriber"

        /** True if [model] (one of [STREAMING_MODEL_CATALOG], #50) is fully installed. */
        fun isAvailable(ctx: Context, model: Model): Boolean = ModelDownloader.isInstalled(ctx, model)

        /** Loads [model] (one of [STREAMING_MODEL_CATALOG], #50). Returns null if it isn't
         *  installed or fails to load -- callers treat that as "no live preview this session",
         *  never a fatal error, since this path is additive to the always-available batch
         *  transcription. */
        fun create(ctx: Context, model: Model): StreamingTranscriber? {
            if (!isAvailable(ctx, model)) return null
            val modelDir = ModelDownloader.modelDir(ctx, model).absolutePath

            val encoder = LocalTranscriber.findFile(modelDir, "encoder")
            val decoder = LocalTranscriber.findFile(modelDir, "decoder")
            val joiner = LocalTranscriber.findFile(modelDir, "joiner")
            val tokens = LocalTranscriber.findTokens(modelDir)
            if (encoder == null || decoder == null || joiner == null || tokens == null) {
                Log.e(TAG, "Streaming model files missing/incomplete in $modelDir")
                return null
            }

            val config = OnlineRecognizerConfig(
                modelConfig = OnlineModelConfig(
                    transducer = OnlineTransducerModelConfig(
                        encoder = encoder,
                        decoder = decoder,
                        joiner = joiner,
                    ),
                    tokens = tokens,
                    numThreads = 2,
                    modelType = model.streamingModelType,
                ),
            )

            return try {
                val recognizer = OnlineRecognizer(assetManager = null, config = config)
                Log.i(TAG, "Loaded streaming model: ${model.archive}")
                StreamingTranscriber(recognizer)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load streaming model: ${e.message}")
                null
            }
        }
    }
}
