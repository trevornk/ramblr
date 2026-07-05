package com.kafkasl.phonewhisper

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

    @Test fun `cleanup adapter explicitly skips Gemini until Gemini cleanup transport exists`() {
        val chain = ProviderChain(
            listOf(
                ProviderChainEntry(ProviderKind.GEMINI, "gemini-2.5-flash"),
                ProviderChainEntry(ProviderKind.OPENAI, "gpt-4o-mini"),
            )
        )

        val waterfall = ProviderChainRuntime.cleanupWaterfallFor(chain)

        assertEquals(listOf(CleanupStep(CleanupStepGroup.OPENAI_DIRECT, "gpt-4o-mini")), waterfall.steps)
        assertTrue(ProviderKind.GEMINI in ProviderChainRuntime.cleanupKindsNotImplemented)
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
    }
}

class ProviderChainRuntimeTranscriptionResolverTest {

    @Test fun `transcription candidates skip non transcription providers and unimplemented Gemini`() {
        val openai = ProviderChainEntry(ProviderKind.OPENAI, "gpt-4o-mini")
        val local = ProviderChainEntry(ProviderKind.LOCAL, "local-model")
        val chain = ProviderChain(
            listOf(
                ProviderChainEntry(ProviderKind.ANTHROPIC, "claude"),
                ProviderChainEntry(ProviderKind.GEMINI, "gemini-2.5-flash"),
                ProviderChainEntry(ProviderKind.OMNIROUTE, "omni"),
                openai,
                local,
            )
        )

        assertEquals(listOf(openai, local), ProviderChainRuntime.transcriptionCandidates(chain))
        assertTrue(ProviderKind.GEMINI in ProviderChainRuntime.transcriptionKindsNotImplemented)
    }

    @Test fun `local remains a valid transcription fallback after skipped Gemini`() {
        val local = ProviderChainEntry(ProviderKind.LOCAL, "local-model")
        val chain = ProviderChain(
            listOf(
                ProviderChainEntry(ProviderKind.GEMINI, "gemini-2.5-flash"),
                local,
            )
        )

        assertEquals(listOf(local), ProviderChainRuntime.transcriptionCandidates(chain))
    }
}
