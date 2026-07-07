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

    const val DEFAULT_MODEL = "gemini-2.5-flash"
    const val TRANSCRIBE_PROMPT = "Transcribe this audio exactly as spoken. Return only the transcript text, with no commentary, labels, or extra formatting."

    /**
     * Builds the generateContent request body with the WAV audio bytes inlined as base64
     * `inline_data` (see https://ai.google.dev/gemini-api/docs/audio#inline-audio). [wavBytes]
     * must already include the WAV header -- callers building from a raw PCM file should use
     * [WavWriter] first, same as [PcmWavRequestBody] does for [TranscriberClient].
     */
    fun buildRequestBody(wavBytes: ByteArray, prompt: String = TRANSCRIBE_PROMPT): JSONObject {
        val audioPart = JSONObject().apply {
            put("inline_data", JSONObject().apply {
                put("mime_type", "audio/wav")
                put("data", wavBytes.toByteString().base64())
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
     */
    fun transcribe(pcmFile: File, apiKey: String, model: String = DEFAULT_MODEL, cancelHolder: InFlightCall, callback: (Result) -> Unit) {
        val wavBytes = try {
            WavWriter.header(pcmFile.length(), 16000, 1, 16) + pcmFile.readBytes()
        } catch (e: IOException) {
            callback(Result(null, e.message ?: "Failed to read audio file"))
            return
        }

        val body = buildRequestBody(wavBytes).toString().toRequestBody("application/json".toMediaType())

        // A malformed key/model (or one OkHttp otherwise rejects) throws IllegalArgumentException
        // from Request.Builder().url() rather than failing the call -- caught so it reports
        // through the same Result/callback path as a network failure, instead of crashing (see
        // PostProcessor.process's identical handling of a bad custom base URL, #4).
        val request = try {
            Request.Builder()
                .url(GeminiCleanupProvider.endpointUrl(model, apiKey))
                .post(body)
                .build()
        } catch (e: IllegalArgumentException) {
            callback(Result(null, "Invalid Gemini endpoint: ${e.message}"))
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
