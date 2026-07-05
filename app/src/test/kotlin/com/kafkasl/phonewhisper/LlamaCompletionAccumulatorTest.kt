package com.kafkasl.phonewhisper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class LlamaCompletionAccumulatorTest {

    @Test fun `accumulates pieces until end-of-generation marker`() {
        val pieces = mutableListOf("Hello", ", ", "world", "[EOG]")
        val result = LlamaCompletionAccumulator.accumulate(maxPieces = 10, endOfGeneration = "[EOG]") {
            pieces.removeAt(0)
        }
        assertEquals("Hello, world", result)
    }

    @Test fun `an immediate end-of-generation marker produces an empty string`() {
        val result = LlamaCompletionAccumulator.accumulate(maxPieces = 10, endOfGeneration = "[EOG]") { "[EOG]" }
        assertEquals("", result)
    }

    @Test fun `a model that never emits the marker is stopped at the cap, not left to run forever`() {
        var calls = 0
        try {
            LlamaCompletionAccumulator.accumulate(maxPieces = 5, endOfGeneration = "[EOG]") {
                calls++
                "more rambling "
            }
            fail("expected an IllegalStateException")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("5 pieces"))
        }
        // Exactly maxPieces calls -- no unbounded loop, no off-by-one running past the cap.
        assertEquals(5, calls)
    }

    @Test fun `hitting the cap on the exact final piece still throws (no off-by-one leniency)`() {
        val pieces = mutableListOf("a", "b", "c") // 3 pieces, cap of 3, no [EOG] ever
        try {
            LlamaCompletionAccumulator.accumulate(maxPieces = 3, endOfGeneration = "[EOG]") {
                pieces.removeAt(0)
            }
            fail("expected an IllegalStateException")
        } catch (e: IllegalStateException) {
            // expected
        }
    }

    @Test fun `a marker arriving just before the cap would be hit still completes normally`() {
        // 3 real pieces then EOG on the 4th call, with a cap of 4 -- the cap check only fires
        // after appending a non-terminal piece, so this must succeed rather than throw.
        val pieces = mutableListOf("a", "b", "c", "[EOG]")
        val result = LlamaCompletionAccumulator.accumulate(maxPieces = 4, endOfGeneration = "[EOG]") {
            pieces.removeAt(0)
        }
        assertEquals("abc", result)
    }
}
