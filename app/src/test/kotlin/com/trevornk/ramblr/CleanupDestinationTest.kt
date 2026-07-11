package com.trevornk.ramblr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CleanupDestinationTest {

    private fun chain(vararg entries: ProviderChainEntry) = ProviderChain(entries.toList())

    @Test fun `first cloud entry skips the LOCAL floor (M9)`() {
        val c = chain(
            ProviderChainEntry(ProviderKind.LOCAL, "model-a"),
            ProviderChainEntry(ProviderKind.GEMINI, "gemini-2.5-flash"),
        )
        assertEquals(ProviderKind.GEMINI, CleanupDestination.firstCloudEntry(c)?.kind)
    }

    @Test fun `a local-only chain has no cloud entry`() {
        assertNull(CleanupDestination.firstCloudEntry(chain(ProviderChainEntry(ProviderKind.LOCAL, "m"))))
    }

    @Test fun `consent host names the real provider, not always OpenAI (M9)`() {
        val gemini = chain(ProviderChainEntry(ProviderKind.GEMINI, "gemini-2.5-flash"))
        assertEquals("generativelanguage.googleapis.com", CleanupDestination.consentHost(gemini))
        val anthropic = chain(ProviderChainEntry(ProviderKind.ANTHROPIC, "claude-haiku-4-5"))
        assertEquals("api.anthropic.com", CleanupDestination.consentHost(anthropic))
    }

    @Test fun `consent host falls back to the OpenAI default when no cloud entry exists`() {
        assertEquals("api.openai.com", CleanupDestination.consentHost(chain(ProviderChainEntry(ProviderKind.LOCAL, "m"))))
    }

    @Test fun `cloud subtitle detail names the provider and model (M9)`() {
        val c = chain(ProviderChainEntry(ProviderKind.GEMINI, "gemini-2.5-flash"))
        assertEquals("Gemini · gemini-2.5-flash", CleanupDestination.cloudSubtitleDetail(c))
    }

    @Test fun `cloud subtitle detail fills a blank model with the provider default`() {
        val c = chain(ProviderChainEntry(ProviderKind.OPENAI, ""))
        assertEquals("OpenAI · gpt-5.4-mini", CleanupDestination.cloudSubtitleDetail(c))
    }

    @Test fun `an OpenAI base URL override reflects the overridden host`() {
        val c = ProviderChainEntry(ProviderKind.OPENAI, "gpt-4o-mini", baseUrlOverride = "https://proxy.example.com/v1")
        assertEquals("proxy.example.com", CleanupDestination.hostFor(c))
    }

    @Test fun `first cloud transcription skips LOCAL and cleanup-only providers (M9)`() {
        val c = chain(
            ProviderChainEntry(ProviderKind.LOCAL, "m"),
            ProviderChainEntry(ProviderKind.ANTHROPIC, "claude-haiku-4-5"), // cleanup-only, can't transcribe
            ProviderChainEntry(ProviderKind.OPENAI, "whisper-1"),
        )
        assertEquals(ProviderKind.OPENAI, CleanupDestination.firstCloudTranscription(c)?.kind)
    }

    @Test fun `no cloud transcription entry when only cleanup-only providers are present`() {
        val c = chain(ProviderChainEntry(ProviderKind.ANTHROPIC, "claude-haiku-4-5"))
        assertNull(CleanupDestination.firstCloudTranscription(c))
    }

    @Test fun `cloud transcription subtitle detail names the provider and model (#101)`() {
        val c = chain(ProviderChainEntry(ProviderKind.OPENAI, "gpt-4o-transcribe"))
        assertEquals("OpenAI · gpt-4o-transcribe", CleanupDestination.cloudTranscriptionSubtitleDetail(c))
    }

    @Test fun `cloud transcription subtitle detail fills a blank model with the transcription default, not the cleanup default (#101)`() {
        val c = chain(ProviderChainEntry(ProviderKind.OPENAI, ""))
        assertEquals("OpenAI · ${TranscriberClient.DEFAULT_MODEL}", CleanupDestination.cloudTranscriptionSubtitleDetail(c))
    }

    @Test fun `cloud transcription subtitle detail uses Gemini's transcription default, not its cleanup default (#101)`() {
        val c = chain(ProviderChainEntry(ProviderKind.GEMINI, ""))
        assertEquals("Gemini · ${GeminiTranscriberClient.DEFAULT_MODEL}", CleanupDestination.cloudTranscriptionSubtitleDetail(c))
    }

    @Test fun `cloud transcription subtitle detail skips a cleanup-only entry and falls back to the default when no transcription-capable entry exists (#101)`() {
        val c = chain(ProviderChainEntry(ProviderKind.ANTHROPIC, "claude-haiku-4-5"))
        assertEquals("OpenAI · ${TranscriberClient.DEFAULT_MODEL}", CleanupDestination.cloudTranscriptionSubtitleDetail(c))
    }
}

class CanFallBackToCloudCleanupTest {
    private fun chain(vararg entries: ProviderChainEntry) = ProviderChain(entries.toList())

    @Test fun `true when a configured cloud entry exists (M14)`() {
        val c = chain(
            ProviderChainEntry(ProviderKind.LOCAL, "m"),
            ProviderChainEntry(ProviderKind.GEMINI, "gemini-2.5-flash"),
        )
        assertEquals(true, canFallBackToCloudCleanup(c) { it == ProviderKind.GEMINI })
    }

    @Test fun `false when the only cloud entry is unconfigured (M14)`() {
        val c = chain(ProviderChainEntry(ProviderKind.GEMINI, "gemini-2.5-flash"))
        assertEquals(false, canFallBackToCloudCleanup(c) { false })
    }

    @Test fun `a local-only chain falls back on the OpenAI default the Cloud switch would seed`() {
        val local = chain(ProviderChainEntry(ProviderKind.LOCAL, "m"))
        assertEquals(true, canFallBackToCloudCleanup(local) { it == ProviderKind.OPENAI })
        assertEquals(false, canFallBackToCloudCleanup(local) { false })
    }
}
