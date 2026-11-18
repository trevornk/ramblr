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
 * #385: [DictationRuntime] is the single start/stop path for the ring, the IME mic and the
 * shortcut/tile/automation triggers, so wiring [DictationHaptics] here covers all of them.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class HapticsRuntimeWiringTest {

    private lateinit var app: Application
    private lateinit var engines: MutableList<Fake>
    private val ticks = mutableListOf<String>()
    private var resets = 0

    private class Fake(cacheDir: File, private val sm: RecordingStateMachine) : RecordingEngine(cacheDir, sm) {
        var startResult = true
        var onFinished: ((Result) -> Unit)? = null
        override fun start(onFinished: (Result) -> Unit, onChunk: (ByteArray, Int) -> Unit): Boolean {
            if (!startResult || !sm.tryStartRecording()) return false
            this.onFinished = onFinished
            return true
        }
        override fun awaitTeardown(timeoutMs: Long) = true
        override fun isReaderTeardownPending() = false
        fun finish(reason: StopReason) {
            sm.tryStartTranscribing()
            onFinished!!(Result(null, 0L, false, reason))
        }
    }

    private val noopListener = object : RuntimeListener {
        override fun onRecordingStartRequested() {}
        override fun onRecordingStartFailed() {}
        override fun onRecordingStarted() { ticks += "ui-started" }
        override fun onEnterTranscribingUi() {}
        override fun onIdleUi() {}
        override fun onStreamingTeardown() {}
        override fun onStreamingPartial(text: String) {}
        override fun onCloudLiveInterim(text: String) {}
        override fun deliverText(
            text: String, rawText: String?, paidFallbackGroup: CleanupStepGroup?,
            cleanupError: String?, feedbackDurationMs: Long,
        ) {}
        override fun foregroundPackageName(): String? = null
    }

    private val haptics = object : HapticFeedback {
        override fun onRecordingStarted() { ticks += "start" }
        override fun onRecordingStopped() { ticks += "stop" }
        override fun reset() { resets++ }
    }

    @Before fun setUp() {
        app = RuntimeEnvironment.getApplication()
        shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO)
        engines = mutableListOf()
    }

    private fun runtime(startOk: Boolean = true) = DictationRuntime(
        app, noopListener, InMemoryDictationSessionLeaseRegistry(), haptics = haptics,
    ) { dir, sm -> Fake(dir, sm).also { it.startResult = startOk; engines += it } }

    @Test fun `successful start ticks once after the UI callback`() {
        runtime().onTap()
        assertEquals(listOf("ui-started", "start"), ticks)
    }

    @Test fun `failed engine start never ticks`() {
        runtime(startOk = false).onTap()
        assertEquals(emptyList<String>(), ticks)
    }

    @Test fun `permission denied never ticks`() {
        shadowOf(app).denyPermissions(Manifest.permission.RECORD_AUDIO)
        runtime().onTap()
        assertEquals(emptyList<String>(), ticks)
    }

    @Test fun `stop tap ticks stop`() {
        val r = runtime()
        r.onTap(); r.onTap()
        assertEquals(listOf("ui-started", "start", "stop"), ticks)
    }

    @Test fun `silence auto-stop path ticks stop through stopAndTranscribe`() {
        val r = runtime()
        r.startRecording(); r.stopAndTranscribe()
        assertEquals(listOf("ui-started", "start", "stop"), ticks)
    }

    @Test fun `max duration stop ticks stop once`() {
        val r = runtime()
        r.onTap()
        engines[0].finish(RecordingEngine.StopReason.MAX_DURATION)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(listOf("ui-started", "start", "stop"), ticks)
    }

    @Test fun `repeated taps around a stop yield exactly one stop tick`() {
        val r = runtime()
        r.onTap(); r.onTap(); r.stopAndTranscribe(); r.onTap()
        assertEquals(1, ticks.count { it == "stop" })
    }

    @Test fun `tap stop then max duration finish yields exactly one stop tick`() {
        val r = runtime()
        r.onTap(); r.onTap()
        engines[0].finish(RecordingEngine.StopReason.MAX_DURATION)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1, ticks.count { it == "stop" })
    }

    @Test fun `shutdown resets haptics`() {
        val r = runtime()
        r.onTap()
        r.beginShutdown()
        assertEquals(1, resets)
    }

    @Test fun `error stop does not tick`() {
        val r = runtime()
        r.onTap()
        engines[0].finish(RecordingEngine.StopReason.ERROR)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(listOf("ui-started", "start"), ticks)
    }
}
