package com.trevornk.ramblr

import android.Manifest
import android.app.Application
import android.content.Context
import android.os.Looper
import java.io.File
import java.util.concurrent.TimeUnit
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * #233 Phase 1 item 10: cloud-live timing + fallback reason must survive the dictation that
 * produced them.
 *
 * These are not schema tests for their own sake. The live -> batch fallback is deliberately
 * LOSSLESS -- a failed live attempt replays the preserved recording through the ordinary batch
 * chain and the user gets the same text, delivered the same way. That makes a live failure
 * completely invisible from the outside: on a real device there is no way to tell "live served
 * this dictation" from "live silently died on every single utterance and batch covered for it".
 * Everything below exists so that distinction is readable off `benchmark_log.jsonl` after the
 * fact, which is the only reason a device trial can produce evidence at all.
 *
 * The privacy assertion is equally load-bearing: [BenchmarkLogger] is documented as length-only
 * and the issue says "without transcript content", so the interim and final text are asserted
 * absent against distinctive sentinel strings rather than merely "not obviously present".
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CloudLiveBenchmarkTest {

    /** A sentinel that could never occur incidentally in JSON structure or an enum name, so an
     *  "is the transcript in the log" assertion cannot pass by accident. */
    private companion object {
        const val SECRET_FINAL = "zqx-final-transcript-must-never-be-logged"
        const val SECRET_INTERIM = "zqx-interim-transcript-must-never-be-logged"
    }

    private lateinit var app: Application
    private lateinit var listener: RecordingListener
    private lateinit var engines: MutableList<FakeRecordingEngine>
    private lateinit var leaseRegistry: InMemoryDictationSessionLeaseRegistry
    private lateinit var runtime: DictationRuntime
    private lateinit var batchServer: MockWebServer

    private class RecordingListener : RuntimeListener {
        val delivered = mutableListOf<String>()
        val interims = mutableListOf<String>()
        override fun onRecordingStartRequested() = Unit
        override fun onRecordingStartFailed() = Unit
        override fun onRecordingStarted() = Unit
        override fun onEnterTranscribingUi() = Unit
        override fun onIdleUi() = Unit
        override fun onStreamingTeardown() = Unit
        override fun onStreamingPartial(text: String) = Unit
        override fun onCloudLiveInterim(text: String) { interims += text }
        override fun deliverText(
            text: String,
            rawText: String?,
            paidFallbackGroup: CleanupStepGroup?,
            cleanupError: String?,
            feedbackDurationMs: Long,
        ) { delivered += text }
        override fun foregroundPackageName(): String? = null
    }

    @Before fun setUp() {
        app = RuntimeEnvironment.getApplication()
        shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO)
        listener = RecordingListener()
        engines = mutableListOf()
        leaseRegistry = InMemoryDictationSessionLeaseRegistry()
        batchServer = MockWebServer().apply { start() }
        // The log is app-private and append-only; Robolectric reuses filesDir across tests in a
        // class, so start every case from a known-empty file rather than a leftover tail.
        BenchmarkLogger.logFile(app).delete()
    }

    @After fun tearDown() {
        batchServer.shutdown()
    }

    private fun idleMainLooper() = shadowOf(Looper.getMainLooper()).idle()

    private fun realSizedPcm(): File = File.createTempFile("rec_", ".pcm", app.cacheDir).apply {
        writeBytes(ByteArray(32_000)) // 1s of 16kHz mono 16-bit, comfortably over the #192 floor
    }

    private fun cloudLiveRuntime(factory: FakeCloudLiveFactory) {
        runtime = DictationRuntime(app, listener, leaseRegistry, cloudLiveFactory = factory) {
            cacheDir, stateMachine -> FakeRecordingEngine(cacheDir, stateMachine).also { engines += it }
        }
    }

    /** Same batch stub DictationRuntimeTest uses, so a fallback case actually walks the real
     *  batch chain and emits its own transcription line to correlate against. */
    private fun stubBatchProvider(transcript: String) {
        batchServer.enqueue(MockResponse().setBody(JSONObject().put("text", transcript).toString()))
        val base = batchServer.url("/v1").toString().trimEnd('/')
        ProviderChainStore.save(
            app,
            ProviderChain(listOf(ProviderChainEntry(ProviderKind.OPENAI, "gpt-5.4-mini", baseUrlOverride = base, transcriptionModel = "gpt-transcribe"))),
        )
        ProviderCredentialStore.set(app, ProviderKind.OPENAI, "test-batch-key")
        app.getSharedPreferences("ramblr", Context.MODE_PRIVATE).edit().putBoolean("use_local", false).apply()
        PostProcessingToggle.setEnabled(app, false)
    }

    /** [BenchmarkLogger] defers its append to a daemon executor, so a reader has to wait for it. */
    private fun awaitCloudLiveLine(): JSONObject {
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline) {
            idleMainLooper()
            val line = logLines().firstOrNull { !it.isNull("cloudLive") }
            if (line != null) return line
            Thread.sleep(10)
        }
        throw AssertionError("no cloudLive benchmark line was written; file=${rawLog()}")
    }

    private fun rawLog(): String =
        BenchmarkLogger.logFile(app).takeIf { it.exists() }?.readText().orEmpty()

    private fun logLines(): List<JSONObject> =
        rawLog().lineSequence().filter { it.isNotBlank() }.map { JSONObject(it) }.toList()

    // --- the three durations item 10 names, on a live attempt that actually served the text ---

    @Test
    fun `a delivered live attempt records setup, first-interim and end-of-audio-to-final durations`() {
        val factory = FakeCloudLiveFactory()
        cloudLiveRuntime(factory)
        val pcm = realSizedPcm()

        runtime.onTap()
        val session = factory.sessions.single()
        session.interim(SECRET_INTERIM, CloudLiveTiming(connectStartedAtMs = 1_000, firstInterimAtMs = 1_400))
        idleMainLooper()

        runtime.onTap()
        engines.single().finishAs(pcm, RecordingEngine.StopReason.USER)
        session.complete(
            CloudLiveTerminal.Success(
                SECRET_FINAL,
                CloudLiveTiming(
                    connectStartedAtMs = 1_000,
                    socketOpenedAtMs = 1_050,
                    setupCompletedAtMs = 1_180,
                    firstInterimAtMs = 1_400,
                    activityEndedAtMs = 5_000,
                    finalAtMs = 5_320,
                ),
            ),
        )
        idleMainLooper()

        val line = awaitCloudLiveLine()
        val live = line.getJSONObject("cloudLive")
        assertEquals("LIVE_DELIVERED", live.getString("outcome"))
        assertFalse("live served this dictation, so nothing fell back", live.getBoolean("fellBackToBatch"))
        assertTrue(live.isNull("failureReason"))
        assertEquals(180L, live.getLong("setupMs"))
        assertEquals(400L, live.getLong("firstInterimMs"))
        assertEquals("the headline post-stop number", 320L, live.getLong("endOfAudioToFinalMs"))
        assertTrue(live.isNull("error"))
    }

    // --- a failed attempt has to name the reason, or a device trial learns nothing ---

    @Test
    fun `a failed live attempt records the specific failure reason and marks the batch fallback`() {
        val factory = FakeCloudLiveFactory()
        cloudLiveRuntime(factory)
        stubBatchProvider("batch covered for the failed live attempt")
        val pcm = realSizedPcm()

        runtime.onTap()
        val session = factory.sessions.single()
        runtime.onTap()
        engines.single().finishAs(pcm, RecordingEngine.StopReason.USER)
        session.complete(
            CloudLiveTerminal.Failure(
                CloudLiveFailureReason.NETWORK_ERROR,
                "socket closed mid-stream",
                CloudLiveTiming(connectStartedAtMs = 2_000, setupCompletedAtMs = 2_090, activityEndedAtMs = 7_000),
            ),
        )
        idleMainLooper()

        val line = awaitCloudLiveLine()
        val live = line.getJSONObject("cloudLive")
        assertEquals("BATCH_SERVED_LIVE_FAILED", live.getString("outcome"))
        assertTrue("the reader must be able to see batch delivered this text", live.getBoolean("fellBackToBatch"))
        assertEquals("NETWORK_ERROR", live.getString("failureReason"))
        assertEquals(90L, live.getLong("setupMs"))
        assertTrue("no interim ever arrived", live.isNull("firstInterimMs"))
        assertTrue("no final ever arrived", live.isNull("endOfAudioToFinalMs"))
        assertEquals("socket closed mid-stream", live.getString("error"))
    }

    @Test
    fun `a live attempt that never reports a terminal is distinguishable from one that failed`() {
        val factory = FakeCloudLiveFactory()
        cloudLiveRuntime(factory)
        stubBatchProvider("batch covered for the silent live attempt")
        val pcm = realSizedPcm()

        runtime.onTap()
        val session = factory.sessions.single()
        // Setup landed and interims flowed, then the session simply went quiet -- the mid-stream
        // drop shape, which reports no CloudLiveFailureReason of its own.
        session.interim(SECRET_INTERIM, CloudLiveTiming(connectStartedAtMs = 3_000, setupCompletedAtMs = 3_120, firstInterimAtMs = 3_500))
        idleMainLooper()
        runtime.onTap()
        engines.single().finishAs(pcm, RecordingEngine.StopReason.USER)
        idleMainLooper()
        shadowOf(Looper.getMainLooper()).idleFor(2_501, TimeUnit.MILLISECONDS)

        val live = awaitCloudLiveLine().getJSONObject("cloudLive")
        assertEquals("BATCH_SERVED_NO_TERMINAL", live.getString("outcome"))
        assertTrue(live.getBoolean("fellBackToBatch"))
        assertTrue("a silent session names no reason", live.isNull("failureReason"))
        assertEquals("but the marks it did emit still survive", 120L, live.getLong("setupMs"))
        assertEquals(500L, live.getLong("firstInterimMs"))
    }

    @Test
    fun `a live success whose final is unusable is not reported as a live delivery`() {
        val factory = FakeCloudLiveFactory()
        cloudLiveRuntime(factory)
        stubBatchProvider("batch covered for the blank live final")
        val pcm = realSizedPcm()

        runtime.onTap()
        val session = factory.sessions.single()
        runtime.onTap()
        engines.single().finishAs(pcm, RecordingEngine.StopReason.USER)
        session.complete(CloudLiveTerminal.Success("   ", CloudLiveTiming(connectStartedAtMs = 1, activityEndedAtMs = 10, finalAtMs = 42)))
        idleMainLooper()

        val live = awaitCloudLiveLine().getJSONObject("cloudLive")
        assertEquals("BATCH_SERVED_UNUSABLE_FINAL", live.getString("outcome"))
        assertTrue(live.getBoolean("fellBackToBatch"))
        assertTrue("a blank final is not a session failure", live.isNull("failureReason"))
        assertEquals(32L, live.getLong("endOfAudioToFinalMs"))
    }

    // --- the hard constraint: never any transcript content ---

    @Test
    fun `no interim or final transcript text ever reaches the benchmark log`() {
        val factory = FakeCloudLiveFactory()
        cloudLiveRuntime(factory)
        val pcm = realSizedPcm()

        runtime.onTap()
        val session = factory.sessions.single()
        session.interim(SECRET_INTERIM, CloudLiveTiming(connectStartedAtMs = 1_000, firstInterimAtMs = 1_100))
        idleMainLooper()
        runtime.onTap()
        engines.single().finishAs(pcm, RecordingEngine.StopReason.USER)
        session.complete(
            CloudLiveTerminal.Success(
                SECRET_FINAL,
                CloudLiveTiming(connectStartedAtMs = 1_000, setupCompletedAtMs = 1_050, firstInterimAtMs = 1_100, activityEndedAtMs = 4_000, finalAtMs = 4_200),
            ),
        )
        idleMainLooper()
        awaitCloudLiveLine()

        // The interim WAS shown to the user (so this isn't vacuous) and the final WAS delivered,
        // yet neither may appear anywhere in the durable log.
        assertTrue("the interim must actually have been surfaced", listener.interims.contains(SECRET_INTERIM))
        val log = rawLog()
        assertFalse("the final transcript leaked into benchmark_log.jsonl", log.contains(SECRET_FINAL))
        assertFalse("an interim transcript leaked into benchmark_log.jsonl", log.contains(SECRET_INTERIM))
    }

    @Test
    fun `a failure message that echoes the transcript is bounded by sanitizeError`() {
        // A provider error envelope can quote the request that caused it. The stage must route
        // through sanitizeError like every other error field: collapsed (JSONL is line-oriented,
        // so an embedded newline would split one record into two unparseable fragments) and
        // capped at MAX_ERROR_DETAIL_CHARS.
        val stage = cloudLiveBenchmarkStage(
            outcome = CloudLiveOutcome.BATCH_SERVED_LIVE_FAILED,
            terminal = CloudLiveTerminal.Failure(
                CloudLiveFailureReason.PROTOCOL_ERROR,
                "rejected\n" + "x".repeat(MAX_ERROR_DETAIL_CHARS * 3),
                CloudLiveTiming(connectStartedAtMs = 1),
            ),
            timing = null,
        )
        val error = requireNotNull(stage.error)
        assertFalse("a newline would split the JSONL record", error.contains("\n"))
        assertEquals(MAX_ERROR_DETAIL_CHARS + 3, error.length)
        assertTrue(error.endsWith("..."))
    }

    // --- correlation with the dictation the live attempt belongs to ---

    @Test
    fun `the cloud-live line shares the dictation's existing correlationId with its batch line`() {
        val factory = FakeCloudLiveFactory()
        cloudLiveRuntime(factory)
        stubBatchProvider("batch transcript")
        val pcm = realSizedPcm()

        runtime.onTap()
        val session = factory.sessions.single()
        runtime.onTap()
        engines.single().finishAs(pcm, RecordingEngine.StopReason.USER)
        session.complete(CloudLiveTerminal.Failure(CloudLiveFailureReason.SETUP_TIMEOUT, "setup timed out", CloudLiveTiming(1)))
        idleMainLooper()

        val liveCorrelationId = awaitCloudLiveLine().getString("correlationId")
        // The same dictation's batch transcription line must land under the identical id, which
        // is the whole point of reusing correlationIdFor(token) rather than minting a new scheme.
        val deadline = System.currentTimeMillis() + 5_000
        var batchLine: JSONObject? = null
        while (System.currentTimeMillis() < deadline && batchLine == null) {
            idleMainLooper()
            batchLine = logLines().firstOrNull { !it.isNull("transcription") }
            Thread.sleep(10)
        }
        assertNotNull("the batch fallback must still have produced its own line", batchLine)
        assertEquals(liveCorrelationId, batchLine!!.getString("correlationId"))
        assertTrue("ids stay in the existing tok-<launch>-<token> shape", liveCorrelationId.startsWith("tok-"))
    }

    // --- backward compatibility: existing consumers must keep working ---

    @Test
    fun `a cloud-live line keeps every pre-existing key and an ordinary line keeps a null cloudLive`() {
        val json = JSONObject(
            BenchmarkLogger.buildLine(
                timestamp = 1_700_000_000_000L,
                correlationId = "tok-7",
                transcription = BenchmarkStage("OPENAI", "gpt-4o-transcribe", 812L, success = true),
                cleanup = null,
                rawTextLength = 12,
                cleanedTextLength = null,
            ),
        )
        // Every consumer (BackupManager's ENTRY_BENCHMARK_LOG copy, DataLogsActivity's share,
        // and the existing tests) reads these keys; an additive block must not disturb them.
        assertEquals(1_700_000_000_000L, json.getLong("timestamp"))
        assertEquals("tok-7", json.getString("correlationId"))
        assertEquals(12, json.getInt("rawTextLength"))
        assertTrue(json.isNull("cleanedTextLength"))
        assertTrue(json.isNull("cleanup"))
        assertTrue(json.isNull("pipeline"))
        assertEquals("OPENAI", json.getJSONObject("transcription").getString("provider"))
        assertTrue("a line with no live attempt still carries the key, as JSON null", json.isNull("cloudLive"))

        val withLive = JSONObject(
            BenchmarkLogger.buildLine(
                timestamp = 2L,
                correlationId = "tok-8",
                transcription = null,
                cleanup = null,
                rawTextLength = null,
                cleanedTextLength = null,
                cloudLive = CloudLiveStage(
                    outcome = CloudLiveOutcome.LIVE_DELIVERED.name,
                    fellBackToBatch = false,
                    setupMs = 180L,
                    firstInterimMs = 400L,
                    endOfAudioToFinalMs = 320L,
                ),
            ),
        )
        // Still a complete, self-contained record: the additive block never replaces the old keys.
        assertEquals(2L, withLive.getLong("timestamp"))
        assertTrue(withLive.isNull("transcription"))
        assertTrue(withLive.isNull("cleanup"))
        assertTrue(withLive.isNull("rawTextLength"))
        assertTrue(withLive.isNull("cleanedTextLength"))
        assertTrue(withLive.isNull("pipeline"))
        assertEquals("LIVE_DELIVERED", withLive.getJSONObject("cloudLive").getString("outcome"))
    }

    // --- failure isolation: observability must never cost a delivery ---

    @Test
    fun `a benchmark log write failure does not break or block live delivery`() {
        val factory = FakeCloudLiveFactory()
        cloudLiveRuntime(factory)
        // Make the append genuinely impossible: a directory where the log file should be. The
        // logger's runCatching must swallow it, and the user must still get their text.
        val logPath = BenchmarkLogger.logFile(app)
        logPath.delete()
        assertTrue(logPath.mkdirs())
        val pcm = realSizedPcm()

        runtime.onTap()
        val session = factory.sessions.single()
        runtime.onTap()
        engines.single().finishAs(pcm, RecordingEngine.StopReason.USER)
        session.complete(CloudLiveTerminal.Success(SECRET_FINAL, CloudLiveTiming(1, activityEndedAtMs = 2, finalAtMs = 3)))
        idleMainLooper()

        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline && listener.delivered.isEmpty()) {
            idleMainLooper(); Thread.sleep(10)
        }
        assertEquals(listOf(SECRET_FINAL), listener.delivered)
        assertEquals(RecordingStateMachine.State.IDLE, runtime.currentState())
        logPath.delete()
    }

    // --- the pure derivation, independent of any host ---

    @Test
    fun `derivation reports a mark that never happened as null rather than a fabricated zero`() {
        val stage = cloudLiveBenchmarkStage(
            outcome = CloudLiveOutcome.BATCH_SERVED_LIVE_FAILED,
            terminal = CloudLiveTerminal.Failure(
                CloudLiveFailureReason.SETUP_TIMEOUT,
                "setup timed out",
                CloudLiveTiming(connectStartedAtMs = 500),
            ),
            timing = null,
        )
        assertNull("setup never completed; 0ms would be a lie", stage.setupMs)
        assertNull(stage.firstInterimMs)
        assertNull(stage.endOfAudioToFinalMs)
        assertEquals("SETUP_TIMEOUT", stage.failureReason)
        assertTrue(stage.fellBackToBatch)
    }

    @Test
    fun `derivation falls back to the last seen interim timing when no terminal ever arrived`() {
        val stage = cloudLiveBenchmarkStage(
            outcome = CloudLiveOutcome.BATCH_SERVED_NO_TERMINAL,
            terminal = null,
            timing = CloudLiveTiming(connectStartedAtMs = 100, setupCompletedAtMs = 260, firstInterimAtMs = 700),
        )
        assertEquals(160L, stage.setupMs)
        assertEquals(600L, stage.firstInterimMs)
        assertNull(stage.endOfAudioToFinalMs)
        assertNull(stage.failureReason)
        assertNull(stage.error)
    }

    @Test
    fun `derivation degrades to outcome only when there are no marks at all`() {
        val stage = cloudLiveBenchmarkStage(CloudLiveOutcome.BATCH_SERVED_NO_TERMINAL, terminal = null, timing = null)
        assertEquals("BATCH_SERVED_NO_TERMINAL", stage.outcome)
        assertTrue(stage.fellBackToBatch)
        assertNull(stage.setupMs)
        assertNull(stage.firstInterimMs)
        assertNull(stage.endOfAudioToFinalMs)
    }

    @Test
    fun `pre-rename outcome names still resolve so historical trial logs stay readable`() {
        // Device trials that ran before the FALLBACK_* -> BATCH_SERVED_* rename wrote the old
        // spellings into benchmark_log.jsonl. Those logs are the only record of what the
        // hardware actually did, so the mapping is load-bearing, not cosmetic.
        assertEquals(
            CloudLiveOutcome.BATCH_SERVED_LIVE_FAILED,
            CloudLiveOutcome.fromLogName("FALLBACK_FAILED"),
        )
        assertEquals(
            CloudLiveOutcome.BATCH_SERVED_NO_TERMINAL,
            CloudLiveOutcome.fromLogName("FALLBACK_NO_TERMINAL"),
        )
        assertEquals(
            CloudLiveOutcome.BATCH_SERVED_UNUSABLE_FINAL,
            CloudLiveOutcome.fromLogName("FALLBACK_UNUSABLE_FINAL"),
        )
        // Current spellings resolve to themselves, and LIVE_DELIVERED never had an alias.
        for (outcome in CloudLiveOutcome.entries) {
            assertEquals(outcome, CloudLiveOutcome.fromLogName(outcome.name))
        }
        assertNull(CloudLiveOutcome.LIVE_DELIVERED.legacyName)
        assertNull(CloudLiveOutcome.fromLogName("NOT_AN_OUTCOME"))
    }

    @Test
    fun `every batch-served outcome reports falling back and live-delivered does not`() {
        // The invariant the name change is meant to make obvious: BATCH_SERVED_* means the user
        // still got their text, via batch. Only LIVE_DELIVERED skipped the fallback entirely.
        for (outcome in CloudLiveOutcome.entries) {
            val stage = cloudLiveBenchmarkStage(outcome, terminal = null, timing = null)
            val expected = outcome != CloudLiveOutcome.LIVE_DELIVERED
            assertEquals(
                "$outcome should report fellBackToBatch=$expected",
                expected,
                stage.fellBackToBatch,
            )
            assertEquals(expected, outcome.name.startsWith("BATCH_SERVED_"))
        }
    }

    /** Same narrow capture-boundary fake DictationRuntimeTest uses (its copy is private to that
     *  class): start() performs the real engine's synchronous IDLE -> RECORDING claim, and
     *  [finishAs] runs the same [RecorderHandoff] claim/discard decision the reader thread does,
     *  so the runtime sees byte-identical state-machine traffic. */
    private class FakeRecordingEngine(
        cacheDir: File,
        private val stateMachine: RecordingStateMachine,
    ) : RecordingEngine(cacheDir, stateMachine) {
        var onFinished: ((Result) -> Unit)? = null
        var onChunk: ((ByteArray, Int) -> Unit)? = null

        override fun start(onFinished: (Result) -> Unit, onChunk: (ByteArray, Int) -> Unit): Boolean {
            if (!stateMachine.tryStartRecording()) return false
            this.onFinished = onFinished
            this.onChunk = onChunk
            return true
        }

        override fun awaitTeardown(timeoutMs: Long): Boolean = true

        override fun isReaderTeardownPending(): Boolean = false

        fun finishAs(pcmFile: File?, stopReason: StopReason, superseded: Boolean = false) {
            if (RecorderHandoff.shouldClaimTranscribing(superseded)) stateMachine.tryStartTranscribing()
            val discarded = RecorderHandoff.discarded(superseded, stateMachine.current())
            onFinished!!(
                Result(
                    pcmFile = if (!discarded) pcmFile else null,
                    bytesRecorded = pcmFile?.length() ?: 0L,
                    discarded = discarded,
                    stopReason = stopReason,
                )
            )
        }
    }

    private class FakeCloudLiveFactory : CloudLiveTranscriptionSessionFactory {
        val sessions = mutableListOf<FakeCloudLiveSession>()
        override fun create(listener: CloudLiveTranscriptionListener): CloudLiveTranscriptionSession =
            FakeCloudLiveSession(listener).also(sessions::add)
    }

    private class FakeCloudLiveSession(
        private val listener: CloudLiveTranscriptionListener,
    ) : CloudLiveTranscriptionSession {
        override fun connect() = Unit
        override fun startActivity(): Boolean = true
        override fun sendPcm(buffer: ByteArray, length: Int): Boolean = true
        override fun endActivity(): Boolean = true
        override fun cancel() = Unit
        override fun close() = Unit
        fun interim(text: String, timing: CloudLiveTiming) = listener.onInterim(text, timing)
        fun complete(result: CloudLiveTerminal) = listener.onTerminal(result)
    }
}
