package com.trevornk.ramblr

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
        val prefs = ctx.getSharedPreferences("ramblr", Context.MODE_PRIVATE)
        val archive = prefs.getString("local_cleanup_model_name", MODEL.archive) ?: MODEL.archive
        return ModelDownloader.resolveActiveModel(LOCAL_CLEANUP_MODEL_CATALOG, archive)
    }

    /**
     * The system prompt [CleanupWaterfallExecutor]'s LOCAL_LLM step should actually send for the
     * currently [selectedModel]. Context-reading wrapper only -- the selection itself lives in
     * [systemPromptFor], which is unit-testable without a Context (no Robolectric here, so
     * pure-logic extraction is how the rest of this module stays covered).
     */
    fun selectedSystemPrompt(ctx: Context): String = systemPromptFor(selectedModel(ctx))

    /**
     * The model's own [Model.localSystemPrompt] when it declares one (a fine-tuned model like
     * `mumble-cleanup-2stage` that requires its exact training prompt), otherwise the
     * general-purpose [PostProcessor.SIMPLE_PROMPT].
     *
     * **The personal-vocabulary clause is deliberately NOT interpolated here (#182).** It is
     * still sent to cloud cleanup, where it works; on-device it made cleanup dramatically worse.
     * Measured on 10 real transcripts with the shipping validator, LFM2.5's valid-output rate
     * fell monotonically with the number of configured terms:
     *
     *   0 terms -> 8/10      5 terms -> 4/10      22 terms -> 2/10
     *
     * The failure is that a 350M model cannot reliably tell a 300-character list of proper nouns
     * in its instructions from content it is supposed to emit, so it returns the term list itself
     * as the "cleaned" transcript -- names and email addresses where the user's words should be.
     * Rewording did not fix it: a terser clause scored 4/10, moving it after the output
     * instruction 6/10, and moving it into the user message 3/10, all below the 8/10 baseline of
     * simply not sending it.
     *
     * Note this is not a regression for the other catalog entry: `mumble-cleanup-2stage` declares
     * its own [Model.localSystemPrompt], which carries no [PostProcessor.VOCABULARY_PLACEHOLDER],
     * so interpolation was always a no-op for it. Local cleanup has therefore never actually
     * delivered this feature -- it either did nothing or did harm. Making vocabulary work
     * on-device needs a deterministic post-processing pass over the model's output rather than a
     * prompt instruction; that is tracked separately in #182.
     *
     * The placeholder must still be *removed* rather than left in the string. Shipping a literal
     * `{{vocabulary}}` to the model was the original on-device bug: LFM2.5 echoed it back as the
     * entire cleaned transcript. Interpolating an empty term list renders the surrounding
     * sentence cleanly (see [PostProcessor.vocabularyClause]).
     */
    fun systemPromptFor(model: Model): String =
        PostProcessor.interpolateVocabulary(
            model.localSystemPrompt ?: PostProcessor.SIMPLE_PROMPT,
            emptyList(),
        )

    // A `run(text, prompt, modelPath, engine)` helper used to live here, kdoc-claiming the
    // Settings "Test" button drove local steps through it -- it never had a production caller
    // (Test goes through PostProcessor.processProviderChain like everything else), and its trim was
    // the only trimming local path while the executor injected untrimmed output. Deleted in #84;
    // the executor's LOCAL_LLM branch now trims, matching the cloud parsers.
}
