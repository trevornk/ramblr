package com.trevornk.ramblr

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** #290: Gemini's batch transcription has no language field, so the user's dictation language
 *  rides in the prompt. Auto-detect must leave the prompt exactly as it was before. */
class GeminiTranscriberLanguageTest {

    @Test fun `auto-detect leaves the prompt byte-identical to pre-290 builds`() {
        assertEquals(GeminiTranscriberClient.TRANSCRIBE_PROMPT, GeminiTranscriberClient.transcribePrompt(emptyList()))
        assertEquals(GeminiTranscriberClient.TRANSCRIBE_PROMPT, GeminiTranscriberClient.transcribePrompt(emptyList(), null))
        assertEquals(
            GeminiTranscriberClient.transcribePrompt(listOf("Ramblr")),
            GeminiTranscriberClient.transcribePrompt(listOf("Ramblr"), null),
        )
    }

    @Test fun `a chosen language names it in english and forbids translation`() {
        val prompt = GeminiTranscriberClient.transcribePrompt(emptyList(), "de")
        assertTrue(prompt.startsWith(GeminiTranscriberClient.TRANSCRIBE_PROMPT))
        assertTrue(prompt, prompt.contains("speaking German (de)"))
        assertTrue(prompt, prompt.contains("never translate"))
    }

    @Test fun `language hint and vocabulary coexist`() {
        val prompt = GeminiTranscriberClient.transcribePrompt(listOf("Claude Code"), "de")
        assertTrue(prompt.contains("German"))
        assertTrue(prompt.contains("Claude Code"))
    }

    @Test fun `the hint reaches the request body Gemini receives`() {
        val body = GeminiTranscriberClient.buildRequestBody(
            ByteArray(4),
            prompt = GeminiTranscriberClient.transcribePrompt(emptyList(), "fr"),
        )
        val text = JSONObject(body.toString())
            .getJSONArray("contents").getJSONObject(0)
            .getJSONArray("parts").getJSONObject(0).getString("text")
        assertTrue(text, text.contains("speaking French (fr)"))
        assertFalse(text.contains("German"))
    }
}
