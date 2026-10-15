package com.trevornk.ramblr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Wire-in for [CloudCleanupFidelityGuard]: [CleanupWaterfallExecutor] must apply it to cloud
 * output and, on a violation, stop and hand back an error (so the caller delivers the raw
 * transcript with the cleanup-failed notice) instead of trying the next provider. All fixtures are
 * synthetic.
 */
class CloudCleanupGuardWaterfallTest {

    private class QueueTransport(private val outcomes: MutableList<CleanupHttpOutcome>) : CleanupHttpTransport {
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
            check(outcomes.isNotEmpty()) { "ran out of queued outcomes for url=$url" }
            callback(outcomes.removeAt(0))
        }
    }

    private fun ok(text: String) = CleanupHttpOutcome.Ok("""{"choices":[{"message":{"content":"$text"}}]}""")

    private val twoCloudSteps = CleanupWaterfall(
        listOf(
            CleanupStep(CleanupStepGroup.OPENAI_DIRECT, "gpt-4o-mini"),
            CleanupStep(CleanupStepGroup.OPENAI_DIRECT, "gpt-5.4-mini"),
        ),
    )

    private fun run(
        raw: String,
        prompt: String,
        transport: QueueTransport,
        waterfall: CleanupWaterfall = twoCloudSteps,
        cursor: CleanupWaterfallCursor = CleanupWaterfallCursor(),
        guard: Boolean? = null,
    ): PostProcessor.Result {
        var captured: PostProcessor.Result? = null
        val callback: (PostProcessor.Result) -> Unit = { captured = it }
        if (guard == null) {
            CleanupWaterfallExecutor.execute(
                text = raw, prompt = prompt, waterfall = waterfall, cursor = cursor, cancelHolder = InFlightCall(),
                credentialLookup = { "key" }, transport = transport, callback = callback,
            )
        } else {
            CleanupWaterfallExecutor.execute(
                text = raw, prompt = prompt, waterfall = waterfall, cursor = cursor, cancelHolder = InFlightCall(),
                credentialLookup = { "key" }, transport = transport, fidelityGuard = guard, callback = callback,
            )
        }
        return captured ?: error("callback never fired")
    }

    private val raw = "tell Sarah the delivery is late and the driver says he will be there around noon"

    @Test fun `a cloud result that fails the guard is rejected and the next provider is not tried`() {
        // First provider replies instead of cleaning up; the second would have been fine.
        val transport = QueueTransport(mutableListOf(ok("Please provide the transcript you would like me to edit."), ok("Tell Sarah the delivery is late.")))
        val result = run(raw, PostProcessor.NATURAL_PROMPT, transport)

        assertNull("rejected output must not be delivered", result.text)
        assertNotNull(result.error)
        assertTrue(result.error!!, result.error!!.contains("META_REPLY"))
        assertEquals("the waterfall must stop, not fall through to the next provider", 1, transport.requestedUrls.size)
    }

    @Test fun `the rejection reads as a plain-language notice, not an enum name`() {
        val transport = QueueTransport(mutableListOf(ok("Tell Sarah the delivery is late.")))
        val result = run(raw, PostProcessor.NATURAL_PROMPT, transport)
        assertEquals("Cloud cleanup output rejected (LENGTH_COLLAPSE)", result.error)
        assertEquals("model dropped most of the text", CleanupFailureNotice.summarize(result.error))
        assertEquals(
            "Cleanup failed (model dropped most of the text) \u2014 inserted raw text",
            CleanupFailureNotice.messageFor(InjectMethod.DIRECT, result.error),
        )
    }

    @Test fun `a faithful cloud result passes through unchanged`() {
        val transport = QueueTransport(mutableListOf(ok("Tell Sarah the delivery is late and the driver says he will be there around noon.")))
        val result = run(raw, PostProcessor.NATURAL_PROMPT, transport)
        assertEquals("Tell Sarah the delivery is late and the driver says he will be there around noon.", result.text)
        assertNull(result.error)
    }

    @Test fun `the prompt the app actually sends still selects the guard`() {
        // DictationRuntime sends interpolated vocabulary plus the keep-language clause.
        val sent = PostProcessor.withKeepLanguage(PostProcessor.interpolateVocabulary(PostProcessor.NATURAL_PROMPT, listOf("Hetzner")))
        val transport = QueueTransport(mutableListOf(ok("Tell Sarah the delivery is late.")))
        assertNull(run(raw, sent, transport).text)
    }

    @Test fun `a rewriting prompt is not guarded`() {
        // Concise is supposed to shorten; the same output must be delivered.
        val transport = QueueTransport(mutableListOf(ok("Tell Sarah the delivery is late.")))
        val result = run(raw, PostProcessor.CONCISE_PROMPT, transport)
        assertEquals("Tell Sarah the delivery is late.", result.text)
        assertNull(result.error)
    }

    @Test fun `a custom prompt is not guarded`() {
        val transport = QueueTransport(mutableListOf(ok("Tell Sarah the delivery is late.")))
        assertEquals("Tell Sarah the delivery is late.", run(raw, "Rewrite this as a haiku.", transport).text)
    }

    @Test fun `the guard can be forced on or off regardless of prompt`() {
        val shortened = ok("Tell Sarah the delivery is late.")
        assertNull(run(raw, "Rewrite this as a haiku.", QueueTransport(mutableListOf(shortened)), guard = true).text)
        assertEquals(
            "Tell Sarah the delivery is late.",
            run(raw, PostProcessor.NATURAL_PROMPT, QueueTransport(mutableListOf(shortened)), guard = false).text,
        )
    }

    @Test fun `a rejected step still counts as the provider that answered`() {
        val cursor = CleanupWaterfallCursor()
        // Step 0 fails outright; step 1 answers but is rejected. The next dictation should still
        // start at step 1, because that provider is the one that is up.
        val transport = QueueTransport(mutableListOf(CleanupHttpOutcome.HttpError(500, ""), ok("Tell Sarah the delivery is late.")))
        val result = run(raw, PostProcessor.NATURAL_PROMPT, transport, cursor = cursor)
        assertNull(result.text)
        assertEquals(1, cursor.startIndex(System.currentTimeMillis()))
    }

    @Test fun `guardCloudOutput leaves local output and non-success outcomes alone`() {
        val violating = CleanupStepOutcome.Success("Tell Sarah the delivery is late.")
        val local = CleanupStep(CleanupStepGroup.LOCAL_LLM, "model")
        val cloud = CleanupStep(CleanupStepGroup.OPENAI_DIRECT, "gpt-4o-mini")

        assertSame(violating, CleanupWaterfallExecutor.guardCloudOutput(local, raw, violating))
        assertEquals(
            CleanupStepOutcome.Rejected(FidelityViolation.LENGTH_COLLAPSE),
            CleanupWaterfallExecutor.guardCloudOutput(cloud, raw, violating),
        )
        val failed = CleanupStepOutcome.StepFailed("HTTP 500")
        assertSame(failed, CleanupWaterfallExecutor.guardCloudOutput(cloud, raw, failed))
    }

    @Test fun `preservesWording selects exactly the wording-preserving built-ins`() {
        for (p in listOf(PostProcessor.NATURAL_PROMPT, PostProcessor.DEV_PROMPT, PostProcessor.SIMPLE_PROMPT)) {
            assertTrue(PostProcessor.preservesWording(p))
            assertTrue(PostProcessor.preservesWording(PostProcessor.withKeepLanguage(PostProcessor.interpolateVocabulary(p, listOf("Hetzner", "Pi")))))
        }
        for (p in listOf(PostProcessor.STRUCTURED_PROMPT, PostProcessor.EMAIL_PROMPT, PostProcessor.CONCISE_PROMPT, "Rewrite this as a haiku.", "")) {
            assertFalse(PostProcessor.preservesWording(p))
        }
    }
}
