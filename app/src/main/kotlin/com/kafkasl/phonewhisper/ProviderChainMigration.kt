package com.kafkasl.phonewhisper

import android.content.Context

/**
 * One-time, idempotent migration from the legacy split state ([ApiKeyStore] +
 * [CleanupCredentialStore] + [CleanupWaterfallStore]) into the unified [ProviderChain] +
 * [ProviderCredentialStore] model (Phase 1 of the provider-chain unification).
 *
 * Deliberately does NOT delete or mutate any legacy state: [ApiKeyStore]'s key,
 * [CleanupCredentialStore]'s three slots, and [CleanupWaterfallStore]'s "cleanup_waterfall_steps"
 * value are all left exactly as they were, because [WhisperAccessibilityService] and
 * [CleanupWaterfallExecutor] still read them directly until Phase 2 rewires those call sites onto
 * the new resolver. This migration only *adds* the new unified state alongside the old.
 *
 * The actual decision logic ([computeMigration]) is a pure function over explicit inputs so it is
 * fully unit-testable without a real Context/Keystore; [migrate] is the thin Context-bound
 * wrapper that gathers those inputs from the legacy stores, calls it, and persists the result.
 */
object ProviderChainMigration {
    private const val PREFS_NAME = "phonewhisper"
    private const val KEY_MIGRATED = "provider_chain_migrated"

    /** Maps one legacy [CleanupStep] to its [ProviderChainEntry] equivalent, preserving [CleanupStep.model]
     *  and [CleanupStep.baseUrlOverride] and collapsing the special-cased [CleanupStepGroup.LEGACY]
     *  concept into a normal [ProviderKind.OPENAI] entry -- the entire point of the unification. */
    fun mapStepToEntry(step: CleanupStep): ProviderChainEntry {
        val kind = when (step.group) {
            CleanupStepGroup.LEGACY -> ProviderKind.OPENAI
            CleanupStepGroup.OMNIROUTE -> ProviderKind.OMNIROUTE
            CleanupStepGroup.OPENAI_DIRECT -> ProviderKind.OPENAI
            CleanupStepGroup.ANTHROPIC_DIRECT -> ProviderKind.ANTHROPIC
            CleanupStepGroup.LOCAL_LLM -> ProviderKind.LOCAL
        }
        return ProviderChainEntry(kind, step.model, step.baseUrlOverride)
    }

    /** Maps an entire legacy [CleanupWaterfall] to a [ProviderChain], preserving step order
     *  exactly (see [mapStepToEntry]). */
    fun buildChain(waterfall: CleanupWaterfall): ProviderChain =
        ProviderChain(waterfall.steps.map(::mapStepToEntry))

    /**
     * Conflict-resolution rule for the case where BOTH the legacy [ApiKeyStore] key and an
     * explicitly-configured [CleanupCredentialSlot.OPENAI_DIRECT] key exist and differ (a real
     * scenario for a power user who set up a separate direct-OpenAI waterfall key before this
     * migration ran).
     *
     * Chosen rule: **prefer [openAiDirectKey] when non-blank, falling back to [legacyApiKey]
     * only when OPENAI_DIRECT was never set.** Rationale: OPENAI_DIRECT represents a deliberate,
     * explicit power-user choice made through the waterfall editor -- it is a more recent and
     * more specific configuration decision than the original single-key field, which predates
     * the whole waterfall feature (#32) and may not reflect the user's current intent for what
     * "the OpenAI provider" should mean going forward. The legacy key is not discarded (it is
     * left untouched in [ApiKeyStore] by this migration, see class kdoc) -- only the *new*
     * unified OPENAI slot prefers the more deliberately-configured value.
     */
    fun resolveOpenAiCredential(legacyApiKey: String, openAiDirectKey: String): String =
        if (openAiDirectKey.isNotBlank()) openAiDirectKey else legacyApiKey

    /** Explicit inputs to [computeMigration], gathered from the legacy stores by [migrate]. */
    data class MigrationInputs(
        val legacyOpenAiKey: String,
        val waterfall: CleanupWaterfall,
        val omniRouteCredential: String,
        val openAiDirectCredential: String,
        val anthropicDirectCredential: String,
    )

    /** Result of [computeMigration]: the [ProviderChain] to persist and the non-blank
     *  credentials to seed into [ProviderCredentialStore], keyed by [ProviderKind]. */
    data class MigrationResult(
        val chain: ProviderChain,
        val credentials: Map<ProviderKind, String>,
    )

    /**
     * Pure decision logic: given the legacy state, computes the unified [ProviderChain] and the
     * credential values to seed. No I/O, no Context -- directly unit-testable, and deterministic
     * (calling it twice with the same inputs always yields the same result, which is what makes
     * the overall migration safely idempotent).
     */
    fun computeMigration(inputs: MigrationInputs): MigrationResult {
        val chain = buildChain(inputs.waterfall)
        val resolvedOpenAi = resolveOpenAiCredential(inputs.legacyOpenAiKey, inputs.openAiDirectCredential)
        val credentials = buildMap {
            if (resolvedOpenAi.isNotBlank()) put(ProviderKind.OPENAI, resolvedOpenAi)
            if (inputs.anthropicDirectCredential.isNotBlank()) put(ProviderKind.ANTHROPIC, inputs.anthropicDirectCredential)
            if (inputs.omniRouteCredential.isNotBlank()) put(ProviderKind.OMNIROUTE, inputs.omniRouteCredential)
        }
        return MigrationResult(chain, credentials)
    }

    /** True once [migrate] has successfully completed at least once on this device. */
    fun isMigrated(context: Context): Boolean = prefs(context).getBoolean(KEY_MIGRATED, false)

    /**
     * Runs the one-time migration if it hasn't already run (guarded by [KEY_MIGRATED], mirroring
     * this repo's other one-time-flag patterns). Safe to call on every app start: idempotent by
     * construction since it no-ops immediately when [isMigrated] is already true, and
     * [computeMigration] is deterministic so even a hypothetical re-run before the flag is set
     * would recompute (not duplicate) the same result.
     */
    fun migrate(context: Context) {
        if (isMigrated(context)) return

        val inputs = MigrationInputs(
            legacyOpenAiKey = ApiKeyStore.getApiKey(context),
            waterfall = CleanupWaterfallStore.load(context),
            omniRouteCredential = CleanupCredentialStore.get(context, CleanupCredentialSlot.OMNIROUTE),
            openAiDirectCredential = CleanupCredentialStore.get(context, CleanupCredentialSlot.OPENAI_DIRECT),
            anthropicDirectCredential = CleanupCredentialStore.get(context, CleanupCredentialSlot.ANTHROPIC_DIRECT),
        )
        val result = computeMigration(inputs)

        ProviderChainStore.save(context, result.chain)
        result.credentials.forEach { (kind, value) -> ProviderCredentialStore.set(context, kind, value) }

        prefs(context).edit().putBoolean(KEY_MIGRATED, true).apply()
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
