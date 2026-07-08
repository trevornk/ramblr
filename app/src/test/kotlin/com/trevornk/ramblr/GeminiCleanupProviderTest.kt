package com.trevornk.ramblr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GeminiCleanupProviderTest {

    @Test fun `endpoint url builds the resource path and carries no api key`() {
        val url = GeminiCleanupProvider.endpointUrl("gemini-2.5-flash")
        assertEquals(
            "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent",
            url,
        )
        // The key must never ride in the URL (M-3) -- it's a header instead.
        assertFalse(url.contains("key"))
        assertFalse(url.contains("?"))
    }

    @Test fun `blank model falls back to the default model`() {
        val url = GeminiCleanupProvider.endpointUrl("")
        assertTrue(url.endsWith("/models/${GeminiCleanupProvider.DEFAULT_MODEL}:generateContent"))
    }

    @Test fun `key travels in the x-goog-api-key header`() {
        val headers = GeminiCleanupProvider.headers("secret-key")
        assertEquals("secret-key", headers["x-goog-api-key"])
        assertNull(headers["Authorization"])
        assertNull(headers["key"])
    }

    @Test fun `request body uses native contents and systemInstruction shape`() {
        val body = GeminiCleanupProvider.buildRequestBody("raw text", "system prompt")
        assertEquals(
            "system prompt",
            body.getJSONObject("systemInstruction").getJSONArray("parts").getJSONObject(0).getString("text"),
        )
        val contents = body.getJSONArray("contents")
        assertEquals(1, contents.length())
        assertEquals("user", contents.getJSONObject(0).getString("role"))
        assertEquals(
            "raw text",
            contents.getJSONObject(0).getJSONArray("parts").getJSONObject(0).getString("text"),
        )
        assertEquals(0.0, body.getJSONObject("generationConfig").getDouble("temperature"), 0.0)
    }

    @Test fun `parses a successful candidates response`() {
        val json = """{"candidates":[{"content":{"parts":[{"text":"cleaned up text"}]}}]}"""
        val result = GeminiCleanupProvider.parseResponse(json)
        assertEquals("cleaned up text", result.text)
        assertNull(result.error)
    }

    @Test fun `concatenates multiple text parts of a single candidate`() {
        val json = """{"candidates":[{"content":{"parts":[{"text":"hello "},{"text":"world"}]}}]}"""
        val result = GeminiCleanupProvider.parseResponse(json)
        assertEquals("hello world", result.text)
        assertNull(result.error)
    }

    @Test fun `empty candidates array reports no candidates`() {
        val result = GeminiCleanupProvider.parseResponse("""{"candidates":[]}""")
        assertNull(result.text)
        assertEquals("No candidates in response", result.error)
    }

    @Test fun `a safety-blocked candidate with no content parts reports missing text`() {
        // Gemini returns a candidate with finishReason SAFETY and no content.parts on a blocked
        // response; the parser must report a missing-text failure, not crash or return null text.
        val json = """{"candidates":[{"finishReason":"SAFETY","content":{}}]}"""
        val result = GeminiCleanupProvider.parseResponse(json)
        assertNull(result.text)
        assertEquals("No text content in response", result.error)
    }

    @Test fun `parses a Gemini error envelope`() {
        val json = """{"error":{"code":400,"message":"API key not valid","status":"INVALID_ARGUMENT"}}"""
        val result = GeminiCleanupProvider.parseResponse(json)
        assertNull(result.text)
        assertEquals("API key not valid", result.error)
    }

    @Test fun `malformed json reports a parse error instead of throwing`() {
        val result = GeminiCleanupProvider.parseResponse("not json")
        assertNull(result.text)
        assertTrue(result.error != null)
    }
}
