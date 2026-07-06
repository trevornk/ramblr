package com.kafkasl.phonewhisper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CleanupWaterfallPlannerTest {

    @Test fun `empty steps produce no groups`() {
        assertEquals(emptyList<List<CleanupStep>>(), CleanupWaterfallPlanner.groupConsecutive(emptyList()))
    }

    @Test fun `single step is its own group`() {
        val steps = listOf(CleanupStep(CleanupStepGroup.LEGACY, "gpt-4o-mini"))
        val groups = CleanupWaterfallPlanner.groupConsecutive(steps)
        assertEquals(1, groups.size)
        assertEquals(steps, groups[0])
    }

    @Test fun `consecutive same-group steps merge into one group`() {
        val steps = listOf(
            CleanupStep(CleanupStepGroup.OMNIROUTE, "claude/claude-sonnet-4-6"),
            CleanupStep(CleanupStepGroup.OMNIROUTE, "cx/gpt-5.5"),
            CleanupStep(CleanupStepGroup.OMNIROUTE, "gemini/gemini-2.5-flash"),
        )
        val groups = CleanupWaterfallPlanner.groupConsecutive(steps)
        assertEquals(1, groups.size)
        assertEquals(3, groups[0].size)
    }

    @Test fun `different groups stay separate even when adjacent`() {
        val steps = listOf(
            CleanupStep(CleanupStepGroup.OMNIROUTE, "claude/claude-sonnet-4-6"),
            CleanupStep(CleanupStepGroup.OPENAI_DIRECT, "gpt-4o-mini"),
            CleanupStep(CleanupStepGroup.ANTHROPIC_DIRECT, "claude-haiku-4-5"),
        )
        val groups = CleanupWaterfallPlanner.groupConsecutive(steps)
        assertEquals(3, groups.size)
        assertEquals(CleanupStepGroup.OMNIROUTE, groups[0][0].group)
        assertEquals(CleanupStepGroup.OPENAI_DIRECT, groups[1][0].group)
        assertEquals(CleanupStepGroup.ANTHROPIC_DIRECT, groups[2][0].group)
    }

    @Test fun `the full 5-step waterfall from ADR-0001 groups into 3 units`() {
        val steps = listOf(
            CleanupStep(CleanupStepGroup.OMNIROUTE, "claude/claude-sonnet-4-6"),
            CleanupStep(CleanupStepGroup.OMNIROUTE, "cx/gpt-5.5"),
            CleanupStep(CleanupStepGroup.OMNIROUTE, "gemini/gemini-2.5-flash"),
            CleanupStep(CleanupStepGroup.OPENAI_DIRECT, "gpt-4o-mini"),
            CleanupStep(CleanupStepGroup.ANTHROPIC_DIRECT, "claude-haiku-4-5"),
        )
        val groups = CleanupWaterfallPlanner.groupConsecutive(steps)
        assertEquals(3, groups.size)
        assertEquals(3, groups[0].size) // OmniRoute sub-steps fail together
        assertEquals(1, groups[1].size)
        assertEquals(1, groups[2].size)
    }

    @Test fun `revisiting the same group later stays a separate group (non-consecutive)`() {
        // Pathological but should not silently merge non-adjacent runs of the same group.
        val steps = listOf(
            CleanupStep(CleanupStepGroup.OMNIROUTE, "claude/claude-sonnet-4-6"),
            CleanupStep(CleanupStepGroup.OPENAI_DIRECT, "gpt-4o-mini"),
            CleanupStep(CleanupStepGroup.OMNIROUTE, "cx/gpt-5.5"),
        )
        val groups = CleanupWaterfallPlanner.groupConsecutive(steps)
        assertEquals(3, groups.size)
    }

    @Test fun `flattenIndex reverses groupConsecutive`() {
        val steps = listOf(
            CleanupStep(CleanupStepGroup.OMNIROUTE, "claude/claude-sonnet-4-6"),
            CleanupStep(CleanupStepGroup.OMNIROUTE, "cx/gpt-5.5"),
            CleanupStep(CleanupStepGroup.OPENAI_DIRECT, "gpt-4o-mini"),
        )
        val groups = CleanupWaterfallPlanner.groupConsecutive(steps)
        assertEquals(steps, CleanupWaterfallPlanner.flattenIndex(groups))
    }
}

class CleanupWaterfallCursorTest {

    @Test fun `starts at index 0 with no prior success`() {
        val cursor = CleanupWaterfallCursor()
        assertEquals(0, cursor.startIndex(nowMs = 1_000L))
    }

    @Test fun `starts at last successful index within the idle window`() {
        val cursor = CleanupWaterfallCursor(idleExpiryMs = 10_000L)
        cursor.recordSuccess(stepIndex = 3, nowMs = 1_000L)
        assertEquals(3, cursor.startIndex(nowMs = 5_000L))
    }

    @Test fun `expires back to 0 after the idle window elapses`() {
        val cursor = CleanupWaterfallCursor(idleExpiryMs = 10_000L)
        cursor.recordSuccess(stepIndex = 3, nowMs = 1_000L)
        assertEquals(0, cursor.startIndex(nowMs = 20_000L))
    }

    @Test fun `reset forces back to 0 immediately, simulating a network change`() {
        val cursor = CleanupWaterfallCursor(idleExpiryMs = 10_000L)
        cursor.recordSuccess(stepIndex = 3, nowMs = 1_000L)
        cursor.reset()
        assertEquals(0, cursor.startIndex(nowMs = 1_001L))
    }

    @Test fun `a fresh success after reset moves the cursor again`() {
        val cursor = CleanupWaterfallCursor(idleExpiryMs = 10_000L)
        cursor.recordSuccess(stepIndex = 3, nowMs = 1_000L)
        cursor.reset()
        cursor.recordSuccess(stepIndex = 1, nowMs = 2_000L)
        assertEquals(1, cursor.startIndex(nowMs = 2_500L))
    }

    @Test fun `idle expiry is evaluated relative to the last success, not first`() {
        val cursor = CleanupWaterfallCursor(idleExpiryMs = 10_000L)
        cursor.recordSuccess(stepIndex = 2, nowMs = 1_000L)
        cursor.recordSuccess(stepIndex = 2, nowMs = 15_000L) // renews the clock
        assertTrue(cursor.startIndex(nowMs = 20_000L) == 2) // only 5s since last success
    }
}

/** Fakes the network layer (see [CleanupHttpTransport]) so executor step-sequencing logic is
 *  testable with no real I/O: each call to [send] pops and returns the next queued outcome. */
private class FakeCleanupHttpTransport(private val outcomes: MutableList<CleanupHttpOutcome>) : CleanupHttpTransport {
    val requestedUrls = mutableListOf<String>()

    override fun send(
        url: String,
        headers: Map<String, String>,
        jsonBody: String,
        timeouts: CleanupStepTimeouts,
        cancelHolder: InFlightCall,
        callback: (CleanupHttpOutcome) -> Unit,
    ) {
        requestedUrls.add(url)
        check(outcomes.isNotEmpty()) { "FakeCleanupHttpTransport ran out of queued outcomes for url=$url" }
        callback(outcomes.removeAt(0))
    }
}

private fun okOutcome(text: String): CleanupHttpOutcome =
    CleanupHttpOutcome.Ok("""{"choices":[{"message":{"content":"$text"}}]}""")

/** Fakes on-device inference (see [LocalInferenceEngine]) so LOCAL_LLM step-sequencing logic is
 *  testable with no native code involved -- see #37: real llama.cpp inference cannot be exercised
 *  in a JVM unit test, only this seam's plumbing can. */
private class FakeLocalInferenceEngine(private val outcomes: MutableList<LocalInferenceResult>) : LocalInferenceEngine {
    val calls = mutableListOf<Triple<String, String, String>>()
    val deadlines = mutableListOf<Long>()

    override fun complete(
        systemPrompt: String,
        userText: String,
        modelPath: String,
        deadlineAtMs: Long,
        isCancelled: () -> Boolean,
    ): LocalInferenceResult {
        calls.add(Triple(systemPrompt, userText, modelPath))
        deadlines.add(deadlineAtMs)
        check(outcomes.isNotEmpty()) { "FakeLocalInferenceEngine ran out of queued outcomes" }
        return outcomes.removeAt(0)
    }
}

class CleanupWaterfallExecutorTest {
    private val cancelHolder = InFlightCall()

    private fun execute(
        waterfall: CleanupWaterfall,
        transport: CleanupHttpTransport,
        cursor: CleanupWaterfallCursor = CleanupWaterfallCursor(),
        credentials: Map<CleanupCredentialSlot, String> = mapOf(
            CleanupCredentialSlot.OMNIROUTE to "omni-key",
            CleanupCredentialSlot.OPENAI_DIRECT to "openai-key",
            CleanupCredentialSlot.ANTHROPIC_DIRECT to "anthropic-key",
        ),
        localInference: LocalInferenceEngine = LocalInferenceEngine { _, _, _, _, _ -> error("no local step expected") },
        localModelPath: () -> String? = { null },
    ): PostProcessor.Result {
        var captured: PostProcessor.Result? = null
        CleanupWaterfallExecutor.execute(
            text = "raw transcript",
            prompt = "clean it up",
            waterfall = waterfall,
            cursor = cursor,
            cancelHolder = cancelHolder,
            legacyApiKey = "legacy-key",
            legacyBaseUrl = PostProcessor.DEFAULT_BASE_URL,
            credentialLookup = { slot -> credentials[slot] ?: "" },
            transport = transport,
            localInference = localInference,
            localModelPath = localModelPath,
            callback = { captured = it },
        )
        return captured ?: error("callback never fired")
    }

    @Test fun `a connection failure on the first OmniRoute sub-step skips its remaining siblings`() {
        val transport = FakeCleanupHttpTransport(
            mutableListOf(
                CleanupHttpOutcome.ConnectionFailure("host unreachable"),
                okOutcome("cleaned via direct openai"),
            )
        )
        val waterfall = CleanupWaterfall(
            listOf(
                CleanupStep(CleanupStepGroup.OMNIROUTE, "claude/claude-sonnet-4-6"),
                CleanupStep(CleanupStepGroup.OMNIROUTE, "cx/gpt-5.5"),
                CleanupStep(CleanupStepGroup.OMNIROUTE, "gemini/gemini-2.5-flash"),
                CleanupStep(CleanupStepGroup.OPENAI_DIRECT, "gpt-4o-mini"),
            )
        )
        val result = execute(waterfall, transport)
        assertEquals("cleaned via direct openai", result.text)
        // Only 2 requests: the first (dead) OmniRoute attempt, then straight to OPENAI_DIRECT —
        // the other 2 OmniRoute sub-steps were skipped, not attempted and failed individually.
        assertEquals(2, transport.requestedUrls.size)
    }

    @Test fun `a step-level failure only advances one step, siblings in the group are still tried`() {
        val transport = FakeCleanupHttpTransport(
            mutableListOf(
                CleanupHttpOutcome.HttpError(400, """{"error":{"message":"bad model"}}"""),
                okOutcome("cleaned via second omniroute model"),
            )
        )
        val waterfall = CleanupWaterfall(
            listOf(
                CleanupStep(CleanupStepGroup.OMNIROUTE, "claude/typo-model"),
                CleanupStep(CleanupStepGroup.OMNIROUTE, "cx/gpt-5.5"),
            )
        )
        val result = execute(waterfall, transport)
        assertEquals("cleaned via second omniroute model", result.text)
        assertEquals(2, transport.requestedUrls.size)
        assertEquals(transport.requestedUrls[0], transport.requestedUrls[1]) // both hit the OmniRoute host
    }

    @Test fun `a missing credential fails that step without ever calling the transport`() {
        val transport = FakeCleanupHttpTransport(
            mutableListOf(CleanupHttpOutcome.Ok("""{"content":[{"type":"text","text":"fell through to anthropic"}]}"""))
        )
        val waterfall = CleanupWaterfall(
            listOf(
                CleanupStep(CleanupStepGroup.OPENAI_DIRECT, "gpt-4o-mini"),
                CleanupStep(CleanupStepGroup.ANTHROPIC_DIRECT, "claude-haiku-4-5"),
            )
        )
        val result = execute(
            waterfall,
            transport,
            credentials = mapOf(CleanupCredentialSlot.ANTHROPIC_DIRECT to "anthropic-key"), // OPENAI_DIRECT unset
        )
        assertEquals("fell through to anthropic", result.text)
        // Exactly 1 real request: the unconfigured OPENAI_DIRECT step never hit the network.
        assertEquals(1, transport.requestedUrls.size)
        assertTrue(transport.requestedUrls[0].contains("anthropic.com"))
    }

    @Test fun `success advances the cursor so the next call starts past earlier dead steps`() {
        val cursor = CleanupWaterfallCursor()
        val waterfall = CleanupWaterfall(
            listOf(
                CleanupStep(CleanupStepGroup.OMNIROUTE, "claude/claude-sonnet-4-6"),
                CleanupStep(CleanupStepGroup.OPENAI_DIRECT, "gpt-4o-mini"),
            )
        )

        val firstCallTransport = FakeCleanupHttpTransport(
            mutableListOf(CleanupHttpOutcome.ConnectionFailure(null), okOutcome("first call result"))
        )
        val first = execute(waterfall, firstCallTransport, cursor)
        assertEquals("first call result", first.text)

        // Second call: only the previously-successful step (index 1) should be attempted.
        val secondCallTransport = FakeCleanupHttpTransport(mutableListOf(okOutcome("second call result")))
        val second = execute(waterfall, secondCallTransport, cursor)
        assertEquals("second call result", second.text)
        assertEquals(1, secondCallTransport.requestedUrls.size)
    }

    @Test fun `all steps failing reports an error instead of throwing`() {
        val transport = FakeCleanupHttpTransport(
            mutableListOf(
                CleanupHttpOutcome.ConnectionFailure("dead"),
                CleanupHttpOutcome.HttpError(500, ""),
            )
        )
        val waterfall = CleanupWaterfall(
            listOf(
                CleanupStep(CleanupStepGroup.OMNIROUTE, "claude/claude-sonnet-4-6"),
                CleanupStep(CleanupStepGroup.ANTHROPIC_DIRECT, "claude-haiku-4-5"),
            )
        )
        val result = execute(waterfall, transport)
        assertNull(result.text)
        assertTrue(result.error != null)
    }

    @Test fun `the Anthropic step hits the native messages endpoint with an x-api-key header shape`() {
        val transport = FakeCleanupHttpTransport(
            mutableListOf(CleanupHttpOutcome.Ok("""{"content":[{"type":"text","text":"anthropic result"}]}"""))
        )
        val waterfall = CleanupWaterfall(listOf(CleanupStep(CleanupStepGroup.ANTHROPIC_DIRECT, "claude-haiku-4-5")))
        val result = execute(waterfall, transport)
        assertEquals("anthropic result", result.text)
        assertEquals(AnthropicCleanupProvider.ENDPOINT_URL, transport.requestedUrls.single())
    }

    /** Per ADR-0001, every non-2xx status is an immediate, non-retried step failure -- no
     *  provider-specific status-code handling. These confirm the Anthropic step participates in
     *  that shared classification like every other provider: 401/429/5xx all just fall through
     *  to the next step, and a connection-level failure is classified separately. */
    @Test fun `a 401 from the Anthropic step falls through to the next step, no retry`() {
        val transport = FakeCleanupHttpTransport(
            mutableListOf(
                CleanupHttpOutcome.HttpError(401, """{"type":"error","error":{"type":"authentication_error","message":"invalid x-api-key"}}"""),
                okOutcome("cleaned via fallback"),
            )
        )
        val waterfall = CleanupWaterfall(
            listOf(
                CleanupStep(CleanupStepGroup.ANTHROPIC_DIRECT, "claude-haiku-4-5"),
                CleanupStep(CleanupStepGroup.OPENAI_DIRECT, "gpt-4o-mini"),
            )
        )
        val result = execute(waterfall, transport)
        assertEquals("cleaned via fallback", result.text)
        assertEquals(2, transport.requestedUrls.size)
    }

    @Test fun `a 429 from the Anthropic step falls through immediately without honoring Retry-After`() {
        val transport = FakeCleanupHttpTransport(
            mutableListOf(
                CleanupHttpOutcome.HttpError(429, """{"type":"error","error":{"type":"rate_limit_error","message":"rate limited"}}"""),
                okOutcome("cleaned via fallback"),
            )
        )
        val waterfall = CleanupWaterfall(
            listOf(
                CleanupStep(CleanupStepGroup.ANTHROPIC_DIRECT, "claude-haiku-4-5"),
                CleanupStep(CleanupStepGroup.OPENAI_DIRECT, "gpt-4o-mini"),
            )
        )
        val result = execute(waterfall, transport)
        assertEquals("cleaned via fallback", result.text)
        assertEquals(2, transport.requestedUrls.size)
    }

    @Test fun `a 5xx from the Anthropic step falls through to the next step`() {
        val transport = FakeCleanupHttpTransport(
            mutableListOf(
                CleanupHttpOutcome.HttpError(529, """{"type":"error","error":{"type":"overloaded_error","message":"overloaded"}}"""),
                okOutcome("cleaned via fallback"),
            )
        )
        val waterfall = CleanupWaterfall(
            listOf(
                CleanupStep(CleanupStepGroup.ANTHROPIC_DIRECT, "claude-haiku-4-5"),
                CleanupStep(CleanupStepGroup.OPENAI_DIRECT, "gpt-4o-mini"),
            )
        )
        val result = execute(waterfall, transport)
        assertEquals("cleaned via fallback", result.text)
        assertEquals(2, transport.requestedUrls.size)
    }

    @Test fun `a network timeout on the Anthropic step is classified as a connection failure and falls through`() {
        val transport = FakeCleanupHttpTransport(
            mutableListOf(
                CleanupHttpOutcome.ConnectionFailure("timeout"),
                okOutcome("cleaned via fallback"),
            )
        )
        val waterfall = CleanupWaterfall(
            listOf(
                CleanupStep(CleanupStepGroup.ANTHROPIC_DIRECT, "claude-haiku-4-5"),
                CleanupStep(CleanupStepGroup.OPENAI_DIRECT, "gpt-4o-mini"),
            )
        )
        val result = execute(waterfall, transport)
        assertEquals("cleaned via fallback", result.text)
        assertEquals(2, transport.requestedUrls.size)
    }
}

/** LOCAL_LLM step-sequencing behavior (#37) -- exercises the executor's fakeable seam over
 *  native inference ([LocalInferenceEngine]), never real llama.cpp/JNI code. See
 *  app/src/main/cpp/llama_cleanup/README.md for why real on-device inference can't be exercised
 *  in a JVM unit test; only this seam's plumbing (routing, credential bypass, fallthrough) is
 *  covered here. */
class LocalLlmWaterfallStepTest {
    private val cancelHolder = InFlightCall()

    private fun execute(
        waterfall: CleanupWaterfall,
        transport: CleanupHttpTransport,
        localInference: LocalInferenceEngine,
        localModelPath: () -> String?,
        legacyApiKey: String = "",
        credentialLookup: (CleanupCredentialSlot) -> String = { "" },
        localPrompt: String = PostProcessor.SIMPLE_PROMPT,
    ): PostProcessor.Result {
        var captured: PostProcessor.Result? = null
        CleanupWaterfallExecutor.execute(
            text = "raw transcript",
            prompt = "clean it up",
            waterfall = waterfall,
            cursor = CleanupWaterfallCursor(),
            cancelHolder = cancelHolder,
            legacyApiKey = legacyApiKey,
            legacyBaseUrl = PostProcessor.DEFAULT_BASE_URL,
            credentialLookup = credentialLookup,
            transport = transport,
            localInference = localInference,
            localModelPath = localModelPath,
            localPrompt = localPrompt,
            callback = { captured = it },
        )
        return captured ?: error("callback never fired")
    }

    @Test fun `a local step succeeds with no credential and no legacy api key configured`() {
        val transport = FakeCleanupHttpTransport(mutableListOf()) // never touched
        val engine = FakeLocalInferenceEngine(mutableListOf(LocalInferenceResult.Success("cleaned locally")))
        val waterfall = CleanupWaterfall(listOf(CleanupStep(CleanupStepGroup.LOCAL_LLM, LocalCleanupProvider.MODEL.archive)))

        val result = execute(waterfall, transport, engine, localModelPath = { "/data/data/app/files/cleanup_models/model.gguf" })

        assertEquals("cleaned locally", result.text)
        assertEquals(0, transport.requestedUrls.size) // no network call at all
        assertEquals(1, engine.calls.size)
    }

    @Test fun `local step uses the dedicated local prompt, not the cloud persona prompt`() {
        // #54 follow-up: today's small on-device models (e.g. LFM2.5-350M) don't reliably follow
        // the longer, few-shot DEV_PROMPT-style cloud persona prompts -- they can drift into
        // answering/chatting instead of restyling. The executor must always route LOCAL_LLM
        // through its own localPrompt (default PostProcessor.SIMPLE_PROMPT), independent of
        // whatever persona prompt the caller passes as [prompt] for cloud steps.
        val transport = FakeCleanupHttpTransport(mutableListOf())
        val engine = FakeLocalInferenceEngine(mutableListOf(LocalInferenceResult.Success("cleaned locally")))
        val waterfall = CleanupWaterfall(listOf(CleanupStep(CleanupStepGroup.LOCAL_LLM, LocalCleanupProvider.MODEL.archive)))

        execute(
            waterfall, transport, engine,
            localModelPath = { "/data/data/app/files/cleanup_models/model.gguf" },
            localPrompt = "a distinct local-only prompt",
        )

        assertEquals(1, engine.calls.size)
        assertEquals("a distinct local-only prompt", engine.calls[0].first) // systemPrompt arg
    }

    @Test fun `local step defaults to SIMPLE_PROMPT when no localPrompt is supplied`() {
        val transport = FakeCleanupHttpTransport(mutableListOf())
        val engine = FakeLocalInferenceEngine(mutableListOf(LocalInferenceResult.Success("cleaned locally")))
        val waterfall = CleanupWaterfall(listOf(CleanupStep(CleanupStepGroup.LOCAL_LLM, LocalCleanupProvider.MODEL.archive)))

        execute(waterfall, transport, engine, localModelPath = { "/data/data/app/files/cleanup_models/model.gguf" })

        assertEquals(1, engine.calls.size)
        assertEquals(PostProcessor.SIMPLE_PROMPT, engine.calls[0].first)
    }

    @Test fun `a cancelled local inference stops the waterfall instead of falling through`() {
        // Like a cancelled HTTP call (#63), a cancel during local inference must not advance to
        // the next (possibly paid) step (#83).
        val transport = FakeCleanupHttpTransport(mutableListOf()) // must never be touched
        val engine = FakeLocalInferenceEngine(mutableListOf(LocalInferenceResult.Cancelled))
        val waterfall = CleanupWaterfall(
            listOf(
                CleanupStep(CleanupStepGroup.LOCAL_LLM, LocalCleanupProvider.MODEL.archive),
                CleanupStep(CleanupStepGroup.OPENAI_DIRECT, "gpt-4o-mini"),
            )
        )

        val result = execute(waterfall, transport, engine, localModelPath = { "/path/to/model.gguf" })

        assertNull(result.text)
        assertEquals("Cleanup cancelled", result.error)
        assertEquals(0, transport.requestedUrls.size)
    }

    @Test fun `a single LOCAL_LLM step -- the last step in the waterfall -- gets the full deadline, not the tighter budget`() {
        // #37 follow-up: Trevor's real Local-only cleanup choice is exactly this shape (one
        // LOCAL_LLM step, nothing after it). The tighter LOCAL_LLM_STEP_BUDGET_MS exists only to
        // protect a *subsequent* step's own timeout window -- with no subsequent step, applying
        // it anyway just turns a healthy-but-slightly-slower completion into a hard failure with
        // llama_decode aborting mid-generation, which is the exact bug Trevor hit live on-device.
        val transport = FakeCleanupHttpTransport(mutableListOf())
        val engine = FakeLocalInferenceEngine(mutableListOf(LocalInferenceResult.Success("ok")))
        val waterfall = CleanupWaterfall(listOf(CleanupStep(CleanupStepGroup.LOCAL_LLM, LocalCleanupProvider.MODEL.archive)))

        val before = System.currentTimeMillis()
        execute(waterfall, transport, engine, localModelPath = { "/path/to/model.gguf" })
        val after = System.currentTimeMillis()

        val deadline = engine.deadlines.single()
        // Full CLEANUP_WATERFALL_HARD_CAP_MS budget, not the tighter LOCAL_LLM_STEP_BUDGET_MS.
        assertTrue(deadline >= before + CLEANUP_WATERFALL_HARD_CAP_MS)
        assertTrue(deadline <= after + CLEANUP_WATERFALL_HARD_CAP_MS)
    }

    @Test fun `a LOCAL_LLM step followed by another step gets the tighter step budget, not the full waterfall deadline`() {
        // #98: the LOCAL_LLM step is bounded by LOCAL_LLM_STEP_BUDGET_MS specifically so a
        // slow/aborted local attempt can't consume the whole waterfall deadline and starve a
        // subsequent cloud step of its own timeout window -- this still applies when a real
        // fallback step follows it (unlike the single-step case above).
        val transport = FakeCleanupHttpTransport(mutableListOf(okOutcome("cleaned via cloud")))
        val engine = FakeLocalInferenceEngine(mutableListOf(LocalInferenceResult.Success("ok")))
        val waterfall = CleanupWaterfall(
            listOf(
                CleanupStep(CleanupStepGroup.LOCAL_LLM, LocalCleanupProvider.MODEL.archive),
                CleanupStep(CleanupStepGroup.OMNIROUTE, "claude/claude-sonnet-4-6"),
            )
        )

        val before = System.currentTimeMillis()
        execute(waterfall, transport, engine, localModelPath = { "/path/to/model.gguf" })
        val after = System.currentTimeMillis()

        val deadline = engine.deadlines.single()
        assertTrue(deadline >= before + LOCAL_LLM_STEP_BUDGET_MS)
        assertTrue(deadline <= after + LOCAL_LLM_STEP_BUDGET_MS)
        // And that tighter budget must never exceed the overall waterfall deadline.
        assertTrue(deadline <= after + CLEANUP_WATERFALL_HARD_CAP_MS)
    }

    @Test fun `a failed local step still falls through to a following cloud step within the waterfall deadline`() {
        // #98: the whole point of LOCAL_LLM_STEP_BUDGET_MS being tighter than
        // CLEANUP_WATERFALL_HARD_CAP_MS is that a fast-failing (or fast-aborted) local attempt
        // leaves real time for a subsequent cloud step to actually be attempted, instead of the
        // local step silently consuming the entire waterfall budget.
        val transport = FakeCleanupHttpTransport(mutableListOf(okOutcome("cleaned via cloud")))
        val engine = FakeLocalInferenceEngine(mutableListOf(LocalInferenceResult.Failure("local model produced an empty response")))
        val waterfall = CleanupWaterfall(
            listOf(
                CleanupStep(CleanupStepGroup.LOCAL_LLM, LocalCleanupProvider.MODEL.archive),
                CleanupStep(CleanupStepGroup.OMNIROUTE, "claude/claude-sonnet-4-6"),
            )
        )

        val result = execute(
            waterfall, transport, engine,
            localModelPath = { "/path/to/model.gguf" },
            credentialLookup = { slot -> if (slot == CleanupCredentialSlot.OMNIROUTE) "omni-key" else "" },
        )

        assertEquals("cleaned via cloud", result.text)
        assertEquals(1, transport.requestedUrls.size) // the cloud step really was attempted
    }

    @Test fun `each waterfall run clears a stale cancel from the previous dictation`() {
        val transport = FakeCleanupHttpTransport(mutableListOf())
        val engine = FakeLocalInferenceEngine(mutableListOf(LocalInferenceResult.Success("cleaned locally")))
        val waterfall = CleanupWaterfall(listOf(CleanupStep(CleanupStepGroup.LOCAL_LLM, LocalCleanupProvider.MODEL.archive)))

        cancelHolder.cancel() // left over from a previous dictation
        val result = execute(waterfall, transport, engine, localModelPath = { "/path/to/model.gguf" })

        assertEquals("cleaned locally", result.text)
    }

    @Test fun `local output is trimmed before it becomes a Success, matching the cloud parsers`() {
        // Small local models routinely emit a leading space/newline as the first sampled piece;
        // both cloud parsers trim, so untrimmed local output was a visible behavior difference
        // whenever the waterfall switched between local and cloud (#84).
        val transport = FakeCleanupHttpTransport(mutableListOf())
        val engine = FakeLocalInferenceEngine(mutableListOf(LocalInferenceResult.Success("\n cleaned locally ")))
        val waterfall = CleanupWaterfall(listOf(CleanupStep(CleanupStepGroup.LOCAL_LLM, LocalCleanupProvider.MODEL.archive)))

        val result = execute(waterfall, transport, engine, localModelPath = { "/path/to/model.gguf" })

        assertEquals("cleaned locally", result.text)
    }

    @Test fun `a whitespace-only local success falls through as a step failure instead of injecting blanks`() {
        val transport = FakeCleanupHttpTransport(mutableListOf(okOutcome("cleaned via fallback")))
        val engine = FakeLocalInferenceEngine(mutableListOf(LocalInferenceResult.Success("   \n ")))
        val waterfall = CleanupWaterfall(
            listOf(
                CleanupStep(CleanupStepGroup.LOCAL_LLM, LocalCleanupProvider.MODEL.archive),
                CleanupStep(CleanupStepGroup.OPENAI_DIRECT, "gpt-4o-mini"),
            )
        )

        val result = execute(
            waterfall, transport, engine,
            localModelPath = { "/path/to/model.gguf" },
            credentialLookup = { slot -> if (slot == CleanupCredentialSlot.OPENAI_DIRECT) "openai-key" else "" },
        )

        assertEquals("cleaned via fallback", result.text)
    }

    @Test fun `the local step uses localPrompt (not the waterfall's cloud prompt) as its system message, with the waterfall text as user`() {
        // Superseded by the #54 follow-up fix above: LOCAL_LLM must NOT receive the cloud
        // persona's `prompt` verbatim -- see "local step uses the dedicated local prompt" and
        // "local step defaults to SIMPLE_PROMPT" for the actual current contract.
        val transport = FakeCleanupHttpTransport(mutableListOf())
        val engine = FakeLocalInferenceEngine(mutableListOf(LocalInferenceResult.Success("cleaned")))
        val waterfall = CleanupWaterfall(listOf(CleanupStep(CleanupStepGroup.LOCAL_LLM, LocalCleanupProvider.MODEL.archive)))

        execute(waterfall, transport, engine, localModelPath = { "/path/to/model.gguf" })

        val (systemPrompt, userText, modelPath) = engine.calls.single()
        assertEquals(PostProcessor.SIMPLE_PROMPT, systemPrompt) // default localPrompt, not the `prompt` arg
        assertEquals("raw transcript", userText) // the `text` arg passed to execute()
        assertEquals("/path/to/model.gguf", modelPath)
    }

    @Test fun `a local step fails without ever calling the engine when the model isn't downloaded`() {
        val transport = FakeCleanupHttpTransport(mutableListOf())
        val engine = FakeLocalInferenceEngine(mutableListOf())
        val waterfall = CleanupWaterfall(listOf(CleanupStep(CleanupStepGroup.LOCAL_LLM, LocalCleanupProvider.MODEL.archive)))

        val result = execute(waterfall, transport, engine, localModelPath = { null })

        // Single-step waterfall: the executor's generic "All cleanup steps failed" message
        // (see CleanupWaterfallExecutor.attempt) subsumes this step's specific failure reason --
        // same behavior the existing missing-credential test works around with a 2-step
        // waterfall. What matters here is that it fails without ever touching the engine.
        assertNull(result.text)
        assertTrue(result.error != null)
        assertEquals(0, engine.calls.size)
        assertEquals(0, transport.requestedUrls.size)
    }

    @Test fun `a local inference failure falls through to the next configured step`() {
        val transport = FakeCleanupHttpTransport(mutableListOf(okOutcome("cleaned via fallback")))
        val engine = FakeLocalInferenceEngine(mutableListOf(LocalInferenceResult.Failure("context size reached")))
        val waterfall = CleanupWaterfall(
            listOf(
                CleanupStep(CleanupStepGroup.LOCAL_LLM, LocalCleanupProvider.MODEL.archive),
                CleanupStep(CleanupStepGroup.OPENAI_DIRECT, "gpt-4o-mini"),
            )
        )

        val result = execute(
            waterfall, transport, engine,
            localModelPath = { "/path/to/model.gguf" },
            credentialLookup = { slot -> if (slot == CleanupCredentialSlot.OPENAI_DIRECT) "openai-key" else "" },
        )

        assertEquals("cleaned via fallback", result.text)
        assertEquals(1, transport.requestedUrls.size)
    }
}

/** Pure unit tests for [localLlmStepDeadline] (#37 follow-up) -- the exact bug Trevor hit live:
 *  a single-step Local-only cleanup chain got the tight LOCAL_LLM_STEP_BUDGET_MS budget meant to
 *  protect a subsequent step that didn't exist, causing llama_decode to abort mid-generation on
 *  a genuinely healthy completion. */
class LocalLlmStepDeadlineTest {
    @Test fun `not the last step gets the tighter budget, capped at the overall deadline`() {
        val now = 1_000_000L
        val overallDeadline = now + CLEANUP_WATERFALL_HARD_CAP_MS
        val deadline = localLlmStepDeadline(overallDeadline, now, isLastStep = false)
        assertEquals(now + LOCAL_LLM_STEP_BUDGET_MS, deadline)
    }

    @Test fun `the last step gets the full overall deadline, not the tighter budget`() {
        val now = 1_000_000L
        val overallDeadline = now + CLEANUP_WATERFALL_HARD_CAP_MS
        val deadline = localLlmStepDeadline(overallDeadline, now, isLastStep = true)
        assertEquals(overallDeadline, deadline)
    }

    @Test fun `not the last step never exceeds the overall deadline even if the tighter budget would`() {
        // If the overall waterfall deadline is already closer than LOCAL_LLM_STEP_BUDGET_MS away
        // (e.g. a retry deep into an already-long-running waterfall), the local step must still
        // respect the smaller of the two -- never run past the overall hard cap.
        val now = 1_000_000L
        val overallDeadline = now + 1_000L // less than LOCAL_LLM_STEP_BUDGET_MS (4000ms)
        val deadline = localLlmStepDeadline(overallDeadline, now, isLastStep = false)
        assertEquals(overallDeadline, deadline)
    }

    @Test fun `the last step ignores the tighter budget entirely, even when it would be smaller`() {
        val now = 1_000_000L
        val overallDeadline = now + 1_000L // less than LOCAL_LLM_STEP_BUDGET_MS
        val deadline = localLlmStepDeadline(overallDeadline, now, isLastStep = true)
        assertEquals(overallDeadline, deadline)
    }
}

class PostProcessorWaterfallGatingTest {
    @Test fun `the pre-waterfall legacy single step does not trigger the executor`() {
        assertEquals(false, PostProcessor.shouldUseWaterfallExecutor(CleanupWaterfall.LEGACY_SINGLE_STEP))
    }

    @Test fun `any non-LEGACY step triggers the executor`() {
        val waterfall = CleanupWaterfall(listOf(CleanupStep(CleanupStepGroup.OMNIROUTE, "claude/claude-sonnet-4-6")))
        assertEquals(true, PostProcessor.shouldUseWaterfallExecutor(waterfall))
    }

    @Test fun `more than one LEGACY step also triggers the executor`() {
        val waterfall = CleanupWaterfall(
            listOf(
                CleanupStep(CleanupStepGroup.LEGACY, "gpt-4o-mini"),
                CleanupStep(CleanupStepGroup.OMNIROUTE, "claude/claude-sonnet-4-6"),
            )
        )
        assertEquals(true, PostProcessor.shouldUseWaterfallExecutor(waterfall))
    }
}

/** #63: a cancelled in-flight call must stop the waterfall outright — previously it reported
 *  through the same onFailure path as a dead host, so a user's cancel *advanced* the waterfall
 *  into the next (possibly paid) group for a result the guard token would discard anyway. */
class CleanupWaterfallExecutorCancellationTest {

    private fun execute(
        waterfall: CleanupWaterfall,
        transport: CleanupHttpTransport,
    ): PostProcessor.Result {
        var captured: PostProcessor.Result? = null
        CleanupWaterfallExecutor.execute(
            text = "raw transcript",
            prompt = "clean it up",
            waterfall = waterfall,
            cursor = CleanupWaterfallCursor(),
            cancelHolder = InFlightCall(),
            legacyApiKey = "legacy-key",
            legacyBaseUrl = PostProcessor.DEFAULT_BASE_URL,
            credentialLookup = { "some-key" },
            transport = transport,
            localInference = LocalInferenceEngine { _, _, _, _, _ -> error("no local step expected") },
            localModelPath = { null },
            callback = { captured = it },
        )
        return captured ?: error("callback never fired")
    }

    @Test fun `a cancelled call stops the waterfall without attempting the next group`() {
        val transport = FakeCleanupHttpTransport(
            mutableListOf(
                CleanupHttpOutcome.Cancelled,
                okOutcome("must never be requested"),
            )
        )
        val waterfall = CleanupWaterfall(
            listOf(
                CleanupStep(CleanupStepGroup.OMNIROUTE, "claude/claude-sonnet-4-6"),
                CleanupStep(CleanupStepGroup.OPENAI_DIRECT, "gpt-4o-mini"),
                CleanupStep(CleanupStepGroup.ANTHROPIC_DIRECT, "claude-haiku-4-5"),
            )
        )
        val result = execute(waterfall, transport)
        assertNull(result.text)
        assertEquals("Cleanup cancelled", result.error)
        // Exactly 1 request: the cancelled step. The paid direct-provider steps were never called.
        assertEquals(1, transport.requestedUrls.size)
    }

    @Test fun `a cancelled call stops the waterfall even within the same group`() {
        val transport = FakeCleanupHttpTransport(
            mutableListOf(CleanupHttpOutcome.Cancelled, okOutcome("sibling must not run"))
        )
        val waterfall = CleanupWaterfall(
            listOf(
                CleanupStep(CleanupStepGroup.OMNIROUTE, "claude/claude-sonnet-4-6"),
                CleanupStep(CleanupStepGroup.OMNIROUTE, "cx/gpt-5.5"),
            )
        )
        val result = execute(waterfall, transport)
        assertNull(result.text)
        assertEquals(1, transport.requestedUrls.size)
    }
}
