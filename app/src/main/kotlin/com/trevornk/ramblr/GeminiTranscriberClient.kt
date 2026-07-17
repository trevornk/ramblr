package com.trevornk.ramblr

import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okio.ByteString.Companion.toByteString
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException

/**
 * Multimodal audio-in client for Gemini transcription (#96): sends the recorded PCM as an
 * inline base64 `audio/wav` blob inside a `generateContent` call, prompting the model to
 * transcribe it, rather than a dedicated ASR endpoint (Gemini has none) -- see [ProviderChain]'s
 * kdoc for why this is the documented capability shape. Mirrors [TranscriberClient]'s Result/
 * callback contract so [WhisperAccessibilityService.transcribeApi] can treat a Gemini candidate
 * exactly like an OpenAI one.
 *
 * No streaming (per capability facts) and no file-upload API: `inline_data` keeps this a single
 * request/response round trip instead of an upload-then-generate two-step flow, which is
 * adequate for the short dictations this app records (see [NetworkClients] timeouts).
 */
object GeminiTranscriberClient {
    data class Result(val text: String?, val error: String?)

    private val client = NetworkClients.shared

    // gemini-2.5-flash -> gemini-3.1-flash-lite: 2.5-flash is on Google's deprecation path
    // (shutdown Oct 16, 2026). 3.1-flash-lite's own model card documents explicit "improved
    // audio input... for ASR" gains and lists transcription as a first-class use case, so it's
    // safe for this multimodal audio-in path too (2026-07-10).
    const val DEFAULT_MODEL = "gemini-3.1-flash-lite"
    const val TRANSCRIBE_PROMPT = "Transcribe this audio exactly as spoken. Return only the transcript text, with no commentary, labels, or extra formatting."

    /**
     * Interpolates the user's personal vocabulary into [TRANSCRIBE_PROMPT] (#114 part 2): the
     * same terms already biasing cleanup-stage prompts (see [PostProcessor.vocabularyClause])
     * appended as a short clause so Gemini's transcription decoding is nudged toward them too.
     * Always on when [terms] is non-empty; [TRANSCRIBE_PROMPT] is returned unchanged otherwise.
     */
    fun transcribePrompt(terms: List<String>): String =
        if (terms.isEmpty()) TRANSCRIBE_PROMPT
        else "$TRANSCRIBE_PROMPT Watch for these project names and personal vocabulary terms, which speech-to-text often mishears: ${terms.joinToString(", ")}."

    /** Max PCM file size this inline-audio path will accept (M6): base64 + JSON string + request
     *  body copy buffers the recording ~4x in memory (a max-length 19.2MB PCM ≈ ~100MB transient),
     *  stacked on the resident STT/cleanup models -- a plausible OOM exactly when memory is tight.
     *  ~10MB PCM (~5 min at 16kHz mono 16-bit) bounds the transient to a safer band; above it the
     *  caller falls through to the next transcription candidate rather than risk the OOM. */
    const val MAX_INLINE_PCM_BYTES = 10L * 1024 * 1024

    /** True when [pcmBytes] is small enough for the inline-audio path (see [MAX_INLINE_PCM_BYTES]). */
    fun canInlineAudio(pcmBytes: Long): Boolean = pcmBytes <= MAX_INLINE_PCM_BYTES

    /**
     * Builds the generateContent request body with the audio bytes inlined as base64
     * `inline_data` (see https://ai.google.dev/gemini-api/docs/audio#inline-audio). [audioBytes]
     * must already be in the container/encoding described by [mimeType] -- callers building from
     * a raw PCM file should use [WavWriter] first for the WAV path, same as [PcmWavRequestBody]
     * does for [TranscriberClient]. [mimeType] defaults to `audio/wav`; #109's compressed-upload
     * path passes `audio/aac` for a finished `.m4a` file instead (AAC is one of Gemini's
     * documented supported inline audio MIME types).
     */
    fun buildRequestBody(audioBytes: ByteArray, prompt: String = TRANSCRIBE_PROMPT, mimeType: String = "audio/wav"): JSONObject {
        val audioPart = JSONObject().apply {
            put("inline_data", JSONObject().apply {
                put("mime_type", mimeType)
                put("data", audioBytes.toByteString().base64())
            })
        }
        val textPart = JSONObject().apply { put("text", prompt) }
        val contents = JSONArray().put(JSONObject().apply {
            put("role", "user")
            put("parts", JSONArray().put(textPart).put(audioPart))
        })
        return JSONObject().apply {
            put("contents", contents)
        }
    }

    /** Same `candidates[0].content.parts[].text` shape as [GeminiCleanupProvider.parseResponse],
     *  extracted independently so the two providers' response contracts (transcript text vs.
     *  cleaned-up text) stay decoupled even though the wire format happens to match today. */
    fun parseResponse(json: String): Result = try {
        val obj = JSONObject(json)
        if (obj.has("candidates")) {
            val candidates = obj.getJSONArray("candidates")
            if (candidates.length() == 0) {
                Result(null, "No candidates in response")
            } else {
                val content = candidates.getJSONObject(0).optJSONObject("content")
                val parts = content?.optJSONArray("parts")
                val text = parts?.let { arr ->
                    (0 until arr.length())
                        .map { arr.getJSONObject(it) }
                        .filter { it.has("text") }
                        .joinToString("") { it.getString("text") }
                }
                if (!text.isNullOrBlank()) Result(text.trim(), null) else Result(null, "No text content in response")
            }
        } else if (obj.has("error")) {
            Result(null, obj.getJSONObject("error").optString("message", "Unknown Gemini error"))
        } else {
            Result(null, "Unknown response")
        }
    } catch (e: Exception) {
        Result(null, e.message ?: "Parse error")
    }

    /**
     * Streams [pcmFile] into a WAV byte array (44-byte header + PCM body, same convention as
     * [PcmWavRequestBody]) and sends it as inline base64 audio data. Unlike
     * [TranscriberClient.transcribe]'s multipart streaming upload, Gemini's inline-data JSON
     * body requires the full base64 payload assembled in memory -- acceptable for this app's
     * short dictation recordings, same tradeoff [NetworkClients]' generous timeouts already
     * budget for.
     *
     * When [compressedFile] is non-null (#109: compressed-upload toggle on and the AAC encode
     * succeeded), that finished `.m4a` is embedded instead as `audio/aac` -- read directly rather
     * than WAV-wrapped, since it's already a complete, playable audio container. Falls back to
     * the WAV path built from [pcmFile] exactly as before whenever it's null.
     */
    fun transcribe(
        pcmFile: File,
        apiKey: String,
        model: String = DEFAULT_MODEL,
        cancelHolder: InFlightCall,
        vocabularyTerms: List<String> = emptyList(),
        compressedFile: File? = null,
        callback: (Result) -> Unit,
    ) {
        val (audioBytes, mimeType) = try {
            if (compressedFile != null) {
                compressedFile.readBytes() to "audio/aac"
            } else {
                (WavWriter.header(pcmFile.length(), 16000, 1, 16) + pcmFile.readBytes()) to "audio/wav"
            }
        } catch (e: IOException) {
            callback(Result(null, e.message ?: "Failed to read audio file"))
            return
        }

        // #114 part 2: interpolate the user's vocabulary into the transcription prompt itself
        // (previously only cleanup-stage prompts got it), always on when terms exist.
        val body = buildRequestBody(audioBytes, prompt = transcribePrompt(vocabularyTerms), mimeType = mimeType)
            .toString().toRequestBody("application/json".toMediaType())

        // A malformed key/model (or one OkHttp otherwise rejects) throws IllegalArgumentException
        // from Request.Builder().url() rather than failing the call -- caught so it reports
        // through the same Result/callback path as a network failure, instead of crashing (see
        // PostProcessor.process's identical handling of a bad custom base URL, #4). The key rides
        // in the x-goog-api-key header (M-3), not the URL, and the message is query-redacted for
        // defense in depth so nothing echoed from the URL can leak a secret.
        val request = try {
            Request.Builder()
                .url(GeminiCleanupProvider.endpointUrl(model))
                .header("x-goog-api-key", apiKey)
                .post(body)
                .build()
        } catch (e: IllegalArgumentException) {
            callback(Result(null, "Invalid Gemini endpoint: ${UrlRedaction.redact(e.message)}"))
            return
        }

        val call = client.newCall(request)
        cancelHolder.set(call)
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                cancelHolder.clear(call)
                callback(Result(null, e.message))
            }

            override fun onResponse(call: Call, response: Response) {
                cancelHolder.clear(call)
                // Read via HttpBodyReader (#62): a body read that throws inside onResponse is
                // swallowed by OkHttp (onFailure never fires), so this callback would otherwise
                // never be invoked and the dictation would hang until the watchdog.
                HttpBodyReader.read(response).fold(
                    onSuccess = { responseBody -> callback(parseResponse(responseBody)) },
                    onFailure = { e -> callback(Result(null, e.message)) },
                )
            }
        })
    }
}
