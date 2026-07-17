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

class GeminiTranscriberClientTest {

    @Test fun `parses a candidates transcript`() {
        val r = GeminiTranscriberClient.parseResponse("""{"candidates":[{"content":{"parts":[{"text":"hi there"}]}}]}""")
        assertEquals("hi there", r.text)
        assertNull(r.error)
    }

    @Test fun `small recordings are allowed on the inline-audio path (M6)`() {
        assertTrue(GeminiTranscriberClient.canInlineAudio(1L))
        assertTrue(GeminiTranscriberClient.canInlineAudio(GeminiTranscriberClient.MAX_INLINE_PCM_BYTES))
    }

    @Test fun `an oversized recording falls off the inline-audio path (M6)`() {
        assertFalse(GeminiTranscriberClient.canInlineAudio(GeminiTranscriberClient.MAX_INLINE_PCM_BYTES + 1))
    }

    @Test fun `buildRequestBody defaults to audio wav mime type`() {
        val body = GeminiTranscriberClient.buildRequestBody(ByteArray(4))
        val audioPart = body.getJSONArray("contents").getJSONObject(0).getJSONArray("parts").getJSONObject(1)
        assertEquals("audio/wav", audioPart.getJSONObject("inline_data").getString("mime_type"))
    }

    @Test fun `buildRequestBody honors an explicit audio aac mime type (#109)`() {
        val body = GeminiTranscriberClient.buildRequestBody(ByteArray(4), mimeType = "audio/aac")
        val audioPart = body.getJSONArray("contents").getJSONObject(0).getJSONArray("parts").getJSONObject(1)
        assertEquals("audio/aac", audioPart.getJSONObject("inline_data").getString("mime_type"))
    }
}
