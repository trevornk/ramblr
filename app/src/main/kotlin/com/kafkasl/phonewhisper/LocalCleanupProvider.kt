package com.kafkasl.phonewhisper

import android.content.Context

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
    /** The recommended/default local-cleanup catalog entry (see [LOCAL_CLEANUP_MODEL_CATALOG] in
     *  ModelDownloader.kt, #50) -- the "Local" simple-choice default when nothing else is selected. */
    val MODEL: Model = LOCAL_CLEANUP_MODEL

    /** The catalog entry the user has actually picked for "Local" cleanup (#50), read from the
     *  "local_cleanup_model_name" preference -- falling back to [MODEL] when unset or when the
     *  named archive is no longer in the catalog. See [ModelDownloader.resolveActiveModel]. */
    fun selectedModel(ctx: Context): Model {
        val prefs = ctx.getSharedPreferences("phonewhisper", Context.MODE_PRIVATE)
        val archive = prefs.getString("local_cleanup_model_name", MODEL.archive) ?: MODEL.archive
        return ModelDownloader.resolveActiveModel(LOCAL_CLEANUP_MODEL_CATALOG, archive)
    }

    // A `run(text, prompt, modelPath, engine)` helper used to live here, kdoc-claiming the
    // Settings "Test" button drove local steps through it -- it never had a production caller
    // (Test goes through PostProcessor.processWaterfall like everything else), and its trim was
    // the only trimming local path while the executor injected untrimmed output. Deleted in #84;
    // the executor's LOCAL_LLM branch now trims, matching the cloud parsers.
}
