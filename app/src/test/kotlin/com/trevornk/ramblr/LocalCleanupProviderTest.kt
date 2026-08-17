package com.trevornk.ramblr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Coverage for what's left of [LocalCleanupProvider] after #84 removed the dead `run` helper
 * (zero production callers; the executor's LOCAL_LLM branch owns result translation and trimming
 * now -- see CleanupWaterfallExecutorTest's local-step tests). [LocalCleanupProvider.selectedModel]
 * needs a Context and is covered indirectly via [ModelDownloader.resolveActiveModel]'s own tests.
 */
class LocalCleanupProviderTest {

    @Test fun `MODEL is the one curated local-cleanup catalog entry`() {
        assertEquals(LOCAL_CLEANUP_MODEL, LocalCleanupProvider.MODEL)
        assertTrue(LocalCleanupProvider.MODEL.isLocalCleanup)
    }

    // --- local-prompt vocabulary interpolation (systemPromptFor) -------------------------------
    //
    // Real on-device failure: with the LOCAL_LLM step selected, dictation injected the literal
    // text "{{vocabulary}}" instead of the transcript. Only the cloud prompt was run through
    // interpolateVocabulary in WhisperAccessibilityService, so LFM2.5 -- which declares no
    // fine-tuned prompt and falls back to SIMPLE_PROMPT -- got the raw placeholder as its system
    // prompt and echoed it back.
    //
    // These deliberately call systemPromptFor rather than PostProcessor.interpolateVocabulary,
    // which was never the broken part: an earlier draft asserted on the latter and passed against
    // the unfixed code. Mutation-check any change by reverting systemPromptFor's body to
    // `model.localSystemPrompt ?: PostProcessor.SIMPLE_PROMPT` -- three of these must fail.

    @Test fun `the local fallback prompt still carries the placeholder to interpolate`() {
        // If this constant ever loses its placeholder, interpolation silently becomes a no-op and
        // local cleanup quietly stops honouring personal vocabulary -- fail loudly instead.
        assertTrue(
            "SIMPLE_PROMPT must contain the vocabulary placeholder for local cleanup to substitute",
            PostProcessor.SIMPLE_PROMPT.contains(PostProcessor.VOCABULARY_PLACEHOLDER),
        )
    }

    @Test fun `a model without its own prompt gets vocabulary interpolated, not a literal placeholder`() {
        // LFM2.5 declares no localSystemPrompt, so it falls back to SIMPLE_PROMPT -- the exact
        // path that shipped "{{vocabulary}}" to the screen.
        val prompt = LocalCleanupProvider.systemPromptFor(LOCAL_CLEANUP_MODEL, listOf("Ramblr", "FastHTML"))
        assertFalse(
            "a literal {{vocabulary}} reaching the local model is the on-device bug",
            prompt.contains(PostProcessor.VOCABULARY_PLACEHOLDER),
        )
        assertTrue(prompt.contains("Ramblr"))
        assertTrue(prompt.contains("FastHTML"))
    }

    @Test fun `a model without its own prompt and no vocabulary terms still drops the placeholder`() {
        // The empty-vocabulary case is the default for a fresh install, so it is the most likely
        // path to ship broken: the clause collapses to "" but the placeholder must still be gone.
        val prompt = LocalCleanupProvider.systemPromptFor(LOCAL_CLEANUP_MODEL, emptyList())
        assertFalse(prompt.contains(PostProcessor.VOCABULARY_PLACEHOLDER))
        assertFalse("no template syntax may survive into the system prompt", prompt.contains("{{"))
    }

    @Test fun `a fine-tuned model's exact training prompt is passed through byte for byte`() {
        // mumble-cleanup-2stage must receive its exact training prompt; it declares no
        // placeholder, so interpolation has to be a no-op even with vocabulary configured.
        val model = LOCAL_CLEANUP_MODEL_CATALOG.first { it.localSystemPrompt != null }
        assertEquals(
            model.localSystemPrompt,
            LocalCleanupProvider.systemPromptFor(model, listOf("Ramblr")),
        )
    }

    @Test fun `no catalog model can send an uninterpolated placeholder to the local engine`() {
        // Guards every current and future catalog entry, not just the two we know about.
        LOCAL_CLEANUP_MODEL_CATALOG.forEach { model ->
            listOf(emptyList(), listOf("Ramblr")).forEach { terms ->
                assertFalse(
                    "${model.archive} would send a literal placeholder to the local model",
                    LocalCleanupProvider.systemPromptFor(model, terms)
                        .contains(PostProcessor.VOCABULARY_PLACEHOLDER),
                )
            }
        }
    }
}
