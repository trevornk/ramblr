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
 * ## Known gap (read before relying on this class)
 * There is **no native library to load**: `System.loadLibrary(LIBRARY_NAME)` will throw
 * `UnsatisfiedLinkError` on every real device today. Building it requires the Android NDK (not
 * present in this development sandbox -- confirmed via `$ANDROID_HOME/ndk` being absent) plus
 * either (a) a full from-source llama.cpp build via CMake, mirroring SmolChat-Android's
 * `add_subdirectory(llama.cpp)` + git-submodule approach, or (b) compiling just the JNI shim
 * (`llama_cleanup_jni.cpp`/`LLMInference.cpp`) against llama.cpp's own official prebuilt
 * `android-arm64` release libraries (e.g. `libllama.so`/`libggml*.so` from
 * https://github.com/ggerganov/llama.cpp/releases) plus its public `llama.h`/`common.h` headers.
 * [RealLocalInferenceEngine] catches `UnsatisfiedLinkError` and reports it as an ordinary
 * [LocalInferenceResult.Failure] so this gap fails the LOCAL_LLM waterfall step cleanly (falling
 * through to the next configured step, or raw injection) instead of crashing the app -- but it
 * has NOT been exercised on a real device. See the #37 closing comment for the precise status.
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
    fun complete(systemPrompt: String, userText: String): String {
        check(nativePtr != 0L) { "Model is not loaded. Call load() first." }
        addChatMessage(nativePtr, systemPrompt, "system")
        startCompletion(nativePtr, userText)
        try {
            return LlamaCompletionAccumulator.accumulate(
                maxPieces = MAX_RESPONSE_TOKENS,
                endOfGeneration = END_OF_GENERATION,
                nextPiece = { completionLoop(nativePtr) },
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

    private external fun completionLoop(modelPtr: Long): String

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
            // See this class's kdoc "Known gap" section -- this line is expected to throw
            // UnsatisfiedLinkError until native provisioning (#37) is completed.
            System.loadLibrary(LIBRARY_NAME)
        }
    }
}
