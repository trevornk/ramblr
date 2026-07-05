package com.kafkasl.phonewhisper

/**
 * Runtime adapter from Phase 1's unified [ProviderChain] model into the legacy cleanup executor
 * shape. This deliberately keeps [CleanupWaterfallExecutor] itself unchanged for Phase 2: its
 * fail-fast grouping, cursor resume, and local-deadline behavior are already heavily tested and
 * are the high-risk part of Trevor's live dictation path.
 */
object ProviderChainRuntime {
    /** Provider-chain kinds that are modeled as capable in Phase 1 but are not implemented by the
     * Phase 2 live HTTP clients yet. They are skipped explicitly rather than crashing or being
     * misrouted to an OpenAI-compatible endpoint. */
    val cleanupKindsNotImplemented: Set<ProviderKind> = setOf(ProviderKind.GEMINI)
    val transcriptionKindsNotImplemented: Set<ProviderKind> = setOf(ProviderKind.GEMINI)

    /**
     * Converts cleanup-capable provider entries to the [CleanupWaterfall] shape expected by the
     * existing executor. GEMINI is intentionally skipped for now: Phase 1 modeled its capability,
     * but Ramblr has no Gemini cleanup transport yet. Falling through preserves dictation instead
     * of breaking a chain that happens to contain a future-ready Gemini entry.
     */
    fun cleanupWaterfallFor(chain: ProviderChain): CleanupWaterfall = CleanupWaterfall(
        chain.capableEntriesFor(needsTranscription = false).mapNotNull { entry ->
            when (entry.kind) {
                ProviderKind.OPENAI -> CleanupStep(CleanupStepGroup.OPENAI_DIRECT, entry.model, entry.baseUrlOverride)
                ProviderKind.ANTHROPIC -> CleanupStep(CleanupStepGroup.ANTHROPIC_DIRECT, entry.model, entry.baseUrlOverride)
                ProviderKind.OMNIROUTE -> CleanupStep(CleanupStepGroup.OMNIROUTE, entry.model, entry.baseUrlOverride)
                ProviderKind.LOCAL -> CleanupStep(CleanupStepGroup.LOCAL_LLM, entry.model, entry.baseUrlOverride)
                ProviderKind.GEMINI -> null
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
    }

    /**
     * Ordered transcription candidates with unimplemented providers (currently GEMINI) explicitly
     * removed. LOCAL is left in the result so cloud-mode callers can fall through to the on-device
     * floor if the chain says that is the next usable option.
     */
    fun transcriptionCandidates(chain: ProviderChain): List<ProviderChainEntry> =
        chain.capableEntriesFor(needsTranscription = true)
            .filterNot { it.kind in transcriptionKindsNotImplemented }

    /**
     * Applies the Phase 3 "Use cloud for Cleanup" toggle ([CloudFeatureToggle.cleanupEnabled|
     * setCleanupEnabled]) ahead of cleanup resolution: when [cloudEnabled] is false, every
     * non-LOCAL entry is dropped from the chain before it's handed to [cleanupWaterfallFor],
     * so cleanup falls back to on-device (if the chain has a LOCAL entry) or is skipped entirely
     * (empty result -- the existing "no cleanup steps configured" raw-injection path). When
     * [cloudEnabled] is true (the default), [chain] passes through unchanged -- zero behavior
     * change for anyone who never touches the new toggle.
     */
    fun effectiveChainForCleanup(chain: ProviderChain, cloudEnabled: Boolean): ProviderChain =
        if (cloudEnabled) chain else ProviderChain(chain.entries.filter { it.kind == ProviderKind.LOCAL })
}
