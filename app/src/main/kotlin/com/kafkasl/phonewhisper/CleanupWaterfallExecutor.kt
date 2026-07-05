package com.kafkasl.phonewhisper

import java.util.concurrent.TimeUnit
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException

/**
 * Timeouts for one waterfall step's network client. See ADR-0001 / docs/adr/0001-cleanup-
 * waterfall.md: the connect timeout is deliberately short so an unreachable host (e.g. OmniRoute
 * while away from home) fails fast instead of hanging to the full read timeout, and the read
 * timeout absorbs a real but slow completion without letting one bad step wreck the whole chain.
 */
data class CleanupStepTimeouts(
    val connectMs: Long = 1_500L,
    val readMs: Long = 7_000L,
) {
    companion object {
        val DEFAULT = CleanupStepTimeouts()
    }
}

/** Hard cap on the whole waterfall, regardless of how many steps remain, before falling back to raw injection. */
const val CLEANUP_WATERFALL_HARD_CAP_MS = 15_000L

/**
 * Pure grouping/ordering logic for [CleanupWaterfall.steps], split out from the network-calling
 * executor so it's independently unit-testable without any I/O. Groups consecutive steps that
 * share a [CleanupStepGroup] into one fail-fast unit: if the first step in a group fails due to
 * a connection-level failure (host unreachable/timeout — see [CleanupStepOutcome.ConnectionFailed]),
 * every remaining step in that same group is skipped without being attempted, since they'd fail
 * identically against the same dead host. A non-connection failure (e.g. a real HTTP error from
 * a reachable host) only fails that one step; the next step in the same group is still attempted.
 */
object CleanupWaterfallPlanner {
    /**
     * Splits [steps] into runs of consecutive equal-[CleanupStep.group] entries, preserving
     * overall order. Each inner list is one fail-fast unit for [CleanupWaterfallExecutor].
     */
    fun groupConsecutive(steps: List<CleanupStep>): List<List<CleanupStep>> {
        if (steps.isEmpty()) return emptyList()
        val result = mutableListOf<MutableList<CleanupStep>>()
        for (step in steps) {
            val last = result.lastOrNull()
            if (last != null && last.last().group == step.group) {
                last.add(step)
            } else {
                result.add(mutableListOf(step))
            }
        }
        return result
    }

    /**
     * Flattens [groups] back into a single ordered index -> step list, used to translate a
     * cursor position (see [CleanupWaterfallCursor]) into "which step to start at".
     */
    fun flattenIndex(groups: List<List<CleanupStep>>): List<CleanupStep> = groups.flatten()
}

/** Why a waterfall step did not produce a result. Distinguishes connection-level failures
 *  (which disqualify the rest of that step's group) from step-level failures (which don't). */
sealed class CleanupStepOutcome {
    data class Success(val text: String) : CleanupStepOutcome()
    /** Host unreachable, connect timeout, or read timeout — the whole group is dead for this call. */
    data class ConnectionFailed(val message: String?) : CleanupStepOutcome()
    /** Reachable host, but this step failed on its own (bad model name, HTTP error, malformed response). */
    data class StepFailed(val message: String?) : CleanupStepOutcome()
    /** The user (or watchdog) cancelled the in-flight call — the whole waterfall must stop, not
     *  fall through to the next group; anything it produced would be discarded by the caller's
     *  guard token anyway, and a paid direct-provider step must never be billed for it (#63). */
    object Cancelled : CleanupStepOutcome()
}

/**
 * Tracks the index of the last waterfall step that succeeded, so the next cleanup call can
 * start there instead of re-probing from step 0 every time (see ADR-0001). Reset to 0 on
 * Android network-change (SSID/VPN change) by the caller (see WhisperAccessibilityService's
 * ConnectivityManager.NetworkCallback registration) and on idle expiry, both handled by the
 * owner of this cursor, not by this class itself — this class only tracks the index and idle
 * timing so both reset triggers can be tested without Android framework dependencies.
 */
class CleanupWaterfallCursor(private val idleExpiryMs: Long = 5 * 60 * 1000L) {
    @Volatile private var index: Int = 0
    @Volatile private var lastSuccessAt: Long = 0L

    /** The step index to start the next call at, given the current time. Expires to 0 if idle too long. */
    fun startIndex(nowMs: Long): Int {
        if (index != 0 && nowMs - lastSuccessAt > idleExpiryMs) {
            index = 0
        }
        return index
    }

    fun recordSuccess(stepIndex: Int, nowMs: Long) {
        index = stepIndex
        lastSuccessAt = nowMs
    }

    /** Called on Android connectivity/network change (SSID/VPN transition). */
    fun reset() {
        index = 0
    }
}

/** Raw result of one HTTP attempt, before it's interpreted as a [CleanupStepOutcome] (which
 *  requires knowing whether the body parses for that step's wire format). */
sealed class CleanupHttpOutcome {
    data class Ok(val body: String) : CleanupHttpOutcome()
    data class HttpError(val code: Int, val body: String) : CleanupHttpOutcome()
    data class ConnectionFailure(val message: String?) : CleanupHttpOutcome()
    /** The call was aborted via [InFlightCall.cancel] (user long-press or watchdog), not by the
     *  network. Kept distinct from [ConnectionFailure] because OkHttp reports a cancel through
     *  the same onFailure(IOException) path a dead host uses — treating it as one made a cancel
     *  *advance* the waterfall to the next (possibly paid) group instead of stopping it (#63). */
    object Cancelled : CleanupHttpOutcome()
}

/**
 * Seam over the network layer: [CleanupWaterfallExecutor] depends on this interface rather than
 * OkHttp directly so tests can fake HTTP responses (including connection failures) without any
 * real I/O. See [RealCleanupHttpTransport] for the production implementation.
 */
fun interface CleanupHttpTransport {
    fun send(
        url: String,
        headers: Map<String, String>,
        jsonBody: String,
        timeouts: CleanupStepTimeouts,
        cancelHolder: InFlightCall,
        callback: (CleanupHttpOutcome) -> Unit,
    )
}

/**
 * Outcome of one on-device llama.cpp completion, before it's interpreted as a
 * [CleanupStepOutcome]. Kept distinct from [CleanupHttpOutcome] because local inference has no
 * HTTP status code or connection-level failure -- it either produces text or it doesn't (#37).
 */
sealed class LocalInferenceResult {
    data class Success(val text: String) : LocalInferenceResult()
    data class Failure(val message: String) : LocalInferenceResult()
    /** The user (or watchdog) cancelled while inference was running (#83) -- like
     *  [CleanupHttpOutcome.Cancelled], this must stop the whole waterfall, not fall through. */
    object Cancelled : LocalInferenceResult()
}

/**
 * Seam over on-device llama.cpp inference (#37): [CleanupWaterfallExecutor] depends on this
 * interface rather than [LlamaCppInference] directly so tests can fake local-model completions
 * without any native code, mirroring how [CleanupHttpTransport] fakes the network layer for the
 * cloud provider groups. [systemPrompt]/[userText] are passed through unchanged from
 * [CleanupWaterfallExecutor.execute]'s `prompt`/`text` -- see [LocalCleanupProvider] for why no
 * translation is needed. [modelPath] is the absolute path to the installed GGUF file.
 * [deadlineAtMs]/[isCancelled] bound the otherwise-uninterruptible synchronous inference (#83):
 * generation must stop once the waterfall's wall-clock budget is spent or the user cancelled.
 */
fun interface LocalInferenceEngine {
    fun complete(
        systemPrompt: String,
        userText: String,
        modelPath: String,
        deadlineAtMs: Long,
        isCancelled: () -> Boolean,
    ): LocalInferenceResult
}

/** Production [LocalInferenceEngine]: drives the vendored/adapted llama.cpp JNI wrapper through
 *  [LocalCleanupModelHolder], which keeps the model loaded across dictations instead of paying a
 *  full GGUF load per call (#74). See [LlamaCppInference] for the native binding itself. */
object RealLocalInferenceEngine : LocalInferenceEngine {
    override fun complete(
        systemPrompt: String,
        userText: String,
        modelPath: String,
        deadlineAtMs: Long,
        isCancelled: () -> Boolean,
    ): LocalInferenceResult =
        try {
            if (isCancelled()) {
                LocalInferenceResult.Cancelled
            } else {
                val text = LocalCleanupModelHolder.withInference(modelPath) { inference ->
                    inference.complete(systemPrompt, userText, deadlineAtMs, isCancelled)
                }
                if (text.isNotBlank()) LocalInferenceResult.Success(text)
                else LocalInferenceResult.Failure("Local model produced an empty response")
            }
        } catch (e: Throwable) {
            // Broad catch is deliberate: this boundary must absorb everything up to and
            // including UnsatisfiedLinkError so a native problem fails the LOCAL_LLM step
            // cleanly instead of crashing the accessibility service. Note that since the
            // native build wiring landed (#37), UnsatisfiedLinkError here indicates a real
            // packaging regression, not an expected gap (#87 item 5) -- it is logged as a
            // step failure the user will see as cleanup falling through.
            if (isCancelled()) LocalInferenceResult.Cancelled
            else LocalInferenceResult.Failure(e.message ?: "Local inference failed")
        }
}

/** Production [CleanupHttpTransport]: a plain POST with per-step connect/read timeouts (ADR-0001). */
object RealCleanupHttpTransport : CleanupHttpTransport {
    override fun send(
        url: String,
        headers: Map<String, String>,
        jsonBody: String,
        timeouts: CleanupStepTimeouts,
        cancelHolder: InFlightCall,
        callback: (CleanupHttpOutcome) -> Unit,
    ) {
        val client = NetworkClients.shared.newBuilder()
            .connectTimeout(timeouts.connectMs, TimeUnit.MILLISECONDS)
            .readTimeout(timeouts.readMs, TimeUnit.MILLISECONDS)
            .build()
        val body = jsonBody.toRequestBody("application/json".toMediaType())
        val requestBuilder = Request.Builder().url(url).post(body)
        headers.forEach { (name, value) -> requestBuilder.header(name, value) }

        // A malformed URL throws IllegalArgumentException from Request.Builder().url() rather
        // than failing the call; treated as a connection failure so it advances the waterfall
        // instead of crashing (mirrors PostProcessor.process's handling of a bad custom base URL).
        val request = try {
            requestBuilder.build()
        } catch (e: IllegalArgumentException) {
            callback(CleanupHttpOutcome.ConnectionFailure(e.message))
            return
        }

        val call = client.newCall(request)
        cancelHolder.set(call)
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                cancelHolder.clear(call)
                if (call.isCanceled()) {
                    callback(CleanupHttpOutcome.Cancelled)
                } else {
                    callback(CleanupHttpOutcome.ConnectionFailure(e.message))
                }
            }

            override fun onResponse(call: Call, response: Response) {
                cancelHolder.clear(call)
                // A body read that dies mid-stream (stalled socket after headers arrived) must
                // report as a connection failure, not throw out of onResponse — OkHttp swallows
                // exceptions thrown here without ever calling onFailure, which would leave the
                // waterfall permanently stuck on this step (#62).
                HttpBodyReader.read(response).fold(
                    onSuccess = { responseBody ->
                        if (response.isSuccessful) {
                            callback(CleanupHttpOutcome.Ok(responseBody))
                        } else {
                            callback(CleanupHttpOutcome.HttpError(response.code, responseBody))
                        }
                    },
                    onFailure = { e -> callback(CleanupHttpOutcome.ConnectionFailure(e.message)) },
                )
            }
        })
    }
}

/**
 * The network-calling half of the cleanup waterfall (see ADR-0001 / docs/adr/0001-cleanup-
 * waterfall.md and [CleanupWaterfallPlanner] for the pure grouping logic this walks). Starts at
 * [cursor]'s last-known-good index, attempts steps in order, and on any [CleanupStepOutcome]
 * falls through to the next step — or, for a [CleanupStepOutcome.ConnectionFailed], skips every
 * remaining step in that same host group, since they'd fail identically against the same dead
 * host. No retries: this is a foreground call blocking a user waiting on their transcript.
 *
 * Deliberately takes no [android.content.Context] — [credentialLookup] is the caller's seam onto
 * [CleanupCredentialStore] (see [PostProcessor.processWaterfall]), and [transport] is the seam
 * onto the network. Both let this object's step-sequencing logic run in a plain JVM unit test
 * with fakes, with no real I/O and no Android framework dependency.
 */
object CleanupWaterfallExecutor {
    fun execute(
        text: String,
        prompt: String,
        waterfall: CleanupWaterfall,
        cursor: CleanupWaterfallCursor,
        cancelHolder: InFlightCall,
        legacyApiKey: String,
        legacyBaseUrl: String,
        credentialLookup: (CleanupCredentialSlot) -> String,
        transport: CleanupHttpTransport = RealCleanupHttpTransport,
        localInference: LocalInferenceEngine = RealLocalInferenceEngine,
        localModelPath: () -> String? = { null },
        callback: (PostProcessor.Result) -> Unit,
    ) {
        val steps = waterfall.steps
        if (steps.isEmpty()) {
            callback(PostProcessor.Result(null, "No cleanup steps configured"))
            return
        }

        val groups = CleanupWaterfallPlanner.groupConsecutive(steps)
        val groupRanges = groupBoundaries(groups)
        val startedAtMs = System.currentTimeMillis()
        val startIndex = cursor.startIndex(startedAtMs).takeIf { it in steps.indices } ?: 0
        // One waterfall run = one unit of cancellable work (#83): clear any cancel left over
        // from a previous dictation, and give the synchronous LOCAL_LLM step the same wall-clock
        // budget the between-step check below enforces.
        cancelHolder.beginWork()
        val deadlineAtMs = startedAtMs + CLEANUP_WATERFALL_HARD_CAP_MS

        fun nextGroupStart(index: Int): Int {
            val range = groupRanges.first { index in it }
            return range.last + 1
        }

        fun attempt(index: Int) {
            if (index >= steps.size) {
                callback(PostProcessor.Result(null, "All cleanup steps failed"))
                return
            }
            if (System.currentTimeMillis() - startedAtMs >= CLEANUP_WATERFALL_HARD_CAP_MS) {
                callback(PostProcessor.Result(null, "Cleanup waterfall exceeded time budget"))
                return
            }

            performStep(steps[index], text, prompt, legacyApiKey, legacyBaseUrl, credentialLookup, transport, localInference, localModelPath, cancelHolder, deadlineAtMs) { outcome ->
                when (outcome) {
                    is CleanupStepOutcome.Success -> {
                        cursor.recordSuccess(index, System.currentTimeMillis())
                        callback(PostProcessor.Result(outcome.text, null))
                    }
                    is CleanupStepOutcome.StepFailed -> attempt(index + 1)
                    is CleanupStepOutcome.ConnectionFailed -> attempt(nextGroupStart(index))
                    is CleanupStepOutcome.Cancelled -> callback(PostProcessor.Result(null, "Cleanup cancelled"))
                }
            }
        }

        attempt(startIndex)
    }

    private fun groupBoundaries(groups: List<List<CleanupStep>>): List<IntRange> {
        var offset = 0
        return groups.map { group ->
            (offset until offset + group.size).also { offset += group.size }
        }
    }

    /** Resolves one step's credential, builds its wire-format-specific request, and interprets the result. */
    private fun performStep(
        step: CleanupStep,
        text: String,
        prompt: String,
        legacyApiKey: String,
        legacyBaseUrl: String,
        credentialLookup: (CleanupCredentialSlot) -> String,
        transport: CleanupHttpTransport,
        localInference: LocalInferenceEngine,
        localModelPath: () -> String?,
        cancelHolder: InFlightCall,
        deadlineAtMs: Long,
        callback: (CleanupStepOutcome) -> Unit,
    ) {
        // LOCAL_LLM branches before the credential check below: it's the one group with no
        // credential slot and no network host, so "no credential configured" would be a
        // misleading failure reason for it (#37).
        if (step.group == CleanupStepGroup.LOCAL_LLM) {
            val modelPath = localModelPath()
            if (modelPath.isNullOrBlank()) {
                callback(CleanupStepOutcome.StepFailed("Local cleanup model not downloaded"))
                return
            }
            callback(
                when (val result = localInference.complete(prompt, text, modelPath, deadlineAtMs, { cancelHolder.isCancelled })) {
                    is LocalInferenceResult.Success -> {
                        // Trim to match what both cloud parsers already do to their model text
                        // (PostProcessor.parseResponse / AnthropicCleanupProvider.parseResponse):
                        // small local models routinely emit a leading space or newline as the
                        // first sampled piece, which was injected verbatim (#84).
                        val trimmed = result.text.trim()
                        if (trimmed.isNotEmpty()) CleanupStepOutcome.Success(trimmed)
                        else CleanupStepOutcome.StepFailed("Local model produced an empty response")
                    }
                    is LocalInferenceResult.Failure -> CleanupStepOutcome.StepFailed(result.message)
                    is LocalInferenceResult.Cancelled -> CleanupStepOutcome.Cancelled
                }
            )
            return
        }

        val apiKey = if (step.group == CleanupStepGroup.LEGACY) {
            legacyApiKey
        } else {
            step.credentialSlot()?.let(credentialLookup) ?: ""
        }
        if (apiKey.isBlank()) {
            // No live host was ever contacted, so this is a step-level (not connection-level)
            // failure: it only skips this one step, not the rest of its group.
            callback(CleanupStepOutcome.StepFailed("No credential configured for ${step.group}"))
            return
        }

        if (step.group == CleanupStepGroup.ANTHROPIC_DIRECT) {
            transport.send(
                AnthropicCleanupProvider.ENDPOINT_URL,
                AnthropicCleanupProvider.headers(apiKey),
                AnthropicCleanupProvider.buildRequestBody(text, prompt, step.model).toString(),
                CleanupStepTimeouts.DEFAULT,
                cancelHolder,
            ) { httpOutcome -> callback(toStepOutcome(httpOutcome, AnthropicCleanupProvider::parseResponse)) }
            return
        }

        val baseUrl = when (step.group) {
            CleanupStepGroup.LEGACY -> legacyBaseUrl
            CleanupStepGroup.OMNIROUTE -> OmniRoute.BASE_URL
            CleanupStepGroup.OPENAI_DIRECT -> step.baseUrlOverride ?: PostProcessor.DEFAULT_BASE_URL
            CleanupStepGroup.ANTHROPIC_DIRECT -> "" // unreachable, handled above
            CleanupStepGroup.LOCAL_LLM -> "" // unreachable, handled above
        }
        transport.send(
            PostProcessor.endpointUrl(baseUrl),
            mapOf("Authorization" to "Bearer $apiKey"),
            PostProcessor.buildRequestBody(text, prompt, step.model).toString(),
            CleanupStepTimeouts.DEFAULT,
            cancelHolder,
        ) { httpOutcome -> callback(toStepOutcome(httpOutcome, PostProcessor::parseResponse)) }
    }

    private fun toStepOutcome(httpOutcome: CleanupHttpOutcome, parse: (String) -> PostProcessor.Result): CleanupStepOutcome =
        when (httpOutcome) {
            is CleanupHttpOutcome.Cancelled -> CleanupStepOutcome.Cancelled
            is CleanupHttpOutcome.ConnectionFailure -> CleanupStepOutcome.ConnectionFailed(httpOutcome.message)
            is CleanupHttpOutcome.HttpError -> CleanupStepOutcome.StepFailed("HTTP ${httpOutcome.code}")
            is CleanupHttpOutcome.Ok -> {
                val result = parse(httpOutcome.body)
                if (!result.text.isNullOrBlank()) CleanupStepOutcome.Success(result.text)
                else CleanupStepOutcome.StepFailed(result.error)
            }
        }
}
