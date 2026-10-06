package com.trevornk.ramblr

import android.Manifest
import android.app.Application
import android.os.Looper
import java.io.File
import java.time.Duration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * #284: the runtime half of background dictation -- when [DictationRuntime] asks for the
 * (lazy) foreground-service hold, when it lets go, and which terminal outcomes it reports as failures.
 * Drives the real runtime through the same fake capture boundary DictationRuntimeTest uses.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BackgroundDictationRuntimeTest {

    private lateinit var app: Application
    private lateinit var work: RecordingWork
    private lateinit var listener: Listener
    private lateinit var engines: MutableList<FakeEngine>
    private lateinit var runtime: DictationRuntime

    private class RecordingWork : BackgroundWork {
        var begins = 0
        var ends = 0
        val held get() = begins - ends
        override fun begin() { begins++ }
        override fun end() { ends++ }
    }

    private class Listener(private val work: RecordingWork) : RuntimeListener {
        val events = mutableListOf<String>()
        val failures = mutableListOf<BackgroundFailure>()
        /** hold count observed at each moment the host would get a failure. */
        val heldAtFailure = mutableListOf<Int>()
        override fun onRecordingStartRequested() {}
        override fun onRecordingStartFailed() {}
        override fun onRecordingStarted() {}
        override fun onEnterTranscribingUi() { events += "transcribing" }
        override fun onIdleUi() { events += "idle" }
        override fun onStreamingTeardown() {}
        override fun onStreamingPartial(text: String) {}
        override fun deliverText(text: String, rawText: String?, paidFallbackGroup: CleanupStepGroup?, cleanupError: String?, feedbackDurationMs: Long) {
            events += "deliver"
        }
        override fun foregroundPackageName(): String? = null
        override fun onDictationFailed(failure: BackgroundFailure) {
            events += "failed:$failure"
            failures += failure
            heldAtFailure += work.held
        }
    }

    private class FakeEngine(cacheDir: File, private val sm: RecordingStateMachine) : RecordingEngine(cacheDir, sm) {
        var onFinished: ((Result) -> Unit)? = null
        override fun start(onFinished: (Result) -> Unit, onChunk: (ByteArray, Int) -> Unit): Boolean {
            if (!sm.tryStartRecording()) return false
            this.onFinished = onFinished
            return true
        }
        override fun awaitTeardown(timeoutMs: Long): Boolean = true
        override fun isReaderTeardownPending(): Boolean = false
        fun finish(pcm: File?) {
            sm.tryStartTranscribing()
            onFinished!!(Result(pcm, pcm?.length() ?: 0L, discarded = false, stopReason = StopReason.USER))
        }
    }

    @Before fun setUp() {
        app = RuntimeEnvironment.getApplication()
        shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO)
        app.getSharedPreferences("ramblr", android.content.Context.MODE_PRIVATE).edit().clear().apply()
        work = RecordingWork()
        listener = Listener(work)
        engines = mutableListOf()
        runtime = DictationRuntime(
            app, listener, InMemoryDictationSessionLeaseRegistry(),
            backgroundWork = work,
            engineFactory = { dir, sm -> FakeEngine(dir, sm).also { engines += it } },
        )
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun pcm(): File = File.createTempFile("rec_", ".pcm", app.cacheDir).apply { writeBytes(ByteArray(32_000)) }

    // --- hold lifecycle ---

    @Test fun `no hold while merely recording -- the mic is not backgrounded work`() {
        runtime.onTap()
        assertEquals(0, work.begins)
    }

    @Test fun `stay-in-field path never asks for the hold -- no service, notification or icon`() {
        runtime.onTap()
        runtime.onTap()
        assertEquals("the stop tap must not start the hold (lazy hold, ADR-0002)", 0, work.begins)

        runtime.handleTranscriptionResult("hello world", token = 1)
        idle()

        assertEquals("a dictation that never left the field never touches the service", 0, work.begins)
        assertEquals(0, work.ends)
        assertTrue("normal success is not a failure", listener.failures.isEmpty())
    }

    @Test fun `stay-in-field failures and the watchdog never ask for the hold either`() {
        runtime.onTap(); runtime.onTap()
        runtime.handleTranscriptionResult("  ", token = 1) // no speech
        idle()
        runtime.onTap(); runtime.onTap()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(401))
        assertEquals(0, work.begins)
    }

    @Test fun `leaving the host while transcribing begins exactly one hold and a delivered result ends it`() {
        runtime.onTap()
        runtime.onTap()
        runtime.holdForLeavingHost()
        runtime.holdForLeavingHost() // onFinishInput then onWindowHidden: still one hold
        assertEquals(1, work.begins)
        assertEquals(0, work.ends)

        runtime.handleTranscriptionResult("hello world", token = 1)
        idle()

        assertEquals(1, work.begins)
        assertEquals(1, work.ends)
        assertEquals(0, work.held)
    }

    @Test fun `leaving the host when nothing is transcribing is a no-op`() {
        runtime.holdForLeavingHost() // idle
        runtime.onTap()
        runtime.holdForLeavingHost() // recording: the mic is torn down by the host, nothing to hold
        assertEquals(0, work.begins)
    }

    @Test fun `cancel ends the hold and is not reported as a failure`() {
        runtime.onTap()
        runtime.onTap()
        runtime.holdForLeavingHost()
        runtime.cancelTranscription()
        idle()
        assertEquals(0, work.held)
        assertEquals(1, work.ends)
        assertTrue("a user cancel must never produce a failure notification", listener.failures.isEmpty())
    }

    @Test fun `repeated dictations each get a balanced hold`() {
        // Tokens: stop tap mints 1, the next 2, ... (guard.cancel in resetToIdle does not reset them).
        runtime.onTap(); runtime.onTap()
        runtime.holdForLeavingHost()
        runtime.handleTranscriptionResult("first", token = 1)
        idle()
        runtime.onTap(); runtime.onTap()
        runtime.holdForLeavingHost()
        runtime.handleTranscriptionResult("second", token = 3)
        idle()
        assertEquals(2, work.begins)
        assertEquals(2, work.ends)
    }

    @Test fun `shutdown mid-transcription releases the hold`() {
        runtime.onTap()
        runtime.onTap()
        runtime.holdForLeavingHost()
        assertEquals(1, work.held)
        runtime.beginShutdown()
        assertEquals("a destroyed host must not leave the foreground service pinned", 0, work.held)
    }

    @Test fun `a throwing work seam never breaks the pipeline`() {
        val throwing = object : BackgroundWork {
            override fun begin() = error("fgs denied")
            override fun end() = error("fgs denied")
        }
        val rt = DictationRuntime(
            app, listener, InMemoryDictationSessionLeaseRegistry(),
            backgroundWork = throwing,
            engineFactory = { dir, sm -> FakeEngine(dir, sm) },
        )
        rt.onTap()
        rt.onTap()
        rt.holdForLeavingHost()
        rt.handleTranscriptionResult("still delivered", token = 1)
        idle()
        assertTrue(listener.events.contains("deliver"))
        assertEquals(RecordingStateMachine.State.IDLE, rt.currentState())
    }

    // --- failure reporting ---

    @Test fun `blank transcript reports NO_SPEECH before the idle callback, hold still live`() {
        runtime.onTap()
        runtime.onTap()
        runtime.holdForLeavingHost()
        runtime.handleTranscriptionResult("  ", token = 1)
        idle()
        assertEquals(listOf(BackgroundFailure.NO_SPEECH), listener.failures)
        assertEquals("host must be told while the hold is still up", listOf(1), listener.heldAtFailure)
        assertTrue(listener.events.indexOf("failed:NO_SPEECH") < listener.events.indexOf("idle"))
        assertEquals(0, work.held)
    }

    @Test fun `a recording below the speech floor reports NO_SPEECH`() {
        val tiny = File.createTempFile("rec_", ".pcm", app.cacheDir).apply { writeBytes(ByteArray(500)) }
        runtime.onTap()
        runtime.onTap()
        runtime.holdForLeavingHost()
        engines.single().finish(tiny)
        idle()
        assertEquals(listOf(BackgroundFailure.NO_SPEECH), listener.failures)
        assertEquals(0, work.held)
    }

    @Test fun `a chain that cannot transcribe reports FAILED and releases the hold`() {
        // Default prefs: local model not installed and cloud fallback off -> the dispatch gives up.
        runtime.onTap()
        runtime.onTap()
        runtime.holdForLeavingHost()
        engines.single().finish(pcm())
        idle()
        assertEquals(listOf(BackgroundFailure.FAILED), listener.failures)
        assertEquals(0, work.held)
    }

    @Test fun `the 400s watchdog reports TIMED_OUT and releases the hold`() {
        runtime.onTap()
        runtime.onTap()
        runtime.holdForLeavingHost()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(401))
        assertEquals(listOf(BackgroundFailure.TIMED_OUT), listener.failures)
        assertEquals(0, work.held)
        assertEquals(RecordingStateMachine.State.IDLE, runtime.currentState())
    }

    @Test fun `a successful dictation reports nothing`() {
        runtime.onTap()
        runtime.onTap()
        runtime.handleTranscriptionResult("all good", token = 1)
        idle()
        assertTrue(listener.failures.isEmpty())
        assertFalse(listener.events.any { it.startsWith("failed") })
    }
}
