package com.trevornk.ramblr

/**
 * Runtime adapter from Phase 1's unified [ProviderChain] model into the legacy cleanup executor
 * shape. This deliberately keeps [CleanupWaterfallExecutor] itself unchanged for Phase 2: its
 * fail-fast grouping, cursor resume, and local-deadline behavior are already heavily tested and
 * are the high-risk part of Trevor's live dictation path.
 */
object ProviderChainRuntime {
    /** Provider-chain kinds that are modeled as capable but are not implemented by the live HTTP
     *  clients yet. Both are empty as of #96: Gemini's cleanup and transcription transports are
     *  wired up (see [GeminiCleanupProvider]/[GeminiTranscriberClient]). Kept as explicit sets
     *  (rather than deleted outright) so a future provider kind added to [ProviderKind] ahead of
     *  its transport has the same "skip explicitly, don't crash or misroute" escape hatch this
     *  used for Gemini during Phase 1/2. */
    val cleanupKindsNotImplemented: Set<ProviderKind> = emptySet()
    val transcriptionKindsNotImplemented: Set<ProviderKind> = emptySet()

    /**
     * Converts cleanup-capable provider entries to the [CleanupWaterfall] shape expected by the
     * existing executor.
     */
    fun cleanupWaterfallFor(chain: ProviderChain): CleanupWaterfall = CleanupWaterfall(
        chain.capableEntriesFor(needsTranscription = false).mapNotNull { entry ->
            when (entry.kind) {
                ProviderKind.OPENAI -> CleanupStep(CleanupStepGroup.OPENAI_DIRECT, entry.model, entry.baseUrlOverride)
                ProviderKind.ANTHROPIC -> CleanupStep(CleanupStepGroup.ANTHROPIC_DIRECT, entry.model, entry.baseUrlOverride)
                ProviderKind.GEMINI -> CleanupStep(CleanupStepGroup.GEMINI_DIRECT, entry.model, entry.baseUrlOverride)
                ProviderKind.OMNIROUTE -> CleanupStep(CleanupStepGroup.OMNIROUTE, entry.model, entry.baseUrlOverride)
                ProviderKind.LOCAL -> CleanupStep(CleanupStepGroup.LOCAL_LLM, entry.model, entry.baseUrlOverride)
            }
        }
    )

    /** True when [chain] is the unified equivalent of the old single LEGACY OpenAI cleanup path. */
    fun isSingleOpenAiCleanup(chain: ProviderChain): Boolean {
        val cleanupEntries = chain.capableEntriesFor(needsTranscription = false)
        return cleanupEntries.size == 1 && cleanupEntries[0].kind == ProviderKind.OPENAI
    }

    /** Same predicate as [PostProcessor.shouldUseWaterfallExecutor], but for ProviderChain. */
    fun shouldUseCleanupExecutor(chain: ProviderChain): Boolean = !isSingleOpenAiCleanup(chain)

    /** Maps executor credential slots to unified provider credential kinds. */
    fun providerKindForCleanupSlot(slot: CleanupCredentialSlot): ProviderKind = when (slot) {
        CleanupCredentialSlot.OMNIROUTE -> ProviderKind.OMNIROUTE
        CleanupCredentialSlot.OPENAI_DIRECT -> ProviderKind.OPENAI
        CleanupCredentialSlot.ANTHROPIC_DIRECT -> ProviderKind.ANTHROPIC
        CleanupCredentialSlot.GEMINI_DIRECT -> ProviderKind.GEMINI
    }

    /**
     * Ordered transcription candidates. When [allowLocalFallback] is true (the default,
     * preserving today's behavior), LOCAL is left in the result so cloud-mode callers can fall
     * through to the on-device floor if the chain says that is the next usable option. When
     * false (#100: explicit "fall back to on-device if cloud fails" toggle turned off), LOCAL is
     * stripped from the cloud candidate list entirely -- every cloud candidate failing means the
     * caller reports an error instead of silently retrying on-device.
     */
    fun transcriptionCandidates(chain: ProviderChain, allowLocalFallback: Boolean = true): List<ProviderChainEntry> {
        val candidates = chain.capableEntriesFor(needsTranscription = true)
            .filterNot { it.kind in transcriptionKindsNotImplemented }
        return if (allowLocalFallback) candidates else candidates.filterNot { it.kind == ProviderKind.LOCAL }
    }

    /**
     * Applies the Phase 3 "Use cloud for Cleanup" toggle ([CloudFeatureToggle.cleanupEnabled|
     * setCleanupEnabled]) ahead of cleanup resolution: when [cloudEnabled] is false, every
     * non-LOCAL entry is dropped from the chain before it's handed to [cleanupWaterfallFor],
     * so cleanup falls back to on-device (if the chain has a LOCAL entry) or is skipped entirely
     * (empty result -- the existing "no cleanup steps configured" raw-injection path). When
     * [cloudEnabled] is true (the default), [chain] passes through unchanged -- zero behavior
     * change for anyone who never touches the new toggle.
     *
     * [allowLocalFallback] (#100: explicit "fall back to on-device if cloud fails" toggle) only
     * matters when [cloudEnabled] is true: when false, the LOCAL floor is stripped from the
     * cloud chain too, so every cloud step failing means cleanup reports an error/raw-injects
     * instead of silently completing on-device.
     */
    fun effectiveChainForCleanup(chain: ProviderChain, cloudEnabled: Boolean, allowLocalFallback: Boolean = true): ProviderChain =
        if (cloudEnabled) {
            if (allowLocalFallback) chain else ProviderChain(chain.entries.filterNot { it.kind == ProviderKind.LOCAL })
        } else {
            ProviderChain(chain.entries.filter { it.kind == ProviderKind.LOCAL })
        }
}
