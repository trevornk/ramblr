package com.trevornk.ramblr

/**
 * Pure "effective routing" summary formatters for #276: a concise, read-only line naming what
 * will actually happen for a dictation right now -- e.g. `"Cloud (OpenAI) \u2192 on-device
 * fallback"` or `"On-device only"` -- computed from the same prefs/chain/toggles the resolver
 * itself reads ([ProviderChainRuntime], [DictationModeToggle], [CloudFeatureToggle]), so the row
 * can never claim a routing the runtime wouldn't actually take.
 *
 * Reuses the existing [CleanupDestination]/[hasConfiguredCloudTranscription] helpers other
 * subtitles already use (see [CloudProviderActivity.cloudTranscriptionSubtitle]) rather than
 * re-deriving "which provider, is it configured" -- read-only [ProviderChain] use throughout, no
 * mutation.
 */
object EffectiveRouting {

    /**
     * "Transcription: ..." body (caller prefixes the label), e.g.:
     *  - "Cloud (OpenAI) \u2192 on-device fallback" -- cloud active, configured, local fallback on
     *  - "Cloud (not configured) \u2192 on-device fallback" -- cloud active, no key set yet
     *  - "Cloud (OpenAI) only" -- cloud active, local fallback off (a cloud failure is an error)
     *  - "On-device \u2192 cloud fallback (OpenAI)" -- local active, cloud fallback on and configured
     *  - "On-device only" -- local active, cloud fallback off
     */
    fun transcription(
        chain: ProviderChain,
        useLocalTranscription: Boolean,
        allowLocalFallback: Boolean,
        allowCloudFallback: Boolean,
        isConfigured: (ProviderKind) -> Boolean,
    ): String {
        val cloudEntry = CleanupDestination.firstCloudTranscription(chain)
        val cloudConfigured = hasConfiguredCloudTranscription(chain, isConfigured)
        val cloudLabel = cloudEntry?.let { CleanupDestination.label(it.kind) }
        val cloudDescription = if (cloudConfigured && cloudLabel != null) "Cloud ($cloudLabel)" else "Cloud (not configured)"

        return if (useLocalTranscription) {
            if (allowCloudFallback) {
                "On-device \u2192 cloud fallback (${if (cloudConfigured && cloudLabel != null) cloudLabel else "not configured"})"
            } else {
                "On-device only"
            }
        } else {
            if (allowLocalFallback) "$cloudDescription \u2192 on-device fallback" else "$cloudDescription only"
        }
    }

    /**
     * "Cleanup: ..." body (caller prefixes the label). Cleanup has only one fallback direction
     * today ([ProviderChainRuntime.effectiveChainForCleanup]'s [allowLocalFallback] gate) -- a
     * local-only cleanup config never automatically retries in the cloud, so unlike
     * [transcription] there is no "on-device \u2192 cloud fallback" case here.
     */
    fun cleanup(
        chain: ProviderChain,
        postProcessingEnabled: Boolean,
        cloudCleanupEnabled: Boolean,
        allowLocalFallback: Boolean,
        isConfigured: (ProviderKind) -> Boolean,
    ): String {
        if (!postProcessingEnabled) return "Off"
        if (!cloudCleanupEnabled) return "On-device only"

        val cloudEntries = chain.capableEntriesFor(needsTranscription = false).filter { it.kind != ProviderKind.LOCAL }
        val cloudConfigured = cloudEntries.any { isConfigured(it.kind) }
        val cloudLabel = cloudEntries.firstOrNull()?.let { CleanupDestination.label(it.kind) }
        val cloudDescription = if (cloudConfigured && cloudLabel != null) "Cloud ($cloudLabel)" else "Cloud (not configured)"

        return if (allowLocalFallback) "$cloudDescription \u2192 on-device fallback" else "$cloudDescription only"
    }
}
