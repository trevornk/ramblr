package com.trevornk.ramblr

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

    @Test fun `a passed wall-clock deadline aborts generation before the next piece (#83)`() {
        var calls = 0
        val clock = mutableListOf(0L, 10L, 20L, 5_000L) // 4th check is past the deadline
        try {
            LlamaCompletionAccumulator.accumulate(
                maxPieces = 100,
                endOfGeneration = "[EOG]",
                deadlineAtMs = 1_000L,
                nowMs = { clock.removeAt(0) },
            ) {
                calls++
                "piece "
            }
            fail("expected an IllegalStateException")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("deadline"))
        }
        assertEquals(3, calls) // aborted before the 4th decode, long before any piece cap
    }

    @Test fun `a cancel aborts generation before the next piece (#83)`() {
        var calls = 0
        var cancelled = false
        try {
            LlamaCompletionAccumulator.accumulate(
                maxPieces = 100,
                endOfGeneration = "[EOG]",
                isCancelled = { cancelled },
            ) {
                calls++
                if (calls == 2) cancelled = true
                "piece "
            }
            fail("expected an IllegalStateException")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("cancelled"))
        }
        assertEquals(2, calls) // no further native decode once the cancel was observed
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

    // -- Degenerate-repetition detection (#179) ------------------------------------------------
    //
    // The fixtures below are real mumble-cleanup-2stage-q4_0 output captured via
    // tools/llama_cleanup_probe against transcripts from an actual dictation history, not
    // hand-written strings: the point of the check is that it fires on what the model really
    // does and stays silent on what it really produces when healthy.

    /** Verbatim tail of the 2197-character degenerate response the 580-char transcript produces. */
    private val loopingResponse =
        "I'm supposed to try that, I'll probably just cruise around, maybe see if she's gonna " +
            "chase after any of those chip-buds hanging out around the park, and it's probably " +
            "just really good. So, I'm supposed to try that, I'll probably just cruise around, " +
            "maybe see if she's gonna chase after any of those chip-buds hanging out around the " +
            "park, and it's probably just really good. So I'm supposed to try that, I'll " +
            "probably just cruise around, maybe see if she's gonna chase after any of those " +
            "chip-buds hanging out around the park, and it looks really good."

    @Test fun `a generation that collapses into a repeating loop is stopped before the cap (#179)`() {
        // Feed the real looping text word by word with a cap far higher than it will reach: the
        // loop check must be what stops it, proving the bound is the repetition and not the cap.
        val pieces = loopingResponse.split(" ").map { "$it " }.toMutableList()
        val totalPieces = pieces.size
        var calls = 0
        try {
            LlamaCompletionAccumulator.accumulate(maxPieces = 512, endOfGeneration = "[EOG]") {
                calls++
                if (pieces.isEmpty()) "[EOG]" else pieces.removeAt(0)
            }
            fail("expected an IllegalStateException")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("repeating loop"))
        }
        // Stopped mid-stream: strictly fewer decodes than the fixture holds, and far short of the
        // cap -- so the repetition is what bounded it, not either pre-existing limit.
        assertTrue("should stop before consuming the whole loop ($calls of $totalPieces)", calls < totalPieces)
        assertTrue("should stop well short of the 512-piece cap", calls < 512)
    }

    @Test fun `real healthy cleanup outputs never trip the loop detector (#179)`() {
        // Every cleaned output the model produced for the 60+ character transcripts in a real
        // dictation history. If the detector fires on any of these it is destroying good output.
        val healthy = listOf(
            "I think I'm gonna go on a walk and then probably see if there's any chipmunks " +
                "hanging out around the park.",
            "Send four hundred and fifty dollars to the account ending in nine three seven two.",
            "Can you grab milk, eggs, and bread on the way home tonight?",
            "The meeting got moved to Thursday at two, so I'll need to reschedule the dentist.",
            "I wanted to follow up on the invoice from last month and see whether it went out " +
                "already, because the client asked about it twice now and I don't want it to " +
                "slip again before the end of the quarter.",
        )
        for (text in healthy) {
            val pieces = text.split(" ").map { "$it " }.toMutableList()
            val result = LlamaCompletionAccumulator.accumulate(maxPieces = 512, endOfGeneration = "[EOG]") {
                if (pieces.isEmpty()) "[EOG]" else pieces.removeAt(0)
            }
            assertEquals(text.trim(), result.trim())
        }
    }

    @Test fun `naturally repeated phrasing below the threshold is preserved (#179)`() {
        // Dictation genuinely repeats itself and cleanup must not truncate that. Two copies of a
        // repeated clause is normal speech; the detector requires three non-overlapping ones.
        val text = "I really think we should go to the store, I really think we should go to " +
            "the store, but only if it stays open late enough for us to get there."
        val pieces = text.split(" ").map { "$it " }.toMutableList()
        val result = LlamaCompletionAccumulator.accumulate(maxPieces = 512, endOfGeneration = "[EOG]") {
            if (pieces.isEmpty()) "[EOG]" else pieces.removeAt(0)
        }
        assertEquals(text.trim(), result.trim())
    }

    @Test fun `the loop check can be disabled without affecting other bounds (#179)`() {
        // A non-positive window turns the check off; the piece cap must still apply, so a
        // degenerate model is never left completely unbounded.
        val pieces = loopingResponse.split(" ").map { "$it " }.toMutableList()
        try {
            LlamaCompletionAccumulator.accumulate(
                maxPieces = 8,
                endOfGeneration = "[EOG]",
                loopWindowChars = 0,
            ) {
                if (pieces.isEmpty()) "[EOG]" else pieces.removeAt(0)
            }
            fail("expected an IllegalStateException")
        } catch (e: IllegalStateException) {
            assertTrue("cap should stop it, not the loop check", e.message!!.contains("8 pieces"))
        }
    }

    @Test fun `overlapping halves of one long run do not count as a cycle (#179)`() {
        // A single long repeated character run contains its own tail window many times if you
        // count overlapping matches. Counting must be non-overlapping or this false-positives.
        val text = "a".repeat(LlamaCompletionAccumulator.LOOP_WINDOW_CHARS * 2 - 1)
        val pieces = text.map { it.toString() }.toMutableList()
        val result = LlamaCompletionAccumulator.accumulate(maxPieces = 512, endOfGeneration = "[EOG]") {
            if (pieces.isEmpty()) "[EOG]" else pieces.removeAt(0)
        }
        assertEquals(text, result)
    }
}
