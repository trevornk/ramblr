package com.trevornk.ramblr

import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.HttpUrl.Companion.toHttpUrl
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
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit

class GeminiCloudLiveTranscriptionClientTest {
    private lateinit var server: MockWebServer
    private lateinit var scheduler: ScheduledThreadPoolExecutor

    @Before fun setUp() {
        server = MockWebServer()
        server.start()
        scheduler = ScheduledThreadPoolExecutor(1)
    }

    @After fun tearDown() {
        scheduler.shutdownNow()
        server.shutdown()
    }

    @Test fun `setup is exact verbatim live transcription shape`() {
        val setup = GeminiCloudLiveTranscriptionClient.setupJson(
            model = "gemini-3.5-transcribe-live",
            languageCodes = listOf("en-US"),
            customVocabulary = listOf("Ramblr", "HitSnooze"),
        )
        val root = JSONObject(setup)
        assertEquals(setOf("setup"), root.keySet())
        val body = root.getJSONObject("setup")
        assertEquals("models/gemini-3.5-transcribe-live", body.getString("model"))
        assertEquals(listOf("TEXT"), body.getJSONObject("generationConfig").getJSONArray("responseModalities").toStringList())
        val transcription = body.getJSONObject("inputAudioTranscription")
        assertEquals(listOf("en-US"), transcription.getJSONArray("languageCodes").toStringList())
        assertEquals(listOf("Ramblr", "HitSnooze"), transcription.getJSONArray("customVocabulary").toStringList())
        assertEquals("VERBATIM", transcription.getString("mode"))
        assertTrue(body.getJSONObject("realtimeInputConfig").getJSONObject("automaticActivityDetection").getBoolean("disabled"))
    }

    @Test fun `endpoint model language and vocabulary validation rejects unsafe values`() {
        assertThrows(IllegalArgumentException::class.java) { factory(endpoint = server.url("/wrong")) }
        assertThrows(IllegalArgumentException::class.java) {
            GeminiCloudLiveTranscriptionClient(
                endpoint = "https://evil.example${GeminiCloudLiveTranscriptionClient.ENDPOINT_PATH}".toHttpUrl(),
                apiKey = "key",
            )
        }
        assertThrows(IllegalArgumentException::class.java) { factory(model = "models/bad?key=secret") }
        assertThrows(IllegalArgumentException::class.java) { factory(languages = listOf("not a language!")) }
        assertThrows(IllegalArgumentException::class.java) { factory(vocabulary = listOf("")) }
        assertThrows(IllegalArgumentException::class.java) { factory(vocabulary = (1..1001).map { "term-$it" }) }
    }

    @Test fun `handshake gates activity and copied pcm then preserves wire order`() {
        val received = Collections.synchronizedList(mutableListOf<String>())
        val allMessages = CountDownLatch(4)
        server.enqueue(MockResponse().withWebSocketUpgrade(object : ClosingWebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                received += text
                if (received.size == 1) webSocket.send("{\"setupComplete\":{}}")
                allMessages.countDown()
            }
        }))
        val terminal = RecordingListener()
        val session = factory().create(terminal)
        val pcm = byteArrayOf(1, 2, 3, 4)

        session.connect()
        session.startActivity()
        assertTrue(session.sendPcm(pcm, pcm.size))
        pcm.fill(9)
        session.endActivity()

        assertTrue(allMessages.await(5, TimeUnit.SECONDS))
        val upgrade = server.takeRequest(5, TimeUnit.SECONDS)!!
        assertEquals("/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent", upgrade.path)
        assertEquals("test-key", upgrade.getHeader("x-goog-api-key"))
        assertFalse(upgrade.path!!.contains("test-key"))
        assertEquals(setOf("setup"), JSONObject(received[0]).keySet())
        assertTrue(JSONObject(received[1]).getJSONObject("realtimeInput").has("activityStart"))
        val audio = JSONObject(received[2]).getJSONObject("realtimeInput").getJSONObject("audio")
        assertEquals("audio/pcm;rate=16000", audio.getString("mimeType"))
        assertEquals("AQIDBA==", audio.getString("data"))
        assertTrue(JSONObject(received[3]).getJSONObject("realtimeInput").has("activityEnd"))
        assertNull(terminal.result)
        session.cancel()
    }

    @Test fun `interim and authoritative final parse and terminal callback exactly once`() {
        val listener = RecordingListener()
        val socket = scriptedSocket(
            "{\"setupComplete\":{}}",
            "{\"serverContent\":{\"interimInputTranscription\":{\"text\":\"hel\"}}}",
            "{\"serverContent\":{\"inputTranscription\":{\"text\":\"hello\"}}}",
            "{\"serverContent\":{\"inputTranscription\":{\"text\":\"late\"}}}",
        )
        val session = factory().create(listener)
        session.connect(); session.startActivity(); session.endActivity()

        assertTrue(listener.done.await(5, TimeUnit.SECONDS))
        Thread.sleep(50)
        assertEquals(listOf("hel"), listener.interims)
        assertEquals("hello", (listener.result as CloudLiveTerminal.Success).text)
        assertEquals(1, listener.terminals)
        assertTrue((listener.result as CloudLiveTerminal.Success).timing.setupCompletedAtMs != null)
        assertTrue((listener.result as CloudLiveTerminal.Success).timing.finalAtMs != null)
        socket.awaitMessages()
    }

    @Test fun `server error malformed event close and send failure are terminal once and redact key`() {
        val secret = "credential-value"
        val cases = listOf(
            "{\"error\":{\"message\":\"bad key $secret\"}}",
            "not-json",
        )
        cases.forEach { event ->
            val listener = RecordingListener()
            scriptedSocket("{\"setupComplete\":{}}", event)
            val session = factory(apiKey = secret).create(listener)
            session.connect(); session.startActivity()
            assertTrue(listener.done.await(5, TimeUnit.SECONDS))
            val failure = listener.result as CloudLiveTerminal.Failure
            assertFalse(failure.message.contains(secret))
            assertEquals(1, listener.terminals)
        }
    }

    @Test fun `cancel is terminal once and late messages cannot escape`() {
        val listener = RecordingListener()
        scriptedSocket("{\"setupComplete\":{}}", "{\"serverContent\":{\"inputTranscription\":{\"text\":\"late\"}}}", delayMs = 150)
        val session = factory().create(listener)
        session.connect(); session.startActivity(); session.cancel(); session.close()
        assertTrue(listener.done.await(5, TimeUnit.SECONDS))
        Thread.sleep(250)
        assertEquals(CloudLiveFailureReason.CANCELLED, (listener.result as CloudLiveTerminal.Failure).reason)
        assertEquals(1, listener.terminals)
    }

    @Test fun `setup and final timeouts fail with distinct reasons`() {
        val setupListener = RecordingListener()
        server.enqueue(MockResponse().withWebSocketUpgrade(object : ClosingWebSocketListener() {}))
        factory(setupTimeoutMs = 40).create(setupListener).connect()
        assertTrue(setupListener.done.await(5, TimeUnit.SECONDS))
        assertEquals(CloudLiveFailureReason.SETUP_TIMEOUT, (setupListener.result as CloudLiveTerminal.Failure).reason)

        val finalListener = RecordingListener()
        scriptedSocket("{\"setupComplete\":{}}")
        val session = factory(finalTimeoutMs = 40).create(finalListener)
        session.connect(); session.startActivity(); session.endActivity()
        assertTrue(finalListener.done.await(5, TimeUnit.SECONDS))
        assertEquals(CloudLiveFailureReason.FINAL_TIMEOUT, (finalListener.result as CloudLiveTerminal.Failure).reason)
    }

    private fun factory(
        apiKey: String = "test-key",
        endpoint: okhttp3.HttpUrl = server.url("/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent"),
        model: String = "gemini-3.5-transcribe-live",
        languages: List<String> = listOf("en-US"),
        vocabulary: List<String> = listOf("Ramblr"),
        setupTimeoutMs: Long = 2_000,
        finalTimeoutMs: Long = 2_000,
    ) = GeminiCloudLiveTranscriptionClient(
        httpClient = OkHttpClient(),
        endpoint = endpoint,
        apiKey = apiKey,
        model = model,
        languageCodes = languages,
        customVocabulary = vocabulary,
        callbackExecutor = Executor { it.run() },
        scheduler = scheduler,
        setupTimeoutMs = setupTimeoutMs,
        finalTimeoutMs = finalTimeoutMs,
        allowTestEndpoint = true,
    )

    private fun scriptedSocket(vararg events: String, delayMs: Long = 0): ServerScript {
        val script = ServerScript(events.toList(), delayMs)
        server.enqueue(MockResponse().withWebSocketUpgrade(script))
        return script
    }

    private open class ClosingWebSocketListener : WebSocketListener() {
        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(code, reason)
        }
    }

    private class ServerScript(private val events: List<String>, private val delayMs: Long) : ClosingWebSocketListener() {
        private val received = CountDownLatch(1)
        override fun onOpen(webSocket: WebSocket, response: Response) {
            Thread {
                if (delayMs > 0) Thread.sleep(delayMs)
                events.forEach { webSocket.send(it) }
            }.start()
        }
        override fun onMessage(webSocket: WebSocket, text: String) { received.countDown() }
        fun awaitMessages() { assertTrue(received.await(5, TimeUnit.SECONDS)) }
    }

    private class RecordingListener : CloudLiveTranscriptionListener {
        val interims = Collections.synchronizedList(mutableListOf<String>())
        val done = CountDownLatch(1)
        @Volatile var result: CloudLiveTerminal? = null
        @Volatile var terminals = 0
        override fun onInterim(text: String, timing: CloudLiveTiming) { interims += text }
        override fun onTerminal(result: CloudLiveTerminal) { terminals++; this.result = result; done.countDown() }
    }

    private fun org.json.JSONArray.toStringList() = (0 until length()).map { getString(it) }
}
