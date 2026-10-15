package com.trevornk.ramblr

import android.Manifest
import android.app.Application
import android.content.ComponentCallbacks2
import android.content.Context
import android.os.Looper
import java.io.File
import java.time.Duration
import java.util.Collections
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * #305: the on-device recognizers are released after [DictationRuntime.TRANSCRIBER_IDLE_UNLOAD_MS]
 * of idleness, and the release can never clobber a model a later reload installed.
 *
 * Native recognizers are faked through [TranscriberHooks]: instances are allocated without
 * running the (native-backed) constructor and only ever compared by identity. The off-main hop is
 * a queue the test drains by hand, so release-vs-reload interleavings are chosen, not raced.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DictationRuntimeIdleUnloadTest {

    private lateinit var app: Application
    private lateinit var runtime: DictationRuntime
    private var lastEngine: FakeRecordingEngine? = null
    private val offMain = ArrayDeque<() -> Unit>()
    private val localCreated = Collections.synchronizedList(mutableListOf<LocalTranscriber>())
    private val localReleased = Collections.synchronizedList(mutableListOf<LocalTranscriber>())
    private val streamingCreated = Collections.synchronizedList(mutableListOf<StreamingTranscriber>())
    private val streamingReleased = Collections.synchronizedList(mutableListOf<StreamingTranscriber>())

    private class NoopListener : RuntimeListener {
        override fun onRecordingStartRequested() {}
        override fun onRecordingStartFailed() {}
        override fun onRecordingStarted() {}
        override fun onEnterTranscribingUi() {}
        override fun onIdleUi() {}
        override fun onStreamingTeardown() {}
        override fun onStreamingPartial(text: String) {}
        override fun onCloudLiveInterim(text: String) {}
        override fun deliverText(
            text: String,
            rawText: String?,
            paidFallbackGroup: CleanupStepGroup?,
            cleanupError: String?,
            feedbackDurationMs: Long,
        ) {}
        override fun foregroundPackageName(): String? = null
    }

    private class FakeRecordingEngine(
        cacheDir: File,
        private val stateMachine: RecordingStateMachine,
    ) : RecordingEngine(cacheDir, stateMachine) {
        /** Simulates an AudioRecord reader that is still draining after a cancel. */
        var readerTeardownPending = false
        var onFinished: ((Result) -> Unit)? = null

        override fun start(onFinished: (Result) -> Unit, onChunk: (ByteArray, Int) -> Unit): Boolean {
            this.onFinished = onFinished
            return stateMachine.tryStartRecording()
        }
        override fun awaitTeardown(timeoutMs: Long): Boolean = true
        override fun isReaderTeardownPending(): Boolean = readerTeardownPending
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> allocateWithoutConstructor(type: Class<T>): T {
        val field = Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe").apply { isAccessible = true }
        val unsafe = field.get(null)
        return unsafe.javaClass.getMethod("allocateInstance", Class::class.java).invoke(unsafe, type) as T
    }

    @Before
    fun setUp() {
        app = RuntimeEnvironment.getApplication()
        shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO)
        app.getSharedPreferences("ramblr", Context.MODE_PRIVATE).edit()
            .putString("model_name", "fake-model")
            .putBoolean("streaming_preview_enabled", false)
            .commit()
        val streamingDir = ModelDownloader.modelDir(app, STREAMING_MODEL)
        streamingDir.mkdirs()
        ModelDownloader.completeMarker(streamingDir).writeText("ok")

        val hooks = TranscriberHooks(
            createLocal = { _, _ -> allocateWithoutConstructor(LocalTranscriber::class.java).also { localCreated += it } },
            releaseLocal = { localReleased += it },
            createStreaming = { _, _ ->
                allocateWithoutConstructor(StreamingTranscriber::class.java).also { streamingCreated += it }
            },
            releaseStreaming = { streamingReleased += it },
            runOffMain = { offMain.addLast(it) },
        )
        runtime = DictationRuntime(
            app,
            NoopListener(),
            InMemoryDictationSessionLeaseRegistry(),
            transcriberHooks = hooks,
            engineFactory = { cacheDir, stateMachine ->
                FakeRecordingEngine(cacheDir, stateMachine).also { lastEngine = it }
            },
        )
    }

    private val mainLooper get() = shadowOf(Looper.getMainLooper())

    private fun advance(duration: Long, unit: TimeUnit) = mainLooper.idleFor(duration, unit)

    private fun runOffMainInOrder() {
        while (offMain.isNotEmpty()) offMain.removeFirst()()
    }

    /** Runs the queued off-main work with the first task LAST: the worst-case scheduling. */
    private fun runOffMainFirstTaskLast() {
        val tasks = offMain.toList()
        offMain.clear()
        tasks.drop(1).forEach { it() }
        tasks.first()()
    }

    /** Loads both recognizers the way service-connect does, then lets the arm-after-load post run. */
    private fun loadBoth() {
        runtime.initLocalModel()
        runtime.initStreamingModel()
        mainLooper.idle()
    }

    /** Streaming is off by default: its fake cannot serve beginSession/endSession (native stream),
     *  so only tests that never start a recording or shut down enable it. */
    private fun enableStreaming() {
        app.getSharedPreferences("ramblr", Context.MODE_PRIVATE).edit()
            .putBoolean("streaming_preview_enabled", true).commit()
    }

    private fun loadedLocal() = localCreated.filter { it !in localReleased }
    private fun loadedStreaming() = streamingCreated.filter { it !in streamingReleased }

    /** TranscriptionGuard: stop mints N, resetToIdle's cancel supersedes with N+1, so the next stop mints N+2. */
    private var nextToken = 1

    private fun dictateOnce() {
        val token = nextToken
        nextToken += 2
        runtime.onTap()
        runtime.onTap() // stop -> TRANSCRIBING
        runtime.handleTranscriptionResult("hello", token)
        mainLooper.idle()
        assertEquals(RecordingStateMachine.State.IDLE, runtime.currentState())
    }

    /** True when anything (delayed work included) is still scheduled on the main looper. */
    private fun mainLooperHasPendingWork(): Boolean = mainLooper.nextScheduledTaskTime != Duration.ZERO

    @Test
    fun `five idle minutes after load releases both slots and the next recording reloads them`() {
        loadBoth()
        assertEquals(1, loadedLocal().size)

        advance(4 * 60 + 59, TimeUnit.SECONDS)
        runOffMainInOrder()
        assertEquals("not yet idle long enough", 1, loadedLocal().size)

        advance(2, TimeUnit.SECONDS)
        runOffMainInOrder()
        assertTrue(loadedLocal().isEmpty())
        assertEquals(1, localReleased.size)

        runtime.onTap() // transcribersTrimmed was set, so recording start reloads both
        runOffMainInOrder()
        assertEquals(2, localCreated.size)
        assertEquals(1, loadedLocal().size)
    }

    @Test
    fun `idle unload releases the streaming slot together with the batch slot`() {
        enableStreaming()
        loadBoth()
        assertEquals(1, loadedLocal().size)
        assertEquals(1, loadedStreaming().size)

        advance(5, TimeUnit.MINUTES)
        runOffMainInOrder()

        assertTrue(loadedLocal().isEmpty())
        assertTrue(loadedStreaming().isEmpty())
        assertEquals(1, streamingReleased.size)
    }

    @Test
    fun `streaming release claimed before a reload cannot drop the reloaded streaming model`() {
        enableStreaming()
        loadBoth()
        val old = loadedStreaming().single()

        runtime.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_BACKGROUND) // claims both releases
        runtime.initStreamingModel() // a reload installs before the hopped release runs
        runOffMainInOrder()

        assertSame(streamingCreated.last(), loadedStreaming().single())
        assertFalse(loadedStreaming().single() === old)
        assertEquals(listOf(old), streamingReleased.toList())
    }

    @Test
    fun `starting a recording before the timeout cancels the unload`() {
        loadBoth()
        advance(4, TimeUnit.MINUTES)

        runtime.onTap()
        runOffMainInOrder()

        assertFalse("the pending idle unload must be cancelled when a recording starts", mainLooperHasPendingWork())
        advance(10, TimeUnit.MINUTES)
        runOffMainInOrder()
        assertEquals(RecordingStateMachine.State.RECORDING, runtime.currentState())
        assertEquals(1, loadedLocal().size)
        assertEquals("no reload: nothing was released", 1, localCreated.size)
        assertTrue(localReleased.isEmpty())
    }

    @Test
    fun `each dictation returning to idle restarts the window`() {
        loadBoth()
        advance(4, TimeUnit.MINUTES)

        dictateOnce()

        advance(4 * 60 + 59, TimeUnit.SECONDS) // ~9 minutes since load, ~5 since the dictation
        runOffMainInOrder()
        assertEquals("activity at minute 4 pushed the unload past minute 9", 1, loadedLocal().size)

        advance(2, TimeUnit.SECONDS)
        runOffMainInOrder()
        assertTrue(loadedLocal().isEmpty())

        // And it re-arms again after the post-release reload plus another dictation.
        dictateOnce()
        runOffMainInOrder()
        mainLooper.idle()
        assertEquals(1, loadedLocal().size)
        advance(5, TimeUnit.MINUTES)
        runOffMainInOrder()
        assertTrue(loadedLocal().isEmpty())
    }

    @Test
    fun `never unloads while recording or transcribing`() {
        loadBoth()
        runtime.onTap()
        assertEquals(RecordingStateMachine.State.RECORDING, runtime.currentState())
        advance(5, TimeUnit.MINUTES)
        runOffMainInOrder()
        assertEquals(1, loadedLocal().size)

        runtime.onTap() // TRANSCRIBING; stays here until a result arrives (watchdog is 400s)
        assertEquals(RecordingStateMachine.State.TRANSCRIBING, runtime.currentState())
        advance(1, TimeUnit.MINUTES)
        runOffMainInOrder()
        assertEquals(1, loadedLocal().size)
        assertTrue(localReleased.isEmpty())
    }

    @Test
    fun `a reload that finishes while recording does not arm the unload`() {
        loadBoth()
        advance(5, TimeUnit.MINUTES)
        runOffMainInOrder() // idle unload released everything
        assertTrue(loadedLocal().isEmpty())

        runtime.onTap() // queues the warm-up reloads
        runOffMainInOrder() // reloads complete while RECORDING and post their arm requests
        mainLooper.idle()

        assertFalse("a load completing mid-recording must not start the idle window", mainLooperHasPendingWork())
    }

    @Test
    fun `release claimed then reload installed then release work runs - fresh models survive`() {
        loadBoth()
        val oldLocal = loadedLocal().single()

        advance(5, TimeUnit.MINUTES) // timer fires on main: trimmed set, release queued but NOT run
        assertEquals(1, offMain.size)
        runtime.onTap() // recording start sees the trim flag and queues both reloads behind the release
        assertEquals(3, offMain.size)

        // Worst-case scheduling: both reloads install first, the hopped release runs last.
        runOffMainFirstTaskLast()

        val freshLocal = loadedLocal().single()
        assertFalse(freshLocal === oldLocal)
        assertSame("the reloaded batch model must survive a late release", freshLocal, localCreated.last())
        assertEquals(listOf(oldLocal), localReleased.toList())
    }

    @Test
    fun `release then reload in natural order leaves one loaded model each`() {
        loadBoth()
        advance(5, TimeUnit.MINUTES)
        runtime.onTap()
        runOffMainInOrder()

        assertEquals(1, loadedLocal().size)
        assertEquals(2, localCreated.size)
        assertEquals(1, localReleased.size)
    }

    @Test
    fun `trim memory release has the same ordering guarantee`() {
        loadBoth()
        val oldLocal = loadedLocal().single()

        runtime.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_BACKGROUND)
        runtime.onTap()
        assertEquals(3, offMain.size)
        runOffMainFirstTaskLast()

        assertSame(localCreated.last(), loadedLocal().single())
        assertFalse(loadedLocal().single() === oldLocal)
    }

    @Test
    fun `shutdown cancels the pending unload`() {
        loadBoth()
        advance(1, TimeUnit.MINUTES)

        runtime.shutdown()
        mainLooper.idle()
        offMain.clear()

        assertFalse("shutdown must cancel the pending idle unload", mainLooperHasPendingWork())
        advance(10, TimeUnit.MINUTES)
        runOffMainInOrder()
        assertTrue(localReleased.isEmpty())
    }

    @Test
    fun `firing with nothing loaded is a harmless no-op`() {
        dictateOnce() // nothing loaded, nothing trimmed; still arms the window on return to idle
        advance(10, TimeUnit.MINUTES)
        runOffMainInOrder()
        assertTrue(localReleased.isEmpty())
        runtime.onTap()
        runOffMainInOrder()
        assertTrue("no spurious reload when nothing was ever released", localCreated.isEmpty())
    }

    @Test
    fun `cancel while the reader is still draining still arms the unload once the lease is released`() {
        loadBoth()
        runtime.onTap()
        runtime.onTap() // TRANSCRIBING
        val engine = lastEngine!!
        engine.readerTeardownPending = true // cancel lands while AudioRecord is still draining

        runtime.cancelTranscription() // resetToIdle: IDLE, but the lease stays held for the reader
        mainLooper.idle()
        assertEquals(RecordingStateMachine.State.IDLE, runtime.currentState())

        advance(1, TimeUnit.MINUTES) // reader finishes: discarded handoff releases the lease
        engine.readerTeardownPending = false
        engine.onFinished!!(
            RecordingEngine.Result(
                pcmFile = null,
                bytesRecorded = 0,
                discarded = true,
                stopReason = RecordingEngine.StopReason.USER,
            ),
        )
        mainLooper.idle()
        runOffMainInOrder()
        assertEquals("still inside the idle window", 1, loadedLocal().size)

        advance(DictationRuntime.TRANSCRIBER_IDLE_UNLOAD_MS, TimeUnit.MILLISECONDS)
        runOffMainInOrder()
        assertTrue("recognizers must be released after the drained-cancel path", loadedLocal().isEmpty())
        assertEquals(1, localReleased.size)
    }
}
