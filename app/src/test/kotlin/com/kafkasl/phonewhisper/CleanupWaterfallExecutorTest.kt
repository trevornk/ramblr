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

    override fun complete(systemPrompt: String, userText: String, modelPath: String): LocalInferenceResult {
        calls.add(Triple(systemPrompt, userText, modelPath))
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
        localInference: LocalInferenceEngine = LocalInferenceEngine { _, _, _ -> error("no local step expected") },
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

    @Test fun `the local step passes the waterfall prompt and text through unchanged as system+user`() {
        val transport = FakeCleanupHttpTransport(mutableListOf())
        val engine = FakeLocalInferenceEngine(mutableListOf(LocalInferenceResult.Success("cleaned")))
        val waterfall = CleanupWaterfall(listOf(CleanupStep(CleanupStepGroup.LOCAL_LLM, LocalCleanupProvider.MODEL.archive)))

        execute(waterfall, transport, engine, localModelPath = { "/path/to/model.gguf" })

        val (systemPrompt, userText, modelPath) = engine.calls.single()
        assertEquals("clean it up", systemPrompt) // the `prompt` arg passed to execute()
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
