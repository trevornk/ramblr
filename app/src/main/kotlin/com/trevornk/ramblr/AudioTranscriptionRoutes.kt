package com.trevornk.ramblr

/**
 * #285: decides WHICH transcriber(s) an audio-file job uses and HOW BIG each request may be, from
 * the same settings live dictation reads (local-vs-cloud, the provider chain, the fall-back
 * toggles). Pure: [DictationRuntime.continueTranscription] / `transcribeApi` make the equivalent
 * choice inline against a recording; jobs need it as data so it can be tested and so the chunk size
 * can follow the chosen route.
 */
sealed class AudioRoute {
    object Local : AudioRoute()
    data class Cloud(val entry: ProviderChainEntry) : AudioRoute()
}

data class AudioRoutePlan(
    val routes: List<AudioRoute>,
    /** Why [routes] is empty (null when it is not). */
    val unavailable: JobFailure? = null,
)

object AudioTranscriptionRoutes {

    /**
     * @param useLocal the "use_local" setting (on-device transcription is the user's choice)
     * @param localReady an on-device model is installed and can be loaded
     * @param allowCloudFallback "fall back to cloud if on-device fails" (off by default): the only
     *        way audio leaves the device for a user who chose on-device transcription
     * @param candidates [ProviderChainRuntime.transcriptionCandidates] (already honors the
     *        local-fallback toggle)
     * @param hasCredential whether a cloud entry has a usable credential
     */
    fun plan(
        useLocal: Boolean,
        localReady: Boolean,
        allowCloudFallback: Boolean,
        candidates: List<ProviderChainEntry>,
        hasCredential: (ProviderChainEntry) -> Boolean,
    ): AudioRoutePlan {
        val cloud = candidates
            .filter { it.kind == ProviderKind.OPENAI || it.kind == ProviderKind.GEMINI }
            .filter(hasCredential)
            .map { AudioRoute.Cloud(it) }

        if (useLocal) {
            val routes = buildList {
                if (localReady) add(AudioRoute.Local)
                if (allowCloudFallback) addAll(cloud)
            }
            return when {
                routes.isNotEmpty() -> AudioRoutePlan(routes)
                else -> AudioRoutePlan(emptyList(), JobFailure.LOCAL_UNAVAILABLE)
            }
        }

        // Cloud-first: walk the chain in order, keeping an on-device entry where the chain has one.
        val routes = candidates.mapNotNull { entry ->
            when (entry.kind) {
                ProviderKind.LOCAL -> if (localReady) AudioRoute.Local else null
                ProviderKind.OPENAI, ProviderKind.GEMINI -> if (hasCredential(entry)) AudioRoute.Cloud(entry) else null
                else -> null
            }
        }
        return if (routes.isNotEmpty()) AudioRoutePlan(routes) else AudioRoutePlan(emptyList(), JobFailure.NO_PROVIDER)
    }

    private const val SAMPLE_RATE = AudioChunkPlanner.SAMPLE_RATE

    /** Cloud request ceiling. 5 min of 16 kHz PCM16 is 9.6 MB: under OpenAI-compatible endpoints'
     *  25 MB upload cap with room for a WAV header and slower models, and under Gemini's 10 MB
     *  inline-audio limit ([GeminiTranscriberClient.MAX_INLINE_PCM_BYTES]). Also keeps one reply
     *  inside a transcription model's output-token budget. */
    const val CLOUD_CHUNK_SAMPLES = 5L * 60 * SAMPLE_RATE

    /** On-device with the VAD model: the recognizer already splits into <=15 s speech segments
     *  (#132), so chunks only bound progress granularity and temp-file size. */
    const val LOCAL_VAD_CHUNK_SAMPLES = 5L * 60 * SAMPLE_RATE

    /** On-device WITHOUT the VAD model: one chunk is one decode, and Whisper-class models see at
     *  most ~30 s, so stay under it. */
    const val LOCAL_UNSEGMENTED_CHUNK_SAMPLES = 25L * SAMPLE_RATE

    /** Largest chunk safe for EVERY route in [plan]: a fallback to a later route must never get a
     *  request bigger than it accepts. */
    fun maxChunkSamples(plan: AudioRoutePlan, vadAvailable: Boolean): Long {
        require(plan.routes.isNotEmpty())
        return plan.routes.minOf {
            when (it) {
                is AudioRoute.Cloud -> CLOUD_CHUNK_SAMPLES
                AudioRoute.Local -> if (vadAvailable) LOCAL_VAD_CHUNK_SAMPLES else LOCAL_UNSEGMENTED_CHUNK_SAMPLES
            }
        }
    }

    /** Cut-search window: wider when chunks are large, tight for the 25 s case. */
    fun searchBackSamples(maxChunkSamples: Long): Long =
        if (maxChunkSamples <= LOCAL_UNSEGMENTED_CHUNK_SAMPLES) 5L * SAMPLE_RATE else AudioChunkPlanner.DEFAULT_SEARCH_BACK_SAMPLES
}

/** Outcome of one route's attempt on one chunk. */
sealed class ChunkAttempt {
    data class Text(val text: String) : ChunkAttempt()
    data class Error(val retryable: Boolean = true) : ChunkAttempt()
    object Cancelled : ChunkAttempt()
}

/**
 * Walks [routes] for one chunk, starting at the route that last worked (so a dead provider is not
 * re-tried for every one of 40 chunks) and wrapping around, like the live path's candidate walk
 * (#H1). Returns the text and the winning route index, or null text if every route failed.
 */
object ChunkWalk {
    data class Result(val text: String?, val routeIndex: Int, val cancelled: Boolean)

    fun run(routeCount: Int, startIndex: Int, attempt: (Int) -> ChunkAttempt): Result {
        require(routeCount > 0)
        for (step in 0 until routeCount) {
            val i = (startIndex + step) % routeCount
            when (val a = attempt(i)) {
                is ChunkAttempt.Text -> return Result(a.text, i, false)
                ChunkAttempt.Cancelled -> return Result(null, startIndex, true)
                is ChunkAttempt.Error -> Unit
            }
        }
        return Result(null, startIndex, false)
    }
}
