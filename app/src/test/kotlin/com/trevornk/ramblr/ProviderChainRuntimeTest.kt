package com.trevornk.ramblr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderChainRuntimeCleanupAdapterTest {

    @Test fun `cleanup adapter maps provider kinds to existing executor groups`() {
        val chain = ProviderChain(
            listOf(
                ProviderChainEntry(ProviderKind.OMNIROUTE, "claude/sonnet"),
                ProviderChainEntry(ProviderKind.OPENAI, "gpt-4o-mini", "https://example.com/v1"),
                ProviderChainEntry(ProviderKind.ANTHROPIC, "claude-haiku"),
                ProviderChainEntry(ProviderKind.LOCAL, "local-model"),
            )
        )

        val waterfall = ProviderChainRuntime.cleanupWaterfallFor(chain)

        assertEquals(
            listOf(
                CleanupStep(CleanupStepGroup.OMNIROUTE, "claude/sonnet"),
                CleanupStep(CleanupStepGroup.OPENAI_DIRECT, "gpt-4o-mini", "https://example.com/v1"),
                CleanupStep(CleanupStepGroup.ANTHROPIC_DIRECT, "claude-haiku"),
                CleanupStep(CleanupStepGroup.LOCAL_LLM, "local-model"),
            ),
            waterfall.steps,
        )
    }

    @Test fun `cleanup adapter maps Gemini to the Gemini direct step group`() {
        val chain = ProviderChain(
            listOf(
                ProviderChainEntry(ProviderKind.GEMINI, "gemini-2.5-flash"),
                ProviderChainEntry(ProviderKind.OPENAI, "gpt-4o-mini"),
            )
        )

        val waterfall = ProviderChainRuntime.cleanupWaterfallFor(chain)

        assertEquals(
            listOf(
                CleanupStep(CleanupStepGroup.GEMINI_DIRECT, "gemini-2.5-flash"),
                CleanupStep(CleanupStepGroup.OPENAI_DIRECT, "gpt-4o-mini"),
            ),
            waterfall.steps,
        )
    }

    @Test fun `single OpenAI cleanup chain uses simple path predicate`() {
        val chain = ProviderChain(listOf(ProviderChainEntry(ProviderKind.OPENAI, "gpt-4o-mini")))

        assertTrue(ProviderChainRuntime.isSingleOpenAiCleanup(chain))
        assertFalse(ProviderChainRuntime.shouldUseCleanupExecutor(chain))
    }

    @Test fun `multi provider cleanup chain uses executor predicate`() {
        val chain = ProviderChain(
            listOf(
                ProviderChainEntry(ProviderKind.OPENAI, "gpt-4o-mini"),
                ProviderChainEntry(ProviderKind.LOCAL, "local-model"),
            )
        )

        assertFalse(ProviderChainRuntime.isSingleOpenAiCleanup(chain))
        assertTrue(ProviderChainRuntime.shouldUseCleanupExecutor(chain))
    }

    @Test fun `cleanup credential slots resolve to unified provider kinds`() {
        assertEquals(ProviderKind.OMNIROUTE, ProviderChainRuntime.providerKindForCleanupSlot(CleanupCredentialSlot.OMNIROUTE))
        assertEquals(ProviderKind.OPENAI, ProviderChainRuntime.providerKindForCleanupSlot(CleanupCredentialSlot.OPENAI_DIRECT))
        assertEquals(ProviderKind.ANTHROPIC, ProviderChainRuntime.providerKindForCleanupSlot(CleanupCredentialSlot.ANTHROPIC_DIRECT))
        assertEquals(ProviderKind.GEMINI, ProviderChainRuntime.providerKindForCleanupSlot(CleanupCredentialSlot.GEMINI_DIRECT))
    }
}

class ProviderChainRuntimeTranscriptionResolverTest {

    @Test fun `transcription candidates skip non transcription providers and include Gemini`() {
        val openai = ProviderChainEntry(ProviderKind.OPENAI, "gpt-4o-mini")
        val gemini = ProviderChainEntry(ProviderKind.GEMINI, "gemini-2.5-flash")
        val local = ProviderChainEntry(ProviderKind.LOCAL, "local-model")
        val chain = ProviderChain(
            listOf(
                ProviderChainEntry(ProviderKind.ANTHROPIC, "claude"),
                gemini,
                ProviderChainEntry(ProviderKind.OMNIROUTE, "omni"),
                openai,
                local,
            )
        )

        assertEquals(listOf(gemini, openai, local), ProviderChainRuntime.transcriptionCandidates(chain))
        assertFalse(ProviderKind.GEMINI in ProviderChainRuntime.transcriptionKindsNotImplemented)
    }

    @Test fun `local remains a valid transcription fallback after Gemini`() {
        val gemini = ProviderChainEntry(ProviderKind.GEMINI, "gemini-2.5-flash")
        val local = ProviderChainEntry(ProviderKind.LOCAL, "local-model")
        val chain = ProviderChain(
            listOf(
                gemini,
                local,
            )
        )

        assertEquals(listOf(gemini, local), ProviderChainRuntime.transcriptionCandidates(chain))
    }

    @Test fun `allowLocalFallback false strips LOCAL from transcription candidates`() {
        val openai = ProviderChainEntry(ProviderKind.OPENAI, "gpt-4o-mini")
        val local = ProviderChainEntry(ProviderKind.LOCAL, "local-model")
        val chain = ProviderChain(listOf(openai, local))

        assertEquals(listOf(openai, local), ProviderChainRuntime.transcriptionCandidates(chain, allowLocalFallback = true))
        assertEquals(listOf(openai), ProviderChainRuntime.transcriptionCandidates(chain, allowLocalFallback = false))
    }

    @Test fun `allowLocalFallback false with only LOCAL configured yields no candidates`() {
        val local = ProviderChainEntry(ProviderKind.LOCAL, "local-model")
        val chain = ProviderChain(listOf(local))

        assertTrue(ProviderChainRuntime.transcriptionCandidates(chain, allowLocalFallback = false).isEmpty())
    }
}

class ProviderChainRuntimeCleanupFallbackTest {

    @Test fun `cloud enabled with local fallback keeps LOCAL floor in effective chain`() {
        val chain = ProviderChain(
            listOf(
                ProviderChainEntry(ProviderKind.OPENAI, "gpt-4o-mini"),
                ProviderChainEntry(ProviderKind.LOCAL, "local-model"),
            )
        )

        val effective = ProviderChainRuntime.effectiveChainForCleanup(chain, cloudEnabled = true, allowLocalFallback = true)

        assertEquals(chain, effective)
    }

    @Test fun `cloud enabled without local fallback strips LOCAL from effective chain`() {
        val chain = ProviderChain(
            listOf(
                ProviderChainEntry(ProviderKind.OPENAI, "gpt-4o-mini"),
                ProviderChainEntry(ProviderKind.LOCAL, "local-model"),
            )
        )

        val effective = ProviderChainRuntime.effectiveChainForCleanup(chain, cloudEnabled = true, allowLocalFallback = false)

        assertEquals(listOf(ProviderChainEntry(ProviderKind.OPENAI, "gpt-4o-mini")), effective.entries)
    }

    @Test fun `cloud disabled always resolves to LOCAL only regardless of allowLocalFallback`() {
        val chain = ProviderChain(
            listOf(
                ProviderChainEntry(ProviderKind.OPENAI, "gpt-4o-mini"),
                ProviderChainEntry(ProviderKind.LOCAL, "local-model"),
            )
        )

        val effective = ProviderChainRuntime.effectiveChainForCleanup(chain, cloudEnabled = false, allowLocalFallback = false)

        assertEquals(listOf(ProviderChainEntry(ProviderKind.LOCAL, "local-model")), effective.entries)
    }
}

class DictationModeResolveTest {

    @Test fun `cloud transcription and cloud cleanup resolves to CLOUD`() {
        assertEquals(DictationMode.CLOUD, DictationMode.resolve(useLocalTranscription = false, cloudCleanupEnabled = true))
    }

    @Test fun `local transcription and local cleanup resolves to LOCAL`() {
        assertEquals(DictationMode.LOCAL, DictationMode.resolve(useLocalTranscription = true, cloudCleanupEnabled = false))
    }

    @Test fun `local transcription and cloud cleanup resolves to FASTEST`() {
        assertEquals(DictationMode.FASTEST, DictationMode.resolve(useLocalTranscription = true, cloudCleanupEnabled = true))
    }

    @Test fun `cloud transcription and local cleanup resolves to CUSTOM`() {
        assertEquals(DictationMode.CUSTOM, DictationMode.resolve(useLocalTranscription = false, cloudCleanupEnabled = false))
    }
}
