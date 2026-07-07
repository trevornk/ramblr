package com.trevornk.ramblr

import org.json.JSONArray
import org.json.JSONObject

/**
 * Native client for Anthropic's real `/v1/messages` API, used only by the ANTHROPIC_DIRECT
 * waterfall step (see ADR-0001 / docs/adr/0001-cleanup-waterfall.md, #31). Anthropic's wire format
 * is not OpenAI-compatible (different endpoint, `x-api-key` auth instead of a Bearer token, and a
 * content-block response shape), so it can't reuse [PostProcessor]'s chat-completions request/
 * response code.
 *
 * Deliberately covers only a single-turn system+user completion, which is all the cleanup
 * waterfall needs — no streaming (Anthropic defaults to non-streaming when `stream` is omitted,
 * unlike OmniRoute), no tool use, no multi-turn history. Per-status-code error classification
 * (401 vs 429 vs 5xx) is intentionally *not* done here: ADR-0001 treats any non-2xx as an
 * immediate, non-retried step failure, and [CleanupWaterfallExecutor]/[RealCleanupHttpTransport]
 * already classify that uniformly for every provider.
 */
object AnthropicCleanupProvider {
    const val BASE_URL = "https://api.anthropic.com/v1"
    const val ENDPOINT_URL = "$BASE_URL/messages"
    const val ANTHROPIC_VERSION = "2023-06-01"

    /** Anthropic requires max_tokens; this is a cap, not a target, so billing only reflects
     *  tokens actually generated. 1024 was tight enough to risk silently truncating a longer
     *  dictation restructured by [PostProcessor.STRUCTURED_PROMPT]; 4096 gives real headroom
     *  while staying well under every current Claude model's output limit. */
    const val DEFAULT_MAX_TOKENS = 4096

    fun headers(apiKey: String): Map<String, String> = mapOf(
        "x-api-key" to apiKey,
        "anthropic-version" to ANTHROPIC_VERSION,
        "content-type" to "application/json",
    )

    /**
     * Deliberately omits `temperature`: Claude Sonnet 5 and Opus 4.7+ hard-reject any non-default
     * sampling parameter with a 400 (`temperature is deprecated for this model` — confirmed live
     * against the real API, see the #97 eval harness run), while every earlier Claude generation
     * accepts an omitted `temperature` and just uses its own default. Explicit `temperature: 0.0`
     * would silently break every ANTHROPIC_DIRECT cleanup call on current-generation models —
     * omitting it is the one request shape that works across all of them without per-model
     * conditional logic.
     */
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
