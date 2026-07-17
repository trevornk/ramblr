package com.trevornk.ramblr

import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okio.BufferedSink
import okio.source
import org.json.JSONObject
import java.io.File
import java.io.IOException

object TranscriberClient {
    data class Result(val text: String?, val error: String?)

    private val client = NetworkClients.shared

    // whisper-1 -> gpt-4o-transcribe: real 20-clip benchmark (2026-07-10) showed lower WER
    // (2.86% vs 4.29%) and ~42% faster avg latency (0.81s vs 1.40s) on identical short
    // push-to-talk-style audio, with zero dropped/wrong words on either model including a
    // one-word clip -- the earlier "drops short words" concern did not reproduce.
    const val DEFAULT_MODEL = "gpt-4o-transcribe"

    /** The `/audio/transcriptions` endpoint for [baseUrl], normalized the same way the cleanup path
     *  normalizes its base URL (M5). A blank/malformed base falls back to OpenAI's default. */
    fun transcriptionEndpoint(baseUrl: String): String =
        (PostProcessor.normalizeBaseUrl(baseUrl) ?: PostProcessor.DEFAULT_BASE_URL) + "/audio/transcriptions"

    fun parseResponse(json: String): Result = try {
        val obj = JSONObject(json)
        when {
            obj.has("text") -> Result(obj.getString("text"), null)
            obj.has("error") -> Result(null, obj.getJSONObject("error").getString("message"))
            else -> Result(null, "Unknown response")
        }
    } catch (e: Exception) {
        Result(null, e.message ?: "Parse error")
    }

    /**
     * Streams [pcmFile]'s bytes into the multipart upload as a WAV file without ever holding the
     * full audio as one contiguous byte array: [PcmWavRequestBody] writes the 44-byte header
     * directly to the sink, then streams the PCM file's bytes straight through.
     *
     * [baseUrl]/[model] are honored so a proxy/self-hosted user's recordings actually go to their
     * configured OpenAI-compatible endpoint and model, not always api.openai.com/whisper-1 (M5) --
     * the cleanup path already honors the per-provider override, and [NetworkWarmup] even pre-warms
     * the override host, but the audio upload itself was hardcoded.
     */
    fun transcribe(
        pcmFile: File,
        apiKey: String,
        cancelHolder: InFlightCall,
        baseUrl: String = PostProcessor.DEFAULT_BASE_URL,
        model: String = DEFAULT_MODEL,
        vocabularyTerms: List<String> = emptyList(),
        callback: (Result) -> Unit,
    ) {
        val bodyBuilder = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("model", model.ifBlank { DEFAULT_MODEL })
        // #114 part 1: /v1/audio/transcriptions' `prompt` field biases decoding toward
        // vocabulary the user has taught Ramblr (project names/jargon), the same terms already
        // interpolated into cleanup-stage prompts (see PostProcessor.vocabularyClause). Always on
        // when terms exist -- unlike the cleanup interpolation this has no placeholder to opt
        // into, it's a plain comma-joined list the docs recommend for this exact use case.
        if (vocabularyTerms.isNotEmpty()) {
            bodyBuilder.addFormDataPart("prompt", VocabularyTerms.asTranscriptionPrompt(vocabularyTerms))
        }
        val body = bodyBuilder
            .addFormDataPart("file", "audio.wav", PcmWavRequestBody(pcmFile))
            .build()

        val endpoint = transcriptionEndpoint(baseUrl)
        // A malformed override URL throws IllegalArgumentException from url(); report it through the
        // same Result/callback path (query-redacted) instead of crashing (mirrors GeminiTranscriberClient).
        val request = try {
            Request.Builder()
                .url(endpoint)
                .header("Authorization", "Bearer $apiKey")
                .post(body)
                .build()
        } catch (e: IllegalArgumentException) {
            callback(Result(null, "Invalid transcription endpoint: ${UrlRedaction.redact(e.message)}"))
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
                // swallowed by OkHttp, so the callback (and the temp PCM file's deletion in it)
                // would otherwise never run and the dictation would hang until the watchdog.
                HttpBodyReader.read(response).fold(
                    onSuccess = { body -> callback(parseResponse(body)) },
                    onFailure = { e -> callback(Result(null, e.message)) },
                )
            }
        })
    }
}

/**
 * A [RequestBody] that streams a WAV file (44-byte header + PCM body) straight from [pcmFile] to
 * OkHttp's sink, so uploading never requires one contiguous byte array holding the whole
 * recording. See #16.
 */
class PcmWavRequestBody(
    private val pcmFile: File,
    private val sampleRate: Int = 16000,
    private val channels: Int = 1,
    private val bitsPerSample: Int = 16
) : RequestBody() {
    override fun contentType(): MediaType = "audio/wav".toMediaType()

    override fun contentLength(): Long = 44L + pcmFile.length()

    override fun writeTo(sink: BufferedSink) {
        sink.write(WavWriter.header(pcmFile.length(), sampleRate, channels, bitsPerSample))
        pcmFile.source().use { source -> sink.writeAll(source) }
    }
}
