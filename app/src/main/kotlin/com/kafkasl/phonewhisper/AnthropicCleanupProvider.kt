package com.kafkasl.phonewhisper

import org.json.JSONArray
import org.json.JSONObject

/**
 * Minimal native client for Anthropic's real `/v1/messages` API, used only by the
 * ANTHROPIC_DIRECT waterfall step (see ADR-0001 / docs/adr/0001-cleanup-waterfall.md). Anthropic's
 * wire format is not OpenAI-compatible (different endpoint, `x-api-key` auth instead of a Bearer
 * token, and a content-block response shape), so it can't reuse [PostProcessor]'s chat-completions
 * request/response code.
 *
 * Deliberately covers only a single-turn system+user completion, which is all the cleanup
 * waterfall needs — issue #31 is the tracked follow-up for a fuller client (streaming, tool use,
 * multi-turn, etc.) if that's ever needed elsewhere.
 */
object AnthropicCleanupProvider {
    const val BASE_URL = "https://api.anthropic.com/v1"
    const val ENDPOINT_URL = "$BASE_URL/messages"
    const val ANTHROPIC_VERSION = "2023-06-01"
    const val DEFAULT_MAX_TOKENS = 1024

    fun headers(apiKey: String): Map<String, String> = mapOf(
        "x-api-key" to apiKey,
        "anthropic-version" to ANTHROPIC_VERSION,
        "content-type" to "application/json",
    )

    fun buildRequestBody(text: String, prompt: String, model: String): JSONObject {
        val messages = JSONArray().apply {
            put(JSONObject().apply {
                put("role", "user")
                put("content", text)
            })
        }
        return JSONObject().apply {
            put("model", model)
            put("system", prompt)
            put("max_tokens", DEFAULT_MAX_TOKENS)
            put("temperature", 0.0)
            put("messages", messages)
        }
    }

    fun parseResponse(json: String): PostProcessor.Result {
        return try {
            val obj = JSONObject(json)
            if (obj.has("content")) {
                val content = obj.getJSONArray("content")
                val text = (0 until content.length())
                    .map { content.getJSONObject(it) }
                    .firstOrNull { it.optString("type") == "text" }
                    ?.optString("text")
                if (!text.isNullOrBlank()) {
                    PostProcessor.Result(text.trim(), null)
                } else {
                    PostProcessor.Result(null, "No text content in response")
                }
            } else if (obj.has("error")) {
                PostProcessor.Result(null, obj.getJSONObject("error").optString("message", "Unknown Anthropic error"))
            } else {
                PostProcessor.Result(null, "Unknown response format")
            }
        } catch (e: Exception) {
            PostProcessor.Result(null, e.message ?: "Parse error")
        }
    }
}
