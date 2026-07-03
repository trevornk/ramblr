package com.kafkasl.phonewhisper

/**
 * Which physical service a waterfall step talks to. Steps sharing a [group] share a network
 * host and fail together: one connection failure to that host disqualifies every step in the
 * group for this call, instead of retrying each sub-step against the same dead host (see
 * ADR-0001 / docs/adr/0001-cleanup-waterfall.md, and the independent architecture review that
 * flagged this as the critical fix over a naive flat sequential waterfall).
 *
 * LEGACY is distinct from OPENAI_DIRECT on purpose: LEGACY reads from the existing
 * [ApiKeyStore]/[PostProcessor] single-key fields (issue #4's pre-waterfall config) so a fresh
 * install or an unmigrated user sees zero behavior change, while OPENAI_DIRECT reads from the
 * new [CleanupCredentialStore] slot a user explicitly configures as part of a real waterfall.
 */
enum class CleanupStepGroup { LEGACY, OMNIROUTE, OPENAI_DIRECT, ANTHROPIC_DIRECT }

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
    /** Null for LEGACY steps, which authenticate via [ApiKeyStore] instead. */
    fun credentialSlot(): CleanupCredentialSlot? = when (group) {
        CleanupStepGroup.LEGACY -> null
        CleanupStepGroup.OMNIROUTE -> CleanupCredentialSlot.OMNIROUTE
        CleanupStepGroup.OPENAI_DIRECT -> CleanupCredentialSlot.OPENAI_DIRECT
        CleanupStepGroup.ANTHROPIC_DIRECT -> CleanupCredentialSlot.ANTHROPIC_DIRECT
    }
}

/**
 * The user's configured cleanup waterfall, in priority order. Two or more steps may share a
 * [CleanupStep.group] (e.g. three OmniRoute sub-steps for Claude/OpenAI/Gemini); the executor
 * (see [CleanupWaterfallExecutor]) treats consecutive same-group steps as one fail-fast unit.
 */
data class CleanupWaterfall(val steps: List<CleanupStep>) {
    companion object {
        /**
         * Default out-of-box waterfall for a fresh install or anyone who hasn't configured the
         * waterfall feature: a single LEGACY step using today's existing OpenAI cloud key/
         * base_url/model fields (see [PostProcessor.DEFAULT_BASE_URL]/[PostProcessor.DEFAULT_MODEL]
         * and [ApiKeyStore]) — zero behavior change from pre-waterfall Ramblr.
         */
        val LEGACY_SINGLE_STEP = CleanupWaterfall(
            steps = listOf(CleanupStep(CleanupStepGroup.LEGACY, PostProcessor.DEFAULT_MODEL))
        )
    }
}
