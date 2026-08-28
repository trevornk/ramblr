package com.trevornk.ramblr

import android.Manifest
import android.app.Application
import android.os.Looper
import java.io.File
import java.util.concurrent.TimeUnit
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowToast

/**
 * Host-side state-transition tests for [DictationRuntime] (#143 Phase 1) -- the pipeline logic
 * that lived inside WhisperAccessibilityService and was previously untestable off-device.
 *
 * The capture boundary is faked at the narrowest realistic seam: a [FakeRecordingEngine]
 * substituted via the runtime's `engineFactory`, which claims/releases the shared
 * [RecordingStateMachine] exactly the way the real engine's reader thread does (start() CASes
 * IDLE -> RECORDING; the finish helpers mirror RecorderHandoff's claim rules). Transcription and
 * cleanup are exercised through [DictationRuntime.handleTranscriptionResult], the exact entry
 * point every transcriber callback funnels into.
 *
 * Token discipline: [TranscriptionGuard] mints tokens from an AtomicInteger starting at 0, so
 * the first stop tap of a fresh runtime always yields token 1; a cancel supersedes it (2), the
 * next stop mints 3, and so on. Tests state the token they expect explicitly rather than
 * reaching into the private guard.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DictationRuntimeTest {

    private lateinit var app: Application
    private lateinit var listener: RecordingListener
    private lateinit var engines: MutableList<FakeRecordingEngine>
    private lateinit var leaseRegistry: InMemoryDictationSessionLeaseRegistry
    private lateinit var runtime: DictationRuntime
    private lateinit var batchServer: MockWebServer

    /** Records every listener callback in arrival order, so ordering assertions are direct. */
    private class RecordingListener : RuntimeListener {
        val events = mutableListOf<String>()
        val delivered = mutableListOf<Delivery>()
        val streamingTeardownLoopers = mutableListOf<Looper?>()

        data class Delivery(
            val text: String,
            val rawText: String?,
            val paidFallbackGroup: CleanupStepGroup?,
            val cleanupError: String?,
            val feedbackDurationMs: Long,
        )

        override fun onRecordingStartRequested() { events += "startRequested" }
        override fun onRecordingStartFailed() { events += "startFailed" }
        override fun onRecordingStarted() { events += "recordingStarted" }
        override fun onEnterTranscribingUi() { events += "transcribingUi" }
        override fun onIdleUi() { events += "idleUi" }
        override fun onStreamingTeardown() {
            events += "streamingTeardown"
            streamingTeardownLoopers += Looper.myLooper()
        }
        override fun onStreamingPartial(text: String) { events += "partial:$text" }
        override fun onCloudLiveInterim(text: String) { events += "cloud:$text" }
        override fun deliverText(
            text: String,
            rawText: String?,
            paidFallbackGroup: CleanupStepGroup?,
            cleanupError: String?,
            feedbackDurationMs: Long,
        ) {
            events += "deliver:$text"
            delivered += Delivery(text, rawText, paidFallbackGroup, cleanupError, feedbackDurationMs)
        }

        override fun foregroundPackageName(): String? = null
    }

    /**
     * Fakes the capture boundary only: no AudioRecord, no reader thread. start() performs the
     * same IDLE -> RECORDING claim the real engine performs synchronously in start(), and the
     * finish helpers run the same RecorderHandoff claim/discard decision the real reader thread
     * runs, so the runtime sees byte-identical state-machine traffic.
     */
    private class FakeRecordingEngine(
        cacheDir: File,
        private val stateMachine: RecordingStateMachine,
    ) : RecordingEngine(cacheDir, stateMachine) {
        var onFinished: ((Result) -> Unit)? = null
        var onChunk: ((ByteArray, Int) -> Unit)? = null
        var startResult = true
        var teardownCompleted = true

        override fun start(onFinished: (Result) -> Unit, onChunk: (ByteArray, Int) -> Unit): Boolean {
            if (!startResult) return false
            if (!stateMachine.tryStartRecording()) return false
            this.onFinished = onFinished
            this.onChunk = onChunk
            return true
        }

        override fun awaitTeardown(timeoutMs: Long): Boolean = teardownCompleted

        override fun isReaderTeardownPending(): Boolean = !teardownCompleted

        /** Mirrors the real reader thread's end-of-loop handoff: claim TRANSCRIBING if nobody
         *  else has (max-duration/error paths), then report discarded from the current state. */
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

    @Before
    fun setUp() {
        app = RuntimeEnvironment.getApplication()
        shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO)
        listener = RecordingListener()
        engines = mutableListOf()
        leaseRegistry = InMemoryDictationSessionLeaseRegistry()
        batchServer = MockWebServer().apply { start() }
        runtime = DictationRuntime(app, listener, leaseRegistry) { cacheDir, stateMachine ->
            FakeRecordingEngine(cacheDir, stateMachine).also { engines += it }
        }
    }

    @After
    fun tearDown() {
        batchServer.shutdown()
    }

    private fun idleMainLooper() = shadowOf(Looper.getMainLooper()).idle()

    /** A PCM file comfortably above the #192 minimum-duration floor (300ms @ 32 bytes/ms). */
    private fun realSizedPcm(): File = File.createTempFile("rec_", ".pcm", app.cacheDir).apply {
        writeBytes(ByteArray(32_000)) // 1s of 16kHz mono 16-bit
    }

    /** A PCM file below the floor -- cannot contain usable speech. */
    private fun tinyPcm(): File = File.createTempFile("rec_", ".pcm", app.cacheDir).apply {
        writeBytes(ByteArray(1_000)) // ~31ms
    }

    // --- start -> recording -> transcribing -> delivered ---

    @Test
    fun `tap starts recording and fires listener in order`() {
        runtime.onTap()
        assertEquals(RecordingStateMachine.State.RECORDING, runtime.currentState())
        assertEquals(listOf("startRequested", "recordingStarted"), listener.events)
    }

    @Test
    fun `second runtime is rejected before host or recorder setup while first owns dictation`() {
        runtime.onTap()
        val secondListener = RecordingListener()
        val secondEngines = mutableListOf<FakeRecordingEngine>()
        val secondRuntime = DictationRuntime(app, secondListener, leaseRegistry) { cacheDir, stateMachine ->
            FakeRecordingEngine(cacheDir, stateMachine).also { secondEngines += it }
        }

        secondRuntime.onTap()
        idleMainLooper()

        assertEquals(RecordingStateMachine.State.IDLE, secondRuntime.currentState())
        assertTrue(secondListener.events.isEmpty())
        assertTrue(secondEngines.isEmpty())
        assertEquals(
            "Ramblr is already dictating from another input surface",
            ShadowToast.getTextOfLatestToast(),
        )
    }

    @Test
    fun `normal completion releases lease for another runtime`() {
        runtime.onTap()
        runtime.onTap()
        runtime.handleTranscriptionResult("first dictation", token = 1)
        idleMainLooper()

        val secondListener = RecordingListener()
        val secondEngines = mutableListOf<FakeRecordingEngine>()
        val secondRuntime = DictationRuntime(app, secondListener, leaseRegistry) { cacheDir, stateMachine ->
            FakeRecordingEngine(cacheDir, stateMachine).also { secondEngines += it }
        }
        secondRuntime.onTap()

        assertEquals(RecordingStateMachine.State.RECORDING, secondRuntime.currentState())
        assertEquals(1, secondEngines.size)
    }

    @Test
    fun `second tap enters transcribing`() {
        runtime.onTap()
        runtime.onTap()
        assertEquals(RecordingStateMachine.State.TRANSCRIBING, runtime.currentState())
        assertEquals(listOf("startRequested", "recordingStarted", "transcribingUi"), listener.events)
    }

    @Test
    fun `tap while transcribing is a no-op`() {
        runtime.onTap()
        runtime.onTap()
        runtime.onTap()
        assertEquals(RecordingStateMachine.State.TRANSCRIBING, runtime.currentState())
        assertEquals(listOf("startRequested", "recordingStarted", "transcribingUi"), listener.events)
    }

    @Test
    fun `transcript is delivered then runtime returns to idle -- full listener ordering`() {
        runtime.onTap()
        runtime.onTap() // TRANSCRIBING; mints token 1
        runtime.handleTranscriptionResult("hello world", token = 1)
        idleMainLooper()

        assertEquals(RecordingStateMachine.State.IDLE, runtime.currentState())
        assertEquals(
            listOf(
                "startRequested",
                "recordingStarted",
                "transcribingUi",
                "deliver:hello world",
                "idleUi",
                "streamingTeardown",
            ),
            listener.events,
        )
        val delivery = listener.delivered.single()
        // Cleanup is off by default, so this is the raw-injection shape: no rawText, no error.
        assertNull(delivery.rawText)
        assertNull(delivery.cleanupError)
        assertNull(delivery.paidFallbackGroup)
        assertEquals(2000L, delivery.feedbackDurationMs)
    }

    @Test
    fun `engine finish with adequate audio reaches transcription dispatch`() {
        val pcm = realSizedPcm()
        runtime.onTap()
        runtime.onTap() // token 1 minted; state TRANSCRIBING
        engines.single().finishAs(pcm, RecordingEngine.StopReason.USER)
        idleMainLooper()

        // Default settings: use_local=true, no local model loaded, cloud fallback off -- the
        // dispatch resolves to the "Local model still downloading" reset. The observable
        // contract here is that the audio was accepted (not gated) and the pipeline funnelled
        // back to IDLE through resetToIdle, deleting the PCM.
        assertEquals(RecordingStateMachine.State.IDLE, runtime.currentState())
        assertFalse(pcm.exists())
        assertTrue(listener.events.contains("idleUi"))
        assertTrue(listener.delivered.isEmpty())
    }

    // --- junk / no-speech gating (#192) ---

    @Test
    fun `recording under minimum duration floor is discarded without transcription`() {
        val pcm = tinyPcm()
        runtime.onTap()
        runtime.onTap() // token 1
        engines.single().finishAs(pcm, RecordingEngine.StopReason.USER)
        idleMainLooper()

        assertEquals(RecordingStateMachine.State.IDLE, runtime.currentState())
        assertFalse("sub-floor PCM must be deleted", pcm.exists())
        assertTrue(listener.delivered.isEmpty())
        assertTrue(listener.events.contains("idleUi"))
    }

    @Test
    fun `blank transcript abandons timing and resets without delivery`() {
        runtime.onTap()
        runtime.onTap() // token 1; timing anchored at stop tap
        runtime.handleTranscriptionResult("   ", token = 1)
        idleMainLooper()

        assertEquals(RecordingStateMachine.State.IDLE, runtime.currentState())
        assertTrue(listener.delivered.isEmpty())
        // H2 (#192): the abandoned timeline must not be consumable by a later injection.
        assertNull(runtime.pipelineTiming.consume())
    }

    @Test
    fun `junk transcript short-circuits cleanup and is delivered raw`() {
        // Cleanup ON so the junk gate is what skips it, not the toggle.
        PostProcessingToggle.setEnabled(app, true)
        runtime.onTap()
        runtime.onTap() // token 1
        runtime.handleTranscriptionResult(".", token = 1)
        idleMainLooper()

        val delivery = listener.delivered.single()
        assertEquals(".", delivery.text)
        assertNull("junk goes raw: no cleanup ran, so no rawText side", delivery.rawText)
        assertNull(delivery.cleanupError)
        assertEquals(RecordingStateMachine.State.IDLE, runtime.currentState())
    }

    // --- cancel semantics (#193 session-locality, #20 stale-callback gating) ---

    @Test
    fun `cancel during transcribing returns to idle and supersedes the token`() {
        runtime.onTap()
        runtime.onTap() // token 1
        runtime.cancelTranscription()
        idleMainLooper()

        assertEquals(RecordingStateMachine.State.IDLE, runtime.currentState())
        assertTrue(listener.events.contains("idleUi"))
        // H2 (#192): cancel abandons the pipeline timeline.
        assertNull(runtime.pipelineTiming.consume())

        // A transcription callback landing after the cancel carries the stale token and must
        // not deliver anything.
        runtime.handleTranscriptionResult("late result", token = 1)
        idleMainLooper()
        assertTrue(listener.delivered.isEmpty())
    }

    @Test
    fun `cancel releases lease for another runtime`() {
        runtime.onTap()
        runtime.onTap()
        runtime.cancelTranscription()
        idleMainLooper()

        val secondListener = RecordingListener()
        val secondEngines = mutableListOf<FakeRecordingEngine>()
        val secondRuntime = DictationRuntime(app, secondListener, leaseRegistry) { cacheDir, stateMachine ->
            FakeRecordingEngine(cacheDir, stateMachine).also { secondEngines += it }
        }
        secondRuntime.onTap()

        assertEquals(RecordingStateMachine.State.RECORDING, secondRuntime.currentState())
        assertEquals(1, secondEngines.size)
    }

    @Test
    fun `cancel with delayed reader teardown blocks another runtime until old reader finishes`() {
        runtime.onTap()
        val stalledEngine = engines.single().also { it.teardownCompleted = false }
        runtime.onTap()
        runtime.cancelTranscription()

        val contenderListener = RecordingListener()
        val contenderEngines = mutableListOf<FakeRecordingEngine>()
        val contender = DictationRuntime(app, contenderListener, leaseRegistry) { cacheDir, stateMachine ->
            FakeRecordingEngine(cacheDir, stateMachine).also { contenderEngines += it }
        }
        contender.onTap()
        assertEquals(RecordingStateMachine.State.IDLE, contender.currentState())
        assertTrue(contenderEngines.isEmpty())

        stalledEngine.finishAs(null, RecordingEngine.StopReason.USER, superseded = true)
        contender.onTap()
        assertEquals(RecordingStateMachine.State.RECORDING, contender.currentState())
        assertEquals(1, contenderEngines.size)
    }

    @Test
    fun `cancel while idle or recording is a no-op`() {
        runtime.cancelTranscription()
        assertEquals(RecordingStateMachine.State.IDLE, runtime.currentState())
        runtime.onTap()
        runtime.cancelTranscription()
        assertEquals(RecordingStateMachine.State.RECORDING, runtime.currentState())
        assertFalse(listener.events.contains("idleUi"))
    }

    // --- restart during an active session (#193 / #66) ---

    @Test
    fun `restart after cancel starts a fresh engine and the new session works end to end`() {
        runtime.onTap()
        runtime.onTap()
        runtime.cancelTranscription()
        idleMainLooper()
        listener.events.clear()

        runtime.onTap() // new recording: second fake engine
        assertEquals(2, engines.size)
        assertEquals(RecordingStateMachine.State.RECORDING, runtime.currentState())
        runtime.onTap() // stop tap: guard was superseded by cancel (2), so this mints token 3
        runtime.handleTranscriptionResult("second dictation", token = 3)
        idleMainLooper()

        assertEquals("deliver:second dictation", listener.events.first { it.startsWith("deliver") })
        assertEquals(RecordingStateMachine.State.IDLE, runtime.currentState())
    }

    @Test
    fun `stale async callback after cancel and same-runtime restart cannot release new lease`() {
        runtime.onTap()
        runtime.onTap()
        runtime.cancelTranscription()
        runtime.onTap()

        runtime.handleTranscriptionResult("stale old result", token = 1)
        idleMainLooper()

        val contenderListener = RecordingListener()
        val contenderEngines = mutableListOf<FakeRecordingEngine>()
        val contender = DictationRuntime(app, contenderListener, leaseRegistry) { cacheDir, stateMachine ->
            FakeRecordingEngine(cacheDir, stateMachine).also { contenderEngines += it }
        }
        contender.onTap()

        assertEquals(RecordingStateMachine.State.RECORDING, runtime.currentState())
        assertEquals(RecordingStateMachine.State.IDLE, contender.currentState())
        assertTrue(contenderEngines.isEmpty())
        assertTrue(contenderListener.events.isEmpty())
    }

    @Test
    fun `late finish from a superseded recording is discarded and does not disturb the new session`() {
        runtime.onTap()
        val firstEngine = engines.single()
        runtime.onTap() // TRANSCRIBING, token 1
        runtime.cancelTranscription() // back to IDLE; token superseded
        idleMainLooper()

        runtime.onTap() // new recording, second engine
        listener.events.clear()

        // The first engine's stalled reader finally drains -- superseded, exactly the #193
        // scenario. Its handoff must discard: state stays RECORDING for the new session and no
        // listener transition fires.
        val stalePcm = realSizedPcm()
        firstEngine.finishAs(stalePcm, RecordingEngine.StopReason.USER, superseded = true)
        idleMainLooper()

        assertEquals(RecordingStateMachine.State.RECORDING, runtime.currentState())
        assertTrue("no listener transitions from the stale finish", listener.events.isEmpty())
        assertTrue(listener.delivered.isEmpty())
    }

    @Test
    fun `late finish from old runtime cannot release newer runtime lease`() {
        runtime.onTap()
        val oldEngine = engines.single()
        runtime.onTap()
        runtime.cancelTranscription()
        idleMainLooper()

        val newerListener = RecordingListener()
        val newerRuntime = DictationRuntime(app, newerListener, leaseRegistry) { cacheDir, stateMachine ->
            FakeRecordingEngine(cacheDir, stateMachine)
        }
        newerRuntime.onTap()

        oldEngine.finishAs(realSizedPcm(), RecordingEngine.StopReason.USER, superseded = true)
        idleMainLooper()

        val contenderListener = RecordingListener()
        val contenderEngines = mutableListOf<FakeRecordingEngine>()
        val contender = DictationRuntime(app, contenderListener, leaseRegistry) { cacheDir, stateMachine ->
            FakeRecordingEngine(cacheDir, stateMachine).also { contenderEngines += it }
        }
        contender.onTap()

        assertEquals(RecordingStateMachine.State.RECORDING, newerRuntime.currentState())
        assertEquals(RecordingStateMachine.State.IDLE, contender.currentState())
        assertTrue(contenderListener.events.isEmpty())
        assertTrue(contenderEngines.isEmpty())
    }

    @Test
    fun `tokenless finish while recording is discarded and the ui recovers to idle`() {
        runtime.onTap() // RECORDING; no stop tap, so no token exists
        val pcm = realSizedPcm()
        // Reader drains without a token and without a MAX_DURATION reason (mic error / raced
        // states, #66/#90): the reader self-claims TRANSCRIBING, then main-thread resolution
        // sees activeToken == 0 in TRANSCRIBING -> DISCARD_AND_RESET: delete the audio and walk
        // the machine back to IDLE so the ring isn't stuck busy forever.
        engines.single().finishAs(pcm, RecordingEngine.StopReason.USER)
        // Not yet resolved: resolution happens on the main looper.
        idleMainLooper()

        assertFalse("discarded recording's PCM must be deleted", pcm.exists())
        assertTrue(listener.delivered.isEmpty())
        assertEquals(RecordingStateMachine.State.IDLE, runtime.currentState())
        assertTrue("the #90 reset walks the ring back to idle", listener.events.contains("idleUi"))
    }

    // --- max-duration auto-stop mints its own token (#115) ---

    @Test
    fun `max duration stop mints a token and enters transcribing ui`() {
        val pcm = realSizedPcm()
        runtime.onTap() // RECORDING; no stop tap
        engines.single().finishAs(pcm, RecordingEngine.StopReason.MAX_DURATION)
        idleMainLooper() // main-thread mint + transcribing UI + background dispatch spawn

        assertTrue(listener.events.contains("transcribingUi"))

        // The background dispatch (default settings -> "local model still downloading" reset)
        // finishes asynchronously; poll it to IDLE so the test asserts the full funnel.
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline &&
            runtime.currentState() != RecordingStateMachine.State.IDLE
        ) {
            idleMainLooper()
            Thread.sleep(10)
        }
        assertEquals(RecordingStateMachine.State.IDLE, runtime.currentState())
        assertFalse(pcm.exists())
    }

    // --- failed recorder start ---

    @Test
    fun `exception during start setup releases lease`() {
        runtime = DictationRuntime(app, listener, leaseRegistry) { _, _ ->
            throw IllegalStateException("setup failed")
        }

        assertThrows(IllegalStateException::class.java) { runtime.onTap() }

        val nextRuntime = DictationRuntime(app, RecordingListener(), leaseRegistry) { cacheDir, stateMachine ->
            FakeRecordingEngine(cacheDir, stateMachine)
        }
        nextRuntime.onTap()
        assertEquals(RecordingStateMachine.State.RECORDING, nextRuntime.currentState())
    }

    @Test
    fun `failed engine start leaves runtime idle and fires no recording callbacks`() {
        runtime = DictationRuntime(app, listener, leaseRegistry) { cacheDir, stateMachine ->
            FakeRecordingEngine(cacheDir, stateMachine).also { it.startResult = false; engines += it }
        }
        runtime.onTap()

        assertEquals(RecordingStateMachine.State.IDLE, runtime.currentState())
        assertEquals(listOf("startRequested"), listener.events)
        assertFalse(listener.events.contains("recordingStarted"))

        val nextRuntime = DictationRuntime(app, RecordingListener(), leaseRegistry) { cacheDir, stateMachine ->
            FakeRecordingEngine(cacheDir, stateMachine)
        }
        nextRuntime.onTap()
        assertEquals(RecordingStateMachine.State.RECORDING, nextRuntime.currentState())
    }

    // --- shutdown ---

    @Test
    fun `shutdown from recording forces the state machine to idle`() {
        runtime.onTap()
        assertEquals(RecordingStateMachine.State.RECORDING, runtime.currentState())
        runtime.shutdown()
        assertEquals(RecordingStateMachine.State.IDLE, runtime.currentState())
        assertTrue("shutdown runs the streaming teardown half", listener.events.contains("streamingTeardown"))

        val nextRuntime = DictationRuntime(app, RecordingListener(), leaseRegistry) { cacheDir, stateMachine ->
            FakeRecordingEngine(cacheDir, stateMachine)
        }
        nextRuntime.onTap()
        assertEquals(RecordingStateMachine.State.RECORDING, nextRuntime.currentState())
    }

    @Test
    fun `asynchronous shutdown posts streaming teardown listener to main`() {
        runtime.beginShutdown()
        val worker = Thread { runtime.finishShutdownAndReleaseTranscribers() }
        worker.start()
        worker.join(2000)
        idleMainLooper()

        assertFalse(worker.isAlive)
        assertEquals(listOf(Looper.getMainLooper()), listener.streamingTeardownLoopers)
    }

    @Test
    fun `shutdown timeout retains lease until reader finish confirms microphone teardown`() {
        runtime.onTap()
        val stalledEngine = engines.single().also { it.teardownCompleted = false }

        runtime.shutdown()

        val contenderListener = RecordingListener()
        val contenderEngines = mutableListOf<FakeRecordingEngine>()
        val contender = DictationRuntime(app, contenderListener, leaseRegistry) { cacheDir, stateMachine ->
            FakeRecordingEngine(cacheDir, stateMachine).also { contenderEngines += it }
        }
        contender.onTap()
        assertEquals(RecordingStateMachine.State.IDLE, contender.currentState())
        assertTrue(contenderEngines.isEmpty())

        stalledEngine.finishAs(null, RecordingEngine.StopReason.USER, superseded = true)
        contender.onTap()
        assertEquals(RecordingStateMachine.State.RECORDING, contender.currentState())
        assertEquals(1, contenderEngines.size)
    }

    @Test
    fun `shutdown invalidation is immediate while microphone lease waits for reader teardown`() {
        runtime.onTap()
        val stalledEngine = engines.single().also { it.teardownCompleted = false }

        runtime.beginShutdown()

        assertEquals(RecordingStateMachine.State.IDLE, runtime.currentState())
        val contender = DictationRuntime(app, RecordingListener(), leaseRegistry) { cacheDir, stateMachine ->
            FakeRecordingEngine(cacheDir, stateMachine)
        }
        contender.onTap()
        assertEquals(RecordingStateMachine.State.IDLE, contender.currentState())

        stalledEngine.finishAs(null, RecordingEngine.StopReason.USER, superseded = true)
        contender.onTap()
        assertEquals(RecordingStateMachine.State.RECORDING, contender.currentState())
    }

    @Test
    fun `authoritative live final uses existing delivery path and suppresses batch exactly once`() {
        val liveFactory = FakeCloudLiveFactory()
        var fallbacks = 0
        runtime = DictationRuntime(
            app, listener, leaseRegistry,
            cloudLiveFactory = liveFactory,
            onCloudLiveBatchFallback = { fallbacks++ },
        ) { cacheDir, stateMachine -> FakeRecordingEngine(cacheDir, stateMachine).also { engines += it } }
        val pcm = realSizedPcm()

        runtime.onTap()
        val session = liveFactory.sessions.single()
        assertEquals(1, session.connects)
        assertEquals(1, session.starts)
        val reused = byteArrayOf(1, 2, 3, 4)
        engines.single().onChunk!!(reused, 3)
        reused.fill(9)
        assertArrayEquals(byteArrayOf(1, 2, 3), session.pcm.single())
        session.interim("hel")
        idleMainLooper()
        assertTrue(listener.events.contains("cloud:hel"))

        runtime.onTap()
        assertEquals(1, session.ends)
        engines.single().finishAs(pcm, RecordingEngine.StopReason.USER)
        session.complete(CloudLiveTerminal.Success("hello live", CloudLiveTiming(1, finalAtMs = 2)))
        idleMainLooper()
        val deliveryDeadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deliveryDeadline && listener.delivered.isEmpty()) {
            idleMainLooper(); Thread.sleep(10)
        }

        assertEquals(listOf("hello live"), listener.delivered.map { it.text })
        assertEquals(0, fallbacks)
        assertFalse(pcm.exists())
        assertTrue("a claimed live success must release the socket", session.closes >= 1)
        assertEquals(RecordingStateMachine.State.IDLE, runtime.currentState())
        session.complete(CloudLiveTerminal.Failure(CloudLiveFailureReason.NETWORK_ERROR, "late", CloudLiveTiming(1)))
        idleMainLooper()
        assertEquals(1, listener.delivered.size)
        assertEquals(0, fallbacks)
    }

    @Test
    fun `live send failure without callback reaches bounded preserved pcm fallback once`() {
        val liveFactory = FakeCloudLiveFactory().apply { acceptPcm = false }
        var fallbacks = 0
        runtime = DictationRuntime(
            app, listener, leaseRegistry,
            cloudLiveFactory = liveFactory,
            onCloudLiveBatchFallback = { fallbacks++ },
        ) { cacheDir, stateMachine -> FakeRecordingEngine(cacheDir, stateMachine).also { engines += it } }
        val pcm = realSizedPcm()

        runtime.onTap()
        engines.single().onChunk!!(byteArrayOf(1, 2), 2)
        assertFalse(liveFactory.sessions.single().lastSendAccepted)
        runtime.onTap()
        engines.single().finishAs(pcm, RecordingEngine.StopReason.USER)
        idleMainLooper()
        shadowOf(Looper.getMainLooper()).idleFor(2_501, TimeUnit.MILLISECONDS)

        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline && runtime.currentState() != RecordingStateMachine.State.IDLE) {
            idleMainLooper(); Thread.sleep(10)
        }
        assertEquals(1, fallbacks)
        assertFalse(pcm.exists())
        liveFactory.sessions.single().complete(CloudLiveTerminal.Success("late", CloudLiveTiming(1, finalAtMs = 2)))
        idleMainLooper()
        assertEquals(1, fallbacks)
        assertTrue(listener.delivered.isEmpty())
    }

    @Test
    fun `shutdown cancels cloud live session before late callbacks`() {
        val liveFactory = FakeCloudLiveFactory()
        runtime = DictationRuntime(app, listener, leaseRegistry, cloudLiveFactory = liveFactory) {
            cacheDir, stateMachine -> FakeRecordingEngine(cacheDir, stateMachine).also { engines += it }
        }
        runtime.onTap()
        val session = liveFactory.sessions.single()
        runtime.beginShutdown()
        assertEquals(1, session.cancels)
        session.interim("late")
        session.complete(CloudLiveTerminal.Success("late", CloudLiveTiming(1, finalAtMs = 2)))
        idleMainLooper()
        assertFalse(listener.events.contains("cloud:late"))
        assertTrue(listener.delivered.isEmpty())
    }

    // --- cloud-live attempt lifetime: no exit path may orphan an authenticated socket ---

    /**
     * Every path that abandons a recording without going through [DictationRuntime.resetToIdle]
     * used to leave `cloudLiveAttempt` untouched. That is not a benign leak: `setupTimeout` is
     * cancelled once setup lands and `finalTimeout` is only armed by `endActivity()`, so an
     * abandoned attempt has NEITHER timeout armed and nothing ever calls `close()`/`cancel()` --
     * an authenticated WSS carrying the user's microphone audio stays open with no reference
     * held anywhere, one per cycle.
     */
    private fun cloudLiveRuntime(liveFactory: FakeCloudLiveFactory, onFallback: () -> Unit = {}) {
        runtime = DictationRuntime(
            app, listener, leaseRegistry,
            cloudLiveFactory = liveFactory,
            onCloudLiveBatchFallback = onFallback,
        ) { cacheDir, stateMachine -> FakeRecordingEngine(cacheDir, stateMachine).also { engines += it } }
    }

    @Test
    fun `discarded reader handoff cancels the cloud live session instead of orphaning it`() {
        val liveFactory = FakeCloudLiveFactory()
        cloudLiveRuntime(liveFactory)

        runtime.onTap()
        val session = liveFactory.sessions.single()
        // A superseded reader drain takes onRecordingFinished's `result.discarded` early return:
        // the lease is released and the method returns without ever touching the attempt.
        engines.single().finishAs(realSizedPcm(), RecordingEngine.StopReason.USER, superseded = true)
        idleMainLooper()

        assertEquals("the abandoned live session must be cancelled", 1, session.cancels)
        assertTrue("and its socket closed", session.closes >= 1)
    }

    @Test
    fun `late recording resolved as discard cancels the cloud live session`() {
        val liveFactory = FakeCloudLiveFactory()
        cloudLiveRuntime(liveFactory)
        val pcm = realSizedPcm()

        runtime.onTap()
        val session = liveFactory.sessions.single()
        // Reader drains with no token yet (#66), so resolution is posted to main...
        engines.single().finishAs(pcm, RecordingEngine.StopReason.USER)
        // ...and before it runs, a non-serialized host teardown resets the state machine and the
        // guard without going through resetToIdle, so the resolution lands on DISCARD.
        runtime.finishShutdownAndReleaseTranscribers()
        idleMainLooper()

        assertFalse("discarded audio is still deleted", pcm.exists())
        assertEquals("the abandoned live session must be cancelled", 1, session.cancels)
    }

    @Test
    fun `cloud live handoff that arrives too late to be claimed cancels its session`() {
        val liveFactory = FakeCloudLiveFactory()
        cloudLiveRuntime(liveFactory)
        val pcm = realSizedPcm()

        runtime.onTap(); runtime.onTap() // TRANSCRIBING, token 1
        val session = liveFactory.sessions.single()
        // Reader handoff posts continueWithCloudLiveOrBatch's main-thread arbitration...
        engines.single().finishAs(pcm, RecordingEngine.StopReason.USER)
        // ...and the lease/guard go stale underneath it, so it takes the discard branch with
        // cloudLiveAttempt still pointing at this attempt and no finalWait ever armed.
        runtime.finishShutdownAndReleaseTranscribers()
        idleMainLooper()

        assertFalse(pcm.exists())
        assertEquals("the permanently-inert attempt must be cancelled", 1, session.cancels)
    }

    @Test
    fun `max duration auto stop that cannot mint a token cancels the cloud live session`() {
        val liveFactory = FakeCloudLiveFactory()
        cloudLiveRuntime(liveFactory)
        val pcm = realSizedPcm()

        runtime.onTap()
        val session = liveFactory.sessions.single()
        engines.single().finishAs(pcm, RecordingEngine.StopReason.MAX_DURATION)
        runtime.finishShutdownAndReleaseTranscribers()
        idleMainLooper()

        assertFalse(pcm.exists())
        assertEquals("the abandoned live session must be cancelled", 1, session.cancels)
    }

    @Test
    fun `max duration auto stop with cloud live active ends activity and delivers the live final`() {
        val liveFactory = FakeCloudLiveFactory()
        var fallbacks = 0
        cloudLiveRuntime(liveFactory) { fallbacks++ }
        val pcm = realSizedPcm()

        runtime.onTap() // RECORDING; no stop tap -- the duration cap is the trigger
        val session = liveFactory.sessions.single()
        engines.single().finishAs(pcm, RecordingEngine.StopReason.MAX_DURATION)
        idleMainLooper() // main-thread token mint + endActivity + transcribing UI

        assertTrue(listener.events.contains("transcribingUi"))
        assertEquals("the cap must end the live activity, exactly as a stop tap does", 1, session.ends)

        session.complete(CloudLiveTerminal.Success("capped live", CloudLiveTiming(1, finalAtMs = 2)))
        awaitDelivery()

        assertEquals(listOf("capped live"), listener.delivered.map { it.text })
        assertEquals(0, fallbacks)
        assertFalse(pcm.exists())
        assertTrue("a claimed live success must release the socket", session.closes >= 1)
        assertEquals(RecordingStateMachine.State.IDLE, runtime.currentState())
    }

    @Test
    fun `a restart after an abandoned recording never leaves the incumbent attempt uncancelled`() {
        val liveFactory = FakeCloudLiveFactory()
        cloudLiveRuntime(liveFactory)

        runtime.onTap()
        val first = liveFactory.sessions.single()
        // Abandon this recording down a path that does NOT funnel through resetToIdle: the reader
        // drains tokenless, the machine is already back at IDLE, so resolution lands on DISCARD
        // and the lease is released with the attempt untouched.
        engines.single().finishAs(realSizedPcm(), RecordingEngine.StopReason.USER)
        runtime.finishShutdownAndReleaseTranscribers()
        idleMainLooper()

        // The next dictation overwrites `cloudLiveAttempt`; whatever the incumbent was, it must
        // already have been cancelled rather than having its last reference silently dropped.
        runtime.onTap()
        idleMainLooper()
        assertEquals(2, liveFactory.sessions.size)
        val second = liveFactory.sessions.last()

        assertEquals("the superseded attempt must never outlive the restart", 1, first.cancels)
        assertEquals("and the new one must still be live", 0, second.cancels)
    }

    private fun awaitDelivery() {
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline && listener.delivered.isEmpty()) {
            idleMainLooper(); Thread.sleep(10)
        }
    }

    @Test
    fun `live failure keeps preserved pcm and the batch fallback actually delivers its transcript`() {
        // The point of the fallback is that the preserved PCM reaches a real batch call -- a
        // fallback counter alone would pass just as happily if the handover had passed a deleted
        // file, a stale lease or a superseded token. Stub a provider and assert the transcript.
        stubBatchProvider("batch rescue")
        val liveFactory = FakeCloudLiveFactory()
        var fallbacks = 0
        cloudLiveRuntime(liveFactory) { fallbacks++ }
        val pcm = realSizedPcm()

        runtime.onTap(); runtime.onTap()
        engines.single().finishAs(pcm, RecordingEngine.StopReason.USER)
        val session = liveFactory.sessions.single()
        session.complete(CloudLiveTerminal.Failure(CloudLiveFailureReason.NETWORK_ERROR, "drop", CloudLiveTiming(1)))
        awaitDelivery()

        assertEquals(1, fallbacks)
        assertEquals(listOf("batch rescue"), listener.delivered.map { it.text })
        assertFalse("the batch call consumed and deleted the preserved PCM", pcm.exists())
        assertTrue("the abandoned live socket must be released", session.closes >= 1)
        awaitIdle()

        // A late live success cannot resurrect a second delivery.
        session.complete(CloudLiveTerminal.Success("late", CloudLiveTiming(1, finalAtMs = 2)))
        idleMainLooper()
        assertEquals(1, fallbacks)
        assertEquals(1, listener.delivered.size)
    }

    @Test
    fun `a live terminal arriving before the reader handoff is resolved by the handoff itself`() {
        // Both existing tests call finishAs before complete(), so resolveCloudLiveAttempt is only
        // ever re-entered from onTerminal. This is the other ordering: the terminal lands first
        // and parks, and continueWithCloudLiveOrBatch's own resolve call is what claims it.
        val liveFactory = FakeCloudLiveFactory()
        var fallbacks = 0
        cloudLiveRuntime(liveFactory) { fallbacks++ }
        val pcm = realSizedPcm()

        runtime.onTap(); runtime.onTap()
        val session = liveFactory.sessions.single()
        session.complete(CloudLiveTerminal.Success("early final", CloudLiveTiming(1, finalAtMs = 2)))
        idleMainLooper()
        assertTrue("a terminal alone cannot claim before reader handoff", listener.delivered.isEmpty())

        engines.single().finishAs(pcm, RecordingEngine.StopReason.USER)
        awaitDelivery()

        assertEquals(listOf("early final"), listener.delivered.map { it.text })
        assertEquals(0, fallbacks)
        assertFalse(pcm.exists())
        assertTrue(session.closes >= 1)
        awaitIdle()
    }

    @Test
    fun `a terminal landing as the final wait expires still yields exactly one delivery`() {
        stubBatchProvider("batch rescue")
        val liveFactory = FakeCloudLiveFactory()
        var fallbacks = 0
        cloudLiveRuntime(liveFactory) { fallbacks++ }
        val pcm = realSizedPcm()

        runtime.onTap(); runtime.onTap()
        val session = liveFactory.sessions.single()
        engines.single().finishAs(pcm, RecordingEngine.StopReason.USER)
        idleMainLooper() // arms the bounded final wait

        // The wait expires and the live final lands in the same main-looper turn: whichever wins,
        // exactly one of them may claim the preserved PCM.
        shadowOf(Looper.getMainLooper()).idleFor(2_501, TimeUnit.MILLISECONDS)
        session.complete(CloudLiveTerminal.Success("racing final", CloudLiveTiming(1, finalAtMs = 2)))
        awaitDelivery()
        awaitIdle()

        assertEquals("the timeout claimed it; the live final must not double-deliver", 1, fallbacks)
        assertEquals(listOf("batch rescue"), listener.delivered.map { it.text })
        assertFalse(pcm.exists())
    }

    /** Points the batch pipeline at a MockWebServer returning [transcript], so the fallback's
     *  handover is proven by a real delivery instead of an empty-delivery tautology. */
    private fun stubBatchProvider(transcript: String) {
        batchServer.enqueue(MockResponse().setBody(JSONObject().put("text", transcript).toString()))
        val base = batchServer.url("/v1").toString().trimEnd('/')
        ProviderChainStore.save(
            app,
            ProviderChain(listOf(ProviderChainEntry(ProviderKind.OPENAI, "gpt-5.4-mini", baseUrlOverride = base, transcriptionModel = "gpt-transcribe"))),
        )
        ProviderCredentialStore.set(app, ProviderKind.OPENAI, "test-batch-key")
        app.getSharedPreferences("ramblr", android.content.Context.MODE_PRIVATE).edit()
            .putBoolean("use_local", false).apply()
        PostProcessingToggle.setEnabled(app, false)
    }

    private fun awaitIdle() {
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline && runtime.currentState() != RecordingStateMachine.State.IDLE) {
            idleMainLooper(); Thread.sleep(10)
        }
        assertEquals(RecordingStateMachine.State.IDLE, runtime.currentState())
    }

    private class FakeCloudLiveFactory : CloudLiveTranscriptionSessionFactory {
        val sessions = mutableListOf<FakeCloudLiveSession>()
        var acceptPcm = true
        override fun create(listener: CloudLiveTranscriptionListener): CloudLiveTranscriptionSession =
            FakeCloudLiveSession(listener, acceptPcm).also(sessions::add)
    }

    private class FakeCloudLiveSession(
        private val listener: CloudLiveTranscriptionListener,
        private val acceptPcm: Boolean,
    ) : CloudLiveTranscriptionSession {
        var connects = 0
        var starts = 0
        var ends = 0
        var cancels = 0
        var closes = 0
        var lastSendAccepted = true
        val pcm = mutableListOf<ByteArray>()
        override fun connect() { connects++ }
        override fun startActivity(): Boolean { starts++; return true }
        override fun sendPcm(buffer: ByteArray, length: Int): Boolean {
            pcm += buffer.copyOf(length)
            lastSendAccepted = acceptPcm
            return acceptPcm
        }
        override fun endActivity(): Boolean { ends++; return true }
        override fun cancel() { cancels++ }
        override fun close() { closes++ }
        fun interim(text: String) = listener.onInterim(text, CloudLiveTiming(1, firstInterimAtMs = 2))
        fun complete(result: CloudLiveTerminal) = listener.onTerminal(result)
    }
}
