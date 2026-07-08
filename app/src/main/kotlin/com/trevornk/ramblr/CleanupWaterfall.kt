package com.trevornk.ramblr

/**
 * Which encrypted credential slot a cleanup-waterfall step authenticates against, resolved at
 * runtime via [ProviderChainRuntime.providerKindForCleanupSlot] into the unified
 * [ProviderCredentialStore]. OMNIROUTE covers three model sub-steps (Claude, OpenAI/Codex,
 * Gemini) behind one shared consumer key and fixed base URL; OPENAI_DIRECT/ANTHROPIC_DIRECT/
 * GEMINI_DIRECT are the pay-per-token fallbacks used when OmniRoute (home LAN/VPN only) is
 * unreachable.
 */
enum class CleanupCredentialSlot { OMNIROUTE, OPENAI_DIRECT, ANTHROPIC_DIRECT, GEMINI_DIRECT }

/**
 * Which physical service a waterfall step talks to. Steps sharing a [group] share a network
 * host and fail together: one connection failure to that host disqualifies every step in the
 * group for this call, instead of retrying each sub-step against the same dead host (see
 * ADR-0001 / docs/adr/0001-cleanup-waterfall.md, and the independent architecture review that
 * flagged this as the critical fix over a naive flat sequential waterfall).
 *
 * LEGACY is distinct from OPENAI_DIRECT on purpose: LEGACY reads from [PostProcessor]'s existing
 * single-key/base_url/model fields (issue #4's pre-waterfall config) so a fresh install sees
 * zero behavior change, while OPENAI_DIRECT reads from a [CleanupCredentialSlot] a user
 * explicitly configures as part of a real waterfall.
 *
 * LOCAL_LLM (#37) is the odd one out: it isn't a network host group at all -- it runs cleanup
 * on-device via llama.cpp (see [LocalCleanupProvider]/[LlamaCppInference]) against the one
 * curated model in [LOCAL_CLEANUP_MODEL_CATALOG]. It needs no credential ([CleanupStep
 * .credentialSlot] returns null, same as LEGACY) and never fails at the "host unreachable"
 * level, so grouping it with other steps is harmless but pointless -- it either succeeds or
 * fails as a single step regardless of neighbors.
 */
enum class CleanupStepGroup { LEGACY, OMNIROUTE, OPENAI_DIRECT, ANTHROPIC_DIRECT, GEMINI_DIRECT, LOCAL_LLM }

/**
 * True for the groups that are billed per-token out of the user's own wallet (see ADR-0001's
 * "served by paid fallback" badge, issue #33). OMNIROUTE is subscription-billed -- effectively
 * free at the margin despite being a network fallback -- and LEGACY/LOCAL_LLM have no
 * incremental cost either, so only the two direct-provider groups count as a "paid fallback"
 * from the user's cost perspective.
 */
fun CleanupStepGroup.isPaidFallback(): Boolean =
    this == CleanupStepGroup.OPENAI_DIRECT || this == CleanupStepGroup.ANTHROPIC_DIRECT ||
        this == CleanupStepGroup.GEMINI_DIRECT

/**
 * One entry in the user-configured cleanup waterfall. [group] determines which credential and
 * base host this step uses; [model] is the model string sent to that provider (e.g.
 * "claude/claude-sonnet-4-6" for an OmniRoute step, "gpt-4o-mini" for a direct-OpenAI step).
 * [baseUrlOverride] is only meaningful for OPENAI_DIRECT (OmniRoute's URL is fixed;
 * ANTHROPIC_DIRECT always targets Anthropic's real API) and lets a user point the direct-OpenAI
 * step at a third OpenAI-compatible host if they want a fourth provider without touching code.
 * LEGACY steps ignore [baseUrlOverride] entirely; they always use [PostProcessor]'s existing
 * base_url setting.
 */
data class CleanupStep(
    val group: CleanupStepGroup,
    val model: String,
    val baseUrlOverride: String? = null,
) {
    /** Null for LEGACY steps, which authenticate via [PostProcessor]'s legacy key field instead,
     *  and for LOCAL_LLM steps, which run on-device and have nothing to authenticate against (#37). */
    fun credentialSlot(): CleanupCredentialSlot? = when (group) {
        CleanupStepGroup.LEGACY -> null
        CleanupStepGroup.OMNIROUTE -> CleanupCredentialSlot.OMNIROUTE
        CleanupStepGroup.OPENAI_DIRECT -> CleanupCredentialSlot.OPENAI_DIRECT
        CleanupStepGroup.ANTHROPIC_DIRECT -> CleanupCredentialSlot.ANTHROPIC_DIRECT
        CleanupStepGroup.GEMINI_DIRECT -> CleanupCredentialSlot.GEMINI_DIRECT
        CleanupStepGroup.LOCAL_LLM -> null
    }
}

/**
 * The user's configured cleanup waterfall, in priority order. Two or more steps may share a
 * [CleanupStep.group] (e.g. three OmniRoute sub-steps for Claude/OpenAI/Gemini); the executor
 * (see [CleanupWaterfallExecutor]) treats consecutive same-group steps as one fail-fast unit.
 */
data class CleanupWaterfall(val steps: List<CleanupStep>) {
    /** True when at least one configured step would run through [CleanupStepGroup.LOCAL_LLM]
     *  (#95) -- used to decide whether pre-warming the local model at recording-start is worth
     *  doing at all. A waterfall can be a mix of local and cloud steps: even one LOCAL_LLM step
     *  justifies paying the warm-up cost. */
    fun usesLocalLlm(): Boolean =
        steps.any { it.group == CleanupStepGroup.LOCAL_LLM }

    companion object {
        /**
         * Default out-of-box waterfall for a fresh install or anyone who hasn't configured the
         * waterfall feature: a single LEGACY step using today's existing OpenAI cloud key/
         * base_url/model fields (see [PostProcessor.DEFAULT_BASE_URL]/[PostProcessor.DEFAULT_MODEL])
         * -- zero behavior change from pre-waterfall Ramblr.
         */
        val LEGACY_SINGLE_STEP = CleanupWaterfall(
            steps = listOf(CleanupStep(CleanupStepGroup.LEGACY, PostProcessor.DEFAULT_MODEL))
        )
    }
}
