package com.kafkasl.phonewhisper

/**
 * A cloud/on-device provider the user can slot into their [ProviderChain]. Unlike the legacy
 * [CleanupStepGroup] model, a [ProviderKind] is not feature-specific -- it is one physical
 * service/host/credential, and each feature (transcription, cleanup) independently decides
 * whether it is *capable* of using that entry (see [supportsTranscription]/[supportsCleanup]).
 * This is the core of the provider-chain unification: instead of two separate settings ("a
 * provider for transcription", "a provider for cleanup"), the user owns ONE ordered chain, and
 * each feature walks the same chain looking for its first capable entry.
 *
 * Capability facts (verified against provider docs, see the Phase 1 architecture task):
 *  - OPENAI: dedicated ASR (Whisper/gpt-4o-transcribe) AND chat completions -- both capabilities.
 *  - GEMINI: multimodal audio-in (no streaming) AND chat completions -- both capabilities. Not
 *    yet wired into any call site as of Phase 1 (OmniRoute has no Gemini credential connected);
 *    included here so the data model is ready ahead of that wiring.
 *  - ANTHROPIC: chat completions only. Claude has no audio-input capability at all, so it can
 *    never satisfy a transcription request.
 *  - OMNIROUTE: Trevor's self-hosted OpenAI-compatible gateway proxying Claude/OpenAI models
 *    behind one shared consumer key (home-LAN/VPN-only). Cleanup only today -- it does not (and
 *    per Phase 1 scope should not be made to) claim transcription capability.
 *  - LOCAL: on-device sherpa-onnx STT + llama.cpp cleanup. Both capabilities, always available,
 *    no credential. It is the guaranteed floor beneath every chain (see [ProviderChain] kdoc) --
 *    it has a place in this enum because it is a valid chain-entry kind (mirroring today's
 *    LOCAL_LLM waterfall step), not because it is an arbitrary peer a user can delete.
 */
enum class ProviderKind { OPENAI, ANTHROPIC, GEMINI, OMNIROUTE, LOCAL }

/** True for provider kinds capable of turning audio into text. See [ProviderKind] kdoc for the
 *  capability facts this encodes. */
fun ProviderKind.supportsTranscription(): Boolean = when (this) {
    ProviderKind.OPENAI -> true
    ProviderKind.GEMINI -> true
    ProviderKind.LOCAL -> true
    ProviderKind.ANTHROPIC -> false
    ProviderKind.OMNIROUTE -> false
}

/** True for provider kinds capable of text cleanup/post-processing. All five kinds qualify --
 *  cleanup is the universal capability, transcription is the selective one. */
fun ProviderKind.supportsCleanup(): Boolean = true

/**
 * One entry in the user's [ProviderChain]. Deliberately mirrors the shape of the legacy
 * [CleanupStep] (a [kind] instead of a [CleanupStepGroup], the same [model]/[baseUrlOverride]
 * fields) so migrating from the old waterfall model is a near 1:1 mapping rather than a
 * redesign -- see [ProviderChainMigration].
 */
data class ProviderChainEntry(
    val kind: ProviderKind,
    val model: String,
    val baseUrlOverride: String? = null,
)

/**
 * The user's single ordered chain of providers. Each feature (transcription, cleanup) walks
 * [entries] in order and uses the first entry capable of that feature -- see
 * [capableEntriesFor]. [LOCAL] is a guaranteed, undeletable floor beneath every chain in the
 * product's actual behavior (Phase 2/3 enforce this at the UI/executor level); this pure data
 * model does not itself forbid an entries list without a LOCAL entry, since that invariant
 * belongs to the code that constructs/edits chains, not to the value type describing one.
 */
data class ProviderChain(val entries: List<ProviderChainEntry>) {

    /** True when every configured entry is [ProviderKind.LOCAL] -- mirrors
     *  [CleanupWaterfall.isLocalOnly]'s semantics generalized to the unified model. An empty
     *  chain is not "local only": it means no entries are configured at all. */
    fun isLocalOnly(): Boolean =
        entries.isNotEmpty() && entries.all { it.kind == ProviderKind.LOCAL }

    /** True when at least one configured entry is [ProviderKind.LOCAL] -- mirrors
     *  [CleanupWaterfall.usesLocalLlm]'s semantics generalized to the unified model. */
    fun usesLocalLlm(): Boolean =
        entries.any { it.kind == ProviderKind.LOCAL }

    /**
     * Filters [entries] down to the ones capable of the requested feature, preserving order.
     * This is the pure "walk the chain, use the first capable entry" resolution logic: callers
     * (Phase 2's resolver/executor) take `.firstOrNull()` of the result to find the actual entry
     * to use for a given call, or fall through to the LOCAL floor if the result is empty.
     *
     * Contract: this function does NOT itself append or guarantee a LOCAL entry. If none of
     * [entries] support the requested feature, it returns an empty list -- it is the caller's
     * responsibility to have a LOCAL floor present (by construction, since LOCAL is meant to be
     * undeletable) or to explicitly check for and handle the empty case (e.g. "cleanup
     * disabled", mirroring today's empty-waterfall behavior in [CleanupWaterfall]).
     */
    fun capableEntriesFor(needsTranscription: Boolean): List<ProviderChainEntry> =
        entries.filter { entry ->
            if (needsTranscription) entry.kind.supportsTranscription() else entry.kind.supportsCleanup()
        }

    companion object {
        /**
         * Default chain for a fresh install or any state that hasn't gone through
         * [ProviderChainMigration] yet: a single OPENAI entry using [PostProcessor.DEFAULT_MODEL].
         * This mirrors [CleanupWaterfall.LEGACY_SINGLE_STEP] and today's actual zero-config
         * behavior (cloud transcription + cleanup both go through the single legacy OpenAI key) --
         * it is expressed as a normal OPENAI entry rather than a special LEGACY case, since
         * collapsing that special case into the unified model is the entire point of this phase.
         */
        val DEFAULT_SINGLE_OPENAI_ENTRY = ProviderChain(
            entries = listOf(ProviderChainEntry(ProviderKind.OPENAI, PostProcessor.DEFAULT_MODEL))
        )
    }
}
