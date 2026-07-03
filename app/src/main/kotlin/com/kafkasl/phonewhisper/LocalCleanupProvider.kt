package com.kafkasl.phonewhisper

/**
 * The LOCAL_LLM waterfall step (#37): cleanup executed on-device via llama.cpp instead of over
 * HTTP. Mirrors the shape of [OmniRoute]/[AnthropicCleanupProvider] -- a small object owning the
 * one thing specific to this provider (here: which model to use) -- but there's no request body
 * to build or response JSON to parse, since [LocalInferenceEngine] already speaks in plain
 * system-prompt/user-text/response-text, not wire bytes.
 *
 * Deliberately reuses [PostProcessor]'s existing prompt constants unchanged (DEV_PROMPT/
 * SIMPLE_PROMPT/STRUCTURED_PROMPT): the same `prompt` string the executor passes to every cloud
 * step becomes the local model's system message, and `text` becomes its user message -- identical
 * to the `messages` array [PostProcessor.buildRequestBody] sends, just executed locally.
 */
object LocalCleanupProvider {
    /** The one curated local-cleanup model shipped for v1 (see [LOCAL_CLEANUP_MODEL_CATALOG] in
     *  ModelDownloader.kt) -- no arbitrary user-supplied GGUF support in this pass (#37 non-goals). */
    val MODEL: Model = LOCAL_CLEANUP_MODEL

    /**
     * Runs one cleanup completion through [engine] and translates the result into the same
     * [PostProcessor.Result] shape every other waterfall step produces, so callers outside the
     * executor (e.g. MainActivity's per-step "Test" button) can drive a local step the same way
     * as a cloud one. [engine] is the fakeable seam over native inference (see
     * [LocalInferenceEngine]) -- production callers use [RealLocalInferenceEngine]; tests inject
     * a fake with no native code involved.
     */
    fun run(text: String, prompt: String, modelPath: String, engine: LocalInferenceEngine): PostProcessor.Result =
        when (val result = engine.complete(prompt, text, modelPath)) {
            is LocalInferenceResult.Success -> PostProcessor.Result(result.text.trim().ifBlank { null }, null)
            is LocalInferenceResult.Failure -> PostProcessor.Result(null, result.message)
        }
}
