package com.trevornk.ramblr

import android.Manifest
import android.app.Application
import android.os.Looper
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    private lateinit var runtime: DictationRuntime

    /** Records every listener callback in arrival order, so ordering assertions are direct. */
    private class RecordingListener : RuntimeListener {
        val events = mutableListOf<String>()
        val delivered = mutableListOf<Delivery>()

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
        override fun onStreamingTeardown() { events += "streamingTeardown" }
        override fun onStreamingPartial(text: String) { events += "partial:$text" }
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

        override fun start(onFinished: (Result) -> Unit, onChunk: (ByteArray, Int) -> Unit): Boolean {
            if (!startResult) return false
            if (!stateMachine.tryStartRecording()) return false
            this.onFinished = onFinished
            this.onChunk = onChunk
            return true
        }

        override fun awaitTeardown(timeoutMs: Long) {}

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
        runtime = DictationRuntime(app, listener) { cacheDir, stateMachine ->
            FakeRecordingEngine(cacheDir, stateMachine).also { engines += it }
        }
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
    fun `failed engine start leaves runtime idle and fires no recording callbacks`() {
        runtime = DictationRuntime(app, listener) { cacheDir, stateMachine ->
            FakeRecordingEngine(cacheDir, stateMachine).also { it.startResult = false; engines += it }
        }
        runtime.onTap()

        assertEquals(RecordingStateMachine.State.IDLE, runtime.currentState())
        assertEquals(listOf("startRequested"), listener.events)
        assertFalse(listener.events.contains("recordingStarted"))
    }

    // --- shutdown ---

    @Test
    fun `shutdown from recording forces the state machine to idle`() {
        runtime.onTap()
        assertEquals(RecordingStateMachine.State.RECORDING, runtime.currentState())
        runtime.shutdown()
        assertEquals(RecordingStateMachine.State.IDLE, runtime.currentState())
        assertTrue("shutdown runs the streaming teardown half", listener.events.contains("streamingTeardown"))
    }
}
