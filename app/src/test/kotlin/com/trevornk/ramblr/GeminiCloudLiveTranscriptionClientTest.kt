package com.trevornk.ramblr

import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.ByteString
import okio.ByteString.Companion.encodeUtf8
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
import java.util.concurrent.Semaphore
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

    /**
     * The blocker this covers: `onSetupComplete` used to flip `setupComplete` and snapshot the
     * buffered queue inside the lock, then flush OUTSIDE it. A recorder-thread `sendPcm` landing
     * in that window saw `setupComplete == true`, took the direct-send branch, and reached the
     * socket AHEAD of the still-unflushed `activityStart`. With
     * `automaticActivityDetection.disabled = true` that audio is dropped by the server, so the
     * ordering contract in [CloudLiveTranscriptionSession] is load-bearing, not cosmetic.
     *
     * The existing wire-order test drives everything from one thread and cannot see this; this
     * one holds the flush open inside the fake socket's `send` and pumps a second chunk from
     * another thread while the drain is suspended mid-flight.
     */
    @Test fun `pcm enqueued while the handshake flush is mid drain cannot overtake activity start`() {
        val socket = GatedWebSocket(gateOn = "activityStart")
        val client = FakeWebSocketClient(socket)
        val listener = RecordingListener()
        val session = factory(httpClient = client, setupTimeoutMs = 30_000).create(listener)

        session.connect()
        val socketListener = requireNotNull(client.socketListener) { "connect() must open a socket" }
        socketListener.onOpen(socket, upgradeResponse())
        assertTrue(session.startActivity())
        assertTrue(session.sendPcm(byteArrayOf(1), 1)) // "AQ=="

        // OkHttp's reader thread delivers setupComplete; the fake socket suspends the drain
        // exactly where the real one would be waiting on the network for activityStart.
        val flusher = Thread({ socketListener.onMessage(socket, "{\"setupComplete\":{}}") }, "flush")
        flusher.start()
        assertTrue("flush must reach activityStart", socket.gateReached.await(5, TimeUnit.SECONDS))

        // ...and the recorder thread pumps its next chunk right into that window.
        val pump = Thread({ assertTrue(session.sendPcm(byteArrayOf(2), 1)) }, "recorder") // "Ag=="
        pump.start()
        pump.join(5_000)
        assertFalse("sendPcm must never block on the flush", pump.isAlive)

        socket.releaseGate.countDown()
        flusher.join(5_000)
        assertFalse(flusher.isAlive)
        socket.awaitSent(4)

        assertEquals(
            listOf("setup", "activityStart", "audio:AQ==", "audio:Ag=="),
            socket.sent.map(::wireKind),
        )
        session.cancel()
    }

    /**
     * BUFFER_LIMIT must mean what it says. It used to also be reported when the post-handshake
     * direct-send branch found a null socket -- a released/never-opened connection, not a full
     * outbound buffer -- which is now [CloudLiveFailureReason.SEND_FAILED] with its own message.
     * This pins the genuinely-reachable direction: pre-handshake buffering really does overflow.
     */
    @Test fun `outbound buffer overflow before the handshake reports buffer limit`() {
        val socket = GatedWebSocket(gateOn = "\u0000never")
        val client = FakeWebSocketClient(socket)
        val listener = RecordingListener()
        val session = factory(httpClient = client, setupTimeoutMs = 30_000).create(listener)

        session.connect()
        socket.let { client.socketListener!!.onOpen(it, upgradeResponse()) }
        assertTrue(session.startActivity())

        // Nothing has been flushed yet (no setupComplete), so these accumulate against the 2MB cap.
        val chunk = ByteArray(768 * 1024)
        assertTrue("first chunk fits under the cap", session.sendPcm(chunk, chunk.size))
        assertFalse("the second overflows it", session.sendPcm(chunk, chunk.size))

        assertTrue(listener.done.await(5, TimeUnit.SECONDS))
        val failure = listener.result as CloudLiveTerminal.Failure
        assertEquals(CloudLiveFailureReason.BUFFER_LIMIT, failure.reason)
        assertTrue(failure.message.contains("buffer limit"))
    }

    private fun wireKind(message: String): String {
        val root = JSONObject(message)
        if (root.has("setup")) return "setup"
        val realtime = root.getJSONObject("realtimeInput")
        return when {
            realtime.has("activityStart") -> "activityStart"
            realtime.has("activityEnd") -> "activityEnd"
            else -> "audio:" + realtime.getJSONObject("audio").getString("data")
        }
    }

    private fun upgradeResponse(): Response = Response.Builder()
        .request(Request.Builder().url("https://example.com/").build())
        .protocol(Protocol.HTTP_1_1)
        .code(101)
        .message("Switching Protocols")
        .build()

    /** Hands the session a socket the test fully controls, and captures its [WebSocketListener]. */
    private class FakeWebSocketClient(private val socket: WebSocket) : OkHttpClient() {
        @Volatile var socketListener: WebSocketListener? = null
        override fun newWebSocket(request: Request, listener: WebSocketListener): WebSocket {
            socketListener = listener
            return socket
        }
    }

    /** Records wire order, and suspends inside `send` for the first message matching [gateOn]. */
    private class GatedWebSocket(private val gateOn: String) : WebSocket {
        val sent: MutableList<String> = Collections.synchronizedList(mutableListOf<String>())
        val gateReached = CountDownLatch(1)
        val releaseGate = CountDownLatch(1)
        private val sentSignal = Semaphore(0)

        override fun request(): Request = Request.Builder().url("https://example.com/").build()
        override fun queueSize(): Long = 0
        override fun send(text: String): Boolean {
            if (text.contains(gateOn)) {
                gateReached.countDown()
                releaseGate.await(5, TimeUnit.SECONDS)
            }
            sent += text
            sentSignal.release()
            return true
        }
        override fun send(bytes: ByteString): Boolean = true
        override fun close(code: Int, reason: String?): Boolean = true
        override fun cancel() = Unit

        fun awaitSent(count: Int) {
            assertTrue("expected $count sends, saw ${sent.size}", sentSignal.tryAcquire(count, 5, TimeUnit.SECONDS))
        }
    }

    private fun factory(
        apiKey: String = "test-key",
        endpoint: okhttp3.HttpUrl = server.url("/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent"),
        model: String = "gemini-3.5-transcribe-live",
        languages: List<String> = listOf("en-US"),
        vocabulary: List<String> = listOf("Ramblr"),
        setupTimeoutMs: Long = 2_000,
        finalTimeoutMs: Long = 2_000,
        httpClient: OkHttpClient = OkHttpClient(),
    ) = GeminiCloudLiveTranscriptionClient(
        httpClient = httpClient,
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

    /**
     * Regression: Gemini Live delivers its JSON events as binary frames. Treating
     * binary as a protocol violation killed every real session before
     * setupComplete, so live transcription always fell back to batch. Every other
     * test in this class drives the String overload, which is why the field defect
     * survived a green suite.
     */
    @Test fun `binary json frames parse identically to text frames`() {
        val listener = RecordingListener()
        val socket = scriptedSocket(
            "{\"setupComplete\":{}}",
            "{\"serverContent\":{\"interimInputTranscription\":{\"text\":\"hel\"}}}",
            "{\"serverContent\":{\"inputTranscription\":{\"text\":\"hello\"}}}",
            binary = true,
        )
        val session = factory().create(listener)
        session.connect(); session.startActivity(); session.endActivity()

        assertTrue(listener.done.await(5, TimeUnit.SECONDS))
        Thread.sleep(50)
        assertEquals(listOf("hel"), listener.interims)
        val success = listener.result as CloudLiveTerminal.Success
        assertEquals("hello", success.text)
        assertEquals(1, listener.terminals)
        assertTrue(success.timing.setupCompletedAtMs != null)
        socket.awaitMessages()
    }

    @Test fun `oversized binary frame fails closed without decoding`() {
        val listener = RecordingListener()
        scriptedSocket(
            "{\"setupComplete\":{}}",
            "{\"pad\":\"" + "x".repeat(GeminiCloudLiveTranscriptionClient.MAX_INBOUND_BYTES + 1) + "\"}",
            binary = true,
        )
        val session = factory().create(listener)
        session.connect(); session.startActivity(); session.endActivity()

        assertTrue(listener.done.await(5, TimeUnit.SECONDS))
        val failure = listener.result as CloudLiveTerminal.Failure
        assertEquals(CloudLiveFailureReason.PROTOCOL_ERROR, failure.reason)
        assertEquals(1, listener.terminals)
    }

    private fun scriptedSocket(vararg events: String, delayMs: Long = 0, binary: Boolean = false): ServerScript {
        val script = ServerScript(events.toList(), delayMs, binary)
        server.enqueue(MockResponse().withWebSocketUpgrade(script))
        return script
    }

    private open class ClosingWebSocketListener : WebSocketListener() {
        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(code, reason)
        }
    }

    private class ServerScript(
        private val events: List<String>,
        private val delayMs: Long,
        private val binary: Boolean = false,
    ) : ClosingWebSocketListener() {
        private val received = CountDownLatch(1)
        override fun onOpen(webSocket: WebSocket, response: Response) {
            Thread {
                if (delayMs > 0) Thread.sleep(delayMs)
                events.forEach { event ->
                    if (binary) webSocket.send(event.encodeUtf8()) else webSocket.send(event)
                }
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
