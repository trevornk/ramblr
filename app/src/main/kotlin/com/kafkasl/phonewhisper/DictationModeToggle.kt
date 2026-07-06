package com.kafkasl.phonewhisper

import android.content.Context

/**
 * Explicit fallback toggles for the unified Cloud provider-chain screen (#100 follow-up).
 *
 * Before this existed, the fall-through behavior between cloud and on-device was entirely
 * implicit in code: [ProviderChainRuntime.transcriptionCandidates] always left LOCAL in the
 * candidate list as an unconditional floor, and [ProviderChainRuntime.effectiveChainForCleanup]
 * likewise never removed it. That meant a user who picked "cloud" for transcription/cleanup had
 * no way to know -- let alone control -- whether a failed cloud call would silently retry
 * on-device, and a user in "local" mode had no way to opt into a cloud retry if the local model
 * wasn't ready. Trevor's ask (#100): make both directions of fallback an explicit, visible
 * setting instead of a backend behavior nobody can see or turn off.
 *
 * Two independent gates:
 *  - [allowLocalFallback]: when cloud is in use for a feature and every cloud candidate fails/is
 *    unavailable, may the resolver fall through to on-device? Defaults to true -- this preserves
 *    every existing chain's actual behavior today (zero behavior change for anyone who never
 *    touches the new toggle).
 *  - [allowCloudFallback]: when on-device is in use for a feature and the local model isn't
 *    ready/fails, may the resolver retry using the cloud chain instead of erroring out? Defaults
 *    to false, since this is genuinely new behavior (today: local failure is always a hard
 *    error/toast, never a network call) and should not be silently opted into.
 *
 * These gates are pure product-facing to-cloud/to-local *direction* controls -- they say nothing
 * about which specific cloud provider is used first; that's still the existing ordered
 * [ProviderChain].
 */
object DictationModeToggle {
    private const val PREFS_NAME = "phonewhisper"
    private const val KEY_ALLOW_LOCAL_FALLBACK = "allow_local_fallback"
    private const val KEY_ALLOW_CLOUD_FALLBACK = "allow_cloud_fallback"
    private const val DEFAULT_ALLOW_LOCAL_FALLBACK = true
    private const val DEFAULT_ALLOW_CLOUD_FALLBACK = false

    fun allowLocalFallback(context: Context): Boolean =
        prefs(context).getBoolean(KEY_ALLOW_LOCAL_FALLBACK, DEFAULT_ALLOW_LOCAL_FALLBACK)

    fun setAllowLocalFallback(context: Context, allowed: Boolean) {
        prefs(context).edit().putBoolean(KEY_ALLOW_LOCAL_FALLBACK, allowed).apply()
    }

    fun allowCloudFallback(context: Context): Boolean =
        prefs(context).getBoolean(KEY_ALLOW_CLOUD_FALLBACK, DEFAULT_ALLOW_CLOUD_FALLBACK)

    fun setAllowCloudFallback(context: Context, allowed: Boolean) {
        prefs(context).edit().putBoolean(KEY_ALLOW_CLOUD_FALLBACK, allowed).apply()
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}

/**
 * The three user-facing dictation modes shown on the unified Cloud screen (#100). This is a
 * display/selection convenience layered on top of the existing `use_local` (transcription) and
 * [CloudFeatureToggle] (cleanup) prefs -- it does not replace them, since a power user can still
 * end up in a state that doesn't cleanly match any of these three (e.g. cloud transcription +
 * local cleanup) by hand-editing the underlying toggles. [resolve] reports that case as
 * [CUSTOM] rather than forcing a bucket that would misdescribe the actual configuration.
 */
enum class DictationMode {
    /** Cloud transcription + cloud cleanup. */
    CLOUD,
    /** On-device transcription + on-device cleanup. */
    LOCAL,
    /** On-device transcription (near-instant, no network round trip) + cloud cleanup (best
     *  quality text). Mirrors FluidVoice's local-STT/cloud-enhancement recipe. */
    FASTEST,
    /** Transcription and cleanup are set to different cloud/local combinations than any of the
     *  three presets above -- only reachable via manual toggle edits, not by picking a preset. */
    CUSTOM;

    companion object {
        fun resolve(useLocalTranscription: Boolean, cloudCleanupEnabled: Boolean): DictationMode = when {
            !useLocalTranscription && cloudCleanupEnabled -> CLOUD
            useLocalTranscription && !cloudCleanupEnabled -> LOCAL
            useLocalTranscription && cloudCleanupEnabled -> FASTEST
            else -> CUSTOM // cloud transcription + local-only cleanup
        }
    }
}
