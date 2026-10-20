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
        var prepared = 0
        var preparedAtCall = -1
        var prepareThrows = false
        override fun prepare(modelPath: String) {
            prepared++
            preparedAtCall = userTexts.size
            if (prepareThrows) error("load failed")
        }

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
        stepBudgetMs: Long = LOCAL_CHUNKED_TOTAL_BUDGET_MS,
        pace: LocalCleanupPaceStore = LocalCleanupPaceStore(),
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
            localStepBudgetMs = stepBudgetMs,
            localPace = pace,
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
        // Each chunk gets its own allowance, not whatever is left of one shared deadline: the
        // roomy initial one for the first chunk (nothing measured yet), then the floor on a
        // device this fast.
        val initial = LocalCleanupChunkBudget.INITIAL_BUDGET_MS
        val floor = LocalCleanupChunkBudget.MIN_BUDGET_MS
        assertTrue(engine.deadlines.first() >= before + initial && engine.deadlines.first() <= after + initial)
        assertTrue(engine.deadlines.drop(1).all { it >= before + floor && it <= after + floor })
        // The output is every chunk cleaned, in order, and nothing was dropped.
        val reassembled = result.text!!.replace("[", "").replace("]", "")
        assertEquals(text, reassembled)
    }

    @Test fun `chunk budgets never undercut the flat budget that shipped and stay far below the watchdog`() {
        assertTrue(LocalCleanupChunkBudget.MIN_BUDGET_MS >= 20_000L)
        assertTrue(LocalCleanupChunkBudget.MIN_BUDGET_MS > TERMINAL_LOCAL_LLM_STEP_BUDGET_MS)
        assertTrue(LocalCleanupChunkBudget.INITIAL_BUDGET_MS in LocalCleanupChunkBudget.MIN_BUDGET_MS..LocalCleanupChunkBudget.MAX_BUDGET_MS)
        // One stuck chunk can never use the whole step, and both stay under the 400 s watchdog.
        assertTrue(LocalCleanupChunkBudget.MAX_BUDGET_MS < LOCAL_CHUNKED_TOTAL_BUDGET_MS)
        assertTrue(LOCAL_CHUNKED_TOTAL_BUDGET_MS < 400_000L)
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

    @Test fun `a chunk that cannot fit in what is left of the step is not started`() {
        val clock = FakeClock()
        val device = SimulatedDevice(clock, msPerChar = 200.0) // ~58 s per 290-character chunk
        val text = sentences(60)
        val result = run(text, device, nowMs = clock::now)

        assertNull(result.error)
        assertEquals("no chunk ran into its deadline", 0, device.timedOut)
        assertTrue("never ran past the step ceiling: ${clock.elapsed}", clock.elapsed <= LOCAL_CHUNKED_TOTAL_BUDGET_MS)
        val partial = result.partial!!
        assertEquals(device.calls, partial.cleanedChunks)
        assertTrue(partial.totalChunks > device.calls)
        assertTrue("several chunks fit before the ceiling, got ${device.calls}", device.calls in 3..5)
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

    // ---- budget fits the device (#325 follow-up) ---------------------------------------------------

    /** Deterministic stand-in for wall time: only the engines advance it. */
    private class FakeClock(var t: Long = 1_000_000L) {
        private val start = t
        fun now() = t
        val elapsed get() = t - start
    }

    /**
     * A device that decodes at a fixed pace, honouring the deadline it is given like the real
     * engine does: a chunk that needs longer than its allowance burns exactly the allowance and
     * reports [LocalInferenceResult.TimedOut].
     */
    private class SimulatedDevice(
        private val clock: FakeClock,
        private val msPerChar: Double,
        private val jitter: (callIndex: Int) -> Double = { 1.0 },
    ) : LocalInferenceEngine {
        var calls = 0
        var timedOut = 0
        val allowances = mutableListOf<Long>()
        val durations = mutableListOf<Long>()
        var preparedAtMs: Long? = null
        override fun prepare(modelPath: String) { preparedAtMs = clock.now() }
        override fun complete(
            systemPrompt: String,
            userText: String,
            modelPath: String,
            deadlineAtMs: Long,
            isCancelled: () -> Boolean,
        ): LocalInferenceResult {
            val needed = (msPerChar * userText.length * jitter(calls)).toLong()
            val allowed = (deadlineAtMs - clock.now()).coerceAtLeast(0)
            allowances += allowed
            calls++
            return if (needed > allowed) {
                clock.t += allowed
                durations += allowed
                timedOut++
                LocalInferenceResult.TimedOut("Local cleanup timed out")
            } else {
                clock.t += needed
                durations += needed
                LocalInferenceResult.Success("[$userText]")
            }
        }
    }

    @Test fun `a fast device finishes every chunk and is never given less than the old budget`() {
        val clock = FakeClock()
        val device = SimulatedDevice(clock, msPerChar = 25.0) // ~7.5 s per chunk, LFM2.5 on a Pixel 10a
        val text = sentences(60)
        val result = run(text, device, nowMs = clock::now)

        assertNull(result.partial)
        assertEquals(0, device.timedOut)
        assertEquals(text, result.text!!.replace("[", "").replace("]", ""))
        assertTrue(device.allowances.all { it >= LocalCleanupChunkBudget.MIN_BUDGET_MS })
    }

    @Test fun `the model is prepared once, before the first chunk, outside the chunk clock`() {
        val engine = ScriptedEngine { _, t -> cleaned(t) }
        run(sentences(30), engine)
        assertEquals(1, engine.prepared)
        assertEquals("prepared before any chunk ran", 0, engine.preparedAtCall)
    }

    @Test fun `a failing prepare does not fail the run -- the first chunk reports the real error`() {
        val engine = ScriptedEngine { _, t -> cleaned(t) }.apply { prepareThrows = true }
        val result = run(sentences(30), engine)
        assertNull(result.error)
        assertTrue(engine.userTexts.size > 1)
    }

    @Test fun `a short transcript never prepares or chunks`() {
        val engine = ScriptedEngine { _, _ -> LocalInferenceResult.Success("Cleaned.") }
        run("a short dictation.", engine)
        assertEquals(0, engine.prepared)
    }

    @Test fun `the 10a with Mumble cleans every chunk even when some run past 20 seconds`() {
        // Measured: median 10-14 s per chunk, cleaned chunks up to 18.3 s, and 3 of 40 chunks
        // needed more than the flat 20 s. Jitter reproduces that spread around ~13 s.
        val spread = doubleArrayOf(1.0, 0.9, 1.15, 1.7, 0.8, 1.0, 1.35, 1.9, 0.95, 1.1)
        val clock = FakeClock()
        val device = SimulatedDevice(clock, msPerChar = 47.0, jitter = { spread[it % spread.size] })
        val text = sentences(36) // ~1,600 characters => 6 chunks, inside one audio piece's budget
        val result = run(text, device, nowMs = clock::now, stepBudgetMs = AUDIO_LOCAL_STEP_BUDGET_MS)

        assertTrue("some chunk really did need more than 20 s", device.durations.any { it > 20_000L })
        assertEquals("no chunk timed out", 0, device.timedOut)
        assertNull("everything cleaned", result.partial)
        assertEquals(text, result.text!!.replace("[", "").replace("]", ""))
    }

    @Test fun `a phone several times slower than the 10a is given the time it needs`() {
        val clock = FakeClock()
        val device = SimulatedDevice(clock, msPerChar = 130.0) // ~38 s per chunk
        val text = sentences(36)
        val result = run(text, device, nowMs = clock::now, stepBudgetMs = AUDIO_LOCAL_STEP_BUDGET_MS)

        assertEquals(0, device.timedOut)
        assertNull(result.partial)
        assertTrue("every chunk was allowed well above the old flat 20 s", device.allowances.all { it > 30_000L })
    }

    @Test fun `a first chunk slower than the initial allowance times out once and then the allowance grows`() {
        val clock = FakeClock()
        val device = SimulatedDevice(clock, msPerChar = 250.0) // ~72 s per chunk > the 60 s initial allowance
        val text = sentences(36)
        val result = run(text, device, nowMs = clock::now, stepBudgetMs = AUDIO_LOCAL_STEP_BUDGET_MS)

        assertEquals("only the unmeasured first chunk timed out", 1, device.timedOut)
        assertTrue(device.allowances[1] > device.allowances[0])
        assertNotNull(result.partial)
        assertEquals(1, result.partial!!.totalChunks - result.partial!!.cleanedChunks)
        assertEquals(text, result.text!!.replace("[", "").replace("]", ""))
    }

    @Test fun `a device that cannot decode a chunk at all stops after two timeouts and delivers raw`() {
        val clock = FakeClock()
        val device = SimulatedDevice(clock, msPerChar = 2_000.0) // ~10 minutes per chunk
        val text = sentences(60)
        val result = run(text, device, nowMs = clock::now, stepBudgetMs = AUDIO_LOCAL_STEP_BUDGET_MS)

        assertEquals(LOCAL_CHUNK_MAX_CONSECUTIVE_TIMEOUTS, device.calls)
        assertNull(result.text)
        assertEquals("All cleanup steps failed: Local cleanup timed out", result.error)
        // 60 s + 120 s: the second timeout is at the ceiling, and nothing else was attempted.
        assertEquals(LocalCleanupChunkBudget.INITIAL_BUDGET_MS + LocalCleanupChunkBudget.MAX_BUDGET_MS, clock.elapsed)
    }

    @Test fun `each chunk's allowance tracks the measured pace of the chunks before it`() {
        val clock = FakeClock()
        val device = SimulatedDevice(clock, msPerChar = 100.0)
        run(sentences(36), device, nowMs = clock::now, stepBudgetMs = AUDIO_LOCAL_STEP_BUDGET_MS)
        // Chunk lengths differ a little; 2.5 x 100 ms/char x ~290 chars ~= 72 s.
        assertTrue(device.allowances.drop(1).all { it in 55_000L..80_000L })
    }

    @Test fun `a cancel during a slow chunk run is honoured at the next boundary without another attempt`() {
        val holder = InFlightCall()
        val clock = FakeClock()
        val inner = SimulatedDevice(clock, msPerChar = 100.0)
        var calls = 0
        val engine = object : LocalInferenceEngine {
            override fun complete(systemPrompt: String, userText: String, modelPath: String, deadlineAtMs: Long, isCancelled: () -> Boolean): LocalInferenceResult {
                val r = inner.complete(systemPrompt, userText, modelPath, deadlineAtMs, isCancelled)
                if (++calls == 2) holder.cancel()
                return r
            }
        }
        val result = run(sentences(60), engine, holder = holder, nowMs = clock::now)
        assertEquals("Cleanup cancelled", result.error)
        assertEquals(2, inner.calls)
    }

    // ---- pace carried across the pieces of one job (#325) -------------------------------------------

    @Test fun `a second piece starts from the pace the first one measured, not the first-chunk allowance`() {
        val clock = FakeClock()
        val device = SimulatedDevice(clock, msPerChar = 47.0) // Mumble on the 10a
        val pace = LocalCleanupPaceStore()
        val piece = sentences(36)

        run(piece, device, nowMs = clock::now, stepBudgetMs = AUDIO_LOCAL_STEP_BUDGET_MS, pace = pace)
        val firstPieceCalls = device.calls
        assertEquals("first piece's first chunk had no evidence", LocalCleanupChunkBudget.INITIAL_BUDGET_MS, device.allowances[0])

        run(piece, device, nowMs = clock::now, stepBudgetMs = AUDIO_LOCAL_STEP_BUDGET_MS, pace = pace)
        val secondFirst = device.allowances[firstPieceCalls]
        assertTrue("second piece's first chunk used the measured pace, got $secondFirst", secondFirst < 40_000L)
        assertTrue(secondFirst >= LocalCleanupChunkBudget.MIN_BUDGET_MS)
    }

    @Test fun `without a shared store every piece restarts at the first-chunk allowance`() {
        val clock = FakeClock()
        val device = SimulatedDevice(clock, msPerChar = 47.0)
        val piece = sentences(36)
        run(piece, device, nowMs = clock::now, stepBudgetMs = AUDIO_LOCAL_STEP_BUDGET_MS)
        val n = device.calls
        run(piece, device, nowMs = clock::now, stepBudgetMs = AUDIO_LOCAL_STEP_BUDGET_MS)
        assertEquals(LocalCleanupChunkBudget.INITIAL_BUDGET_MS, device.allowances[n])
    }

    @Test fun `a timeout learnt in one piece raises the allowance of the next piece's first chunk`() {
        val clock = FakeClock()
        val device = SimulatedDevice(clock, msPerChar = 250.0) // slower than the 60 s initial allowance
        val pace = LocalCleanupPaceStore()
        run(sentences(36), device, nowMs = clock::now, stepBudgetMs = AUDIO_LOCAL_STEP_BUDGET_MS, pace = pace)
        val n = device.calls
        run(sentences(36), device, nowMs = clock::now, stepBudgetMs = AUDIO_LOCAL_STEP_BUDGET_MS, pace = pace)
        assertTrue(
            "next piece starts above the allowance that failed: ${device.allowances[n]}",
            device.allowances[n] > LocalCleanupChunkBudget.INITIAL_BUDGET_MS,
        )
    }

    // ---- runaway generation (#325) ----------------------------------------------------------------

    @Test fun `a chunk stopped by the response cap is raw, not a timeout, and does not trip the breaker`() {
        val engine = ScriptedEngine { _, _ ->
            LocalInferenceResult.Failure("Local model exceeded max response length (112 pieces) without emitting an end-of-generation token")
        }
        run(sentences(60), engine)
        assertTrue(
            "the cap is a bad chunk, not a slow device: every chunk is still attempted",
            engine.userTexts.size > LOCAL_CHUNK_MAX_CONSECUTIVE_TIMEOUTS,
        )
    }

    @Test fun `a capped chunk is not counted as pace evidence`() {
        val clock = FakeClock()
        val pace = LocalCleanupPaceStore()
        var calls = 0
        val engine = object : LocalInferenceEngine {
            override fun complete(systemPrompt: String, userText: String, modelPath: String, deadlineAtMs: Long, isCancelled: () -> Boolean): LocalInferenceResult {
                clock.t += 9_000
                return if (calls++ == 0) LocalInferenceResult.Failure("Local model exceeded max response length (112 pieces)")
                else LocalInferenceResult.Success("[$userText]")
            }
        }
        run(sentences(36), engine, nowMs = clock::now, stepBudgetMs = AUDIO_LOCAL_STEP_BUDGET_MS, pace = pace)
        val est = pace.budgetFor("/model.gguf", clock.now()).estimateMsPerChar()!!
        // The capped chunk was 9 s too, but a failure that is not a timeout is left out; the
        // estimate comes from the successful chunks only, which all took 9 s each.
        assertTrue(est in 20.0..45.0)
    }

    // ---- reset / reload and cancellation interplay ---------------------------------------------------

    @Test fun `a cancel arriving while a chunk is being timed out ends the run without starting another`() {
        val holder = InFlightCall()
        val clock = FakeClock()
        val engine = object : LocalInferenceEngine {
            var calls = 0
            override fun complete(systemPrompt: String, userText: String, modelPath: String, deadlineAtMs: Long, isCancelled: () -> Boolean): LocalInferenceResult {
                calls++
                clock.t += 30_000
                holder.cancel()
                return LocalInferenceResult.TimedOut("Local cleanup timed out")
            }
        }
        val result = run(sentences(60), engine, holder = holder, nowMs = clock::now)
        assertEquals("Cleanup cancelled", result.error)
        assertEquals(1, engine.calls)
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
