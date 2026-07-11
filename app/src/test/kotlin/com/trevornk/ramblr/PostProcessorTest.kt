package com.trevornk.ramblr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PostProcessorTest {

    @Test
    fun destinationHostMatchesEndpoint() {
        assertEquals("api.openai.com", PostProcessor.DESTINATION_HOST)
    }

    @Test
    fun parseSuccess() {
        val json = """
        {
            "id": "chatcmpl-123",
            "object": "chat.completion",
            "created": 1677652288,
            "model": "gpt-4o-mini",
            "choices": [{
                "index": 0,
                "message": {
                    "role": "assistant",
                    "content": "Hello there, how are you?"
                },
                "finish_reason": "stop"
            }],
            "usage": {
                "prompt_tokens": 9,
                "completion_tokens": 12,
                "total_tokens": 21
            }
        }
        """.trimIndent()

        val result = PostProcessor.parseResponse(json)
        assertEquals("Hello there, how are you?", result.text)
        assertEquals(null, result.error)
    }

    @Test
    fun parseError() {
        val json = """
        {
            "error": {
                "message": "Incorrect API key provided.",
                "type": "invalid_request_error",
                "param": null,
                "code": "invalid_api_key"
            }
        }
        """.trimIndent()

        val result = PostProcessor.parseResponse(json)
        assertEquals(null, result.text)
        assertEquals("Incorrect API key provided.", result.error)
    }

    @Test
    fun parseEmptyChoices() {
        val json = """
        {
            "choices": []
        }
        """.trimIndent()

        val result = PostProcessor.parseResponse(json)
        assertEquals(null, result.text)
        assertEquals("No choices in response", result.error)
    }

    @Test
    fun parseInvalidJson() {
        val result = PostProcessor.parseResponse("invalid json")
        assertEquals(null, result.text)
        assertTrue(
            result.error?.contains("JSONObject") == true ||
                result.error?.contains("must begin with '{'") == true
        )
    }

    // --- normalizeBaseUrl (#4) ---

    @Test
    fun normalizeBaseUrlAcceptsHttps() {
        assertEquals("https://api.openai.com/v1", PostProcessor.normalizeBaseUrl("https://api.openai.com/v1"))
    }

    @Test
    fun normalizeBaseUrlAcceptsHttp() {
        assertEquals("http://omniroute.example:8080/v1", PostProcessor.normalizeBaseUrl("http://omniroute.example:8080/v1"))
    }

    @Test
    fun normalizeBaseUrlTrimsTrailingSlash() {
        assertEquals("https://omniroute.example/v1", PostProcessor.normalizeBaseUrl("https://omniroute.example/v1/"))
        assertEquals("https://omniroute.example/v1", PostProcessor.normalizeBaseUrl("https://omniroute.example/v1///"))
    }

    @Test
    fun normalizeBaseUrlTrimsWhitespace() {
        assertEquals("https://omniroute.example/v1", PostProcessor.normalizeBaseUrl("  https://omniroute.example/v1  "))
    }

    @Test
    fun normalizeBaseUrlRejectsBlank() {
        assertEquals(null, PostProcessor.normalizeBaseUrl(""))
        assertEquals(null, PostProcessor.normalizeBaseUrl("   "))
    }

    @Test
    fun normalizeBaseUrlRejectsNonHttpScheme() {
        assertEquals(null, PostProcessor.normalizeBaseUrl("ftp://omniroute.example/v1"))
        assertEquals(null, PostProcessor.normalizeBaseUrl("javascript:alert(1)"))
    }

    @Test
    fun normalizeBaseUrlRejectsMalformedInput() {
        assertEquals(null, PostProcessor.normalizeBaseUrl("not a url"))
        assertEquals(null, PostProcessor.normalizeBaseUrl("://missing-scheme"))
        assertEquals(null, PostProcessor.normalizeBaseUrl("https://"))
    }

    // --- endpointUrl / destinationHost (#4) ---

    @Test
    fun endpointUrlAppendsChatCompletionsPath() {
        assertEquals(
            "https://omniroute.example/v1/chat/completions",
            PostProcessor.endpointUrl("https://omniroute.example/v1")
        )
    }

    @Test
    fun endpointUrlFallsBackToDefaultWhenBlank() {
        assertEquals(PostProcessor.ENDPOINT_URL, PostProcessor.endpointUrl(""))
    }

    @Test
    fun endpointUrlFallsBackToDefaultWhenInvalid() {
        assertEquals(PostProcessor.ENDPOINT_URL, PostProcessor.endpointUrl("not a url"))
    }

    @Test
    fun destinationHostReflectsCustomBaseUrl() {
        assertEquals("192.168.1.50", PostProcessor.destinationHost("http://192.168.1.50:8000/v1"))
    }

    @Test
    fun destinationHostFallsBackToDefaultWhenInvalid() {
        assertEquals(PostProcessor.DESTINATION_HOST, PostProcessor.destinationHost("garbage"))
        assertEquals(PostProcessor.DESTINATION_HOST, PostProcessor.destinationHost(""))
    }

    // --- buildRequestBody (#4) ---

    @Test
    fun buildRequestBodyUsesGivenModel() {
        val body = PostProcessor.buildRequestBody("raw text", "system prompt", "omniroute-local-7b")
        assertEquals("omniroute-local-7b", body.getString("model"))
    }

    @Test
    fun buildRequestBodyFallsBackToDefaultModelWhenBlank() {
        val body = PostProcessor.buildRequestBody("raw text", "system prompt", "")
        assertEquals(PostProcessor.DEFAULT_MODEL, body.getString("model"))
    }

    @Test
    fun buildRequestBodyIncludesSystemAndUserMessages() {
        val body = PostProcessor.buildRequestBody("raw text", "system prompt", "gpt-4o-mini")
        val messages = body.getJSONArray("messages")
        assertEquals(2, messages.length())
        assertEquals("system", messages.getJSONObject(0).getString("role"))
        assertEquals("system prompt", messages.getJSONObject(0).getString("content"))
        assertEquals("user", messages.getJSONObject(1).getString("role"))
        assertEquals("raw text", messages.getJSONObject(1).getString("content"))
    }

    @Test
    fun buildRequestBodyUsesDeterministicTemperature() {
        val body = PostProcessor.buildRequestBody("raw text", "system prompt", "gpt-4o-mini")
        assertEquals(0.0, body.getDouble("temperature"), 0.0001)
    }

    @Test
    fun buildRequestBodyOmitsTemperatureWhenRequested() {
        // #106: GPT-5.6-family reasoning models reject a non-default temperature outright.
        val body = PostProcessor.buildRequestBody("raw text", "system prompt", "gpt-5.6-terra", omitTemperature = true)
        assertFalse(body.has("temperature"))
    }

    @Test
    fun buildRequestBodyIncludesTemperatureByDefaultForBackcompat() {
        // Every existing call site (CleanupWaterfallExecutor) doesn't pass omitTemperature, so
        // the default must keep including it -- this is the real production request shape today.
        val body = PostProcessor.buildRequestBody("raw text", "system prompt", "gpt-5.4-mini")
        assertTrue(body.has("temperature"))
        assertEquals(0.0, body.getDouble("temperature"), 0.0001)
    }

    // --- vocabulary interpolation (#26) ---

    @Test
    fun vocabularyClauseIsEmptyForNoTerms() {
        assertEquals("", PostProcessor.vocabularyClause(emptyList()))
    }

    @Test
    fun vocabularyClauseListsEveryTerm() {
        val clause = PostProcessor.vocabularyClause(listOf("FastHTML", "OmniRoute", "nbdev"))
        assertTrue(clause.contains("FastHTML"))
        assertTrue(clause.contains("OmniRoute"))
        assertTrue(clause.contains("nbdev"))
    }

    @Test
    fun interpolateVocabularyReplacesPlaceholderWithClause() {
        val prompt = "Fix spelling.${PostProcessor.VOCABULARY_PLACEHOLDER} Done."
        val result = PostProcessor.interpolateVocabulary(prompt, listOf("FastHTML"))
        assertEquals("Fix spelling. Watch for these project names and personal vocabulary terms, which speech-to-text often mishears: FastHTML. Done.", result)
    }

    @Test
    fun interpolateVocabularyWithEmptyTermsLeavesNoDanglingPlaceholderText() {
        val prompt = "Fix spelling.${PostProcessor.VOCABULARY_PLACEHOLDER} Done."
        val result = PostProcessor.interpolateVocabulary(prompt, emptyList())
        assertEquals("Fix spelling. Done.", result)
        assertTrue(!result.contains("{{"))
        assertTrue(!result.contains("}}"))
    }

    @Test
    fun interpolateVocabularyOnCustomPromptWithoutPlaceholderIsUnchanged() {
        val prompt = "Always write in pirate speak."
        assertEquals(prompt, PostProcessor.interpolateVocabulary(prompt, listOf("FastHTML")))
    }

    @Test
    fun interpolateVocabularyOnCustomPromptOptsInViaPlaceholder() {
        val prompt = "Always write in pirate speak. Known terms:${PostProcessor.VOCABULARY_PLACEHOLDER}"
        val result = PostProcessor.interpolateVocabulary(prompt, listOf("FastHTML"))
        assertTrue(result.contains("FastHTML"))
        assertTrue(!result.contains(PostProcessor.VOCABULARY_PLACEHOLDER))
    }

    @Test
    fun devPromptProvablyContainsCurrentTermList() {
        val terms = listOf("OmniRoute", "Ramblr")
        val active = PostProcessor.interpolateVocabulary(PostProcessor.DEV_PROMPT, terms)
        for (term in terms) assertTrue(active.contains(term))
        assertTrue(!active.contains(PostProcessor.VOCABULARY_PLACEHOLDER))
    }

    @Test
    fun structuredPromptProvablyContainsCurrentTermList() {
        val terms = listOf("OmniRoute", "Ramblr")
        val active = PostProcessor.interpolateVocabulary(PostProcessor.STRUCTURED_PROMPT, terms)
        for (term in terms) assertTrue(active.contains(term))
        assertTrue(!active.contains(PostProcessor.VOCABULARY_PLACEHOLDER))
    }

    @Test
    fun simplePromptProvablyContainsCurrentTermList() {
        val terms = listOf("OmniRoute", "Ramblr")
        val active = PostProcessor.interpolateVocabulary(PostProcessor.SIMPLE_PROMPT, terms)
        for (term in terms) assertTrue(active.contains(term))
        assertTrue(!active.contains(PostProcessor.VOCABULARY_PLACEHOLDER))
    }

    @Test
    fun builtInPromptsWithEmptyTermsHaveNoDanglingPlaceholder() {
        for (prompt in listOf(PostProcessor.SIMPLE_PROMPT, PostProcessor.DEV_PROMPT, PostProcessor.STRUCTURED_PROMPT)) {
            val active = PostProcessor.interpolateVocabulary(prompt, emptyList())
            assertTrue(!active.contains(PostProcessor.VOCABULARY_PLACEHOLDER))
            assertTrue(!active.contains("{{"))
        }
    }
}
