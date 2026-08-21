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

    // --- local prompt construction (systemPromptFor) -------------------------------------------
    //
    // Two separate on-device failures shaped this function, and the tests below pin both.
    //
    // (1) The literal-placeholder bug: with the LOCAL_LLM step selected, dictation injected the
    //     text "{{vocabulary}}" instead of the transcript, because only the cloud prompt was run
    //     through interpolateVocabulary. LFM2.5 falls back to SIMPLE_PROMPT, so it received the
    //     raw placeholder and echoed it back. The placeholder must still be *removed*.
    //
    // (2) #182: interpolating the real term list is what the placeholder was removed *for*, and
    //     it turned out to be actively harmful on-device. LFM2.5's valid-output rate against the
    //     shipping validator fell from 8/10 (no terms) to 4/10 (5 terms) to 2/10 (22 terms) --
    //     it returns the term list itself as the cleaned transcript. Local cleanup now renders
    //     the clause empty; vocabulary remains a cloud-only feature.
    //
    // Mutation-check any change by making systemPromptFor interpolate a non-empty term list --
    // the #182 tests must fail. Reverting its body to a bare
    // `model.localSystemPrompt ?: PostProcessor.SIMPLE_PROMPT` must fail the placeholder tests.

    @Test fun `the local fallback prompt still carries the placeholder to strip`() {
        // If this constant ever loses its placeholder the interpolation call becomes dead code,
        // and a future edit reintroducing terms would have nothing to substitute into. Pin it.
        assertTrue(
            "SIMPLE_PROMPT must contain the vocabulary placeholder for local cleanup to substitute",
            PostProcessor.SIMPLE_PROMPT.contains(PostProcessor.VOCABULARY_PLACEHOLDER),
        )
    }

    @Test fun `a model without its own prompt gets no literal placeholder`() {
        // LFM2.5 declares no localSystemPrompt, so it falls back to SIMPLE_PROMPT -- the exact
        // path that shipped "{{vocabulary}}" to the screen.
        val prompt = LocalCleanupProvider.systemPromptFor(LOCAL_CLEANUP_MODEL)
        assertFalse(
            "a literal {{vocabulary}} reaching the local model is the on-device bug",
            prompt.contains(PostProcessor.VOCABULARY_PLACEHOLDER),
        )
        assertFalse("no template syntax may survive into the system prompt", prompt.contains("{{"))
    }

    @Test fun `the local prompt carries no personal vocabulary terms (#182)`() {
        // The regression this fix exists to prevent. Terms configured by the user must not reach
        // the local model's system prompt in any form: a 350M model returns them as its answer.
        val prompt = LocalCleanupProvider.systemPromptFor(LOCAL_CLEANUP_MODEL)
        assertFalse(
            "vocabulary terms in the local system prompt are what LFM2.5 echoes back (#182)",
            prompt.contains("Watch for these project names"),
        )
        assertEquals(
            "the local prompt must be exactly SIMPLE_PROMPT with an empty vocabulary clause",
            PostProcessor.interpolateVocabulary(PostProcessor.SIMPLE_PROMPT, emptyList()),
            prompt,
        )
    }

    @Test fun `the local prompt is identical no matter what vocabulary the user has configured (#182)`() {
        // systemPromptFor no longer takes terms at all, so this asserts the property that removal
        // was meant to guarantee: nothing user-configured can vary the local prompt. If someone
        // reintroduces a terms parameter, this test is the reason to think twice.
        val prompt = LocalCleanupProvider.systemPromptFor(LOCAL_CLEANUP_MODEL)
        val realDeviceVocabulary = listOf(
            "Nash-Keller", "Wyatt", "Terelle", "Emsley", "trevor@nashkellermedia.com",
        )
        realDeviceVocabulary.forEach { term ->
            assertFalse(
                "$term leaked into the local system prompt",
                prompt.contains(term),
            )
        }
    }

    @Test fun `a fine-tuned model's exact training prompt is passed through byte for byte`() {
        // mumble-cleanup-2stage must receive its exact training prompt; it declares no
        // placeholder, so the interpolation has to be a no-op for it.
        val model = LOCAL_CLEANUP_MODEL_CATALOG.first { it.localSystemPrompt != null }
        assertEquals(
            model.localSystemPrompt,
            LocalCleanupProvider.systemPromptFor(model),
        )
    }

    @Test fun `no catalog model can send an uninterpolated placeholder to the local engine`() {
        // Guards every current and future catalog entry, not just the two we know about.
        LOCAL_CLEANUP_MODEL_CATALOG.forEach { model ->
            assertFalse(
                "${model.archive} would send a literal placeholder to the local model",
                LocalCleanupProvider.systemPromptFor(model)
                    .contains(PostProcessor.VOCABULARY_PLACEHOLDER),
            )
        }
    }

    @Test fun `no catalog model leaks vocabulary terms into its local prompt (#182)`() {
        // The catalog-wide form of the #182 guard: a future entry that ships a prompt with a
        // placeholder must still not receive the user's terms.
        LOCAL_CLEANUP_MODEL_CATALOG.forEach { model ->
            assertFalse(
                "${model.archive} would send vocabulary terms to a local model (#182)",
                LocalCleanupProvider.systemPromptFor(model).contains("Watch for these project names"),
            )
        }
    }
}
