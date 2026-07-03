package com.kafkasl.phonewhisper

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
     */
    fun transcribe(pcmFile: File, apiKey: String, cancelHolder: InFlightCall, callback: (Result) -> Unit) {
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("model", "whisper-1")
            .addFormDataPart("file", "audio.wav", PcmWavRequestBody(pcmFile))
            .build()

        val request = Request.Builder()
            .url("https://api.openai.com/v1/audio/transcriptions")
            .header("Authorization", "Bearer $apiKey")
            .post(body)
            .build()

        val call = client.newCall(request)
        cancelHolder.set(call)
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                cancelHolder.clear(call)
                callback(Result(null, e.message))
            }
            override fun onResponse(call: Call, response: Response) {
                cancelHolder.clear(call)
                callback(parseResponse(response.body?.string() ?: ""))
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
