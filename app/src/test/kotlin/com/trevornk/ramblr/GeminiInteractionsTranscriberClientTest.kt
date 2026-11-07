package com.trevornk.ramblr

import okhttp3.OkHttpClient
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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
            // The production client, so redirect-following behavior under test is the real one.
            httpClient = NetworkClients.noRedirects,
            uploadEndpoint = server.url("/upload/v1beta/files"),
            interactionsEndpoint = server.url("/v1beta/interactions"),
            filesEndpoint = server.url("/v1beta/files/"),
        )
    }

    @After fun tearDown() = server.close()

    private fun audio(name: String = "clip.wav"): File =
        File(temp.root, name).apply { writeBytes(byteArrayOf(1, 2, 3, 4, 5)) }

    private fun enqueueSuccessfulUpload() {
        server.enqueue(
            MockResponse.Builder()
                .code(200)
                .addHeader("X-Goog-Upload-URL", server.url("/resumable/session-1")).build(),
        )
        server.enqueue(
            MockResponse.Builder().body(
                """{"file":{"name":"files/file-123","uri":"https://files.example/file-123","mimeType":"audio/wav","state":"ACTIVE"}}""",
            ).build(),
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

    @Test fun `a custom-vocabulary answer from the interactions API is delivered as an empty transcript (306)`() {
        val terms = listOf("Zorbex", "Quillfarm", "Nimbato", "Plinth")
        enqueueSuccessfulUpload()
        server.enqueue(
            MockResponse.Builder().body(
                """{"status":"completed","steps":[{"type":"model_output","content":[{"type":"text","text":"Zorbex, Quillfarm, Nimbato, Plinth."}]}]}""",
            ).build(),
        )
        server.enqueue(MockResponse.Builder().body("{}").build())

        val (result, callbacks) = awaitResult(vocabulary = terms)

        assertEquals("", result.text)
        assertNull(result.error)
        assertEquals(1, callbacks)
    }

    @Test fun `resumable upload interaction and deletion use the documented request shapes`() {
        enqueueSuccessfulUpload()
        server.enqueue(
            MockResponse.Builder().body(
                """{"status":"completed","steps":[{"type":"model_output","content":[{"type":"text","text":" hello "},{"type":"text","text":"world "}]}]}""",
            ).build(),
        )
        server.enqueue(MockResponse.Builder().body("{}").build())

        val (result, callbacks) = awaitResult()

        assertEquals("hello world", result.text)
        assertNull(result.error)
        assertEquals(1, callbacks)

        val start = server.takeRequest()
        assertEquals("POST", start.method)
        assertEquals("/upload/v1beta/files", start.target)
        assertEquals("test-api-key", start.headers["x-goog-api-key"])
        assertEquals("resumable", start.headers["X-Goog-Upload-Protocol"])
        assertEquals("start", start.headers["X-Goog-Upload-Command"])
        assertEquals("5", start.headers["X-Goog-Upload-Header-Content-Length"])
        assertEquals("audio/wav", start.headers["X-Goog-Upload-Header-Content-Type"])
        assertEquals("ramblr-audio", JSONObject(start.body!!.utf8()).getJSONObject("file").getString("display_name"))

        val upload = server.takeRequest()
        assertEquals("POST", upload.method)
        assertEquals("/resumable/session-1", upload.target)
        assertEquals("upload, finalize", upload.headers["X-Goog-Upload-Command"])
        assertEquals("0", upload.headers["X-Goog-Upload-Offset"])
        assertEquals(listOf<Byte>(1, 2, 3, 4, 5), upload.body!!.toByteArray().toList())

        val interaction = server.takeRequest()
        assertEquals("POST", interaction.method)
        assertEquals("/v1beta/interactions", interaction.target)
        assertEquals("test-api-key", interaction.headers["x-goog-api-key"])
        val body = JSONObject(interaction.body!!.utf8())
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
        assertEquals("/v1beta/files/file-123", delete.target)
        assertEquals("test-api-key", delete.headers["x-goog-api-key"])
    }

    @Test fun `smart mode is explicit and never encoded as verbatim`() {
        enqueueSuccessfulUpload()
        server.enqueue(MockResponse.Builder().body("""{"steps":[{"type":"model_output","content":[{"type":"text","text":"clean"}]}]}""").build())
        server.enqueue(MockResponse.Builder().body("{}").build())

        val (result, _) = awaitResult(mode = GeminiInteractionsTranscriberClient.Mode.SMART)

        assertEquals("clean", result.text)
        server.takeRequest()
        server.takeRequest()
        val config = JSONObject(server.takeRequest().body!!.utf8())
            .getJSONObject("generation_config").getJSONObject("transcription_config")
        assertEquals("smart", config.getJSONObject("mode").getString("type"))
    }

    /**
     * Both modes use the same `{"type": ...}` object shape. The live API accepts a bare string too
     * (`mode: "smart"`), but only the object form is documented, and encoding the two modes
     * differently invites a reader into thinking one of them is special. Verified against the live
     * endpoint 2026-08-28: a bad enum is rejected with "Supported values: 'verbatim', 'smart'"
     * under both shapes, so neither is a silently-ignored no-op.
     */
    @Test fun `both transcription modes use the documented object wire shape`() {
        for (mode in GeminiInteractionsTranscriberClient.Mode.entries) {
            enqueueSuccessfulUpload()
            server.enqueue(MockResponse.Builder().body("""{"steps":[{"type":"model_output","content":[{"type":"text","text":"ok"}]}]}""").build())
            server.enqueue(MockResponse.Builder().body("{}").build())

            awaitResult(mode = mode)

            server.takeRequest()
            server.takeRequest()
            val config = JSONObject(server.takeRequest().body!!.utf8())
                .getJSONObject("generation_config").getJSONObject("transcription_config")
            val encoded = config.opt("mode")
            assertTrue("mode for $mode must be an object, was ${encoded?.javaClass}", encoded is JSONObject)
            assertEquals(mode.wireValue, (encoded as JSONObject).getString("type"))
            server.takeRequest()
        }
    }

    /**
     * A validated same-origin upload URL must not become a cross-host request via a redirect.
     * OkHttp follows redirects by default and only strips `Authorization` when the host changes --
     * `x-goog-api-key` is not stripped, so a followed 302 would hand both the audio body and the
     * API key to whatever host the redirect names. The client must refuse to follow instead.
     */
    @Test fun `upload does not follow a redirect to another host`() {
        val attacker = MockWebServer()
        attacker.start()
        try {
            attacker.enqueue(MockResponse.Builder().body("""{"file":{"name":"files/pwned","uri":"https://files.example/pwned","mimeType":"audio/wav","state":"ACTIVE"}}""").build())
            server.enqueue(
                MockResponse.Builder()
                    .code(200)
                    .setHeader("X-Goog-Upload-URL", server.url("/resumable/session-1").toString()).build(),
            )
            server.enqueue(
                MockResponse.Builder()
                    .code(302)
                    .setHeader("Location", attacker.url("/steal").toString()).build(),
            )

            val (result, callbacks) = awaitResult()

            assertEquals(1, callbacks)
            assertNull(result.text)
            assertNotNull(result.error)
            assertEquals("attacker host must receive no request", 0, attacker.requestCount)
        } finally {
            attacker.close()
        }
    }

    /**
     * Same guarantee on the key-bearing start call, which carries `x-goog-api-key` from the very
     * first request.
     */
    @Test fun `upload start does not follow a redirect to another host`() {
        val attacker = MockWebServer()
        attacker.start()
        try {
            attacker.enqueue(
                MockResponse.Builder()
                    .code(200)
                    .setHeader("X-Goog-Upload-URL", attacker.url("/resumable/evil").toString()).build(),
            )
            server.enqueue(
                MockResponse.Builder()
                    .code(302)
                    .setHeader("Location", attacker.url("/start").toString()).build(),
            )

            val (result, callbacks) = awaitResult()

            assertEquals(1, callbacks)
            assertNull(result.text)
            assertNotNull(result.error)
            assertEquals("attacker host must receive no request", 0, attacker.requestCount)
        } finally {
            attacker.close()
        }
    }

    @Test fun `successful callback does not wait for uploaded file deletion`() {
        enqueueSuccessfulUpload()
        server.enqueue(MockResponse.Builder().body("""{"steps":[{"type":"model_output","content":[{"type":"text","text":"ready"}]}]}""").build())
        server.enqueue(MockResponse.Builder().onResponseStart(mockwebserver3.SocketEffect.Stall).build())

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
        server.enqueue(MockResponse.Builder().code(429).body("""{"error":{"message":"quota exceeded"}}""").build())
        server.enqueue(MockResponse.Builder().code(500).body("cleanup failed").build())

        val (result, callbacks) = awaitResult()

        assertNull(result.text)
        assertEquals("quota exceeded", result.error)
        assertEquals(1, callbacks)
        repeat(3) { server.takeRequest() }
        assertEquals("DELETE", server.takeRequest().method)
    }

    @Test fun `malformed finalization response still deletes a named uploaded artifact`() {
        server.enqueue(
            MockResponse.Builder().addHeader("X-Goog-Upload-URL", server.url("/resumable/session-1")).build(),
        )
        server.enqueue(MockResponse.Builder().body("""{"file":{"name":"files/file-123"}}""").build())
        server.enqueue(MockResponse.Builder().body("{}").build())

        val (result, callbacks) = awaitResult()

        assertEquals("Files API upload response contained no valid file resource", result.error)
        assertEquals(1, callbacks)
        repeat(2) { server.takeRequest() }
        assertEquals("DELETE", server.takeRequest().method)
    }

    @Test fun `upload handshake error has no artifact to delete and does not leak the api key`() {
        val credential = "test-credential-value"
        server.enqueue(MockResponse.Builder().code(401).body("""{"error":{"message":"bad key $credential"}}""").build())

        val (result, callbacks) = awaitResult(apiKey = credential)

        assertEquals(1, callbacks)
        assertFalse(result.error.orEmpty().contains(credential))
        assertTrue(result.error.orEmpty().contains("bad key"))
        assertEquals(1, server.requestCount)
        val request = server.takeRequest()
        assertFalse(request.target.contains(credential))
        assertFalse(request.body!!.utf8().contains(credential))
    }

    @Test fun `cancelling an interaction aborts it cleans up and calls back once`() {
        enqueueSuccessfulUpload()
        server.enqueue(MockResponse.Builder().onResponseStart(mockwebserver3.SocketEffect.Stall).build())
        server.enqueue(MockResponse.Builder().body("{}").build())
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