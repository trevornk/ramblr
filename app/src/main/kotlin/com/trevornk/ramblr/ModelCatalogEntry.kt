package com.trevornk.ramblr

/**
 * Simple 3-tier rating for a [ModelCatalogEntry] (#98). Deliberately not a 1-5 star scheme --
 * a coarse RECOMMENDED/GOOD/ADVANCED split is much easier to keep honest across a curated,
 * human-edited catalog than a fine-grained score nobody can consistently justify. ADVANCED is
 * also reused as the tier for the hidden "enter a custom model id" escape hatch (see
 * [ModelCatalogEntry.customAdvancedEntry]) -- a value outside the curated catalog is,
 * definitionally, unreviewed/advanced.
 */
enum class ModelTier { RECOMMENDED, GOOD, ADVANCED }

/**
 * Which feature(s) a [ModelCatalogEntry] is curated for. [BOTH] entries (e.g. Gemini's
 * multimodal models) show up in both the Cleanup and Transcription pickers; [CLEANUP]/
 * [TRANSCRIPTION]-only entries show up in just one.
 */
enum class ModelUseCase {
    CLEANUP, TRANSCRIPTION, BOTH;

    fun supportsCleanup(): Boolean = this == CLEANUP || this == BOTH
    fun supportsTranscription(): Boolean = this == TRANSCRIPTION || this == BOTH
}

/**
 * One curated entry in the remote-updatable model catalog (#98). Replaces the old free-text
 * model field's total lack of guidance: every entry the picker offers has a known [tier],
 * [description], and rough per-token cost, curated by Trevor rather than typed in blind by a
 * user who has no way to know which model ids are good, bad, or retired.
 *
 * [provider] reuses [ProviderKind] rather than introducing a parallel enum -- this catalog is
 * additive to the existing [ProviderChain]/[ProviderChainEntry] model, not a competing one; a
 * selected [modelId] is written straight into [ProviderChainEntry.model] unchanged.
 */
data class ModelCatalogEntry(
    val provider: ProviderKind,
    val modelId: String,
    val displayName: String,
    val description: String,
    val tier: ModelTier,
    val useCase: ModelUseCase,
    val costPer1MInputUsd: Double,
    val costPer1MOutputUsd: Double,
) {
    companion object {
        /**
         * Synthesizes the hidden "Advanced: enter a custom model id" picker option for
         * [modelId] typed by a power user (#98) -- not a real catalog entry (no cost data, no
         * curation), just enough of the shape for the picker/tier-badge UI to render it
         * consistently alongside real entries.
         */
        fun customAdvancedEntry(provider: ProviderKind, modelId: String): ModelCatalogEntry =
            ModelCatalogEntry(
                provider = provider,
                modelId = modelId,
                displayName = modelId,
                description = "Custom model id -- untested, unsupported by the curated catalog.",
                tier = ModelTier.ADVANCED,
                useCase = ModelUseCase.BOTH,
                costPer1MInputUsd = 0.0,
                costPer1MOutputUsd = 0.0,
            )
    }
}

/**
 * The bundled, last-resort-fallback catalog (#98) -- ships inside the APK so a fresh install
 * with no network yet (and no cached fetch) still gets a curated picker instead of a blank
 * free-text field. See [ModelCatalogResolver] for how this fits into the
 * bundled -> cached -> fresh-fetch fallback chain.
 *
 * Seeded from the pass-2 live benchmark (#97, `eval-reports/pass2-full-benchmark.md`, 216 real
 * API calls across OpenAI/Anthropic/Gemini): cleanup quality is essentially indistinguishable
 * between a provider's cheapest tier and its next tier up, so the cheapest tier of each direct
 * provider is RECOMMENDED, not the priciest model available. Pricing figures are the providers'
 * own public per-1M-token list prices as of this pass, not secrets -- fine to ship in this JSON.
 */
val BUNDLED_DEFAULT_MODEL_CATALOG: List<ModelCatalogEntry> = listOf(
    // --- OpenAI: cleanup (chat-completions) and transcription (dedicated ASR endpoint) are
    // DISJOINT model namespaces on the same provider -- a cleanup model like gpt-5.4-nano can
    // never serve /v1/audio/transcriptions, and whisper-1/gpt-4o-transcribe can't serve chat
    // completions. Tagged CLEANUP vs. TRANSCRIPTION accordingly, never BOTH (#101/#102: the
    // real bug this fixes was a single shared model field silently sending a cleanup model id
    // to the transcription endpoint). ---
    ModelCatalogEntry(
        provider = ProviderKind.OPENAI,
        modelId = "gpt-5.4-nano",
        displayName = "GPT-5.4 Nano",
        description = "Fastest, cheapest -- benchmark-confirmed indistinguishable from Mini for cleanup quality.",
        tier = ModelTier.RECOMMENDED,
        useCase = ModelUseCase.CLEANUP,
        costPer1MInputUsd = 0.05,
        costPer1MOutputUsd = 0.40,
    ),
    ModelCatalogEntry(
        provider = ProviderKind.OPENAI,
        modelId = "gpt-5.4-mini",
        displayName = "GPT-5.4 Mini",
        description = "One tier up from Nano -- same real-world cleanup quality, costs more.",
        tier = ModelTier.GOOD,
        useCase = ModelUseCase.CLEANUP,
        costPer1MInputUsd = 0.25,
        costPer1MOutputUsd = 2.00,
    ),
    // Real 20-clip benchmark (2026-07-10): gpt-4o-transcribe beat whisper-1 on both WER
    // (2.86% vs 4.29%) and latency (~42% faster avg), zero dropped words on either including a
    // one-word clip -- see TranscriberClient.DEFAULT_MODEL's kdoc for the full benchmark note.
    ModelCatalogEntry(
        provider = ProviderKind.OPENAI,
        modelId = "gpt-4o-transcribe",
        displayName = "GPT-4o Transcribe",
        description = "Benchmark-confirmed lower error rate and faster than Whisper-1 on short push-to-talk clips.",
        tier = ModelTier.RECOMMENDED,
        useCase = ModelUseCase.TRANSCRIPTION,
        costPer1MInputUsd = 0.0, // priced per-minute of audio, not per-token -- catalog cost fields don't apply
        costPer1MOutputUsd = 0.0,
    ),
    ModelCatalogEntry(
        provider = ProviderKind.OPENAI,
        modelId = "whisper-1",
        displayName = "Whisper-1",
        description = "OpenAI's original ASR model -- higher error rate and slower than GPT-4o Transcribe in Ramblr's own benchmark, kept as a fallback option.",
        tier = ModelTier.GOOD,
        useCase = ModelUseCase.TRANSCRIPTION,
        costPer1MInputUsd = 0.0,
        costPer1MOutputUsd = 0.0,
    ),

    // --- Gemini: only direct provider that's transcription-capable (multimodal audio-in) as
    // well as cleanup-capable, so its entries are tagged BOTH. 2.5-flash/-lite -> 3.1-flash-lite/
    // 3.5-flash (2026-07-10): 2.5-flash and 2.5-flash-lite are both on Google's deprecation path
    // (shutdown Oct 16, 2026); Google's own deprecation table maps 2.5-flash-lite ->
    // 3.1-flash-lite and 2.5-flash -> 3.5-flash. 3.1-flash-lite's model card documents explicit
    // "improved audio input... for ASR" gains, confirming it's safe for the transcription use
    // case too. Note 3.5-flash carries a real ~6x price jump over 3.1-flash-lite (not a smooth
    // one-tier step like the old 2.5-flash/-lite pair) -- flagged honestly below rather than
    // understated, but it's still the officially documented stable migration target. ---
    ModelCatalogEntry(
        provider = ProviderKind.GEMINI,
        modelId = "gemini-3.1-flash-lite",
        displayName = "Gemini 3.1 Flash-Lite",
        description = "Cheapest Gemini tier -- Google's documented replacement for 2.5 Flash-Lite (which is deprecating); improved audio input for ASR, handles transcription too.",
        tier = ModelTier.RECOMMENDED,
        useCase = ModelUseCase.BOTH,
        costPer1MInputUsd = 0.25,
        costPer1MOutputUsd = 1.50,
    ),
    ModelCatalogEntry(
        provider = ProviderKind.GEMINI,
        modelId = "gemini-3.5-flash",
        displayName = "Gemini 3.5 Flash",
        description = "Google's documented replacement for 2.5 Flash (which is deprecating) -- meaningfully pricier than Flash-Lite (~6x), not a small step up; pick deliberately, not as a default.",
        tier = ModelTier.GOOD,
        useCase = ModelUseCase.BOTH,
        costPer1MInputUsd = 1.50,
        costPer1MOutputUsd = 9.00,
    ),

    // --- Anthropic: cleanup-only power-user option (Claude has no audio-input capability at
    // all, confirmed via Anthropic's own docs -- never transcription-capable). Deliberately
    // GOOD, not RECOMMENDED: the benchmark found no clear cleanup-quality win over OpenAI/
    // Gemini's cheap tiers, and Anthropic requires a whole separate provider account most users
    // won't already have just for cleanup -- see #98 for the full reasoning. ---
    ModelCatalogEntry(
        provider = ProviderKind.ANTHROPIC,
        modelId = "claude-haiku-4-5-20251001",
        displayName = "Claude Haiku 4.5",
        description = "Legitimate power-user option if you already have Claude credits -- not defaulted: no transcription capability, and cleanup quality doesn't clearly beat OpenAI/Gemini's cheap tiers.",
        tier = ModelTier.GOOD,
        useCase = ModelUseCase.CLEANUP,
        costPer1MInputUsd = 1.00,
        costPer1MOutputUsd = 5.00,
    ),

    // --- OmniRoute: the actual recommended DEFAULT cleanup path overall (#98), BUT tiered to
    // mirror the exact same cheap/good/advanced split as the direct providers above -- Trevor's
    // explicit correction: going through your own OmniRoute server should never default to a
    // *pricier* model than going direct just because OmniRoute happens to expose one. Re-queried
    // omniroute.example.com/v1/models live to confirm what's actually available before fixing
    // this (don't guess at alias names):
    //  - gemini/gemini-flash-lite-latest exists and auto-upgrades -- the correct RECOMMENDED
    //    pick, matching direct Gemini's flash-lite recommendation (previously this wrongly
    //    pointed at gemini/gemini-flash-latest, the pricier non-lite tier).
    //  - No "-latest"/"auto/" alias exists for Haiku specifically (only pinned
    //    cc/claude-haiku-4-5-20251001 / claude/claude-haiku-4-5-20251001) -- still the correct
    //    GOOD-tier pick to mirror direct Anthropic's Haiku entry, even though it won't auto-
    //    upgrade the same way the "-latest" aliases do; flagged honestly in its description
    //    rather than silently claiming an alias that doesn't exist.
    //  - No OpenAI "-latest"/"auto/" alias at all; cx/gpt-5.4-mini is OmniRoute's cheapest
    //    available OpenAI-family model (no nano-equivalent routes through OmniRoute today).
    //  - auto/claude-sonnet and gemini/gemini-pro-latest are demoted to ADVANCED (moved down
    //    from RECOMMENDED/GOOD) -- premium tiers, not the "cheapest that's still top-notch"
    //    default this catalog is supposed to steer people toward. ---
    ModelCatalogEntry(
        provider = ProviderKind.OMNIROUTE,
        modelId = "gemini/gemini-flash-lite-latest",
        displayName = "OmniRoute: Gemini Flash-Lite (auto-upgrading)",
        description = "Cheapest, auto-upgrading -- mirrors direct Gemini's own recommended flash-lite tier so you don't overpay just because you're using OmniRoute. Recommended default cleanup path.",
        tier = ModelTier.RECOMMENDED,
        useCase = ModelUseCase.CLEANUP,
        costPer1MInputUsd = 0.10,
        costPer1MOutputUsd = 0.40,
    ),
    ModelCatalogEntry(
        provider = ProviderKind.OMNIROUTE,
        modelId = "cx/gpt-5.4-mini",
        displayName = "OmniRoute: GPT-5.4 Mini",
        description = "OmniRoute's cheapest available OpenAI-family model (no nano-tier route exists through OmniRoute today). Pinned, not an auto-upgrading alias.",
        tier = ModelTier.GOOD,
        useCase = ModelUseCase.CLEANUP,
        costPer1MInputUsd = 0.75,
        costPer1MOutputUsd = 4.50,
    ),
    ModelCatalogEntry(
        provider = ProviderKind.OMNIROUTE,
        modelId = "cc/claude-haiku-4-5-20251001",
        displayName = "OmniRoute: Claude Haiku 4.5",
        description = "Mirrors direct Anthropic's Haiku entry -- good cleanup quality, cheap. Pinned, not an auto-upgrading alias (no Haiku-specific \"-latest\"/\"auto/\" route exists on OmniRoute today).",
        tier = ModelTier.GOOD,
        useCase = ModelUseCase.CLEANUP,
        costPer1MInputUsd = 1.00,
        costPer1MOutputUsd = 5.00,
    ),
    ModelCatalogEntry(
        provider = ProviderKind.OMNIROUTE,
        modelId = "gemini/gemini-pro-latest",
        displayName = "OmniRoute: Gemini Pro (auto-upgrading)",
        description = "Always resolves to Google's current Pro-tier model server-side -- higher cost, for when you deliberately want the strongest available cleanup quality over the cheap default.",
        tier = ModelTier.ADVANCED,
        useCase = ModelUseCase.CLEANUP,
        costPer1MInputUsd = 1.25,
        costPer1MOutputUsd = 10.00,
    ),
    ModelCatalogEntry(
        provider = ProviderKind.OMNIROUTE,
        modelId = "auto/claude-sonnet",
        displayName = "OmniRoute: Claude Sonnet (auto-upgrading)",
        description = "Always resolves to Anthropic's current Sonnet-tier model server-side -- premium, not the cost-efficient default.",
        tier = ModelTier.ADVANCED,
        useCase = ModelUseCase.CLEANUP,
        costPer1MInputUsd = 3.00,
        costPer1MOutputUsd = 15.00,
    ),
    ModelCatalogEntry(
        provider = ProviderKind.OMNIROUTE,
        modelId = "auto/claude-opus",
        displayName = "OmniRoute: Claude Opus (auto-upgrading)",
        description = "Always resolves to Anthropic's current Opus-tier model server-side -- the priciest OmniRoute option.",
        tier = ModelTier.ADVANCED,
        useCase = ModelUseCase.CLEANUP,
        costPer1MInputUsd = 15.00,
        costPer1MOutputUsd = 75.00,
    ),
)
