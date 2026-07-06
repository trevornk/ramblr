package com.kafkasl.phonewhisper

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Covers [NetworkWarmup.hostsToWarm], the pure part of the pre-warm fix (#100 perceived-latency
 * follow-up): given the resolved transcription candidates and effective cleanup chain for a
 * dictation, it must return exactly the distinct cloud hostnames a real call could hit -- no
 * more (that would waste a handshake on a host nothing will call), no less (that would leave the
 * real call to pay full DNS+TLS cold).
 */
class NetworkWarmupTest {
    private fun entry(kind: ProviderKind, model: String = "m", baseUrlOverride: String? = null) =
        ProviderChainEntry(kind, model, baseUrlOverride)

    @Test fun `maps each cloud provider kind to its default host`() {
        val hosts = NetworkWarmup.hostsToWarm(
            transcriptionCandidates = listOf(entry(ProviderKind.OPENAI)),
            cleanupChain = ProviderChain(listOf(entry(ProviderKind.ANTHROPIC), entry(ProviderKind.GEMINI))),
        )
        assertEquals(
            setOf("api.openai.com", "api.anthropic.com", "generativelanguage.googleapis.com"),
            hosts,
        )
    }

    @Test fun `LOCAL entries contribute no host`() {
        val hosts = NetworkWarmup.hostsToWarm(
            transcriptionCandidates = listOf(entry(ProviderKind.LOCAL)),
            cleanupChain = ProviderChain(listOf(entry(ProviderKind.LOCAL))),
        )
        assertEquals(emptySet<String>(), hosts)
    }

    @Test fun `duplicate hosts across transcription and cleanup are deduped`() {
        val hosts = NetworkWarmup.hostsToWarm(
            transcriptionCandidates = listOf(entry(ProviderKind.OPENAI)),
            cleanupChain = ProviderChain(listOf(entry(ProviderKind.OPENAI), entry(ProviderKind.OPENAI, "other-model"))),
        )
        assertEquals(setOf("api.openai.com"), hosts)
    }

    @Test fun `baseUrlOverride wins over the provider's default host`() {
        val hosts = NetworkWarmup.hostsToWarm(
            transcriptionCandidates = emptyList(),
            cleanupChain = ProviderChain(listOf(entry(ProviderKind.OPENAI, baseUrlOverride = "https://my-proxy.example.com/v1"))),
        )
        assertEquals(setOf("my-proxy.example.com"), hosts)
    }

    @Test fun `empty chains warm nothing`() {
        val hosts = NetworkWarmup.hostsToWarm(
            transcriptionCandidates = emptyList(),
            cleanupChain = ProviderChain(emptyList()),
        )
        assertEquals(emptySet<String>(), hosts)
    }
}
