package com.trevornk.ramblr

import org.junit.Assert.*
import org.junit.Test

class AudioTranscriptionRoutesTest {
    private val openai = ProviderChainEntry(ProviderKind.OPENAI, "m", id = "o")
    private val gemini = ProviderChainEntry(ProviderKind.GEMINI, "m", id = "g")
    private val local = ProviderChainEntry(ProviderKind.LOCAL, "m", id = "l")

    private fun plan(
        useLocal: Boolean, localReady: Boolean = true, cloudFallback: Boolean = false,
        cands: List<ProviderChainEntry> = listOf(openai), cred: (ProviderChainEntry) -> Boolean = { true },
    ) = AudioTranscriptionRoutes.plan(useLocal, localReady, cloudFallback, cands, cred)

    @Test fun `on-device choice stays on-device`() {
        val p = plan(useLocal = true, cands = listOf(openai, gemini))
        assertEquals(listOf<AudioRoute>(AudioRoute.Local), p.routes)
    }

    @Test fun `audio only leaves the device for an on-device user when cloud fallback is on`() {
        val p = plan(useLocal = true, cloudFallback = true, cands = listOf(openai, gemini))
        assertEquals(3, p.routes.size)
        assertSame(AudioRoute.Local, p.routes[0])
    }

    @Test fun `on-device without a model and no fallback is unavailable`() {
        val p = plan(useLocal = true, localReady = false)
        assertTrue(p.routes.isEmpty())
        assertEquals(JobFailure.LOCAL_UNAVAILABLE, p.unavailable)
    }

    @Test fun `on-device model missing but cloud fallback on goes to cloud`() {
        assertEquals(1, plan(useLocal = true, localReady = false, cloudFallback = true).routes.size)
    }

    @Test fun `cloud-first follows chain order and keeps a local entry`() {
        val p = plan(useLocal = false, cands = listOf(gemini, openai, local))
        assertEquals(listOf("GEMINI", "OPENAI", "LOCAL"), p.routes.map { (it as? AudioRoute.Cloud)?.entry?.kind?.name ?: "LOCAL" })
    }

    @Test fun `entries without a credential are skipped`() {
        val p = plan(useLocal = false, cands = listOf(openai, gemini), cred = { it.kind == ProviderKind.GEMINI })
        assertEquals(1, p.routes.size)
        assertEquals(ProviderKind.GEMINI, (p.routes[0] as AudioRoute.Cloud).entry.kind)
    }

    @Test fun `nothing usable reports no provider`() {
        val p = plan(useLocal = false, cands = listOf(openai), cred = { false })
        assertEquals(JobFailure.NO_PROVIDER, p.unavailable)
    }

    @Test fun `non transcription providers are never routed`() {
        val p = plan(useLocal = false, cands = listOf(ProviderChainEntry(ProviderKind.ANTHROPIC, "m", id = "a")))
        assertTrue(p.routes.isEmpty())
    }

    @Test fun `chunk size respects every route in the plan`() {
        val cloudOnly = plan(useLocal = false)
        assertEquals(AudioTranscriptionRoutes.CLOUD_CHUNK_SAMPLES, AudioTranscriptionRoutes.maxChunkSamples(cloudOnly, vadAvailable = false))
        val localNoVad = plan(useLocal = true)
        assertEquals(AudioTranscriptionRoutes.LOCAL_UNSEGMENTED_CHUNK_SAMPLES, AudioTranscriptionRoutes.maxChunkSamples(localNoVad, vadAvailable = false))
        assertEquals(AudioTranscriptionRoutes.LOCAL_VAD_CHUNK_SAMPLES, AudioTranscriptionRoutes.maxChunkSamples(localNoVad, vadAvailable = true))
        val mixed = plan(useLocal = true, cloudFallback = true)
        assertEquals(minOf(AudioTranscriptionRoutes.CLOUD_CHUNK_SAMPLES, AudioTranscriptionRoutes.LOCAL_UNSEGMENTED_CHUNK_SAMPLES),
            AudioTranscriptionRoutes.maxChunkSamples(mixed, vadAvailable = false))
    }

    @Test fun `a cloud chunk fits provider upload limits`() {
        val bytes = AudioTranscriptionRoutes.CLOUD_CHUNK_SAMPLES * 2
        assertTrue("under OpenAI's 25 MB", bytes + 44 < 25L * 1024 * 1024)
        assertTrue("under Gemini's inline cap", GeminiTranscriberClient.canInlineAudio(bytes))
    }

    @Test fun `walk starts at the last good route and wraps`() {
        val tried = mutableListOf<Int>()
        val r = ChunkWalk.run(3, 1) { i -> tried += i; if (i == 0) ChunkAttempt.Text("ok") else ChunkAttempt.Error() }
        assertEquals(listOf(1, 2, 0), tried)
        assertEquals("ok", r.text)
        assertEquals(0, r.routeIndex)
    }

    @Test fun `walk reports total failure and cancellation`() {
        assertNull(ChunkWalk.run(2, 0) { ChunkAttempt.Error() }.text)
        val c = ChunkWalk.run(2, 0) { ChunkAttempt.Cancelled }
        assertTrue(c.cancelled)
        assertNull(c.text)
    }
}
