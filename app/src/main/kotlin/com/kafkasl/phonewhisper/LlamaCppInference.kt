package com.kafkasl.phonewhisper

import java.io.Closeable
import java.io.FileNotFoundException

/**
 * Kotlin JNI binding for on-device llama.cpp inference (#37). This class -- its `external fun`
 * surface and the load/addChatMessage/startCompletion/completionLoop/stopCompletion lifecycle it
 * drives -- is **adapted from** the `SmolLM` class in shubham0204/SmolChat-Android's `smollm`
 * module (Apache License 2.0, commit `8408e1ced09e` as of 2026-07-03,
 * https://github.com/shubham0204/SmolChat-Android/blob/main/smollm/src/main/java/io/shubham0204/smollm/SmolLM.kt),
 * a real, actively-maintained, known-working llama.cpp/Android JNI binding -- not hand-written
 * from memory. See app/src/main/cpp/llama_cleanup/README.md for exactly what was vendored
 * verbatim vs. trimmed/renamed, and for the JNI glue and CMake build files this class's native
 * counterpart is adapted from.
 *
 * Deliberately a much smaller surface than the real `SmolLM`: no chat history/multi-turn
 * (`storeChats` is always false -- cleanup is one shot per call), no streaming `Flow`, no
 * `benchModel`/CPU-feature-variant library selection. Cleanup is a single system-prompt +
 * user-text completion, not a chat session, so that surface would be unused complexity (YAGNI).
 *
 * One loaded instance IS safely reusable for multiple sequential [complete] calls (#74):
 * with `storeChats=false` the native side clears its message history, KV cache, and partial
 * UTF-8 accumulator per completion (see the divergence notes in LLMInference.cpp), so each call
 * is a genuinely independent one-shot. [LocalCleanupModelHolder] relies on this to keep the
 * model loaded across dictations instead of paying a full GGUF load per call. Concurrent calls
 * on one instance are NOT safe -- the holder serializes them.
 *
 * ## Build status
 * The native library IS built and packaged: app/build.gradle.kts wires
 * `externalNativeBuild`/CMake against the pinned llama.cpp git submodule, and
 * `libllama-cleanup-jni.so` (plus the llama.cpp/ggml libraries) lands in the APK's
 * `lib/arm64-v8a/` -- see app/src/main/cpp/llama_cleanup/README.md for the full setup (#37).
 * [RealLocalInferenceEngine] still catches `UnsatisfiedLinkError` defensively and reports it as
 * an ordinary [LocalInferenceResult.Failure], but since the build wiring landed that error
 * indicates a real packaging regression, not an expected gap (#87 item 5).
 */
class LlamaCppInference : Closeable {
    private var nativePtr = 0L

    /**
     * Loads [modelPath] with cleanup-appropriate defaults: greedy-ish low-temperature sampling
     * (cleanup should not be creative), no chat history retention, and the model's own chat
     * template (null -> native side falls back to the model's embedded template, same as
     * upstream `SmolLM.load` when `params.chatTemplate` is null).
     *
     * @throws FileNotFoundException if [modelPath] doesn't exist.
     * @throws IllegalStateException if the native load fails for any other reason.
     */
    fun load(
        modelPath: String,
        contextSize: Long = DEFAULT_CONTEXT_SIZE,
        numThreads: Int = DEFAULT_NUM_THREADS,
    ) {
        if (!java.io.File(modelPath).isFile) {
            throw FileNotFoundException("Local cleanup model not found at $modelPath")
        }
        nativePtr = loadModel(
            modelPath,
            /* minP = */ DEFAULT_MIN_P,
            /* temperature = */ DEFAULT_TEMPERATURE,
            /* storeChats = */ false,
            contextSize,
            /* chatTemplate = */ "",
            numThreads,
            /* useMmap = */ true,
            /* useMlock = */ false,
        )
        check(nativePtr != 0L) { "loadModel returned a null native handle" }
    }

    /**
     * Runs one cleanup completion: [systemPrompt] becomes the system message, [userText] the
     * user message -- the same two-message shape [PostProcessor.buildRequestBody] sends to every
     * cloud provider (see [LocalCleanupProvider]) -- and returns the concatenated response text.
     *
     * Generation is capped at [MAX_RESPONSE_TOKENS] pieces via [LlamaCompletionAccumulator] (#60)
     * -- see that object's kdoc for why. [stopCompletion] still runs before the resulting
     * exception propagates, so the native side tears down cleanly; [RealLocalInferenceEngine]
     * already converts any thrown exception here into an ordinary [LocalInferenceResult.Failure],
     * falling through to the next waterfall step (or raw text) exactly like any other
     * local-inference failure.
     */
    fun complete(
        systemPrompt: String,
        userText: String,
        deadlineAtMs: Long = Long.MAX_VALUE,
        isCancelled: () -> Boolean = { false },
    ): String {
        check(nativePtr != 0L) { "Model is not loaded. Call load() first." }
        // Content is sanitized because the native side tokenizes the rendered template with
        // parse_special=true -- literal <|im_end|>-style text in a transcript would otherwise
        // become real control tokens and forge turn boundaries (#78).
        addChatMessage(nativePtr, SpecialTokenSanitizer.sanitize(systemPrompt), "system")
        startCompletion(nativePtr, SpecialTokenSanitizer.sanitize(userText))
        try {
            return LlamaCompletionAccumulator.accumulate(
                maxPieces = MAX_RESPONSE_TOKENS,
                endOfGeneration = END_OF_GENERATION,
                deadlineAtMs = deadlineAtMs,
                isCancelled = isCancelled,
                // completionLoop returns raw UTF-8 bytes (#75): JNI's NewStringUTF can't carry
                // supplementary-plane characters (emoji) -- it requires Modified UTF-8 -- so the
                // native side hands back bytes and we decode them here instead.
                nextPiece = { String(completionLoop(nativePtr), Charsets.UTF_8) },
            )
        } finally {
            stopCompletion(nativePtr)
        }
    }

    override fun close() {
        if (nativePtr != 0L) {
            close(nativePtr)
            nativePtr = 0L
        }
    }

    private external fun loadModel(
        modelPath: String,
        minP: Float,
        temperature: Float,
        storeChats: Boolean,
        contextSize: Long,
        chatTemplate: String,
        nThreads: Int,
        useMmap: Boolean,
        useMlock: Boolean,
    ): Long

    private external fun addChatMessage(modelPtr: Long, message: String, role: String)

    private external fun startCompletion(modelPtr: Long, prompt: String)

    private external fun completionLoop(modelPtr: Long): ByteArray

    private external fun stopCompletion(modelPtr: Long)

    private external fun close(modelPtr: Long)

    companion object {
        /** Cleanup should stay close to the source text, not go creative -- much lower than
         *  SmolLM's chat-assistant defaults (minP 0.1 / temperature 0.8). Matches the
         *  temperature=0.0 every cloud waterfall step already uses (see
         *  [PostProcessor.buildRequestBody]/[AnthropicCleanupProvider.buildRequestBody]). */
        const val DEFAULT_TEMPERATURE = 0.0f
        const val DEFAULT_MIN_P = 0.05f
        const val DEFAULT_CONTEXT_SIZE = 2048L
        const val DEFAULT_NUM_THREADS = 4
        const val END_OF_GENERATION = "[EOG]"
        const val LIBRARY_NAME = "llama-cleanup-jni"

        /** Hard cap on generated pieces per [complete] call (#60) -- see that method's kdoc for
         *  why this exists. 512 is comfortably more than a cleaned-up dictation transcript should
         *  ever need (typical dictations are well under this in raw token count, and cleanup
         *  should not expand text), while still failing fast instead of running for minutes. */
        const val MAX_RESPONSE_TOKENS = 512

        init {
            // The library is built and packaged by the Gradle build (see the kdoc "Build
            // status" section); an UnsatisfiedLinkError here means a packaging regression.
            System.loadLibrary(LIBRARY_NAME)
        }
    }
}
