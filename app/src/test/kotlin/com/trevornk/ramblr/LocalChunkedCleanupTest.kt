package com.trevornk.ramblr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Chunked on-device cleanup for transcripts too long for one pass (#325): what the terminal
 * LOCAL_LLM step does with a long transcript, how it fails, how it is bounded.
 */
class LocalChunkedCleanupTest {

    /** Engine fake driven by a function of the chunk text; records every call. */
    private class ScriptedEngine(
        private val onAfterCall: () -> Unit = {},
        private val onCall: (callIndex: Int, userText: String) -> LocalInferenceResult,
    ) : LocalInferenceEngine {
        val userTexts = mutableListOf<String>()
        val systemPrompts = mutableListOf<String>()
        val deadlines = mutableListOf<Long>()
        override fun complete(
            systemPrompt: String,
            userText: String,
            modelPath: String,
            deadlineAtMs: Long,
            isCancelled: () -> Boolean,
        ): LocalInferenceResult {
            systemPrompts += systemPrompt
            userTexts += userText
            deadlines += deadlineAtMs
            val result = onCall(userTexts.size - 1, userText)
            onAfterCall()
            return result
        }
    }

    private val localOnly = CleanupWaterfall(listOf(CleanupStep(CleanupStepGroup.LOCAL_LLM, "lfm")))
    private val prompt = PostProcessor.SIMPLE_PROMPT

    private fun sentences(n: Int) = (1..n).joinToString(" ") { "This is sentence number $it of the recording." }

    private fun run(
        text: String,
        engine: LocalInferenceEngine,
        waterfall: CleanupWaterfall = localOnly,
        holder: InFlightCall = InFlightCall(),
        vocabulary: List<String> = emptyList(),
        nowMs: () -> Long = { System.currentTimeMillis() },
        transport: CleanupHttpTransport = CleanupHttpTransport { _, _, _, _, _, _ -> error("no cloud step expected") },
        credential: String = "",
    ): PostProcessor.Result {
        var captured: PostProcessor.Result? = null
        CleanupWaterfallExecutor.execute(
            text = text,
            prompt = "cloud prompt",
            waterfall = waterfall,
            cursor = CleanupWaterfallCursor(),
            cancelHolder = holder,
            credentialLookup = { credential },
            transport = transport,
            localInference = engine,
            localModelPath = { "/model.gguf" },
            localPrompt = prompt,
            localVocabulary = vocabulary,
            nowMs = nowMs,
            callback = { captured = it },
        )
        return captured ?: error("callback never fired")
    }

    private fun cleaned(userText: String) = LocalInferenceResult.Success("[$userText]")

    // ---- long input -> several calls ---------------------------------------------------------

    @Test fun `a long transcript is cleaned in several calls that each fit the model and the budget`() {
        val text = sentences(60) // ~2,700 characters
        val before = System.currentTimeMillis()
        val engine = ScriptedEngine { _, t -> cleaned(t) }

        val result = run(text, engine)
        val after = System.currentTimeMillis()

        assertNull(result.error)
        assertNull("every chunk cleaned => not partial", result.partial)
        assertEquals("a fully cleaned run still reports its real size", PartialCleanup(engine.userTexts.size, engine.userTexts.size), result.sections)
        assertTrue("expected several chunks, got ${engine.userTexts.size}", engine.userTexts.size > 5)
        val max = LocalCleanupChunker.maxChunkChars(prompt)
        assertTrue(engine.userTexts.all { it.length <= max })
        assertTrue("same system prompt for every chunk", engine.systemPrompts.all { it == prompt })
        // Each chunk gets its own full budget, not whatever is left of one shared deadline.
        assertTrue(engine.deadlines.all { it >= before + LOCAL_CHUNK_BUDGET_MS && it <= after + LOCAL_CHUNK_BUDGET_MS })
        // The output is every chunk cleaned, in order, and nothing was dropped.
        val reassembled = result.text!!.replace("[", "").replace("]", "")
        assertEquals(text, reassembled)
    }

    @Test fun `chunk budget is generous enough to matter for a slow phone but far below the watchdog`() {
        assertTrue(LOCAL_CHUNK_BUDGET_MS > TERMINAL_LOCAL_LLM_STEP_BUDGET_MS)
        assertTrue(LOCAL_CHUNKED_TOTAL_BUDGET_MS < 400_000L)
        assertTrue(LOCAL_CHUNKED_TOTAL_BUDGET_MS >= 10 * LOCAL_CHUNK_BUDGET_MS / 2)
    }

    @Test fun `paragraph breaks in the transcript survive chunked cleanup`() {
        val a = sentences(10)
        val b = sentences(10)
        val result = run("$a\n\n$b", ScriptedEngine { _, t -> cleaned(t) })
        assertTrue(result.text!!.contains("recording.]\n\n[This is sentence number 1 of"))
    }

    // ---- short input is untouched --------------------------------------------------------------

    @Test fun `a short transcript takes the single pass path with the original terminal deadline`() {
        val text = "um so this is a short dictation that fits easily."
        val engine = ScriptedEngine { _, _ -> LocalInferenceResult.Success("Cleaned.") }
        val before = System.currentTimeMillis()
        val result = run(text, engine)
        val after = System.currentTimeMillis()

        assertEquals("Cleaned.", result.text)
        assertNull(result.partial)
        assertNull("single pass has no chunk report", result.sections)
        assertEquals(listOf(text), engine.userTexts)
        val deadline = engine.deadlines.single()
        assertTrue(deadline >= before + TERMINAL_LOCAL_LLM_STEP_BUDGET_MS)
        assertTrue(deadline <= after + TERMINAL_LOCAL_LLM_STEP_BUDGET_MS)
    }

    @Test fun `text exactly at the limit is still a single pass`() {
        val max = LocalCleanupChunker.maxChunkChars(prompt)
        val text = "a".repeat(max)
        val engine = ScriptedEngine { _, _ -> LocalInferenceResult.Success("ok") }
        run(text, engine)
        assertEquals(1, engine.userTexts.size)
    }

    @Test fun `a short transcript that fails still fails the step with the engine's own message`() {
        val engine = ScriptedEngine { _, _ -> LocalInferenceResult.Failure("Local cleanup output rejected (LENGTH_COLLAPSE)") }
        val result = run("short text here", engine)
        assertNull(result.text)
        assertEquals("All cleanup steps failed: Local cleanup output rejected (LENGTH_COLLAPSE)", result.error)
    }

    @Test fun `a non-terminal local step never chunks -- its small budget protects the step behind it`() {
        val waterfall = CleanupWaterfall(
            listOf(
                CleanupStep(CleanupStepGroup.LOCAL_LLM, "lfm"),
                CleanupStep(CleanupStepGroup.OPENAI_DIRECT, "gpt"),
            ),
        )
        val engine = ScriptedEngine { _, _ -> LocalInferenceResult.Failure("too long for one pass") }
        val transport = CleanupHttpTransport { _, _, _, _, _, cb ->
            cb(CleanupHttpOutcome.Ok("""{"choices":[{"message":{"content":"cloud cleaned"}}]}"""))
        }
        val text = sentences(60)
        val result = run(text, engine, waterfall = waterfall, transport = transport, credential = "key")

        assertEquals(listOf(text), engine.userTexts)
        assertEquals("cloud cleaned", result.text)
    }

    // ---- per-chunk failure isolation -------------------------------------------------------------

    @Test fun `one rejected chunk stays raw and the rest are cleaned -- partial success`() {
        val engine = ScriptedEngine { i, t ->
            if (i == 2) LocalInferenceResult.Failure("Local cleanup output rejected (NUMERIC_DIVERGENCE)") else cleaned(t)
        }
        val text = sentences(60)
        val result = run(text, engine)

        assertNull(result.error)
        val partial = result.partial!!
        assertEquals(engine.userTexts.size - 1, partial.cleanedChunks)
        assertEquals(engine.userTexts.size, partial.totalChunks)
        assertEquals(partial, result.sections)
        val out = result.text!!
        val rawChunk = engine.userTexts[2]
        assertTrue("failed chunk delivered raw, unbracketed", out.contains(" $rawChunk ") && !out.contains("[$rawChunk]"))
        assertTrue(out.contains("[${engine.userTexts[1]}]") && out.contains("[${engine.userTexts[3]}]"))
        assertTrue("the run continued past the failure", engine.userTexts.size > 3)
    }

    @Test fun `a thrown cap or loop failure on one chunk is isolated the same way`() {
        val engine = ScriptedEngine { i, t ->
            if (i == 0) LocalInferenceResult.Failure("Local model exceeded max response length (512 pieces) without emitting an end-of-generation token")
            else cleaned(t)
        }
        val result = run(sentences(30), engine)
        assertNull(result.error)
        assertEquals(1, result.partial!!.totalChunks - result.partial!!.cleanedChunks)
    }

    @Test fun `when no chunk could be cleaned the step fails with the last reason`() {
        val engine = ScriptedEngine { _, _ -> LocalInferenceResult.Failure("Local cleanup output rejected (PROMPT_ECHO)") }
        val result = run(sentences(30), engine)
        assertNull(result.text)
        assertEquals("All cleanup steps failed: Local cleanup output rejected (PROMPT_ECHO)", result.error)
    }

    @Test fun `numeric divergence is checked per chunk against that chunk's own numbers`() {
        // Chunk text contains "forty five"; the fake drops it, so the numeric verifier rejects that chunk only.
        val text = (1..30).joinToString(" ") { if (it == 12) "The total was forty five dollars." else "Plain sentence $it goes here." }
        val engine = ScriptedEngine { _, t ->
            if (t.contains("45") || t.contains("forty")) LocalInferenceResult.Success("The total was a lot of dollars.") else cleaned(t)
        }
        val result = run(text, engine)
        assertNull(result.error)
        assertEquals(1, result.partial!!.totalChunks - result.partial!!.cleanedChunks)
    }

    @Test fun `vocabulary correction is applied to each cleaned chunk`() {
        val text = sentences(20) + " I talked to terelle about it."
        val engine = ScriptedEngine { _, t -> cleaned(t) }
        val result = run(text, engine, vocabulary = listOf("Terelle"))
        assertTrue(result.text!!.contains("Terelle"))
    }

    // ---- timeouts, budgets -------------------------------------------------------------------------

    @Test fun `two timeouts in a row stop the run and leave the remainder raw`() {
        val engine = ScriptedEngine { i, t ->
            if (i in 1..2) LocalInferenceResult.TimedOut("Local cleanup timed out") else cleaned(t)
        }
        val text = sentences(60)
        val result = run(text, engine)

        assertEquals("no engine call after the second consecutive timeout", 3, engine.userTexts.size)
        assertNull(result.error)
        assertEquals(1, result.partial!!.cleanedChunks)
        // Everything after the stopped chunk is raw -- the output still contains all the text.
        assertEquals(text, result.text!!.replace("[", "").replace("]", ""))
    }

    @Test fun `isolated timeouts do not trip the breaker`() {
        val engine = ScriptedEngine { i, t ->
            if (i % 3 == 1) LocalInferenceResult.TimedOut("Local cleanup timed out") else cleaned(t)
        }
        val result = run(sentences(60), engine)
        assertTrue(engine.userTexts.size > 6)
        assertNotNull(result.partial)
    }

    @Test fun `the accumulator's own deadline failure counts as a timeout for the breaker`() {
        val engine = ScriptedEngine { _, _ -> LocalInferenceResult.Failure("Local completion exceeded its wall-clock deadline") }
        run(sentences(60), engine)
        assertEquals(LOCAL_CHUNK_MAX_CONSECUTIVE_TIMEOUTS, engine.userTexts.size)
    }

    @Test fun `validator rejections never trip the timeout breaker`() {
        val engine = ScriptedEngine { _, _ -> LocalInferenceResult.Failure("Local cleanup output rejected (LENGTH_COLLAPSE)") }
        run(sentences(60), engine)
        assertTrue(engine.userTexts.size > LOCAL_CHUNK_MAX_CONSECUTIVE_TIMEOUTS)
    }

    @Test fun `the total budget ends the run and the remaining chunks are delivered raw`() {
        var now = 1_000_000L
        val engine = ScriptedEngine(onCall = { _, t -> cleaned(t) }, onAfterCall = { now += 60_000L }) // 60s per chunk
        val text = sentences(60)
        val result = run(text, engine, nowMs = { now })

        val expectedCalls = (LOCAL_CHUNKED_TOTAL_BUDGET_MS / 60_000L).toInt() // 4
        assertEquals(expectedCalls, engine.userTexts.size)
        assertNull(result.error)
        assertEquals(expectedCalls, result.partial!!.cleanedChunks)
        assertTrue(result.partial!!.totalChunks > expectedCalls)
        assertEquals(text, result.text!!.replace("[", "").replace("]", ""))
    }

    // ---- cancellation -------------------------------------------------------------------------------

    @Test fun `a cancel between chunks stops the run before the next chunk`() {
        val holder = InFlightCall()
        var calls = 0
        val engine = ScriptedEngine(onCall = { _, t -> cleaned(t) }, onAfterCall = {
            if (++calls == 2) holder.cancel()
        })
        val result = run(sentences(60), engine, holder = holder)

        assertNull(result.text)
        assertEquals("Cleanup cancelled", result.error)
        assertEquals("no chunk starts after the cancel", 2, engine.userTexts.size)
    }

    @Test fun `an engine-level cancel during a chunk stops the whole run`() {
        val engine = ScriptedEngine { i, t -> if (i == 1) LocalInferenceResult.Cancelled else cleaned(t) }
        val result = run(sentences(60), engine)
        assertNull(result.text)
        assertEquals("Cleanup cancelled", result.error)
        assertEquals(2, engine.userTexts.size)
    }

    // ---- misc ----------------------------------------------------------------------------------------

    @Test fun `partial results are not an error and keep Result error null`() {
        val engine = ScriptedEngine { i, t -> if (i == 0) LocalInferenceResult.Failure("x") else cleaned(t) }
        val result = run(sentences(60), engine)
        assertNotNull(result.text)
        assertNull(result.error)
        assertFalse(result.partial == null)
    }
}
