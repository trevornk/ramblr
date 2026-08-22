package com.trevornk.ramblr

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * #175 fixture guard: proves the `RAW_` strings asserted in [CleanupFailureNoticeTest] are what
 * [CleanupWaterfallExecutor] actually produces, by running its real `execute()`.
 *
 * Without this, the substring mapping in [CleanupFailureNotice] is pinned only to strings I typed
 * by hand. If the executor reworks its error prefixes, the notice would silently degrade every
 * bubble to "unknown error" and every test in the sibling file would still pass.
 */
class CleanupFailureNoticeExecutorFixtureTest {

    private fun terminalErrorFor(localResult: LocalInferenceResult): String? {
        var captured: PostProcessor.Result? = null
        CleanupWaterfallExecutor.execute(
            text = "raw transcript",
            prompt = "clean it up",
            waterfall = CleanupWaterfall(
                listOf(CleanupStep(CleanupStepGroup.LOCAL_LLM, "lfm2.5-350m-q4_0"))
            ),
            cursor = CleanupWaterfallCursor(),
            cancelHolder = InFlightCall(),
            credentialLookup = { "" },
            transport = CleanupHttpTransport { _, _, _, _, _, _ ->
                error("no cloud step in a local-only chain")
            },
            localInference = LocalInferenceEngine { _, _, _, _, _ -> localResult },
            localModelPath = { "/fake/model.gguf" },
            callback = { captured = it },
        )
        return (captured ?: error("callback never fired")).error
    }

    @Test fun `the executor really does nest a rejection under the all-steps-failed prefix`() {
        // RealLocalInferenceEngine.validated() builds this exact Failure message for a rejection;
        // the executor then wraps it. Both halves are load-bearing for the substring match.
        val error = terminalErrorFor(
            LocalInferenceResult.Failure("Local cleanup output rejected (PROMPT_ECHO)")
        )
        assertEquals(
            "All cleanup steps failed: Local cleanup output rejected (PROMPT_ECHO)",
            error,
        )
        assertEquals("model repeated its instructions", CleanupFailureNotice.summarize(error))
    }

    @Test fun `the executor really does nest a local timeout the same way`() {
        val error = terminalErrorFor(LocalInferenceResult.TimedOut("Local cleanup timed out"))
        assertEquals("All cleanup steps failed: Local cleanup timed out", error)
        assertEquals("model timed out", CleanupFailureNotice.summarize(error))
    }

    @Test fun `an empty local response maps through the executor as written`() {
        val error = terminalErrorFor(
            LocalInferenceResult.Failure("Local model produced an empty response")
        )
        assertEquals("All cleanup steps failed: Local model produced an empty response", error)
        assertEquals("model returned nothing", CleanupFailureNotice.summarize(error))
    }

    @Test fun `the executor's own missing-model pre-flight maps to the model-missing phrase`() {
        // This branch never reaches localInference at all: the executor short-circuits when
        // localModelPath() is null/blank, so it needs its own fixture rather than a
        // LocalInferenceResult. On-device this is the string a user with an uninstalled model
        // hits, and before the fix it rendered as "unknown error".
        var captured: PostProcessor.Result? = null
        CleanupWaterfallExecutor.execute(
            text = "raw transcript",
            prompt = "clean it up",
            waterfall = CleanupWaterfall(
                listOf(CleanupStep(CleanupStepGroup.LOCAL_LLM, "lfm2.5-350m-q4_0"))
            ),
            cursor = CleanupWaterfallCursor(),
            cancelHolder = InFlightCall(),
            credentialLookup = { "" },
            transport = CleanupHttpTransport { _, _, _, _, _, _ ->
                error("no cloud step in a local-only chain")
            },
            localInference = LocalInferenceEngine { _, _, _, _, _ ->
                error("must short-circuit before inference when the model is absent")
            },
            localModelPath = { null },
            callback = { captured = it },
        )
        val error = (captured ?: error("callback never fired")).error
        assertEquals("All cleanup steps failed: Local cleanup model not downloaded", error)
        assertEquals("cleanup model isn't installed", CleanupFailureNotice.summarize(error))
    }
}
