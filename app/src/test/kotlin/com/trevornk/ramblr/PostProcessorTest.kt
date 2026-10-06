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
    fun buildRequestBodySeedsKnownRejectingModelFamilies() {
        val body = PostProcessor.buildRequestBody("raw text", "system prompt", "gpt-5.6-luna")
        assertFalse(body.has("temperature"))
        assertFalse(PostProcessor.buildRequestBody("raw text", "system prompt", "o1-mini").has("temperature"))
        assertFalse(PostProcessor.buildRequestBody("raw text", "system prompt", "o3").has("temperature"))
        assertFalse(PostProcessor.buildRequestBody("raw text", "system prompt", "o4-mini").has("temperature"))
        assertTrue(PostProcessor.buildRequestBody("raw text", "system prompt", "gpt-6-luna").has("temperature"))
    }

    @Test
    fun buildRequestBodyKeepsTemperatureForUnknownModels() {
        val body = PostProcessor.buildRequestBody("raw text", "system prompt", "gpt-6-luna")
        assertTrue(body.has("temperature"))
        assertEquals("gpt-6-luna", body.getString("model"))
        assertFalse(body.getBoolean("stream"))
        assertEquals("system prompt", body.getJSONArray("messages").getJSONObject(0).getString("content"))
        assertEquals("raw text", body.getJSONArray("messages").getJSONObject(1).getString("content"))
    }

    @Test
    fun explicitTemperatureParameterRejectionClassifierIsNarrow() {
        assertTrue(TemperatureRejectionClassifier.isTemperatureRejection("""{"error":{"type":"unsupported_value","code":"unsupported_value","param":"temperature","message":"unsupported value"}}"""))
        assertTrue(TemperatureRejectionClassifier.isTemperatureRejection("""{"error":{"type":"unsupported_parameter","code":"unsupported_parameter","param":"temperature","message":"temperature is not supported"}}"""))
        assertFalse(TemperatureRejectionClassifier.isTemperatureRejection("""{"error":{"param":"model","message":"unsupported model"}}"""))
        assertFalse(TemperatureRejectionClassifier.isTemperatureRejection("""{"error":{"type":"invalid_request_error","code":"invalid_value","param":"model","message":"temperature is unsupported"}}"""))
        assertFalse(TemperatureRejectionClassifier.isTemperatureRejection("""{"error":{"type":"invalid_value","code":"invalid_value","param":"temperature","message":"value must be between 0 and 2"}}"""))
        assertTrue(TemperatureRejectionClassifier.isTemperatureRejection("""{"error":{"code":400,"message":"invalid request","details":[{"fieldViolations":[{"field":"generationConfig.temperature","description":"temperature is not supported"}]}]}}"""))
        assertFalse(TemperatureRejectionClassifier.isTemperatureRejection("""{"error":{"code":400,"message":"invalid request","details":[{"fieldViolations":[{"field":"generationConfig.topP","description":"temperature unsupported"}]}]}}"""))
        assertFalse(TemperatureRejectionClassifier.isTemperatureRejection("""{"error":{"code":400,"message":"invalid request","details":[{"fieldViolations":[{"field":"generationConfig.temperatureSuffix","description":"unsupported"}]}]}}"""))
        assertFalse(TemperatureRejectionClassifier.isTemperatureRejection("""{"error":{"param":"temperature","message":"unsupported value"}}""", 403))
    }

    @Test
    fun buildRequestBodyIncludesTemperatureByDefaultForBackcompat() {
        // Original requests carry temperature; retry code removes it from a clone only after a
        // structured rejection.
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

    // --- #290: keep cleanup in the transcript's language ---

    @Test fun `withKeepLanguage appends the clause as the final paragraph`() {
        val out = PostProcessor.withKeepLanguage(PostProcessor.SIMPLE_PROMPT)
        assertTrue(out.startsWith(PostProcessor.SIMPLE_PROMPT))
        assertTrue(out.endsWith("\n\n" + PostProcessor.KEEP_LANGUAGE_CLAUSE))
    }

    @Test fun `withKeepLanguage trims trailing whitespace so a user prompt doesn't grow blank lines`() {
        assertEquals("Fix it.\n\n" + PostProcessor.KEEP_LANGUAGE_CLAUSE, PostProcessor.withKeepLanguage("Fix it.\n\n  "))
    }

    @Test fun `the clause forbids translation but leaves room for a translate style`() {
        val clause = PostProcessor.KEEP_LANGUAGE_CLAUSE
        assertTrue(clause.contains("same language as the transcript"))
        assertTrue(clause.contains("do not translate"))
        assertTrue(clause.contains("unless the instructions above explicitly ask for a translation"))
    }

    @Test fun `every built-in persona prompt gets the clause after vocabulary interpolation`() {
        for (persona in CleanupPersonas.BUILT_IN + CleanupPersonas.LEGACY_RETIRED) {
            val out = PostProcessor.withKeepLanguage(PostProcessor.interpolateVocabulary(persona.prompt, listOf("Ramblr")))
            assertTrue(persona.key, out.endsWith(PostProcessor.KEEP_LANGUAGE_CLAUSE))
            assertFalse(persona.key, out.contains(PostProcessor.VOCABULARY_PLACEHOLDER))
        }
    }
}
