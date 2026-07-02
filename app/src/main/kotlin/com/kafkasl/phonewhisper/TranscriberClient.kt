package com.kafkasl.phonewhisper

import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
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

    fun transcribe(wavData: ByteArray, apiKey: String, cancelHolder: InFlightCall, callback: (Result) -> Unit) {
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("model", "whisper-1")
            .addFormDataPart("file", "audio.wav", wavData.toRequestBody("audio/wav".toMediaType()))
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
