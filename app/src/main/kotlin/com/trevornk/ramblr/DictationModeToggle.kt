package com.trevornk.ramblr

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
    private const val PREFS_NAME = "ramblr"
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

/**
 * Reads and applies the [DictationMode] presets for every screen that offers them (the Cloud
 * providers screen and the main screen's "Dictation mode" row), so both always flip the same two
 * prefs the same way.
 */
object DictationModePresets {
    /** Presets in display order. [DictationMode.CUSTOM] is never offered: it is only what the
     *  per-feature overrides add up to. */
    val OPTIONS = listOf(DictationMode.CLOUD, DictationMode.LOCAL, DictationMode.FASTEST)

    fun current(context: android.content.Context): DictationMode = DictationMode.resolve(
        useLocalTranscription = prefs(context).getBoolean("use_local", true),
        cloudCleanupEnabled = CloudFeatureToggle.cleanupEnabled(context),
    )

    /** Flips the two underlying per-feature toggles together; [DictationMode] has no persisted
     *  state of its own. */
    fun apply(context: android.content.Context, mode: DictationMode) {
        when (mode) {
            DictationMode.CLOUD -> {
                prefs(context).edit().putBoolean("use_local", false).apply()
                CloudFeatureToggle.setCleanupEnabled(context, true)
            }
            DictationMode.LOCAL -> {
                prefs(context).edit().putBoolean("use_local", true).apply()
                CloudFeatureToggle.setCleanupEnabled(context, false)
            }
            DictationMode.FASTEST -> {
                prefs(context).edit().putBoolean("use_local", true).apply()
                CloudFeatureToggle.setCleanupEnabled(context, true)
            }
            DictationMode.CUSTOM -> Unit // not directly selectable
        }
    }

    fun label(mode: DictationMode): String = when (mode) {
        DictationMode.CLOUD -> "Cloud — cloud transcription + cloud cleanup"
        DictationMode.LOCAL -> "Local — on-device transcription + on-device cleanup, fully offline"
        DictationMode.FASTEST -> "Fastest — on-device transcription (near-instant) + cloud cleanup"
        DictationMode.CUSTOM -> "Custom"
    }

    /** Main screen row subtitle. The presets only pick where cleanup runs; whether it runs at
     *  all is AI cleanup's own switch, so with that off the subtitle says so instead of
     *  promising a cleanup that won't happen. */
    fun summary(mode: DictationMode, cleanupOn: Boolean = true): String {
        if (!cleanupOn) {
            val speech = when (mode) {
                DictationMode.CLOUD -> "Cloud: speech goes to your cloud providers"
                DictationMode.LOCAL -> "Local: speech stays on this phone, works offline"
                DictationMode.FASTEST -> "Fastest: speech on this phone"
                DictationMode.CUSTOM -> "Custom mix (Cloud providers › Mix and match)"
            }
            return "$speech · AI cleanup is off"
        }
        return when (mode) {
            DictationMode.CLOUD -> "Cloud: speech and cleanup both go to your cloud providers"
            DictationMode.LOCAL -> "Local: everything stays on this phone, works offline"
            DictationMode.FASTEST -> "Fastest: speech on this phone, cleanup in the cloud"
            DictationMode.CUSTOM -> "Custom mix: set under Cloud providers › Mix and match"
        }
    }

    private fun prefs(context: android.content.Context) =
        context.getSharedPreferences("ramblr", android.content.Context.MODE_PRIVATE)
}
