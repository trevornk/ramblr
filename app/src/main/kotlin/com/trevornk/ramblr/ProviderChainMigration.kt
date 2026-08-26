package com.trevornk.ramblr

import android.content.Context

/**
 * One-shot, idempotent migration that upgrades a user's already-saved [ProviderChain] entries
 * off superseded/deprecated model ids onto their current replacement, WITHOUT touching any
 * entry the user explicitly picked via the advanced/custom model-id field or that was never a
 * shipped default in the first place (#100 follow-up to the 2026-07-10 model-routing update).
 *
 * The gap this closes: [ProviderChainStore.load] only ever falls back to a code-level default
 * (e.g. [PostProcessor.DEFAULT_MODEL]) when NOTHING has been saved yet. Once a chain entry is
 * persisted -- which happens the moment a user configures any provider -- its model string is
 * permanently pinned in on-device SharedPreferences. Bumping a `DEFAULT_MODEL` constant in code
 * (as happened 2026-07-10: gpt-4o-mini -> gpt-5.4-mini, whisper-1 -> gpt-4o-transcribe,
 * gemini-2.5-flash(-lite) -> gemini-3.1-flash-lite) only changes what a *fresh* install or a
 * *never-configured* slot resolves to; every already-configured device silently keeps serving
 * the old model forever, including models on a provider's own deprecation path (verified live:
 * Trevor's Fold was still pinned to gemini-2.5-flash-lite, which Google shuts down 2026-10-16,
 * after the code-level fix had already shipped and been installed).
 *
 * Design: a fixed map of (kind, oldModelId) -> newModelId, applied once per new mapping via a
 * monotonically-increasing [MIGRATION_VERSION] stored in prefs -- the same "self-heal on load,
 * one persisted version gate" shape as [CustomPersonaStore.ensureLegacySeeded] and
 * [ProviderChainStore.normalizeLocalPosition], not a new pattern invented for this file.
 * Deliberately conservative: this only ever rewrites a model id that exactly matches a KNOWN
 * SUPERSEDED SHIPPED DEFAULT. It never touches a model id it doesn't recognize (a user's
 * deliberate custom/advanced choice, a newer model than this migration knows about, or a typo)
 * -- silently upgrading an unrecognized string would risk overwriting an intentional choice.
 */
object ProviderChainMigration {
    private const val PREFS_NAME = "ramblr"
    private const val KEY_MIGRATION_VERSION = "provider_chain_model_migration_version"

    /**
     * Bump this and add new entries to [SUPERSEDED_MODELS] whenever a shipped `DEFAULT_MODEL`
     * constant changes to a new "current best" model. The migration re-runs (idempotently) for
     * every version between the device's last-applied version and this one, so a device that
     * skipped several app updates still picks up every intermediate mapping in order.
     */
    private const val MIGRATION_VERSION = 4

    /**
     * (provider, old shipped-default model id) -> new shipped-default model id. Only exact
     * matches are rewritten. Entries are grouped by the migration version that introduced them
     * so future additions have an obvious place to land and a clear audit trail.
     */
    private val SUPERSEDED_MODELS: Map<Pair<ProviderKind, String>, String> = mapOf(
        // v1 (2026-07-10): gpt-4o-mini/whisper-1/gemini-2.5-flash(-lite) default swap.
        (ProviderKind.OPENAI to "gpt-4o-mini") to PostProcessor.DEFAULT_MODEL,
        (ProviderKind.OPENAI to "whisper-1") to TranscriberClient.DEFAULT_MODEL,
        (ProviderKind.GEMINI to "gemini-2.5-flash") to GeminiCleanupProvider.DEFAULT_MODEL,
        (ProviderKind.GEMINI to "gemini-2.5-flash-lite") to GeminiCleanupProvider.DEFAULT_MODEL,
        // v3 (2026-07-31): gpt-4o-transcribe -> gpt-transcribe (OpenAI's successor ASR model,
        // more accurate and 25% cheaper). Also covers the pre-#101/#102 shared-model-field era:
        // a device whose single `model` field held gpt-4o-transcribe (e.g. via the v1
        // whisper-1 rewrite applied when gpt-4o-transcribe was the current default).
        (ProviderKind.OPENAI to "gpt-4o-transcribe") to TranscriberClient.DEFAULT_MODEL,
        // v4 (2026-08-25): cleanup-default refresh (#194).
        // - gpt-5.4-nano was the catalog's RECOMMENDED OpenAI cleanup pick (what the picker and
        //   onboarding seeded on every configured device) until superseded by gpt-5.6-luna,
        //   OpenAI's current cheapest tier. The new id is a literal here because the shipped
        //   value lives in BUNDLED_DEFAULT_MODEL_CATALOG, not a DEFAULT_MODEL constant.
        (ProviderKind.OPENAI to "gpt-5.4-nano") to "gpt-5.6-luna",
        // - gemini-3.1-flash-lite -> gemini-3.5-flash-lite for the CLEANUP `model` field only.
        //   Audio safety: even for pre-#101 shared-field-era chains this can't flip anyone's
        //   transcription path to the ~3x-slower 3.5-flash-lite -- the Gemini audio call reads
        //   `transcriptionModel ?: GeminiTranscriberClient.DEFAULT_MODEL` (never `model`), this
        //   migration's null-seeding uses GeminiTranscriberClient.DEFAULT_MODEL (still
        //   3.1-flash-lite), and SUPERSEDED_TRANSCRIPTION_MODELS deliberately has NO entry for
        //   3.1-flash-lite, so every non-null transcriptionModel keeps its value.
        (ProviderKind.GEMINI to "gemini-3.1-flash-lite") to GeminiCleanupProvider.DEFAULT_MODEL,
    )

    /**
     * v3: like [SUPERSEDED_MODELS] but applied to [ProviderChainEntry.transcriptionModel].
     * Needed because the v2 migration SEEDED transcriptionModel with the then-current default
     * (gpt-4o-transcribe) on every transcription-capable entry -- those seeded values are just
     * as pinned as the v1-era `model` strings were, and [migrate]'s null-seeding path can never
     * fix a non-null value. Kept as a SEPARATE map deliberately: the main map's
     * whisper-1 -> current-default entry must NOT apply here, because a non-null whisper-1
     * transcriptionModel can only be a deliberate picker choice (the field did not exist yet
     * when whisper-1 was the shipped default; v2 seeded gpt-4o-transcribe, never whisper-1).
     */
    private val SUPERSEDED_TRANSCRIPTION_MODELS: Map<Pair<ProviderKind, String>, String> = mapOf(
        (ProviderKind.OPENAI to "gpt-4o-transcribe") to TranscriberClient.DEFAULT_MODEL,
    )

    /** Kinds where [ProviderChainEntry.transcriptionModel] is a meaningful, seedable field --
     *  mirrors [ProviderKind.supportsTranscription] restricted to the cloud kinds that actually
     *  have a distinct transcription model namespace ([ProviderKind.LOCAL]'s transcription path
     *  never reads this field at all, see [ProviderChainEntry]'s kdoc). */
    private fun defaultTranscriptionModelFor(kind: ProviderKind): String? = when (kind) {
        ProviderKind.OPENAI -> TranscriberClient.DEFAULT_MODEL
        ProviderKind.GEMINI -> GeminiTranscriberClient.DEFAULT_MODEL
        else -> null
    }

    /**
     * Rewrites [chain]'s entries per [SUPERSEDED_MODELS], leaving every other field (kind,
     * baseUrlOverride, entry order) and every non-superseded model id completely untouched. Also
     * seeds [ProviderChainEntry.transcriptionModel] (v2, #101/#102) for any transcription-capable
     * cloud entry that still has it null -- every entry saved before this field existed. This is
     * NOT overwriting a user choice: null is defined as "never explicitly set," so seeding it
     * with the current shipped transcription default is exactly the same "unset resolves to
     * current default" behavior [model] already had via `.ifBlank`, just applied once, at
     * migration time, instead of read-time -- necessary here (unlike [model]) because the actual
     * OpenAI/Gemini transcription HTTP calls need a real, non-null value to send, not a runtime
     * fallback computed fresh every call.
     * Pure function so the actual rewrite logic is unit-testable without a fake [Context].
     */
    internal fun migrate(chain: ProviderChain): ProviderChain {
        val migratedEntries = chain.entries.map { entry ->
            val replacement = SUPERSEDED_MODELS[entry.kind to entry.model]
            val withModelFixed = if (replacement != null) entry.copy(model = replacement) else entry
            // v3: a non-null transcriptionModel that exactly matches a superseded shipped/seeded
            // default is rewritten too (see SUPERSEDED_TRANSCRIPTION_MODELS' kdoc for why this
            // is a separate map from the `model`-field one).
            val transcriptionReplacement = withModelFixed.transcriptionModel
                ?.let { SUPERSEDED_TRANSCRIPTION_MODELS[withModelFixed.kind to it] }
            val withTranscriptionFixed =
                if (transcriptionReplacement != null) withModelFixed.copy(transcriptionModel = transcriptionReplacement)
                else withModelFixed
            if (withTranscriptionFixed.transcriptionModel == null) {
                val seeded = defaultTranscriptionModelFor(withTranscriptionFixed.kind)
                if (seeded != null) withTranscriptionFixed.copy(transcriptionModel = seeded) else withTranscriptionFixed
            } else {
                withTranscriptionFixed
            }
        }
        return ProviderChain(migratedEntries)
    }

    /**
     * Runs the migration at most once per [MIGRATION_VERSION] bump: if the device's persisted
     * version is already current, this is a no-op read (no SharedPreferences write, no chain
     * re-save) so a normal already-migrated launch costs nothing beyond one int read. Call from
     * the same service-startup path that already runs [CustomPersonaStore.ensureLegacySeeded]
     * (see [WhisperAccessibilityService.onServiceConnected]).
     */
    fun runIfNeeded(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val appliedVersion = prefs.getInt(KEY_MIGRATION_VERSION, 0)
        if (appliedVersion >= MIGRATION_VERSION) return

        val currentChain = ProviderChainStore.load(context)
        val migratedChain = migrate(currentChain)
        if (migratedChain != currentChain) {
            ProviderChainStore.save(context, migratedChain)
        }
        prefs.edit().putInt(KEY_MIGRATION_VERSION, MIGRATION_VERSION).apply()
    }
}
