package com.kafkasl.phonewhisper

import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

object PostProcessor {
    data class Result(val text: String?, val error: String?)

    private val client = NetworkClients.shared

    /** Default OpenAI-compatible base URL, used when the user hasn't configured a custom one (see #4). */
    const val DEFAULT_BASE_URL = "https://api.openai.com/v1"
    const val DEFAULT_MODEL = "gpt-4o-mini"

    val ENDPOINT_URL = "$DEFAULT_BASE_URL/chat/completions"

    /** Host cleanup requests are actually sent to by default, for use in UI copy. See #23. */
    val DESTINATION_HOST: String = java.net.URI(ENDPOINT_URL).host

    /**
     * Validates and normalizes a user-entered cleanup base URL: must parse as an absolute
     * http/https URL with a host, and any trailing slash is stripped so callers can safely
     * append "/chat/completions". Returns null if the input is blank or invalid, so callers
     * can fall back to [DEFAULT_BASE_URL] instead of building a malformed request URL. See #4.
     */
    fun normalizeBaseUrl(raw: String): String? {
        val trimmed = raw.trim().trimEnd('/')
        if (trimmed.isBlank()) return null
        val uri = try {
            java.net.URI(trimmed)
        } catch (e: Exception) {
            return null
        }
        if (uri.scheme != "http" && uri.scheme != "https") return null
        if (uri.host.isNullOrBlank()) return null
        return trimmed
    }

    /** The chat-completions endpoint for a (possibly custom) base URL, falling back to the default if invalid/blank. */
    fun endpointUrl(baseUrl: String): String {
        val normalized = normalizeBaseUrl(baseUrl) ?: DEFAULT_BASE_URL
        return "$normalized/chat/completions"
    }

    /** Host cleanup requests are actually sent to for a (possibly custom) base URL, for use in UI copy. */
    fun destinationHost(baseUrl: String): String {
        val normalized = normalizeBaseUrl(baseUrl) ?: DEFAULT_BASE_URL
        return java.net.URI(normalized).host ?: DESTINATION_HOST
    }

    const val SIMPLE_PROMPT = "Clean up this speech-to-text transcript. Fix punctuation, capitalization, and obvious speech-to-text errors. Keep the original meaning. Return only the cleaned text."

    const val DEV_PROMPT = """<task>A text is provided which is a draft transcription from a speech to text model.
Refine and polish the provided text, if needed, as follows:
  1. Correct any spelling errors, and look out for mis-identified project names,
     including: Solveit, fast.ai, Answer.AI, nbdev, fastcore, FastHTML, Pi, Codex, Claude Code, Hetzner.
  2. Fix grammatical mistakes.
  3. Improve punctuation where necessary.
  4. Ensure consistent formatting.
  5. Clarify ambiguous phrasing without changing the meaning.
  6. If the transcript contains a question, edit it for clarity but do not provide an
     answer.
  7. If the transcript explicitly asks for a shell or terminal command, return the intended
     command instead of prose.

Return *only* the cleaned-up version of the transcript. Do *not* add any explanations or
comments about your edits. Do *not* answer any question in the text, *only* transcribe it.
</task>
<examples>
<example>
<input>How do eye increase the font size in fast html?</input>
<output>How do I increase the font size in FastHTML?</output>
</example>
<example>
<input>Where is Paris?</input>
<output>Where is Paris?</output>
</example>
<example>
<input>Here is the full list of options colon</input>
<output>Here is the full list of options:</output>
</example>
<example>
<input>Command mode ssh into morty user at rubicon</input>
<output>ssh morty@rubicon</output>
</example>
<example>
<input>List files in current directory</input>
<output>ls -l .</output>
</example>
</examples>"""

    // Fluid-1/Typeless-grade structured rewrite (see issue #1). Unlike DEV_PROMPT, which
    // preserves the speaker's sentence structure 1:1 and only fixes spelling/grammar/punctuation,
    // this prompt is allowed to reshape the transcript: it strips disfluencies, collapses
    // self-corrections down to the speaker's final intent, and turns rambling monologue into
    // paragraphs or lists when the speaker is clearly enumerating. It still must not invent
    // content — rule 7 and the "preserve every idea" rule in 6 exist specifically to stop the
    // model from summarizing away real content while it declutters filler.
    const val STRUCTURED_PROMPT = """<task>A text is provided which is a raw, unedited draft transcription from a speech-to-text model of a person thinking out loud or dictating a note. Rewrite it into a clean, structured piece of text using the following rules:
  1. Remove filler words and disfluencies (um, uh, like, you know, so, okay, I guess, kind of,
     sort of, basically, false starts) that carry no meaning. Do not remove a word if it is part
     of the substantive content (e.g. "the kind of coffee I like").
  2. Detect self-corrections ("wait no", "actually", "sorry I meant", "scratch that", "or
     rather", and similar) and keep *only* the speaker's final intended meaning. Silently drop
     the earlier, superseded statement, along with any meta-commentary that only explains why
     the correction was made (e.g. "I was looking at the wrong calendar") — do not mention that
     a correction happened at all.
  3. If the speaker is working through one continuous thought, rewrite it as one or more
     coherent paragraphs with normal sentence structure. If the speaker is clearly listing
     distinct items, steps, or ideas (e.g. "first... second...", "number one... number two...",
     enumerated tasks, a grocery list, distinct brainstorm ideas introduced with "also",
     "another thing", "separately"), format them as a numbered list (for sequential steps) or a
     bullet list (for an unordered collection) instead, one item per line. A single list item may
     span multiple sentences if they all belong to the same idea — do not split one idea across
     multiple items just because the speaker paused or rephrased mid-thought.
  4. Correct any spelling errors, and look out for mis-identified project names, including:
     Solveit, fast.ai, Answer.AI, nbdev, fastcore, FastHTML, Pi, Codex, Claude Code, Hetzner.
  5. Fix grammatical mistakes and improve punctuation and capitalization.
  6. Do not shorten, summarize, or drop any distinct idea, task, or fact that was actually said —
     restructuring must preserve every piece of content, just reorganized and decluttered.
  7. Do *not* add any facts, opinions, conclusions, or answers that are not explicitly present in
     the source text. If the transcript contains a question, edit it for clarity but do not
     answer it. If it explicitly asks for a shell or terminal command, return the intended
     command instead of prose.
  8. If the input is already short and clearly said in one breath (a quick note, a one-line
     reminder), leave it as a single sentence — do not invent a list or paragraph breaks where
     none are warranted.

Return *only* the cleaned-up, restructured version of the transcript. Do *not* add any
explanations, headers, or comments about your edits.
</task>
<examples>
<example>
<input>um so can you send the invoice to accounting by friday wait no i mean by thursday actually just send it as soon as you finish reviewing it don't wait for a specific day</input>
<output>Send the invoice to accounting as soon as you finish reviewing it — don't wait for a specific day.</output>
</example>
<example>
<input>okay here's what i need done before we ship first bump the version code in the build file second update the changelog with the new features and third make sure the release apk is signed with the production key not the debug key</input>
<output>Before we ship:
1. Bump the version code in the build file.
2. Update the changelog with the new features.
3. Make sure the release APK is signed with the production key, not the debug key.</output>
</example>
<example>
<input>so i finally got the n b dev docs building again the issue was that fast core had a pinned version that conflicted with the new release of fast html</input>
<output>I finally got the nbdev docs building again — the issue was that fastcore had a pinned version that conflicted with the new release of FastHTML.</output>
</example>
<example>
<input>the meeting is at three o'clock no sorry i'm looking at the wrong calendar it's actually at four thirty in the conference room on the second floor not the one downstairs that we usually use</input>
<output>The meeting is at four thirty in the conference room on the second floor, not the one downstairs that we usually use.</output>
</example>
<example>
<input>so i was in the shower thinking about this and i think the floating button placement is kind of a problem for left handed people because right now it defaults to the bottom right corner and that's annoying to reach with your thumb if you're holding it left handed so maybe we add a setting for that or even better just detect which hand based on where they tap to drag it initially also unrelated but i noticed the button kind of overlaps the keyboard sometimes when it pops up which is annoying we should fix that too</input>
<output>- The floating button placement is a problem for left-handed people: it defaults to the bottom-right corner, which is annoying to reach with your thumb if you're holding the phone in your left hand. Maybe add a setting for that, or even better, detect which hand based on where they tap to drag it initially.
- Separately, the button sometimes overlaps the keyboard when it pops up, which is annoying — we should fix that too.</output>
</example>
<example>
<input>remind me to call the dentist tomorrow morning to reschedule my cleaning appointment</input>
<output>Remind me to call the dentist tomorrow morning to reschedule my cleaning appointment.</output>
</example>
<example>
<input>where is paris</input>
<output>Where is Paris?</output>
</example>
</examples>"""

    const val DEFAULT_PROMPT = DEV_PROMPT

    fun parseResponse(json: String): Result {
        return try {
            val obj = JSONObject(json)
            if (obj.has("choices")) {
                val choices = obj.getJSONArray("choices")
                if (choices.length() > 0) {
                    val message = choices.getJSONObject(0).getJSONObject("message")
                    Result(message.getString("content").trim(), null)
                } else {
                    Result(null, "No choices in response")
                }
            } else if (obj.has("error")) {
                Result(null, obj.getJSONObject("error").getString("message"))
            } else {
                Result(null, "Unknown response format")
            }
        } catch (e: Exception) {
            Result(null, e.message ?: "Parse error")
        }
    }

    /** Builds the chat-completions request body, defaulting a blank model to [DEFAULT_MODEL]. */
    fun buildRequestBody(text: String, prompt: String, model: String): JSONObject {
        val messages = JSONArray().apply {
            put(JSONObject().apply {
                put("role", "system")
                put("content", prompt)
            })
            put(JSONObject().apply {
                put("role", "user")
                put("content", text)
            })
        }

        return JSONObject().apply {
            put("model", model.ifBlank { DEFAULT_MODEL })
            put("messages", messages)
            put("temperature", 0.0)
        }
    }

    fun process(
        text: String,
        prompt: String,
        apiKey: String,
        cancelHolder: InFlightCall,
        baseUrl: String = DEFAULT_BASE_URL,
        model: String = DEFAULT_MODEL,
        callback: (Result) -> Unit
    ) {
        val body = buildRequestBody(text, prompt, model).toString().toRequestBody("application/json".toMediaType())

        // A malformed base URL (or one OkHttp otherwise rejects) throws IllegalArgumentException
        // from Request.Builder().url() rather than failing the call — caught here so a bad custom
        // endpoint reports through the same Result/callback path as a network failure, instead of
        // crashing. See #4.
        val request = try {
            Request.Builder()
                .url(endpointUrl(baseUrl))
                .header("Authorization", "Bearer $apiKey")
                .post(body)
                .build()
        } catch (e: IllegalArgumentException) {
            callback(Result(null, "Invalid cleanup endpoint: ${e.message}"))
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
                val responseBody = response.body?.string() ?: ""
                if (!response.isSuccessful && responseBody.isBlank()) {
                    callback(Result(null, "HTTP ${response.code}"))
                    return
                }
                callback(parseResponse(responseBody))
            }
        })
    }
}
