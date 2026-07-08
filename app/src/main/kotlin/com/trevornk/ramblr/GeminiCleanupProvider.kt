package com.trevornk.ramblr

import org.json.JSONArray
import org.json.JSONObject

/**
 * Native client for Gemini's `generateContent` REST API, used for the GEMINI cleanup provider
 * (#96). Gemini's wire format is not OpenAI-compatible (no `/chat/completions` endpoint, a
 * `contents`/`parts` request shape instead of `messages`, the API key passed as a query
 * parameter rather than an Authorization header, and a `candidates[].content.parts[].text`
 * response shape), so it can't reuse [PostProcessor]'s chat-completions request/response code --
 * mirrors [AnthropicCleanupProvider]'s reasoning for the same kind of divergence.
 *
 * Deliberately covers only a single-turn systemInstruction+user completion, which is all the
 * cleanup waterfall needs -- no streaming (per the capability facts already documented in
 * [ProviderChain]'s kdoc), no tool use, no multi-turn history, no thinking-config tuning.
 */
object GeminiCleanupProvider {
    const val BASE_URL = "https://generativelanguage.googleapis.com/v1beta"

    /** [model] is bare (e.g. "gemini-2.5-flash"), not the "models/..." resource name; this builds
     *  the full resource path. No `?key=` query string: the key travels in the [headers] below
     *  instead, so it can't leak through a malformed-URL exception message or a logged URL (M-3). */
    fun endpointUrl(model: String): String =
        "$BASE_URL/models/${model.ifBlank { DEFAULT_MODEL }}:generateContent"

    /** Gemini accepts the API key in an `x-goog-api-key` header as an alternative to the `?key=`
     *  query parameter (https://ai.google.dev/gemini-api/docs/api-key). Using the header keeps the
     *  key out of the URL entirely, mirroring [AnthropicCleanupProvider.headers]'s `x-api-key`. */
    fun headers(key: String): Map<String, String> = mapOf("x-goog-api-key" to key)

    const val DEFAULT_MODEL = "gemini-2.5-flash"

    /**
     * Builds the generateContent request body: [prompt] as `systemInstruction`, [text] as the
     * single user `contents` entry. `temperature: 0.0` mirrors [PostProcessor.buildRequestBody]/
     * [AnthropicCleanupProvider.buildRequestBody]'s deterministic-cleanup intent.
     */
    fun buildRequestBody(text: String, prompt: String): JSONObject {
        val systemInstruction = JSONObject().apply {
            put("parts", JSONArray().put(JSONObject().apply { put("text", prompt) }))
        }
        val contents = JSONArray().put(JSONObject().apply {
            put("role", "user")
            put("parts", JSONArray().put(JSONObject().apply { put("text", text) }))
        })
        val generationConfig = JSONObject().apply {
            put("temperature", 0.0)
        }
        return JSONObject().apply {
            put("systemInstruction", systemInstruction)
            put("contents", contents)
            put("generationConfig", generationConfig)
        }
    }

    /**
     * Parses a `generateContent` response: `candidates[0].content.parts[].text`, concatenated in
     * case the model splits its answer across multiple parts. Gemini error responses come back
     * as a top-level `{"error": {"message": ...}}` envelope, same shape [PostProcessor] and
     * [AnthropicCleanupProvider] already handle.
     */
    fun parseResponse(json: String): PostProcessor.Result {
        return try {
            val obj = JSONObject(json)
            if (obj.has("candidates")) {
                val candidates = obj.getJSONArray("candidates")
                if (candidates.length() == 0) {
                    return PostProcessor.Result(null, "No candidates in response")
                }
                val content = candidates.getJSONObject(0).optJSONObject("content")
                val parts = content?.optJSONArray("parts")
                val text = parts?.let { arr ->
                    (0 until arr.length())
                        .map { arr.getJSONObject(it) }
                        .filter { it.has("text") }
                        .joinToString("") { it.getString("text") }
                }
                if (!text.isNullOrBlank()) {
                    PostProcessor.Result(text.trim(), null)
                } else {
                    PostProcessor.Result(null, "No text content in response")
                }
            } else if (obj.has("error")) {
                PostProcessor.Result(null, obj.getJSONObject("error").optString("message", "Unknown Gemini error"))
            } else {
                PostProcessor.Result(null, "Unknown response format")
            }
        } catch (e: Exception) {
            PostProcessor.Result(null, e.message ?: "Parse error")
        }
    }
}
