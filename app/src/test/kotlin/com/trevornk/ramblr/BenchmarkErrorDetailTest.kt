package com.trevornk.ramblr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.json.JSONObject

/**
 * #138: failures used to be recorded as a bare `"success": false` with the provider's reason
 * discarded, making 21 real-world failures undiagnosable. These pin both halves of the fix --
 * that the reason is persisted, and that persisting it can't leak dictated content into a log
 * that is deliberately length-only.
 */
class BenchmarkErrorDetailTest {

    @Test
    fun `a provider message survives into the durable record`() {
        val line = BenchmarkLogger.buildLine(
            timestamp = 1L,
            correlationId = "c1",
            transcription = null,
            cleanup = BenchmarkStage(
                provider = "ANTHROPIC_DIRECT",
                model = "claude-haiku-4-5",
                latencyMs = 733,
                success = false,
                error = sanitizeError("HTTP 401: invalid x-api-key"),
            ),
            rawTextLength = null,
            cleanedTextLength = null,
        )

        val cleanup = JSONObject(line).getJSONObject("cleanup")
        assertEquals("HTTP 401: invalid x-api-key", cleanup.getString("error"))
    }

    @Test
    fun `a successful stage records no error`() {
        val line = BenchmarkLogger.buildLine(
            timestamp = 1L,
            correlationId = "c1",
            transcription = null,
            cleanup = BenchmarkStage("GEMINI_DIRECT", "gemini-3.1-flash-lite", 700, true),
            rawTextLength = null,
            cleanedTextLength = null,
        )

        // JSONObject.NULL, not an omitted key -- same convention as every other optional field.
        assertTrue(JSONObject(line).getJSONObject("cleanup").isNull("error"))
    }

    @Test
    fun `an echoed error body is bounded but NOT rendered content-free`() {
        // Pins the HONEST property. An earlier version of this test asserted the echoed content
        // could not be reconstructed; it failed, correctly, because truncation bounds exposure
        // rather than eliminating it -- 200 chars still holds two full copies of a 61-char
        // string. Documented as a real tradeoff in sanitizeError's kdoc rather than papered over.
        val secret = "my bank password is hunter2 and my address is 123 Main Street"
        val body = "HTTP 400: invalid request: " + secret.repeat(20)

        val sanitized = sanitizeError(body)!!

        // The guarantee that actually holds: exposure is capped.
        assertTrue("must be truncated", sanitized.length <= MAX_ERROR_DETAIL_CHARS + 3)
        assertTrue("the 20x echo must not survive whole", sanitized.length < body.length / 5)

        // And the guarantee that does NOT hold, asserted explicitly so nobody later mistakes
        // truncation for redaction.
        assertTrue(
            "truncation is a bound, not redaction -- see sanitizeError kdoc",
            sanitized.contains(secret),
        )
    }

    @Test
    fun `newlines are collapsed so one failure can never split a JSONL record`() {
        // JSONL is line-oriented: a raw newline here would turn one record into two unparseable
        // fragments and silently corrupt every downstream analysis.
        val sanitized = sanitizeError("HTTP 500:\n  upstream\n\ttimed out\r\n")!!

        assertEquals("HTTP 500: upstream timed out", sanitized)
        assertFalse(sanitized.contains("\n"))
        assertFalse(sanitized.contains("\r"))
    }

    @Test
    fun `blank and null messages normalize to no error rather than an empty string`() {
        assertNull(sanitizeError(null))
        assertNull(sanitizeError(""))
        assertNull(sanitizeError("   \n\t "))
    }

    @Test
    fun `a truncated message is marked as truncated`() {
        val sanitized = sanitizeError("x".repeat(MAX_ERROR_DETAIL_CHARS + 50))!!

        assertTrue("a reader must be able to tell it was cut", sanitized.endsWith("..."))
        assertEquals(MAX_ERROR_DETAIL_CHARS + 3, sanitized.length)
    }

    @Test
    fun `a message exactly at the cap is kept whole`() {
        val exact = "y".repeat(MAX_ERROR_DETAIL_CHARS)
        val sanitized = sanitizeError(exact)!!

        assertEquals(exact, sanitized)
        assertFalse(sanitized.endsWith("..."))
    }

    @Test
    fun `existing records without the error key still parse`() {
        // Backward compatibility with the 910 records already on Trevor's device.
        val old = """{"timestamp":1,"correlationId":"c","transcription":null,
            |"cleanup":{"provider":"OPENAI_DIRECT","model":"gpt-5.4-nano","latencyMs":1782,
            |"success":false,"compressedUpload":null},"rawTextLength":null,
            |"cleanedTextLength":null,"pipeline":null}""".trimMargin().replace("\n", "")

        val cleanup = JSONObject(old).getJSONObject("cleanup")
        assertFalse(cleanup.has("error"))
        assertEquals("OPENAI_DIRECT", cleanup.getString("provider"))
    }

    // --- transcription stage -------------------------------------------------------------
    //
    // #138 originally landed on the cleanup path only: sanitizeError had exactly one call site
    // in main, and all four transcription BenchmarkStage constructions omitted `error`, so it
    // defaulted to null. Every test above pins `cleanup =`, which is precisely why that gap
    // survived review. These pin the transcription half.

    @Test
    fun `a failed transcription persists its reason too`() {
        // The local path's catch block had the exception and sent it only to logcat.
        val line = BenchmarkLogger.buildLine(
            timestamp = 1L,
            correlationId = "c1",
            transcription = BenchmarkStage(
                provider = "LOCAL",
                model = "parakeet_tdt_ctc_110m",
                latencyMs = 402,
                success = false,
                error = sanitizeError("java.lang.OutOfMemoryError: Failed to allocate"),
            ),
            cleanup = null,
            rawTextLength = null,
            cleanedTextLength = null,
        )

        val t = JSONObject(line).getJSONObject("transcription")
        assertFalse(t.getBoolean("success"))
        assertEquals("java.lang.OutOfMemoryError: Failed to allocate", t.getString("error"))
    }

    @Test
    fun `a cloud transcription error envelope is persisted, not just blank text`() {
        // OpenAI/Gemini report failure as Result(text=null, error="..."), so `success` is
        // computed from blank text and no exception is ever thrown -- without plumbing
        // result.error through, these failures record success=false with no reason at all.
        val line = BenchmarkLogger.buildLine(
            timestamp = 1L,
            correlationId = "c1",
            transcription = BenchmarkStage(
                provider = "GEMINI",
                model = "gemini-3.1-flash-lite",
                latencyMs = 1180,
                success = false,
                error = sanitizeError("HTTP 429: rate limit exceeded"),
            ),
            cleanup = null,
            rawTextLength = null,
            cleanedTextLength = null,
        )

        val t = JSONObject(line).getJSONObject("transcription")
        assertEquals("HTTP 429: rate limit exceeded", t.getString("error"))
    }

    @Test
    fun `a successful transcription records no error`() {
        val line = BenchmarkLogger.buildLine(
            timestamp = 1L,
            correlationId = "c1",
            transcription = BenchmarkStage("LOCAL", "parakeet_tdt_ctc_110m", 402, true),
            cleanup = null,
            rawTextLength = 42,
            cleanedTextLength = null,
        )

        assertTrue(JSONObject(line).getJSONObject("transcription").isNull("error"))
    }

    @Test
    fun `transcription and cleanup reasons are recorded independently`() {
        // A take where transcription succeeded and cleanup failed must not conflate the two.
        val line = BenchmarkLogger.buildLine(
            timestamp = 1L,
            correlationId = "c1",
            transcription = BenchmarkStage("LOCAL", "parakeet_tdt_ctc_110m", 402, true),
            cleanup = BenchmarkStage(
                provider = "OPENAI_DIRECT",
                model = "gpt-5.4-nano",
                latencyMs = 9,
                success = false,
                error = sanitizeError(
                    "Unable to resolve host \"api.openai.com\": No address associated with hostname"
                ),
            ),
            rawTextLength = 42,
            cleanedTextLength = null,
        )

        val root = JSONObject(line)
        assertTrue(root.getJSONObject("transcription").isNull("error"))
        assertTrue(
            root.getJSONObject("cleanup").getString("error").contains("Unable to resolve host"),
        )
    }
}
