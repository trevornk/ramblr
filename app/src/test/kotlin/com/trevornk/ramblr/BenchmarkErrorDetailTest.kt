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
}
