package com.kafkasl.phonewhisper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure request/response-shape coverage for [LocalCleanupProvider] (#37), the LOCAL_LLM
 * counterpart to [OmniRoute]/[AnthropicCleanupProvider]. Uses a fake [LocalInferenceEngine], the
 * same seam [CleanupWaterfallExecutor] depends on -- real llama.cpp/JNI inference is not, and
 * cannot be, exercised here; see app/src/main/cpp/llama_cleanup/README.md.
 */
class LocalCleanupProviderTest {

    @Test fun `run translates a successful completion into a trimmed PostProcessor Result`() {
        val engine = LocalInferenceEngine { _, _, _ -> LocalInferenceResult.Success("  cleaned text  ") }
        val result = LocalCleanupProvider.run("raw", "prompt", "/model.gguf", engine)
        assertEquals("cleaned text", result.text)
        assertNull(result.error)
    }

    @Test fun `run translates a blank completion into a null-text error result`() {
        val engine = LocalInferenceEngine { _, _, _ -> LocalInferenceResult.Success("   ") }
        val result = LocalCleanupProvider.run("raw", "prompt", "/model.gguf", engine)
        assertNull(result.text)
    }

    @Test fun `run translates a failure into an error Result with no text`() {
        val engine = LocalInferenceEngine { _, _, _ -> LocalInferenceResult.Failure("context size reached") }
        val result = LocalCleanupProvider.run("raw", "prompt", "/model.gguf", engine)
        assertNull(result.text)
        assertEquals("context size reached", result.error)
    }

    @Test fun `run passes prompt as the system message and text as the user message, same shape as PostProcessor`() {
        var capturedSystem: String? = null
        var capturedUser: String? = null
        val engine = LocalInferenceEngine { systemPrompt, userText, _ ->
            capturedSystem = systemPrompt
            capturedUser = userText
            LocalInferenceResult.Success("ok")
        }
        LocalCleanupProvider.run(text = "the raw transcript", prompt = PostProcessor.DEFAULT_PROMPT, "/model.gguf", engine)
        assertEquals(PostProcessor.DEFAULT_PROMPT, capturedSystem)
        assertEquals("the raw transcript", capturedUser)
    }

    @Test fun `MODEL is the one curated local-cleanup catalog entry`() {
        assertEquals(LOCAL_CLEANUP_MODEL, LocalCleanupProvider.MODEL)
        assertTrue(LocalCleanupProvider.MODEL.isLocalCleanup)
    }
}
