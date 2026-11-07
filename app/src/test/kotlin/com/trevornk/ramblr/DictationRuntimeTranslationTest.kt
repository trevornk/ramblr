package com.trevornk.ramblr

import android.Manifest
import android.app.Application
import android.content.Context
import android.os.Looper
import java.io.File
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import org.junit.After
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
 * The keyboard's translation through the real [DictationRuntime]: runs on the first cloud cleanup
 * step even with an on-device step ahead of it, and when nothing can translate the text goes in
 * untranslated with a clear message instead of silently skipping the translation.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DictationRuntimeTranslationTest {

    private lateinit var app: Application
    private lateinit var server: MockWebServer
    private lateinit var listener: Listener
    private lateinit var runtime: DictationRuntime

    private class Listener : RuntimeListener {
        var target: String? = null
        val delivered = mutableListOf<Pair<String, String?>>() // text, cleanupError
        val messages = mutableListOf<String>()
        override fun onRecordingStartRequested() {}
        override fun onRecordingStartFailed() {}
        override fun onRecordingStarted() {}
        override fun onEnterTranscribingUi() {}
        override fun onIdleUi() {}
        override fun onStreamingTeardown() {}
        override fun onStreamingPartial(text: String) {}
        override fun onCloudLiveInterim(text: String) {}
        override fun onUserMessage(message: String) { messages += message }
        override fun deliverText(text: String, rawText: String?, paidFallbackGroup: CleanupStepGroup?, cleanupError: String?, feedbackDurationMs: Long) {
            delivered += text to cleanupError
        }
        override fun foregroundPackageName(): String? = null
        override fun translationTarget(): String? = target
    }

    private class Engine(cacheDir: File, sm: RecordingStateMachine) : RecordingEngine(cacheDir, sm) {
        override fun start(onFinished: (Result) -> Unit, onChunk: (ByteArray, Int) -> Unit): Boolean = sm.tryStartRecording()
        private val sm = sm
        override fun awaitTeardown(timeoutMs: Long): Boolean = true
        override fun isReaderTeardownPending(): Boolean = false
    }

    private val bodies = mutableListOf<String>()

    @Before fun setUp() {
        app = RuntimeEnvironment.getApplication()
        shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO)
        server = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    bodies += request.body!!.utf8()
                    return MockResponse.Builder().body("""{"choices":[{"message":{"content":"Hallo zusammen, die Lieferung ist spät."}}]}""").build()
                }
            }
            start()
        }
        app.getSharedPreferences("ramblr", Context.MODE_PRIVATE).edit().clear()
            .putBoolean("use_local", false)
            .putBoolean(KeyboardTranslationReadiness.KEY_CONSENT, true).apply()
        // Secure credential storage outlives the shared prefs reset, and other tests leave keys in it.
        ProviderKind.values().forEach { ProviderCredentialStore.clearLegacyByKind(app, it) }
        PostProcessingToggle.setEnabled(app, false)
        listener = Listener()
        runtime = DictationRuntime(app, listener, InMemoryDictationSessionLeaseRegistry()) { dir, sm -> Engine(dir, sm) }
    }

    @After fun tearDown() {
        server.close()
        ProviderKind.values().forEach { ProviderCredentialStore.clearLegacyByKind(app, it) }
    }

    private fun base() = server.url("/v1").toString().trimEnd('/')

    private fun saveChain(vararg entries: ProviderChainEntry) = ProviderChainStore.save(app, ProviderChain(entries.toList()))

    private fun dictate(text: String) {
        runtime.onTap(); runtime.onTap()
        runtime.handleTranscriptionResult(text, token = 1)
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline && listener.delivered.isEmpty()) {
            shadowOf(Looper.getMainLooper()).idle(); Thread.sleep(10)
        }
    }

    @Test fun `a local step ahead of a cloud step does not swallow the translation`() {
        saveChain(
            ProviderChainEntry(ProviderKind.LOCAL, "on-device"),
            ProviderChainEntry(ProviderKind.OPENAI, "gpt-4o-mini", baseUrlOverride = base()),
        )
        ProviderCredentialStore.setLegacyByKind(app, ProviderKind.OPENAI, "k")
        listener.target = "German"

        dictate("hello everyone the delivery is late")

        assertEquals("Hallo zusammen, die Lieferung ist spät.", listener.delivered.single().first)
        assertNull(listener.delivered.single().second)
        assertEquals(1, bodies.size)
        assertTrue("translation prompt reached the cloud step", bodies.single().contains("German"))
        assertTrue(listener.messages.isEmpty())
    }

    @Test fun `no cloud credential inserts the text untranslated and says why`() {
        saveChain(
            ProviderChainEntry(ProviderKind.LOCAL, "on-device"),
            ProviderChainEntry(ProviderKind.OPENAI, "gpt-4o-mini", baseUrlOverride = base()),
        )
        listener.target = "German"

        dictate("hello everyone the delivery is late")

        assertEquals("hello everyone the delivery is late", listener.delivered.single().first)
        assertEquals(0, bodies.size)
        assertEquals(listOf(app.getString(R.string.ime_translate_unavailable_cloud)), listener.messages)
    }

    @Test fun `missing off-device consent inserts untranslated and asks for consent`() {
        app.getSharedPreferences("ramblr", Context.MODE_PRIVATE).edit()
            .putBoolean("use_local", true).putBoolean(KeyboardTranslationReadiness.KEY_CONSENT, false).apply()
        saveChain(ProviderChainEntry(ProviderKind.OPENAI, "gpt-4o-mini", baseUrlOverride = base()))
        ProviderCredentialStore.setLegacyByKind(app, ProviderKind.OPENAI, "k")
        listener.target = "German"

        dictate("hello everyone the delivery is late")

        assertEquals("hello everyone the delivery is late", listener.delivered.single().first)
        assertEquals(0, bodies.size)
        assertEquals(listOf(app.getString(R.string.ime_translate_unavailable_consent)), listener.messages)
    }

    @Test fun `without a translate target nothing is sent and nothing changes`() {
        saveChain(ProviderChainEntry(ProviderKind.OPENAI, "gpt-4o-mini", baseUrlOverride = base()))
        ProviderCredentialStore.setLegacyByKind(app, ProviderKind.OPENAI, "k")
        listener.target = null

        dictate("hello everyone the delivery is late")

        assertEquals("hello everyone the delivery is late", listener.delivered.single().first)
        assertEquals(0, bodies.size)
        assertFalse(listener.messages.isNotEmpty())
    }
}
