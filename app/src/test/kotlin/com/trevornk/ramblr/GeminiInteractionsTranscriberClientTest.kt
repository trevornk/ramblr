package com.trevornk.ramblr

import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class GeminiInteractionsTranscriberClientTest {
    @get:Rule val temp = TemporaryFolder()

    private lateinit var server: MockWebServer
    private lateinit var client: GeminiInteractionsTranscriberClient

    @Before fun setUp() {
        server = MockWebServer()
        server.start()
        client = GeminiInteractionsTranscriberClient(
            httpClient = OkHttpClient(),
            uploadEndpoint = server.url("/upload/v1beta/files"),
            interactionsEndpoint = server.url("/v1beta/interactions"),
            filesEndpoint = server.url("/v1beta/files/"),
        )
    }

    @After fun tearDown() = server.shutdown()

    private fun audio(name: String = "clip.wav"): File =
        File(temp.root, name).apply { writeBytes(byteArrayOf(1, 2, 3, 4, 5)) }

    private fun enqueueSuccessfulUpload() {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .addHeader("X-Goog-Upload-URL", server.url("/resumable/session-1")),
        )
        server.enqueue(
            MockResponse().setBody(
                """{"file":{"name":"files/file-123","uri":"https://files.example/file-123","mimeType":"audio/wav","state":"ACTIVE"}}""",
            ),
        )
    }

    private fun awaitResult(
        file: File = audio(),
        mimeType: String = "audio/wav",
        apiKey: String = "test-api-key",
        model: String = "gemini-3.5-transcribe",
        vocabulary: List<String> = listOf("Ramblr", "Claude Code"),
        languages: List<String> = listOf("en-US"),
        mode: GeminiInteractionsTranscriberClient.Mode = GeminiInteractionsTranscriberClient.Mode.VERBATIM,
        holder: InFlightCall = InFlightCall(),
    ): Pair<GeminiInteractionsTranscriberClient.Result, Int> {
        val latch = CountDownLatch(1)
        val callbacks = AtomicInteger()
        val result = AtomicReference<GeminiInteractionsTranscriberClient.Result>()
        client.transcribe(file, mimeType, apiKey, model, vocabulary, languages, mode, holder) {
            callbacks.incrementAndGet()
            result.set(it)
            latch.countDown()
        }
        assertTrue("callback timed out", latch.await(5, TimeUnit.SECONDS))
        Thread.sleep(50)
        return result.get() to callbacks.get()
    }

    @Test fun `resumable upload interaction and deletion use the documented request shapes`() {
        enqueueSuccessfulUpload()
        server.enqueue(
            MockResponse().setBody(
                """{"status":"completed","steps":[{"type":"model_output","content":[{"type":"text","text":" hello "},{"type":"text","text":"world "}]}]}""",
            ),
        )
        server.enqueue(MockResponse().setBody("{}"))

        val (result, callbacks) = awaitResult()

        assertEquals("hello world", result.text)
        assertNull(result.error)
        assertEquals(1, callbacks)

        val start = server.takeRequest()
        assertEquals("POST", start.method)
        assertEquals("/upload/v1beta/files", start.path)
        assertEquals("test-api-key", start.getHeader("x-goog-api-key"))
        assertEquals("resumable", start.getHeader("X-Goog-Upload-Protocol"))
        assertEquals("start", start.getHeader("X-Goog-Upload-Command"))
        assertEquals("5", start.getHeader("X-Goog-Upload-Header-Content-Length"))
        assertEquals("audio/wav", start.getHeader("X-Goog-Upload-Header-Content-Type"))
        assertEquals("ramblr-audio", JSONObject(start.body.readUtf8()).getJSONObject("file").getString("display_name"))

        val upload = server.takeRequest()
        assertEquals("POST", upload.method)
        assertEquals("/resumable/session-1", upload.path)
        assertEquals("upload, finalize", upload.getHeader("X-Goog-Upload-Command"))
        assertEquals("0", upload.getHeader("X-Goog-Upload-Offset"))
        assertEquals(listOf<Byte>(1, 2, 3, 4, 5), upload.body.readByteArray().toList())

        val interaction = server.takeRequest()
        assertEquals("POST", interaction.method)
        assertEquals("/v1beta/interactions", interaction.path)
        assertEquals("test-api-key", interaction.getHeader("x-goog-api-key"))
        val body = JSONObject(interaction.body.readUtf8())
        assertEquals("gemini-3.5-transcribe", body.getString("model"))
        assertFalse(body.getBoolean("store"))
        val input = body.getJSONArray("input").getJSONObject(0)
        assertEquals("audio", input.getString("type"))
        assertEquals("https://files.example/file-123", input.getString("uri"))
        assertEquals("audio/wav", input.getString("mime_type"))
        val transcription = body.getJSONObject("generation_config").getJSONObject("transcription_config")
        assertEquals(listOf("Ramblr", "Claude Code"), (0 until transcription.getJSONArray("custom_vocabulary").length()).map { transcription.getJSONArray("custom_vocabulary").getString(it) })
        assertEquals(listOf("en-US"), (0 until transcription.getJSONArray("language_codes").length()).map { transcription.getJSONArray("language_codes").getString(it) })
        assertEquals("verbatim", transcription.getJSONObject("mode").getString("type"))

        val delete = server.takeRequest()
        assertEquals("DELETE", delete.method)
        assertEquals("/v1beta/files/file-123", delete.path)
        assertEquals("test-api-key", delete.getHeader("x-goog-api-key"))
    }

    @Test fun `smart mode is explicit and never encoded as verbatim`() {
        enqueueSuccessfulUpload()
        server.enqueue(MockResponse().setBody("""{"steps":[{"type":"model_output","content":[{"type":"text","text":"clean"}]}]}"""))
        server.enqueue(MockResponse().setBody("{}"))

        val (result, _) = awaitResult(mode = GeminiInteractionsTranscriberClient.Mode.SMART)

        assertEquals("clean", result.text)
        server.takeRequest()
        server.takeRequest()
        val config = JSONObject(server.takeRequest().body.readUtf8())
            .getJSONObject("generation_config").getJSONObject("transcription_config")
        assertEquals("smart", config.getString("mode"))
        assertFalse(config.opt("mode") is JSONObject)
    }

    @Test fun `successful callback does not wait for uploaded file deletion`() {
        enqueueSuccessfulUpload()
        server.enqueue(MockResponse().setBody("""{"steps":[{"type":"model_output","content":[{"type":"text","text":"ready"}]}]}"""))
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))

        val (result, callbacks) = awaitResult()

        assertEquals("ready", result.text)
        assertNull(result.error)
        assertEquals(1, callbacks)
        repeat(3) { server.takeRequest() }
        assertEquals("DELETE", server.takeRequest(5, TimeUnit.SECONDS)?.method)
    }

    @Test fun `parser joins only text content from model output steps`() {
        val result = GeminiInteractionsTranscriberClient.parseResponse(
            """{"steps":[{"type":"thought","content":[{"type":"text","text":"secret reasoning"}]},{"type":"model_output","content":[{"type":"text","text":"one "},{"type":"audio","uri":"ignored"}]},{"type":"model_output","content":[{"type":"text","text":"two"}]}]}""",
        )
        assertEquals("one two", result.text)
        assertNull(result.error)
    }

    @Test fun `parser reports structured errors and completed responses without text`() {
        assertEquals("quota exceeded", GeminiInteractionsTranscriberClient.parseResponse("""{"error":{"message":"quota exceeded"}}""").error)
        assertEquals("No text content in response", GeminiInteractionsTranscriberClient.parseResponse("""{"status":"completed","steps":[]}""").error)
    }

    @Test fun `interaction HTTP error still deletes uploaded file and callback fires once`() {
        enqueueSuccessfulUpload()
        server.enqueue(MockResponse().setResponseCode(429).setBody("""{"error":{"message":"quota exceeded"}}"""))
        server.enqueue(MockResponse().setResponseCode(500).setBody("cleanup failed"))

        val (result, callbacks) = awaitResult()

        assertNull(result.text)
        assertEquals("quota exceeded", result.error)
        assertEquals(1, callbacks)
        repeat(3) { server.takeRequest() }
        assertEquals("DELETE", server.takeRequest().method)
    }

    @Test fun `malformed finalization response still deletes a named uploaded artifact`() {
        server.enqueue(
            MockResponse().addHeader("X-Goog-Upload-URL", server.url("/resumable/session-1")),
        )
        server.enqueue(MockResponse().setBody("""{"file":{"name":"files/file-123"}}"""))
        server.enqueue(MockResponse().setBody("{}"))

        val (result, callbacks) = awaitResult()

        assertEquals("Files API upload response contained no valid file resource", result.error)
        assertEquals(1, callbacks)
        repeat(2) { server.takeRequest() }
        assertEquals("DELETE", server.takeRequest().method)
    }

    @Test fun `upload handshake error has no artifact to delete and does not leak the api key`() {
        val credential = "test-credential-value"
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":{"message":"bad key $credential"}}"""))

        val (result, callbacks) = awaitResult(apiKey = credential)

        assertEquals(1, callbacks)
        assertFalse(result.error.orEmpty().contains(credential))
        assertTrue(result.error.orEmpty().contains("bad key"))
        assertEquals(1, server.requestCount)
        val request = server.takeRequest()
        assertFalse(request.path!!.contains(credential))
        assertFalse(request.body.readUtf8().contains(credential))
    }

    @Test fun `cancelling an interaction aborts it cleans up and calls back once`() {
        enqueueSuccessfulUpload()
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        server.enqueue(MockResponse().setBody("{}"))
        val holder = InFlightCall()
        val latch = CountDownLatch(1)
        val callbacks = AtomicInteger()
        val result = AtomicReference<GeminiInteractionsTranscriberClient.Result>()

        client.transcribe(audio(), "audio/wav", "key", cancelHolder = holder) {
            callbacks.incrementAndGet()
            result.set(it)
            latch.countDown()
        }
        repeat(100) {
            if (server.requestCount >= 3) return@repeat
            Thread.sleep(10)
        }
        holder.cancel()

        assertTrue("cancel callback timed out", latch.await(5, TimeUnit.SECONDS))
        assertTrue(result.get().error.orEmpty().contains("cancel", ignoreCase = true))
        assertEquals(1, callbacks.get())
        repeat(3) { server.takeRequest() }
        assertEquals("DELETE", server.takeRequest().method)
    }

    @Test fun `blank api key error is stable and redaction safe`() {
        val (result, callbacks) = awaitResult(apiKey = "")
        assertEquals("Gemini API key is blank", result.error)
        assertEquals(1, callbacks)
        assertEquals(0, server.requestCount)
    }

    @Test fun `invalid mime model and oversized vocabulary fail once without network calls`() {
        val invalid = listOf(
            awaitResult(mimeType = "text/plain"),
            awaitResult(model = "models/gemini-3.5-transcribe?key=oops"),
            awaitResult(vocabulary = (1..1001).map { "term-$it" }),
        )
        invalid.forEach { (result, callbacks) ->
            assertNull(result.text)
            assertEquals(1, callbacks)
        }
        assertEquals(0, server.requestCount)
    }
}