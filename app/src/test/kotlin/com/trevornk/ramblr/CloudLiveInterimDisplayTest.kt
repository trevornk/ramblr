package com.trevornk.ramblr

import android.Manifest
import android.app.Application
import android.os.Looper
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * End to end through the real [DictationRuntime]: Gemini's cumulative interims intermittently lose
 * the space at a revised seam (`send areport`, `schema.Run`); the runtime must hand every listener
 * -- accessibility field, bubble and IME alike, they all sit behind
 * [RuntimeListener.onCloudLiveInterim] -- the repaired text.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CloudLiveInterimDisplayTest {

    private lateinit var app: Application
    private lateinit var listener: RecordingListener
    private lateinit var engines: MutableList<FakeRecordingEngine>
    private lateinit var factory: FakeCloudLiveFactory
    private lateinit var runtime: DictationRuntime

    private class RecordingListener : RuntimeListener {
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
        ) = Unit
        override fun foregroundPackageName(): String? = null
    }

    @Before fun setUp() {
        app = RuntimeEnvironment.getApplication()
        shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO)
        listener = RecordingListener()
        engines = mutableListOf()
        factory = FakeCloudLiveFactory()
        runtime = DictationRuntime(app, listener, InMemoryDictationSessionLeaseRegistry(), cloudLiveFactory = { factory }) {
            cacheDir, stateMachine -> FakeRecordingEngine(cacheDir, stateMachine).also { engines += it }
        }
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    @Test fun `glued seams in Gemini interims reach the listener repaired`() {
        runtime.onTap()
        val session = factory.sessions.single()
        val timing = CloudLiveTiming(connectStartedAtMs = 1_000, firstInterimAtMs = 1_100)
        // Captured from gemini-3.5-transcribe-live (synthesized speech).
        session.interim("Okay, so basically what I want to do is send a report to Dyson and Adalyn and", timing)
        session.interim("Okay, so basically what I want to do is send areport to Dyson and Adalyn and then a", timing)
        session.interim("We need to update the database schema.Run the migration", timing)
        idle()

        assertEquals(
            listOf(
                "Okay, so basically what I want to do is send a report to Dyson and Adalyn and",
                "Okay, so basically what I want to do is send a report to Dyson and Adalyn and then a",
                "We need to update the database schema. Run the migration",
            ),
            listener.interims,
        )
    }

    private class FakeCloudLiveFactory : CloudLiveTranscriptionSessionFactory {
        val sessions = mutableListOf<FakeCloudLiveSession>()
        override fun create(listener: CloudLiveTranscriptionListener): CloudLiveTranscriptionSession =
            FakeCloudLiveSession(listener).also(sessions::add)
    }

    private class FakeCloudLiveSession(private val listener: CloudLiveTranscriptionListener) : CloudLiveTranscriptionSession {
        override fun connect() = Unit
        override fun startActivity(): Boolean = true
        override fun sendPcm(buffer: ByteArray, length: Int): Boolean = true
        override fun endActivity(): Boolean = true
        override fun cancel() = Unit
        override fun close() = Unit
        fun interim(text: String, timing: CloudLiveTiming) = listener.onInterim(text, timing)
    }

    private class FakeRecordingEngine(cacheDir: File, private val stateMachine: RecordingStateMachine) :
        RecordingEngine(cacheDir, stateMachine) {
        var onFinished: ((Result) -> Unit)? = null
        override fun start(onFinished: (Result) -> Unit, onChunk: (ByteArray, Int) -> Unit): Boolean {
            if (!stateMachine.tryStartRecording()) return false
            this.onFinished = onFinished
            return true
        }
        override fun awaitTeardown(timeoutMs: Long): Boolean = true
        override fun isReaderTeardownPending(): Boolean = false
        fun finishAs(pcmFile: File?, stopReason: StopReason) {
            stateMachine.tryStartTranscribing()
            onFinished!!(Result(pcmFile = pcmFile, bytesRecorded = 0L, discarded = true, stopReason = stopReason))
        }
    }
}
