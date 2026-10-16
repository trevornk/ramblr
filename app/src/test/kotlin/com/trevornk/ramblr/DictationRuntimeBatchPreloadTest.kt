package com.trevornk.ramblr

import android.Manifest
import android.app.Application
import android.content.Context
import android.os.Looper
import java.io.File
import java.util.Collections
import kotlin.concurrent.thread
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
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
 * #310: the on-device batch recognizer is preloaded only when the user's settings can ever route
 * a dictation to it, switching settings re-evaluates that, and every path that needs the model
 * later still loads it on demand (#280) instead of failing.
 *
 * Native recognizers are faked through [TranscriberHooks]; the off-main hop is a queue the test
 * drains by hand so listener-triggered loads are observed deterministically.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DictationRuntimeBatchPreloadTest {

    private lateinit var app: Application
    private lateinit var runtime: DictationRuntime
    private lateinit var server: MockWebServer
    private val engines = mutableListOf<FakeEngine>()
    private val delivered = Collections.synchronizedList(mutableListOf<String>())
    private val offMain = ArrayDeque<() -> Unit>()
    private val localCreated = Collections.synchronizedList(mutableListOf<LocalTranscriber>())
    private val localReleased = Collections.synchronizedList(mutableListOf<LocalTranscriber>())
    private val decoded = Collections.synchronizedList(mutableListOf<LocalTranscriber>())

    private val prefs get() = app.getSharedPreferences("ramblr", Context.MODE_PRIVATE)

    private val cloudOnlyChain = ProviderChain(
        listOf(
            ProviderChainEntry(ProviderKind.GEMINI, "gemini-2.5-flash"),
            ProviderChainEntry(ProviderKind.OPENAI, "gpt-5.4-mini"),
        ),
    )

    private inner class Listener : RuntimeListener {
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
        ) { delivered += text }
        override fun foregroundPackageName(): String? = null
    }

    private class FakeEngine(cacheDir: File, private val stateMachine: RecordingStateMachine) :
        RecordingEngine(cacheDir, stateMachine) {
        var onFinished: ((Result) -> Unit)? = null
        override fun start(onFinished: (Result) -> Unit, onChunk: (ByteArray, Int) -> Unit): Boolean {
            this.onFinished = onFinished
            return stateMachine.tryStartRecording()
        }
        override fun awaitTeardown(timeoutMs: Long): Boolean = true
        override fun isReaderTeardownPending(): Boolean = false
        fun finish(pcm: File) {
            stateMachine.tryStartTranscribing()
            onFinished!!(Result(pcmFile = pcm, bytesRecorded = pcm.length(), discarded = false, stopReason = StopReason.USER))
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> allocateWithoutConstructor(type: Class<T>): T {
        val field = Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe").apply { isAccessible = true }
        val unsafe = field.get(null)
        return unsafe.javaClass.getMethod("allocateInstance", Class::class.java).invoke(unsafe, type) as T
    }

    private fun newRuntime(runOffMain: (() -> Unit) -> Unit) {
        runtime = DictationRuntime(
            app,
            Listener(),
            InMemoryDictationSessionLeaseRegistry(),
            transcriberHooks = TranscriberHooks(
                createLocal = { _, _ -> allocateWithoutConstructor(LocalTranscriber::class.java).also { localCreated += it } },
                releaseLocal = { localReleased += it },
                decodeLocal = { t, _, _ -> decoded += t; "on-device transcript" },
                runOffMain = runOffMain,
            ),
            engineFactory = { cacheDir, stateMachine -> FakeEngine(cacheDir, stateMachine).also { engines += it } },
        )
    }

    @Before
    fun setUp() {
        app = RuntimeEnvironment.getApplication()
        shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO)
        server = MockWebServer().apply { start() }
        // transcribeLocal enqueues the optional VAD model download (#139); WorkManager must exist.
        if (!androidx.work.WorkManager.isInitialized()) {
            androidx.work.WorkManager.initialize(app, androidx.work.Configuration.Builder().build())
        }
        prefs.edit().putString("model_name", "fake-model").commit()
        ProviderChainStore.save(app, cloudOnlyChain)
        newRuntime { offMain.addLast(it) }
    }

    @After
    fun tearDown() {
        runtime.shutdown()
        server.shutdown()
    }

    private fun drainOffMain() {
        while (offMain.isNotEmpty()) offMain.removeFirst()()
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun setCloudOnly() { prefs.edit().putBoolean("use_local", false).commit(); drainOffMain() }

    // --- preload gate ---

    @Test
    fun `preload is skipped when transcription is cloud-only`() {
        setCloudOnly()
        runtime.initLocalModel()
        drainOffMain()
        assertTrue("cloud-only setup must not create the batch recognizer", localCreated.isEmpty())
    }

    @Test
    fun `preload is skipped when cloud-only even with allow_cloud_fallback on`() {
        prefs.edit().putBoolean("allow_cloud_fallback", true).commit()
        setCloudOnly()
        runtime.initLocalModel()
        assertTrue(localCreated.isEmpty())
    }

    @Test
    fun `preload happens when use_local is on`() {
        prefs.edit().putBoolean("use_local", true).commit()
        runtime.initLocalModel()
        assertEquals(1, localCreated.size)
    }

    @Test
    fun `preload happens with the default use_local`() {
        runtime.initLocalModel()
        assertEquals(1, localCreated.size)
    }

    @Test
    fun `preload is skipped for a LOCAL candidate in a cloud-first chain`() {
        // A LOCAL entry is only reached after every cloud provider fails; it loads on demand.
        ProviderChainStore.save(app, cloudOnlyChain.withLocalFloor("some-model"))
        setCloudOnly()
        runtime.initLocalModel()
        drainOffMain()
        assertTrue(localCreated.isEmpty())
    }

    // --- settings changes ---

    @Test
    fun `switching to local loads the recognizer`() {
        setCloudOnly()
        runtime.initLocalModel()
        assertTrue(localCreated.isEmpty())

        prefs.edit().putBoolean("use_local", true).commit()
        drainOffMain()

        assertEquals(1, localCreated.size)
    }

    @Test
    fun `adding a LOCAL candidate to the chain does not load the recognizer`() {
        setCloudOnly()
        runtime.initLocalModel()
        ProviderChainStore.save(app, cloudOnlyChain.withLocalFloor("some-model"))
        prefs.edit().putBoolean("allow_local_fallback", true).commit()
        drainOffMain()
        runtime.initLocalModel()
        assertTrue(localCreated.isEmpty())
    }

    @Test
    fun `switching to cloud-only releases a loaded recognizer`() {
        runtime.initLocalModel()
        assertEquals(1, localCreated.size)

        setCloudOnly()

        assertEquals(localCreated.toList(), localReleased.toList())
    }

    @Test
    fun `unrelated chain edits do not reload an already loaded recognizer`() {
        runtime.initLocalModel()
        assertEquals(1, localCreated.size)

        ProviderChainStore.save(app, cloudOnlyChain.withLocalFloor("another-model"))
        prefs.edit().putBoolean("allow_local_fallback", true).commit()
        drainOffMain()

        assertEquals("a loaded recognizer must not be rebuilt by a settings write", 1, localCreated.size)
        assertTrue(localReleased.isEmpty())
    }

    @Test
    fun `a shut down runtime ignores later settings changes`() {
        setCloudOnly()
        runtime.shutdown()
        prefs.edit().putBoolean("use_local", true).commit()
        drainOffMain()
        assertTrue(localCreated.isEmpty())
    }

    // --- on-demand load of a LOCAL fallback candidate (#280 path) ---

    @Test
    fun `a LOCAL fallback reached after a failing cloud candidate loads on demand and transcribes`() {
        // Cloud first (fails with a server error), LOCAL as the floor; the recognizer was never
        // preloaded -- the state after a trim, an idle unload, or a skipped preload.
        File(app.filesDir, "models/fake-model").apply { mkdirs() }.also { ModelDownloader.completeMarker(it).writeText("") }
        val base = server.url("/v1").toString().trimEnd('/')
        ProviderChainStore.save(
            app,
            ProviderChain(
                listOf(ProviderChainEntry(ProviderKind.OPENAI, "gpt-5.4-mini", baseUrlOverride = base, transcriptionModel = "gpt-transcribe")),
            ).withLocalFloor("fake-model"),
        )
        ProviderCredentialStore.setLegacyByKind(app, ProviderKind.OPENAI, "test-key")
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = MockResponse().setResponseCode(500).setBody("{}")
        }
        prefs.edit().putBoolean("use_local", false).commit()
        newRuntime { thread { it() } }
        assertTrue("nothing is preloaded", localCreated.isEmpty())

        val pcm = File.createTempFile("rec_", ".pcm", app.cacheDir).apply { writeBytes(ByteArray(32_000)) }
        runtime.onTap(); runtime.onTap()
        engines.last().finish(pcm)
        val deadline = System.currentTimeMillis() + 15_000
        while (System.currentTimeMillis() < deadline && delivered.isEmpty()) {
            shadowOf(Looper.getMainLooper()).idle(); Thread.sleep(10)
        }

        assertEquals("toast=${ShadowToast.getTextOfLatestToast()}", listOf("on-device transcript"), delivered.toList())
        assertEquals("loaded on demand, exactly once", 1, localCreated.size)
        assertEquals("the on-demand recognizer is the one that decoded", localCreated.toList(), decoded.toList())
        val toast = ShadowToast.getTextOfLatestToast()
        assertTrue("must not report a model download: $toast", toast == null || !toast.contains("still downloading"))
        assertNotEquals(LocalModelUnavailability.NOT_INSTALLED, toast)
    }

    @Test
    fun `use_local with a skipped preload still loads on demand at dictation time`() {
        File(app.filesDir, "models/fake-model").apply { mkdirs() }.also { ModelDownloader.completeMarker(it).writeText("") }
        prefs.edit().putBoolean("use_local", true).commit()
        newRuntime { thread { it() } }

        val pcm = File.createTempFile("rec_", ".pcm", app.cacheDir).apply { writeBytes(ByteArray(32_000)) }
        runtime.onTap(); runtime.onTap()
        engines.last().finish(pcm)
        val deadline = System.currentTimeMillis() + 15_000
        while (System.currentTimeMillis() < deadline && delivered.isEmpty()) {
            shadowOf(Looper.getMainLooper()).idle(); Thread.sleep(10)
        }

        assertEquals("toast=${ShadowToast.getTextOfLatestToast()}", listOf("on-device transcript"), delivered.toList())
        assertFalse(localCreated.isEmpty())
    }
}
