package com.trevornk.ramblr

import java.util.Base64
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.HttpUrl.Companion.toHttpUrl
import okio.ByteString
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Production Gemini 3.5 Transcribe Live WebSocket factory.
 *
 * Authentication uses the existing device-resident Gemini API key in an HTTP header, never a URL.
 * OkHttp invokes socket methods off-main; listener callbacks are serialized on [callbackExecutor]
 * (also off-main by default). The runtime is responsible for explicitly hopping UI work to main.
 */
class GeminiCloudLiveTranscriptionClient(
    private val httpClient: OkHttpClient = NetworkClients.shared,
    // OkHttp's WebSocket API accepts an HTTPS HttpUrl and performs the WSS upgrade itself.
    private val endpoint: HttpUrl = DEFAULT_HTTP_ENDPOINT.toHttpUrl(),
    private val apiKey: String,
    private val model: String = DEFAULT_MODEL,
    private val languageCodes: List<String> = emptyList(),
    private val customVocabulary: List<String> = emptyList(),
    callbackExecutor: Executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "GeminiLive-callback").apply { isDaemon = true }
    },
    private val scheduler: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "GeminiLive-timeout").apply { isDaemon = true }
    },
    private val setupTimeoutMs: Long = DEFAULT_SETUP_TIMEOUT_MS,
    private val finalTimeoutMs: Long = DEFAULT_FINAL_TIMEOUT_MS,
    private val nowMs: () -> Long = System::currentTimeMillis,
    internal val allowTestEndpoint: Boolean = false,
) : CloudLiveTranscriptionSessionFactory {
    private val callbacks = SerialCallbackExecutor(callbackExecutor)

    init {
        require(endpoint.scheme == "https" || (allowTestEndpoint && endpoint.scheme == "http"))
        require(endpoint.encodedPath == ENDPOINT_PATH && endpoint.query == null && endpoint.fragment == null) { "Unexpected Gemini Live endpoint" }
        require(allowTestEndpoint || endpoint.host == GEMINI_HOST) { "Unexpected Gemini Live host" }
        require(apiKey.isNotBlank()) { "Gemini API key is blank" }
        require(MODEL_ID.matches(model) && model == DEFAULT_MODEL) { "Invalid Gemini Live model id" }
        require(languageCodes.all(LANGUAGE_CODE::matches)) { "Invalid language code" }
        require(customVocabulary.size <= MAX_CUSTOM_VOCABULARY && customVocabulary.none { it.isBlank() }) { "Invalid custom vocabulary" }
        require(setupTimeoutMs > 0 && finalTimeoutMs > 0)
    }

    override fun create(listener: CloudLiveTranscriptionListener): CloudLiveTranscriptionSession = Session(listener)

    private inner class Session(private val listener: CloudLiveTranscriptionListener) : CloudLiveTranscriptionSession {
        private val lock = Any()
        private val terminal = AtomicBoolean(false)
        private val pending = ArrayDeque<String>()
        private var pendingBytes = 0L
        private var socket: WebSocket? = null
        private var connected = false
        private var setupComplete = false
        private var draining = false
        private var startRequested = false
        private var endRequested = false
        private var setupTimeout: ScheduledFuture<*>? = null
        private var finalTimeout: ScheduledFuture<*>? = null
        private var timing = CloudLiveTiming(connectStartedAtMs = nowMs())

        override fun connect() {
            synchronized(lock) {
                if (connected || terminal.get()) return
                connected = true
                timing = timing.copy(connectStartedAtMs = nowMs())
                setupTimeout = scheduler.schedule(
                    { fail(CloudLiveFailureReason.SETUP_TIMEOUT, "Live transcription setup timed out") },
                    setupTimeoutMs,
                    TimeUnit.MILLISECONDS,
                )
                val request = Request.Builder().url(endpoint).header(API_KEY_HEADER, apiKey).build()
                socket = httpClient.newWebSocket(request, SocketListener())
            }
        }

        override fun startActivity(): Boolean = enqueueControl(activityStartJson(), isStart = true)

        override fun sendPcm(buffer: ByteArray, length: Int): Boolean {
            if (length < 0 || length > buffer.size) {
                fail(CloudLiveFailureReason.INVALID_REQUEST, "Invalid PCM length")
                return false
            }
            val copy = buffer.copyOf(length)
            val encoded = Base64.getEncoder().encodeToString(copy)
            val message = audioJson(encoded)
            return enqueue(message)
        }

        override fun endActivity(): Boolean {
            val accepted = enqueueControl(activityEndJson(), isStart = false)
            if (accepted) synchronized(lock) {
                timing = timing.copy(activityEndedAtMs = nowMs())
                if (setupComplete) armFinalTimeoutLocked()
            }
            return accepted
        }

        private fun enqueueControl(message: String, isStart: Boolean): Boolean {
            synchronized(lock) {
                if (terminal.get()) return false
                if (isStart) {
                    if (startRequested || endRequested) return false
                    startRequested = true
                } else {
                    if (!startRequested || endRequested) return false
                    endRequested = true
                }
            }
            return enqueue(message)
        }

        /**
         * Appends [message] to [pending] -- the single ordering authority -- then drains the queue
         * if no other thread already owns the drain.
         *
         * Nothing is ever handed straight to the socket, even after the handshake lands. A direct
         * send would let a message enqueued *later* reach the wire ahead of one still sitting in
         * the queue: the recorder thread's next PCM chunk used to overtake the not-yet-flushed
         * `activityStart` while [onSetupComplete] was mid-flush, and with
         * `automaticActivityDetection.disabled = true` the server drops audio that arrives before
         * activityStart. Routing every message through the queue makes append order == wire order
         * by construction, which is exactly what [CloudLiveTranscriptionSession] promises.
         *
         * The monitor is never held across a send: OkHttp's [WebSocket.send] enqueues onto its own
         * writer rather than blocking on the network, but keeping it outside the lock also means a
         * slow writer can never stall the recorder thread's next `sendPcm`.
         */
        private fun enqueue(message: String): Boolean {
            val buffered = synchronized(lock) {
                if (terminal.get()) return false
                val bytes = message.toByteArray(Charsets.UTF_8).size.toLong()
                if (pendingBytes + bytes > MAX_PENDING_BYTES) {
                    false // Failure is completed outside this monitor.
                } else {
                    pending.addLast(message)
                    pendingBytes += bytes
                    true
                }
            }
            if (!buffered) {
                fail(CloudLiveFailureReason.BUFFER_LIMIT, "Live transcription outbound buffer limit reached")
                return false
            }
            drainPending()
            return true
        }

        /**
         * Sends every queued message in FIFO order. At most one thread drains at a time; whoever
         * loses the race simply returns, because the winner is guaranteed to pick up its message
         * (the emptiness check that ends a drain and the `draining = false` that reopens it happen
         * in the same critical section, so no enqueue can be stranded).
         */
        private fun drainPending() {
            val owned = synchronized(lock) {
                if (terminal.get() || !setupComplete || draining) return
                draining = true
                true
            }
            if (!owned) return
            var failure: String? = null
            while (true) {
                val message = takeNextPendingOrEndDrain() ?: break
                // A missing socket after setup is a released/never-opened connection, not a full
                // outbound buffer -- report what actually happened.
                val ws = synchronized(lock) { socket }
                if (ws == null) { failure = "Live transcription socket unavailable"; break }
                if (ws.queueSize() > MAX_WEBSOCKET_QUEUE_BYTES || !ws.send(message)) {
                    failure = "Live transcription send failed"
                    break
                }
            }
            if (failure != null) {
                synchronized(lock) { draining = false }
                fail(CloudLiveFailureReason.SEND_FAILED, failure)
            }
        }

        /** Pops the next queued message, or ends this drain (in the same critical section as the
         *  emptiness check) and returns null. */
        private fun takeNextPendingOrEndDrain(): String? = synchronized(lock) {
            if (terminal.get() || pending.isEmpty()) {
                draining = false
                return null
            }
            val next = pending.removeFirst()
            pendingBytes -= next.toByteArray(Charsets.UTF_8).size.toLong()
            next
        }

        override fun cancel() = fail(CloudLiveFailureReason.CANCELLED, "Live transcription cancelled")

        override fun close() {
            if (!terminal.get()) cancel()
            synchronized(lock) { socket?.close(NORMAL_CLOSE, "done") }
        }

        private fun onSetupComplete() {
            synchronized(lock) {
                if (terminal.get() || setupComplete) return
                setupComplete = true
                timing = timing.copy(setupCompletedAtMs = nowMs())
                setupTimeout?.cancel(false)
                setupTimeout = null
            }
            drainPending()
            synchronized(lock) { if (endRequested && !terminal.get()) armFinalTimeoutLocked() }
        }

        private fun armFinalTimeoutLocked() {
            if (finalTimeout != null) return
            finalTimeout = scheduler.schedule(
                { fail(CloudLiveFailureReason.FINAL_TIMEOUT, "Live transcription final timed out") },
                finalTimeoutMs,
                TimeUnit.MILLISECONDS,
            )
        }

        private fun onText(text: String) {
            if (text.toByteArray(Charsets.UTF_8).size > MAX_INBOUND_BYTES) {
                fail(CloudLiveFailureReason.PROTOCOL_ERROR, "Live transcription message too large")
                return
            }
            val root = try { JSONObject(text) } catch (_: Exception) {
                fail(CloudLiveFailureReason.PROTOCOL_ERROR, "Malformed live transcription event")
                return
            }
            root.optJSONObject("error")?.let {
                fail(CloudLiveFailureReason.PROTOCOL_ERROR, it.optString("message").ifBlank { "Gemini Live error" })
                return
            }
            if (root.has("setupComplete")) {
                onSetupComplete()
                return
            }
            val content = root.optJSONObject("serverContent") ?: return
            content.optJSONObject("interimInputTranscription")?.optString("text")?.takeIf { it.isNotBlank() }?.let { interim ->
                val snapshot = synchronized(lock) {
                    if (terminal.get()) return@let
                    if (timing.firstInterimAtMs == null) timing = timing.copy(firstInterimAtMs = nowMs())
                    timing
                }
                callbacks.execute { if (!terminal.get()) listener.onInterim(interim, snapshot) }
            }
            content.optJSONObject("inputTranscription")?.optString("text")?.trim()?.takeIf { it.isNotBlank() }?.let(::succeed)
        }

        private fun succeed(text: String) {
            val result = synchronized(lock) {
                timing = timing.copy(finalAtMs = nowMs())
                CloudLiveTerminal.Success(text, timing)
            }
            complete(result)
        }

        private fun fail(reason: CloudLiveFailureReason, message: String) {
            val safe = UrlRedaction.redact(message.replace(apiKey, "[REDACTED]"))
                ?: "Live transcription failed"
            val result = synchronized(lock) { CloudLiveTerminal.Failure(reason, safe, timing) }
            complete(result)
        }

        private fun complete(result: CloudLiveTerminal) {
            if (!terminal.compareAndSet(false, true)) return
            synchronized(lock) {
                setupTimeout?.cancel(false); setupTimeout = null
                finalTimeout?.cancel(false); finalTimeout = null
                pending.clear(); pendingBytes = 0
                socket?.close(NORMAL_CLOSE, "done")
            }
            callbacks.execute { listener.onTerminal(result) }
        }

        private inner class SocketListener : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                synchronized(lock) { timing = timing.copy(socketOpenedAtMs = nowMs()) }
                if (!webSocket.send(setupJson(model, languageCodes, customVocabulary))) {
                    fail(CloudLiveFailureReason.SEND_FAILED, "Live transcription setup send failed")
                }
            }

            override fun onMessage(webSocket: WebSocket, text: String) = onText(text)
            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                fail(CloudLiveFailureReason.PROTOCOL_ERROR, "Unexpected binary live transcription event")
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                fail(CloudLiveFailureReason.NETWORK_ERROR, t.message ?: "Live transcription network failure")
            }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (!terminal.get()) fail(CloudLiveFailureReason.NETWORK_ERROR, "Live transcription connection closed")
            }
        }
    }

    companion object {
        const val DEFAULT_MODEL = "gemini-3.5-transcribe-live"
        const val ENDPOINT_PATH = "/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent"
        const val GEMINI_HOST = "generativelanguage.googleapis.com"
        const val DEFAULT_WSS_ENDPOINT = "wss://$GEMINI_HOST$ENDPOINT_PATH"
        internal const val DEFAULT_HTTP_ENDPOINT = "https://$GEMINI_HOST$ENDPOINT_PATH"
        const val MAX_CUSTOM_VOCABULARY = 1000
        const val DEFAULT_SETUP_TIMEOUT_MS = 10_000L
        const val DEFAULT_FINAL_TIMEOUT_MS = 2_000L
        const val MAX_PENDING_BYTES = 2L * 1024 * 1024
        const val MAX_WEBSOCKET_QUEUE_BYTES = 4L * 1024 * 1024
        const val MAX_INBOUND_BYTES = 256 * 1024
        private const val API_KEY_HEADER = "x-goog-api-key"
        private const val NORMAL_CLOSE = 1000
        private val MODEL_ID = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")
        private val LANGUAGE_CODE = Regex("[A-Za-z]{2,3}(?:-[A-Za-z0-9]{2,8})*")

        fun setupJson(model: String, languageCodes: List<String>, customVocabulary: List<String>): String =
            JSONObject().put("setup", JSONObject()
                .put("model", "models/$model")
                .put("generationConfig", JSONObject().put("responseModalities", JSONArray().put("TEXT")))
                .put("inputAudioTranscription", JSONObject()
                    .put("languageCodes", JSONArray(languageCodes))
                    .put("customVocabulary", JSONArray(customVocabulary))
                    .put("mode", "VERBATIM"))
                .put("realtimeInputConfig", JSONObject().put("automaticActivityDetection", JSONObject().put("disabled", true))))
                .toString()

        private fun activityStartJson() = JSONObject().put("realtimeInput", JSONObject().put("activityStart", JSONObject())).toString()
        private fun activityEndJson() = JSONObject().put("realtimeInput", JSONObject().put("activityEnd", JSONObject())).toString()
        private fun audioJson(base64: String) = JSONObject().put("realtimeInput", JSONObject().put("audio", JSONObject()
            .put("data", base64)
            .put("mimeType", "audio/pcm;rate=16000"))).toString()
    }
}
