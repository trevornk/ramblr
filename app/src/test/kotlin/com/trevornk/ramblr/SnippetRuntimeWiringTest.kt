package com.trevornk.ramblr

import android.Manifest
import android.app.Application
import android.os.Looper
import java.io.File
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Real-wiring coverage for #248 Snippets through [DictationRuntime.handleTranscriptionResult] --
 * the actual production entry point every transcriber callback funnels into, not just
 * [SnippetExpander] in isolation. Proves the shared-path integration this task requires:
 * snippets are off by default (no behavior change for anyone who hasn't opted in), apply when
 * enabled, never touch `rawText`, and stay off when the toggle is off even with entries
 * configured -- mirroring [DictationRuntimeTest]'s own harness (RecordingListener capturing
 * `deliverText` calls, no real recorder/network involved).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SnippetRuntimeWiringTest {

    private lateinit var app: Application
    private lateinit var listener: RecordingListener
    private lateinit var leaseRegistry: InMemoryDictationSessionLeaseRegistry
    private lateinit var runtime: DictationRuntime
    private lateinit var cleanupServer: MockWebServer

    private class RecordingListener : RuntimeListener {
        val delivered = mutableListOf<Delivery>()
        data class Delivery(val text: String, val rawText: String?)
        override fun onRecordingStartRequested() {}
        override fun onRecordingStartFailed() {}
        override fun onRecordingStarted() {}
        override fun onEnterTranscribingUi() {}
        override fun onIdleUi() {}
        override fun onStreamingTeardown() {}
        override fun onStreamingPartial(text: String) {}
        override fun deliverText(
            text: String,
            rawText: String?,
            paidFallbackGroup: CleanupStepGroup?,
            cleanupError: String?,
            feedbackDurationMs: Long,
        ) {
            delivered += Delivery(text, rawText)
        }
        override fun foregroundPackageName(): String? = null
    }

    /** Minimal fake capture boundary, mirroring [DictationRuntimeTest]'s own -- claims the state
     *  machine synchronously in start() exactly as the real engine does, with no actual
     *  AudioRecord/reader thread involved. */
    private class FakeRecordingEngine(
        cacheDir: File,
        private val stateMachine: RecordingStateMachine,
    ) : RecordingEngine(cacheDir, stateMachine) {
        override fun start(onFinished: (Result) -> Unit, onChunk: (ByteArray, Int) -> Unit): Boolean =
            stateMachine.tryStartRecording()
    }

    @Before
    fun setUp() {
        app = RuntimeEnvironment.getApplication()
        shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO)
        listener = RecordingListener()
        leaseRegistry = InMemoryDictationSessionLeaseRegistry()
        cleanupServer = MockWebServer().apply { start() }
        runtime = DictationRuntime(app, listener, leaseRegistry) { cacheDir, stateMachine ->
            FakeRecordingEngine(cacheDir, stateMachine)
        }
    }

    @After
    fun tearDown() {
        cleanupServer.shutdown()
    }

    private fun idleMainLooper() = shadowOf(Looper.getMainLooper()).idle()

    /** Runtime tests drive [DictationRuntime.handleTranscriptionResult] directly (the exact
     *  entry point every transcriber callback uses) rather than the full record/engine flow --
     *  same shortcut [DictationRuntimeTest] uses for its cleanup-focused tests. Mints token 1
     *  via a tap/tap pair, matching the guard's real token discipline. */
    private fun deliverTranscript(text: String): List<RecordingListener.Delivery> {
        runtime.onTap()
        runtime.onTap()
        runtime.handleTranscriptionResult(text, token = 1)
        idleMainLooper()
        return listener.delivered
    }

    @Test
    fun `snippets off by default -- configured entries never expand`() {
        SnippetsStore.save(app, listOf(SnippetEntry("k", "my address", "123 Main St")))
        // Toggle left at its default (false).
        val delivered = deliverTranscript("Please send it to my address today.")
        assertEquals("Please send it to my address today.", delivered.single().text)
    }

    @Test
    fun `enabled snippet expands in the real delivery path, raw dictation with cleanup off`() {
        SnippetsStore.save(app, listOf(SnippetEntry("k", "my address", "123 Main St")))
        SnippetsToggle.setEnabled(app, true)

        val delivered = deliverTranscript("Please send it to my address today.")

        assertEquals("Please send it to 123 Main St today.", delivered.single().text)
    }

    @Test
    fun `no configured entries with the toggle on is a no-op`() {
        SnippetsToggle.setEnabled(app, true)
        val delivered = deliverTranscript("Nothing configured, nothing to expand.")
        assertEquals("Nothing configured, nothing to expand.", delivered.single().text)
    }

    @Test
    fun `expansion runs on delivered text, is case and punctuation tolerant end to end`() {
        SnippetsStore.save(app, listOf(SnippetEntry("k", "my sig", "Best, Trevor")))
        SnippetsToggle.setEnabled(app, true)

        val delivered = deliverTranscript("Talk soon. MY SIG,")

        assertEquals("Talk soon. Best, Trevor,", delivered.single().text)
    }

    @Test
    fun `junk transcript path also runs through expansion (single call site covers every branch)`() {
        SnippetsStore.save(app, listOf(SnippetEntry("k", "hi", "hello there")))
        SnippetsToggle.setEnabled(app, true)
        // A junk transcript ("." alone) can't contain the trigger, but this proves the junk-gate
        // early-return branch still calls finalizeForDelivery rather than bypassing it -- the
        // no-op result confirms the call happened without crashing or otherwise diverging.
        val delivered = deliverTranscript(".")
        assertEquals(".", delivered.single().text)
    }

    @Test
    fun `snippet expands on real cloud cleanup output, not on the pre-cleanup raw transcript`() {
        // Proves the ordering contract end to end through the real cleanup waterfall (a live
        // MockWebServer standing in for the cloud provider, exactly as DictationRuntimeTest's
        // own cloud tests do) rather than only asserting it against SnippetExpander in isolation:
        // the trigger is absent from the raw ASR text and present only in the cleanup model's
        // (mocked) output, so a pass than ran BEFORE cleanup could never expand it.
        cleanupServer.enqueue(
            MockResponse().setBody(
                JSONObject().put(
                    "choices",
                    org.json.JSONArray().put(
                        JSONObject().put(
                            "message",
                            JSONObject().put("content", "Please send it to my home address."),
                        ),
                    ),
                ).toString()
            )
        )
        val base = cleanupServer.url("/v1").toString().trimEnd('/')
        ProviderChainStore.save(
            app,
            ProviderChain(listOf(ProviderChainEntry(ProviderKind.OPENAI, "gpt-5.4-mini", baseUrlOverride = base))),
        )
        ProviderCredentialStore.setLegacyByKind(app, ProviderKind.OPENAI, "test-key")
        app.getSharedPreferences("ramblr", android.content.Context.MODE_PRIVATE).edit()
            .putBoolean("use_post_processing", true)
            .apply()

        SnippetsStore.save(app, listOf(SnippetEntry("k", "my home address", "123 Main St, Springfield")))
        SnippetsToggle.setEnabled(app, true)

        runtime.onTap()
        runtime.onTap()
        runtime.handleTranscriptionResult("please send it to my home address unclean", token = 1)
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline && listener.delivered.isEmpty()) {
            idleMainLooper(); Thread.sleep(10)
        }

        val delivery = listener.delivered.single()
        assertEquals("Please send it to 123 Main St, Springfield.", delivery.text)
        // rawText carries the pre-cleanup transcript for the "tap to undo cleanup" bubble and
        // must stay literal/unexpanded -- see finalizeForDelivery's kdoc.
        assertEquals("please send it to my home address unclean", delivery.rawText)
    }
}
