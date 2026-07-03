package com.kafkasl.phonewhisper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AnthropicCleanupProviderTest {

    @Test fun `request body uses native Anthropic shape, not OpenAI messages`() {
        val body = AnthropicCleanupProvider.buildRequestBody("raw text", "system prompt", "claude-haiku-4-5-20251001")
        assertEquals("claude-haiku-4-5-20251001", body.getString("model"))
        assertEquals("system prompt", body.getString("system"))
        assertEquals(1, body.getJSONArray("messages").length())
        assertEquals("user", body.getJSONArray("messages").getJSONObject(0).getString("role"))
        assertEquals("raw text", body.getJSONArray("messages").getJSONObject(0).getString("content"))
    }

    @Test fun `headers use x-api-key, not a Bearer token`() {
        val headers = AnthropicCleanupProvider.headers("secret-key")
        assertEquals("secret-key", headers["x-api-key"])
        assertTrue(headers.containsKey("anthropic-version"))
        assertEquals(null, headers["Authorization"])
    }

    @Test fun `parses a successful content-block response`() {
        val json = """{"content":[{"type":"text","text":"cleaned up text"}]}"""
        val result = AnthropicCleanupProvider.parseResponse(json)
        assertEquals("cleaned up text", result.text)
        assertNull(result.error)
    }

    @Test fun `parses a real full response envelope, not just a stripped-down content array`() {
        // Full shape a live /v1/messages call actually returns, including fields this parser
        // doesn't need (id, type, role, model, stop_reason, usage) -- confirms their presence
        // doesn't break extraction of the text block.
        val json = """
            {
              "id": "msg_01ABC",
              "type": "message",
              "role": "assistant",
              "model": "claude-haiku-4-5-20251001",
              "content": [{"type": "text", "text": "cleaned up text"}],
              "stop_reason": "end_turn",
              "stop_sequence": null,
              "usage": {"input_tokens": 42, "output_tokens": 7}
            }
        """.trimIndent()
        val result = AnthropicCleanupProvider.parseResponse(json)
        assertEquals("cleaned up text", result.text)
        assertNull(result.error)
    }

    @Test fun `a max_tokens stop_reason still extracts the truncated text rather than failing`() {
        val json = """{"content":[{"type":"text","text":"cleaned up but cut off"}],"stop_reason":"max_tokens"}"""
        val result = AnthropicCleanupProvider.parseResponse(json)
        assertEquals("cleaned up but cut off", result.text)
        assertNull(result.error)
    }

    @Test fun `parses an Anthropic error response`() {
        val json = """{"type":"error","error":{"type":"authentication_error","message":"invalid x-api-key"}}"""
        val result = AnthropicCleanupProvider.parseResponse(json)
        assertNull(result.text)
        assertEquals("invalid x-api-key", result.error)
    }

    @Test fun `malformed json reports a parse error instead of throwing`() {
        val result = AnthropicCleanupProvider.parseResponse("not json")
        assertNull(result.text)
        assertTrue(result.error != null)
    }
}
