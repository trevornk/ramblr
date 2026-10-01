package com.trevornk.ramblr

import org.junit.Assert.assertEquals
import org.junit.Test

class EffectiveRoutingTest {

    private fun chain(vararg entries: ProviderChainEntry) = ProviderChain(entries.toList())
    private val openAiEntry = ProviderChainEntry(ProviderKind.OPENAI, "gpt-5.4-mini")

    // --- EffectiveRouting.transcription ---

    @Test fun `cloud active, configured, local fallback on`() {
        assertEquals(
            "Cloud (OpenAI) \u2192 on-device fallback",
            EffectiveRouting.transcription(
                chain = chain(openAiEntry),
                useLocalTranscription = false,
                allowLocalFallback = true,
                allowCloudFallback = false,
                isConfigured = { it.kind == ProviderKind.OPENAI },
            )
        )
    }

    @Test fun `cloud active, configured, local fallback off`() {
        assertEquals(
            "Cloud (OpenAI) only",
            EffectiveRouting.transcription(
                chain = chain(openAiEntry),
                useLocalTranscription = false,
                allowLocalFallback = false,
                allowCloudFallback = false,
                isConfigured = { it.kind == ProviderKind.OPENAI },
            )
        )
    }

    @Test fun `cloud active but not configured names the gap instead of a provider`() {
        assertEquals(
            "Cloud (not configured) \u2192 on-device fallback",
            EffectiveRouting.transcription(
                chain = chain(openAiEntry),
                useLocalTranscription = false,
                allowLocalFallback = true,
                allowCloudFallback = false,
                isConfigured = { false },
            )
        )
    }

    @Test fun `local active, cloud fallback on and configured`() {
        assertEquals(
            "On-device \u2192 cloud fallback (OpenAI)",
            EffectiveRouting.transcription(
                chain = chain(openAiEntry),
                useLocalTranscription = true,
                allowLocalFallback = true,
                allowCloudFallback = true,
                isConfigured = { it.kind == ProviderKind.OPENAI },
            )
        )
    }

    @Test fun `local active, cloud fallback off is on-device only`() {
        assertEquals(
            "On-device only",
            EffectiveRouting.transcription(
                chain = chain(openAiEntry),
                useLocalTranscription = true,
                allowLocalFallback = true,
                allowCloudFallback = false,
                isConfigured = { it.kind == ProviderKind.OPENAI },
            )
        )
    }

    @Test fun `local active, cloud fallback on but not configured names the gap`() {
        assertEquals(
            "On-device \u2192 cloud fallback (not configured)",
            EffectiveRouting.transcription(
                chain = chain(),
                useLocalTranscription = true,
                allowLocalFallback = true,
                allowCloudFallback = true,
                isConfigured = { false },
            )
        )
    }

    // --- EffectiveRouting.cleanup ---

    @Test fun `cleanup off reports Off regardless of everything else`() {
        assertEquals(
            "Off",
            EffectiveRouting.cleanup(
                chain = chain(openAiEntry),
                postProcessingEnabled = false,
                cloudCleanupEnabled = true,
                allowLocalFallback = true,
                isConfigured = { true },
            )
        )
    }

    @Test fun `cleanup on-device only when cloud cleanup toggle is off`() {
        assertEquals(
            "On-device only",
            EffectiveRouting.cleanup(
                chain = chain(openAiEntry),
                postProcessingEnabled = true,
                cloudCleanupEnabled = false,
                allowLocalFallback = true,
                isConfigured = { true },
            )
        )
    }

    @Test fun `cleanup cloud active with local fallback on`() {
        assertEquals(
            "Cloud (OpenAI) \u2192 on-device fallback",
            EffectiveRouting.cleanup(
                chain = chain(openAiEntry),
                postProcessingEnabled = true,
                cloudCleanupEnabled = true,
                allowLocalFallback = true,
                isConfigured = { it.kind == ProviderKind.OPENAI },
            )
        )
    }

    @Test fun `cleanup cloud active with local fallback off`() {
        assertEquals(
            "Cloud (OpenAI) only",
            EffectiveRouting.cleanup(
                chain = chain(openAiEntry),
                postProcessingEnabled = true,
                cloudCleanupEnabled = true,
                allowLocalFallback = false,
                isConfigured = { it.kind == ProviderKind.OPENAI },
            )
        )
    }

    @Test fun `cleanup cloud active but not configured names the gap`() {
        assertEquals(
            "Cloud (not configured) \u2192 on-device fallback",
            EffectiveRouting.cleanup(
                chain = chain(openAiEntry),
                postProcessingEnabled = true,
                cloudCleanupEnabled = true,
                allowLocalFallback = true,
                isConfigured = { false },
            )
        )
    }

    // --- #274 integration: routing summary follows per-entry state, not ProviderKind ---

    @Test fun `transcription summary skips a disabled entry and names the routed one`() {
        assertEquals(
            "Cloud (Gemini) \u2192 on-device fallback",
            EffectiveRouting.transcription(
                chain = chain(
                    ProviderChainEntry(ProviderKind.OPENAI, "gpt-5.4-mini", enabled = false),
                    ProviderChainEntry(ProviderKind.GEMINI, "gemini-2.5-flash"),
                ),
                useLocalTranscription = false,
                allowLocalFallback = true,
                allowCloudFallback = false,
                isConfigured = { it.kind == ProviderKind.GEMINI },
            )
        )
    }

    @Test fun `cleanup configured state is per entry, not per kind`() {
        val unkeyed = ProviderChainEntry(ProviderKind.OPENAI, "gpt-5.4-mini", id = "a")
        val keyed = ProviderChainEntry(ProviderKind.OPENAI, "llama-3.1-8b-instant", id = "b")
        fun summary(configured: Set<String>) = EffectiveRouting.cleanup(
            chain = chain(unkeyed, keyed),
            postProcessingEnabled = true,
            cloudCleanupEnabled = true,
            allowLocalFallback = false,
            isConfigured = { it.id in configured },
        )
        assertEquals("Cloud (OpenAI) only", summary(setOf("b")))
        assertEquals("Cloud (not configured) only", summary(emptySet()))
    }
}
