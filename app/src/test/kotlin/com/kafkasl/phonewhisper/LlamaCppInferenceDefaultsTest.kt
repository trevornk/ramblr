package com.kafkasl.phonewhisper

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins [LlamaCppInference]'s load()/complete() defaults to the exact values independently proven
 * to produce real, working llama.cpp completions (#37) -- see tools/llama_cleanup_probe/, a
 * standalone host harness that runs the same LLMInference.cpp with these same parameters against
 * real downloaded GGUF models (SmolLM2-360M-Instruct and Qwen2.5-0.5B-Instruct, both Q4_K_M),
 * outside the Android/JNI runtime. These are all `const val`s inlined at compile time, so
 * referencing them here never touches LlamaCppInference's companion `init` block (which loads the
 * native library and would throw UnsatisfiedLinkError on a plain JVM test run).
 */
class LlamaCppInferenceDefaultsTest {
    @Test fun `verified-working inference defaults are unchanged`() {
        assertEquals(0.0f, LlamaCppInference.DEFAULT_TEMPERATURE)
        assertEquals(0.05f, LlamaCppInference.DEFAULT_MIN_P)
        assertEquals(2048L, LlamaCppInference.DEFAULT_CONTEXT_SIZE)
        assertEquals(4, LlamaCppInference.DEFAULT_NUM_THREADS)
        assertEquals("[EOG]", LlamaCppInference.END_OF_GENERATION)
        assertEquals("llama-cleanup-jni", LlamaCppInference.LIBRARY_NAME)
    }
}
