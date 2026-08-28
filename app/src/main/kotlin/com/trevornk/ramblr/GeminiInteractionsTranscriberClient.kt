package com.trevornk.ramblr

import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Unary Gemini 3.5 Transcribe client using the resumable Files API and stateless Interactions API.
 *
 * This class is intentionally not wired into the provider catalog or runtime yet. It is a complete
 * production client so the benchmark can evaluate the dedicated ASR path before Ramblr exposes it.
 * Uploaded Files API resources are deleted best-effort after every terminal interaction outcome;
 * cleanup failures never replace the transcription result that caused cleanup.
 */
class GeminiInteractionsTranscriberClient(
    private val httpClient: OkHttpClient = NetworkClients.shared,
    private val uploadEndpoint: HttpUrl = DEFAULT_UPLOAD_ENDPOINT.toHttpUrl(),
    private val interactionsEndpoint: HttpUrl = DEFAULT_INTERACTIONS_ENDPOINT.toHttpUrl(),
    private val filesEndpoint: HttpUrl = DEFAULT_FILES_ENDPOINT.toHttpUrl(),
) {
    data class Result(val text: String?, val error: String?)

    enum class Mode(val wireValue: String) {
        VERBATIM("verbatim"),
        SMART("smart"),
    }

    private data class UploadedFile(val name: String, val uri: String)

    init {
        requireValidEndpoint(uploadEndpoint, "/upload/v1beta/files")
        requireValidEndpoint(interactionsEndpoint, "/v1beta/interactions")
        requireValidEndpoint(filesEndpoint, "/v1beta/files/")
    }

    /**
     * Uploads [audioFile], creates one stateless transcription interaction, and invokes [callback]
     * exactly once. [mimeType] must describe the complete local container (`audio/wav` or M4A's
     * `audio/mp4`; `audio/aac` is accepted for an AAC elementary stream).
     */
    fun transcribe(
        audioFile: File,
        mimeType: String,
        apiKey: String,
        model: String = DEFAULT_MODEL,
        customVocabulary: List<String> = emptyList(),
        languageCodes: List<String> = emptyList(),
        mode: Mode = Mode.VERBATIM,
        cancelHolder: InFlightCall,
        callback: (Result) -> Unit,
    ) {
        val completed = AtomicBoolean(false)
        fun complete(result: Result) {
            if (completed.compareAndSet(false, true)) callback(redact(result, apiKey))
        }

        validate(audioFile, mimeType, apiKey, model, customVocabulary, languageCodes)?.let {
            complete(Result(null, it))
            return
        }
        if (cancelHolder.isCancelled) {
            complete(Result(null, CANCELLED_ERROR))
            return
        }

        val metadata = JSONObject()
            .put("file", JSONObject().put("display_name", "ramblr-audio"))
            .toString()
            .toRequestBody(JSON_MEDIA_TYPE)
        val startRequest = Request.Builder()
            .url(uploadEndpoint)
            .header(API_KEY_HEADER, apiKey)
            .header("X-Goog-Upload-Protocol", "resumable")
            .header("X-Goog-Upload-Command", "start")
            .header("X-Goog-Upload-Header-Content-Length", audioFile.length().toString())
            .header("X-Goog-Upload-Header-Content-Type", mimeType)
            .post(metadata)
            .build()

        enqueueTracked(startRequest, cancelHolder,
            onFailure = { complete(Result(null, failureMessage(it))) },
            onResponse = { response, body ->
                if (!response.isSuccessful) {
                    complete(parseError(body, "Files API upload start failed (HTTP ${response.code})"))
                    return@enqueueTracked
                }
                val uploadUrl = response.header("X-Goog-Upload-URL")?.let(::validatedUploadUrl)
                if (uploadUrl == null) {
                    complete(Result(null, "Files API upload start returned no valid X-Goog-Upload-URL"))
                    return@enqueueTracked
                }
                uploadAndFinalize(
                    audioFile, mimeType, apiKey, model, customVocabulary, languageCodes, mode,
                    uploadUrl, cancelHolder, ::complete,
                )
            },
        )
    }

    private fun uploadAndFinalize(
        audioFile: File,
        mimeType: String,
        apiKey: String,
        model: String,
        customVocabulary: List<String>,
        languageCodes: List<String>,
        mode: Mode,
        uploadUrl: HttpUrl,
        cancelHolder: InFlightCall,
        complete: (Result) -> Unit,
    ) {
        val request = Request.Builder()
            .url(uploadUrl)
            .header("X-Goog-Upload-Offset", "0")
            .header("X-Goog-Upload-Command", "upload, finalize")
            .post(audioFile.asRequestBody(mimeType.toMediaType()))
            .build()
        enqueueTracked(request, cancelHolder,
            onFailure = { complete(Result(null, failureMessage(it))) },
            onResponse = { response, body ->
                if (!response.isSuccessful) {
                    complete(parseError(body, "Files API upload failed (HTTP ${response.code})"))
                    return@enqueueTracked
                }
                val uploaded = parseUploadedFile(body)
                if (uploaded == null) {
                    val primary = Result(null, "Files API upload response contained no valid file resource")
                    val fileName = parseUploadedFileName(body)
                    if (fileName != null) cleanup(fileName, apiKey, primary, complete) else complete(primary)
                    return@enqueueTracked
                }
                createInteraction(
                    uploaded, mimeType, apiKey, model, customVocabulary, languageCodes, mode,
                    cancelHolder, complete,
                )
            },
        )
    }

    private fun createInteraction(
        uploaded: UploadedFile,
        mimeType: String,
        apiKey: String,
        model: String,
        customVocabulary: List<String>,
        languageCodes: List<String>,
        mode: Mode,
        cancelHolder: InFlightCall,
        complete: (Result) -> Unit,
    ) {
        val transcriptionConfig = JSONObject()
            .put("custom_vocabulary", JSONArray(customVocabulary))
            .put("language_codes", JSONArray(languageCodes))
            .put("mode", JSONObject().put("type", mode.wireValue))
        val body = JSONObject()
            .put("model", model)
            .put("store", false)
            .put("input", JSONArray().put(JSONObject()
                .put("type", "audio")
                .put("uri", uploaded.uri)
                .put("mime_type", mimeType)))
            .put("generation_config", JSONObject().put("transcription_config", transcriptionConfig))
            .toString()
            .toRequestBody(JSON_MEDIA_TYPE)
        val request = Request.Builder()
            .url(interactionsEndpoint)
            .header(API_KEY_HEADER, apiKey)
            .post(body)
            .build()

        enqueueTracked(request, cancelHolder,
            onFailure = { error ->
                cleanup(uploaded.name, apiKey, Result(null, failureMessage(error)), complete)
            },
            onResponse = { response, responseBody ->
                val result = if (response.isSuccessful) parseResponse(responseBody)
                else parseError(responseBody, "Interactions API failed (HTTP ${response.code})")
                cleanup(uploaded.name, apiKey, result, complete)
            },
        )
    }

    /** Cleanup is deliberately untracked: a user's cancellation must abort inference, not the
     * best-effort deletion of the already-uploaded artifact. */
    private fun cleanup(fileName: String, apiKey: String, primary: Result, complete: (Result) -> Unit) {
        val id = FILE_NAME.matchEntire(fileName)?.groupValues?.get(1)
        if (id == null) {
            complete(primary)
            return
        }
        val deleteUrl = filesEndpoint.newBuilder().addPathSegment(id).build()
        val request = Request.Builder()
            .url(deleteUrl)
            .header(API_KEY_HEADER, apiKey)
            .delete()
            .build()
        httpClient.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) = complete(primary)
            override fun onResponse(call: Call, response: Response) {
                response.close()
                complete(primary)
            }
        })
    }

    private fun enqueueTracked(
        request: Request,
        cancelHolder: InFlightCall,
        onFailure: (IOException) -> Unit,
        onResponse: (Response, String) -> Unit,
    ) {
        if (cancelHolder.isCancelled) {
            onFailure(IOException(CANCELLED_ERROR))
            return
        }
        val call = httpClient.newCall(request)
        cancelHolder.set(call)
        if (cancelHolder.isCancelled) call.cancel()
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                cancelHolder.clear(call)
                onFailure(if (call.isCanceled()) IOException(CANCELLED_ERROR) else e)
            }

            override fun onResponse(call: Call, response: Response) {
                cancelHolder.clear(call)
                val headerSafeResponse = response
                HttpBodyReader.read(response).fold(
                    onSuccess = { onResponse(headerSafeResponse, it) },
                    onFailure = { onFailure(IOException(it.message ?: "Failed to read response", it)) },
                )
            }
        })
    }

    private fun validatedUploadUrl(raw: String): HttpUrl? = try {
        raw.toHttpUrl().takeIf {
            it.scheme == uploadEndpoint.scheme &&
                it.host == uploadEndpoint.host &&
                it.port == uploadEndpoint.port
        }
    } catch (_: IllegalArgumentException) {
        null
    }

    private fun parseUploadedFileName(body: String): String? = try {
        JSONObject(body).optJSONObject("file")?.optString("name")?.takeIf(FILE_NAME::matches)
    } catch (_: Exception) {
        null
    }

    private fun parseUploadedFile(body: String): UploadedFile? = try {
        val file = JSONObject(body).optJSONObject("file") ?: return null
        val name = file.optString("name")
        val uri = file.optString("uri")
        if (!FILE_NAME.matches(name) || uri.isBlank()) null else UploadedFile(name, uri)
    } catch (_: Exception) {
        null
    }

    private fun validate(
        audioFile: File,
        mimeType: String,
        apiKey: String,
        model: String,
        customVocabulary: List<String>,
        languageCodes: List<String>,
    ): String? = when {
        !audioFile.isFile || !audioFile.canRead() -> "Audio file is missing or unreadable"
        audioFile.length() <= 0 -> "Audio file is empty"
        mimeType !in SUPPORTED_MIME_TYPES -> "Unsupported audio MIME type: $mimeType"
        apiKey.isBlank() -> "Gemini API key is blank"
        !MODEL_ID.matches(model) -> "Invalid Gemini model id"
        customVocabulary.size > MAX_CUSTOM_VOCABULARY ->
            "Custom vocabulary exceeds the $MAX_CUSTOM_VOCABULARY-term API limit"
        customVocabulary.any { it.isBlank() } -> "Custom vocabulary contains a blank term"
        languageCodes.any { !LANGUAGE_CODE.matches(it) } -> "Invalid language code"
        else -> null
    }

    private fun redact(result: Result, apiKey: String): Result =
        if (apiKey.isBlank()) result else result.copy(error = result.error?.replace(apiKey, "[REDACTED]"))

    private fun failureMessage(error: IOException): String =
        error.message?.let(UrlRedaction::redact) ?: "Network request failed"

    companion object {
        const val DEFAULT_MODEL = "gemini-3.5-transcribe"
        const val MAX_CUSTOM_VOCABULARY = 1000
        const val DEFAULT_UPLOAD_ENDPOINT = "https://generativelanguage.googleapis.com/upload/v1beta/files"
        const val DEFAULT_INTERACTIONS_ENDPOINT = "https://generativelanguage.googleapis.com/v1beta/interactions"
        const val DEFAULT_FILES_ENDPOINT = "https://generativelanguage.googleapis.com/v1beta/files/"

        private const val API_KEY_HEADER = "x-goog-api-key"
        private const val CANCELLED_ERROR = "Transcription cancelled"
        private val JSON_MEDIA_TYPE = "application/json".toMediaType()
        private val SUPPORTED_MIME_TYPES = setOf("audio/wav", "audio/mp4", "audio/aac")
        private val MODEL_ID = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")
        private val LANGUAGE_CODE = Regex("[A-Za-z]{2,3}(?:-[A-Za-z0-9]{2,8})*")
        private val FILE_NAME = Regex("files/([a-z0-9](?:[a-z0-9-]{0,38}[a-z0-9])?)")

        private fun requireValidEndpoint(url: HttpUrl, expectedPath: String) {
            require(url.scheme == "https" || url.scheme == "http") { "Endpoint must use HTTP(S)" }
            require(url.host.isNotBlank()) { "Endpoint host is required" }
            require(url.encodedPath == expectedPath) { "Unexpected endpoint path" }
            require(url.query == null && url.fragment == null) { "Endpoint must not contain query or fragment" }
        }

        /** Parses all text blocks from model-output steps and ignores thoughts/tool output. */
        fun parseResponse(json: String): Result = try {
            val root = JSONObject(json)
            root.optJSONObject("error")?.let {
                return Result(null, it.optString("message").ifBlank { "Unknown Gemini error" })
            }
            val texts = mutableListOf<String>()
            val steps = root.optJSONArray("steps") ?: JSONArray()
            for (i in 0 until steps.length()) {
                val step = steps.optJSONObject(i) ?: continue
                if (step.optString("type") != "model_output") continue
                val content = step.optJSONArray("content") ?: continue
                for (j in 0 until content.length()) {
                    val part = content.optJSONObject(j) ?: continue
                    if (part.optString("type") == "text") {
                        part.optString("text").takeIf { it.isNotBlank() }?.let(texts::add)
                    }
                }
            }
            val text = texts.joinToString("").trim()
            if (text.isBlank()) Result(null, "No text content in response") else Result(text, null)
        } catch (e: Exception) {
            Result(null, e.message ?: "Parse error")
        }

        private fun parseError(body: String, fallback: String): Result {
            val parsed = parseResponse(body)
            return if (parsed.error != null && parsed.error != "No text content in response") parsed
            else Result(null, fallback)
        }
    }
}
