package com.trevornk.ramblr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers which execution path [PostProcessor.processProviderChain] dispatches a chain down (#105).
 *
 * The bug: a single-OpenAI cleanup chain -- the most common configuration in production -- was
 * special-cased to call a since-deleted `PostProcessor.process()` helper directly through
 * `NetworkClients.shared`
 * (20s connect / 120s read / 180s call), bypassing [CleanupWaterfallExecutor]'s far tighter
 * [CleanupStepTimeouts] and hard cap. A stalled cleanup on the default config could therefore hang
 * for minutes instead of failing over to raw-text injection in seconds, and benchmark correlation
 * ids were silently dropped on that path.
 *
 * The pre-existing shape test in ProviderChainRuntimeTest asserts that such a chain *maps to* a
 * one-step waterfall, but that passes just as happily while the special case diverts around the
 * executor -- it never observes the dispatch. These tests observe it directly: the injected
 * transport is reached only via [CleanupWaterfallExecutor], so a request arriving here at all is
 * proof the executor ran, and the timeouts it is handed are the ones the issue was about.
 */
class PostProcessorProviderChainRoutingTest {

    /** Records what the executor actually sent, and answers with a canned success. */
    private class RecordingTransport : CleanupHttpTransport {
        val urls = mutableListOf<String>()
        val timeouts = mutableListOf<CleanupStepTimeouts>()

        override fun send(
            url: String,
            headers: Map<String, String>,
            jsonBody: String,
            timeouts: CleanupStepTimeouts,
            cancelHolder: InFlightCall,
            callback: (CleanupHttpOutcome) -> Unit,
        ) {
            urls.add(url)
            this.timeouts.add(timeouts)
            callback(CleanupHttpOutcome.Ok("""{"choices":[{"message":{"content":"cleaned"}}]}"""))
        }
    }

    private fun run(chain: ProviderChain): Pair<RecordingTransport, PostProcessor.Result> {
        val transport = RecordingTransport()
        var captured: PostProcessor.Result? = null
        PostProcessor.processProviderChain(
            text = "raw transcript",
            prompt = "clean it up",
            chain = chain,
            cursor = CleanupWaterfallCursor(),
            cancelHolder = InFlightCall(),
            credentialLookup = { "test-key" },
            transport = transport,
            callback = { captured = it },
        )
        return transport to (captured ?: error("callback never fired"))
    }

    @Test fun `a single OpenAI chain is executed by CleanupWaterfallExecutor, not the direct path (#105)`() {
        val chain = ProviderChain(listOf(ProviderChainEntry(ProviderKind.OPENAI, "gpt-4o-mini")))

        val (transport, result) = run(chain)

        // Reaching the injected transport at all is the assertion: the old special case called
        // the now-deleted PostProcessor.process() (removed as dead code, M4 audit 2026-08-26),
        // which built its own OkHttp call and would never touch this.
        assertEquals(1, transport.urls.size)
        assertEquals("cleaned", result.text)
    }

    @Test fun `a single OpenAI chain gets the waterfall's tight timeouts, not the long shared ones (#105)`() {
        val chain = ProviderChain(listOf(ProviderChainEntry(ProviderKind.OPENAI, "gpt-4o-mini")))

        val (transport, _) = run(chain)

        // The substance of #105: these are per-step cleanup timeouts, seconds not minutes. The
        // bypassed path used NetworkClients.shared's 20s/120s budget sized for audio uploads.
        val used = transport.timeouts.single()
        assertTrue(
            "connect timeout ${used.connectMs}ms should be a cleanup-sized budget, not an upload-sized one",
            used.connectMs in 1..5_000,
        )
        assertTrue(
            "read timeout ${used.readMs}ms should be a cleanup-sized budget, not an upload-sized one",
            used.readMs in 1..30_000,
        )
    }

    @Test fun `a multi provider chain still routes through the executor`() {
        val chain = ProviderChain(
            listOf(
                ProviderChainEntry(ProviderKind.OPENAI, "gpt-4o-mini"),
                ProviderChainEntry(ProviderKind.ANTHROPIC, "claude-haiku"),
            ),
        )

        val (transport, result) = run(chain)

        assertEquals(1, transport.urls.size) // first step succeeds, no failover needed
        assertEquals("cleaned", result.text)
    }
}
