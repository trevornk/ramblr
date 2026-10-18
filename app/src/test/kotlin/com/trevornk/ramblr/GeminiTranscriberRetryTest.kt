package com.trevornk.ramblr

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Gemini's "high demand" 503s are retried a couple of times before the dictation is given up. */
class GeminiTranscriberRetryTest {
    @get:Rule val temp = TemporaryFolder()

    private lateinit var server: MockWebServer

    @Before fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After fun tearDown() = server.shutdown()

    private val overloaded = MockResponse().setResponseCode(503).setBody(
        """{"error":{"code":503,"message":"This model is currently experiencing high demand. Spikes in demand are usually temporary. Please try again later.","status":"UNAVAILABLE"}}""",
    )

    private fun transcript(text: String) = MockResponse().setBody(
        """{"candidates":[{"content":{"parts":[{"text":"$text"}]},"finishReason":"STOP"}]}""",
    )

    private fun transcribe(
        holder: InFlightCall = InFlightCall(),
        delays: List<Long> = listOf(10L, 10L),
        onStarted: () -> Unit = {},
    ): GeminiTranscriberClient.Result {
        val pcm = File(temp.root, "clip.pcm").apply { writeBytes(ByteArray(320)) }
        val latch = CountDownLatch(1)
        val result = AtomicReference<GeminiTranscriberClient.Result>()
        GeminiTranscriberClient.transcribe(
            pcm, "key", "gemini-3.1-flash-lite", holder,
            endpoint = server.url("/v1beta/models/gemini-3.1-flash-lite:generateContent").toString(),
            retryDelaysMs = delays,
        ) { result.set(it); latch.countDown() }
        onStarted()
        assertTrue("no result", latch.await(10, TimeUnit.SECONDS))
        return result.get()
    }

    @Test fun `one overload then success returns the transcript`() {
        server.enqueue(overloaded)
        server.enqueue(transcript("hello there"))

        val result = transcribe()

        assertEquals("hello there", result.text)
        assertEquals(2, server.requestCount)
    }

    @Test fun `gives up after the last retry with a message naming the model`() {
        repeat(3) { server.enqueue(overloaded) }

        val result = transcribe()

        assertNull(result.text)
        assertEquals(3, server.requestCount)
        assertEquals(GeminiTranscriberClient.overloadedMessage("gemini-3.1-flash-lite", 3), result.error)
    }

    @Test fun `a non-overload error is reported at once`() {
        server.enqueue(MockResponse().setResponseCode(400).setBody("""{"error":{"code":400,"message":"API key not valid.","status":"INVALID_ARGUMENT"}}"""))

        val result = transcribe()

        assertEquals("API key not valid.", result.error)
        assertEquals(1, server.requestCount)
    }

    @Test fun `a cancel during the backoff stops the retry`() {
        server.enqueue(overloaded)
        server.enqueue(transcript("too late"))
        val holder = InFlightCall()

        val result = transcribe(holder, delays = listOf(1_000L)) {
            server.takeRequest(5, TimeUnit.SECONDS)
            holder.cancel()
        }

        assertNull(result.text)
        assertEquals(1, server.requestCount)
    }

    @Test fun `overload detection`() {
        assertTrue(GeminiTranscriberClient.isOverloaded(503, null))
        assertTrue(GeminiTranscriberClient.isOverloaded(500, "Internal error"))
        assertTrue(GeminiTranscriberClient.isOverloaded(200, "The model is overloaded. Please try again later."))
        assertFalse(GeminiTranscriberClient.isOverloaded(429, "Quota exceeded"))
        assertFalse(GeminiTranscriberClient.isOverloaded(400, "API key not valid."))
    }
}
