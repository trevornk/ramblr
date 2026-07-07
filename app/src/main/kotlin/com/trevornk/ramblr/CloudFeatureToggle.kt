package com.trevornk.ramblr

import android.content.Context

/**
 * The unified Cloud provider-chain screen's "Use cloud for Cleanup" toggle (#95 Phase 3).
 *
 * Transcription's cloud/local gate already exists and is read by the verified Phase 2 resolver
 * ([WhisperAccessibilityService.continueTranscription]'s `use_local` pref) -- the new Cloud
 * screen's "Use cloud for Transcription" switch is just that same pref shown under a clearer
 * label, so it needs no new toggle mechanism or resolver change.
 *
 * Cleanup had no equivalent: whether cleanup used a cloud provider was entirely a function of
 * what happened to be in the [ProviderChain] (a LOCAL entry ran local, a cloud entry ran cloud),
 * with no way to flip to "local only" without editing/removing chain entries. This object adds
 * that minimal, explicitly-scoped toggle: [cleanupEnabled] gates whether cleanup resolution
 * considers the chain's cloud-capable entries at all, defaulting to true (zero behavior change
 * for every existing chain). [ProviderChainRuntime.effectiveChainForCleanup] is the pure function
 * that applies the gate; [WhisperAccessibilityService]'s cleanup call site applies it before
 * building the executable waterfall -- this is the one, carefully-scoped addition to the
 * already-verified Phase 2 path called out in the Phase 3 task brief.
 */
object CloudFeatureToggle {
    private const val PREFS_NAME = "ramblr"
    private const val KEY_CLOUD_CLEANUP_ENABLED = "provider_chain_cloud_cleanup_enabled"
    private const val DEFAULT_CLOUD_CLEANUP_ENABLED = true

    fun cleanupEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_CLOUD_CLEANUP_ENABLED, DEFAULT_CLOUD_CLEANUP_ENABLED)

    fun setCleanupEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_CLOUD_CLEANUP_ENABLED, enabled).apply()
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
