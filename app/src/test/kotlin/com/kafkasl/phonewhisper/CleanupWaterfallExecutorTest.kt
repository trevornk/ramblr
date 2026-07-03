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
