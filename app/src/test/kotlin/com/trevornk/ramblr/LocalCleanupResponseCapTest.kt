package com.trevornk.ramblr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class LocalCleanupResponseCapTest {

    @Test fun `the cap scales with the input but keeps a floor`() {
        assertEquals(LocalCleanupResponseCap.MIN_TOKENS, LocalCleanupResponseCap.maxTokensFor(0))
        assertEquals(LocalCleanupResponseCap.MIN_TOKENS, LocalCleanupResponseCap.maxTokensFor(10))
        assertTrue(LocalCleanupResponseCap.maxTokensFor(124) < LocalCleanupResponseCap.maxTokensFor(300))
    }

    @Test fun `the global ceiling still applies to long input`() {
        assertEquals(LlamaCppInference.MAX_RESPONSE_TOKENS, LocalCleanupResponseCap.maxTokensFor(100_000))
        assertTrue(LocalCleanupResponseCap.maxTokensFor(1_000) <= LlamaCppInference.MAX_RESPONSE_TOKENS)
    }

    @Test fun `a runaway on a 124-character chunk is cut far below the global ceiling`() {
        val cap = LocalCleanupResponseCap.maxTokensFor(124)
        assertTrue("cap $cap", cap < LlamaCppInference.MAX_RESPONSE_TOKENS / 3)
    }

    @Test fun `a legitimate cleanup of prose or digit-heavy text fits under the cap with room`() {
        // ~4 characters per token prose, and the harsh case of ~2 characters per token.
        for (chars in listOf(40, 124, 300, 480)) {
            assertTrue(chars / 4 * 2 < LocalCleanupResponseCap.maxTokensFor(chars))
            assertTrue(chars / 2 < LocalCleanupResponseCap.maxTokensFor(chars))
        }
    }

    @Test fun `the accumulator stops a never-ending generation at the cap`() {
        var pieces = 0
        val cap = LocalCleanupResponseCap.maxTokensFor(124)
        try {
            LlamaCompletionAccumulator.accumulate(maxPieces = cap, endOfGeneration = "[EOG]") {
                pieces++
                "w$pieces "
            }
            fail("expected the cap to trip")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("exceeded max response length"))
        }
        assertEquals(cap, pieces)
    }

    @Test fun `a generation that finishes inside the cap is untouched`() {
        val cap = LocalCleanupResponseCap.maxTokensFor(300)
        val out = LlamaCompletionAccumulator.accumulate(maxPieces = cap, endOfGeneration = "[EOG]") {
            if (it0++ < 75) "word${it0} " else "[EOG]"
        }
        assertFalse(out.isEmpty())
    }

    private var it0 = 0
}
