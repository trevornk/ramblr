package com.trevornk.ramblr

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
 *  - GEMINI: multimodal audio-in (no streaming) AND chat completions -- both capabilities, wired
 *    into live HTTP transports (see GeminiCleanupProvider / GeminiTranscriberClient, #96).
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
 * True when [chain] has at least one non-LOCAL, transcription-capable entry whose credential is
 * configured -- i.e. cloud transcription is actually usable, regardless of which provider. Setup
 * readiness previously hardcoded OpenAI, so a fully-supported Gemini-only cloud-transcription user
 * was stuck on "Setup required" forever (M8). [isConfigured] is the caller's seam onto the
 * credential store, keyed per entry (#274) rather than per kind, so two same-kind entries with
 * different credentials are told apart correctly. Also requires the entry be [ProviderChainEntry
 * .enabled] and opted in to transcription via [ProviderChainEntry.useForTranscription] (#274).
 */
fun hasConfiguredCloudTranscription(chain: ProviderChain, isConfigured: (ProviderChainEntry) -> Boolean): Boolean =
    chain.entries.any {
        it.kind != ProviderKind.LOCAL && it.enabled && it.useForTranscription &&
            it.kind.supportsTranscription() && isConfigured(it)
    }

/**
 * True when switching cleanup to Cloud would land on a configured credential: any non-LOCAL entry in
 * [chain] is configured, or -- if [chain] has no cloud entry yet -- the OpenAI default the Cloud
 * switch would seed is configured. Used so deleting the active local cleanup model falls back to
 * Cloud only when it would actually work, else turns cleanup off instead of seeding a config that
 * fails at call time (M14).
 *
 * [isConfigured] is entry-based (#274, per-entry credentials); for the "no cloud entry yet" branch
 * this is called with [ProviderChain.DEFAULT_SINGLE_OPENAI_ENTRY]'s single (unsaved, blank-id)
 * entry as a stand-in for "the OpenAI entry the Cloud switch would seed" -- see
 * [ProviderCredentialStore.isConfiguredOrLegacy] for the production caller that resolves a
 * blank-id entry like this one against the legacy per-kind slot instead of a per-entry one.
 */
fun canFallBackToCloudCleanup(chain: ProviderChain, isConfigured: (ProviderChainEntry) -> Boolean): Boolean {
    val cloudEntries = chain.entries.filter { it.kind != ProviderKind.LOCAL }
    return if (cloudEntries.isEmpty()) isConfigured(ProviderChain.DEFAULT_SINGLE_OPENAI_ENTRY.entries.first())
    else cloudEntries.any { isConfigured(it) }
}

/**
 * One entry in the user's [ProviderChain]. Deliberately mirrors the shape of [CleanupStep]
 * (a [kind] instead of a [CleanupStepGroup], the same [model]/[baseUrlOverride] fields) so
 * mapping between the two models (see [ProviderChainRuntime.cleanupWaterfallFor]) stays a
 * near 1:1 conversion rather than a redesign.
 *
 * [model] is the CLEANUP model for [kind] -- kept named plainly `model` (not `cleanupModel`)
 * for minimum diff against the many pre-existing call sites that already treat it that way
 * (every real caller of this field feeds [CleanupWaterfallExecutor]/[CleanupStep]/
 * [PostProcessor], never the transcription path).
 *
 * [transcriptionModel] is the separate TRANSCRIPTION model for [kind] (#101/#102: cleanup and
 * transcription are genuinely different jobs with disjoint model namespaces on the same
 * provider -- e.g. OpenAI's chat-completions models like "gpt-5.4-mini" can never serve
 * `/v1/audio/transcriptions`, which needs "whisper-1"/"gpt-4o-transcribe" -- so one shared
 * [model] field silently broke transcription the moment a user picked a cleanup-only model id).
 * `null` (not blank) means "no explicit choice yet -- use this [kind]'s current transcription
 * default", the same "unset means use the shipped default" convention [model] already has via
 * `.ifBlank`. Only meaningful for kinds where [ProviderKind.supportsTranscription] is true;
 * [ProviderKind.LOCAL]'s transcription path ([WhisperAccessibilityService.transcribeLocal])
 * never reads this field at all -- on-device transcription model choice lives in a completely
 * separate system ([TranscriptionActivity]'s local sherpa-onnx model picker), not the provider
 * chain, so leaving it null for LOCAL is correct, not an oversight.
 */
data class ProviderChainEntry(
    val kind: ProviderKind,
    val model: String,
    val baseUrlOverride: String? = null,
    val transcriptionModel: String? = null,
    /**
     * Stable identity for this entry (#274), used to key its own credential slot in
     * [ProviderCredentialStore] so two entries of the same [kind] never share one secret (#273).
     * Blank ("") means "not yet assigned" -- every entry saved before #274 has no `id` in its
     * persisted JSON, and [ProviderChainStore.deserialize] leaves it blank rather than fabricate
     * one at parse time (parsing must stay pure); assigning a real one is
     * [ProviderAccountMigration]'s job, run once (and safely re-run) at service startup. A
     * freshly-added entry (Add provider dialog) is given a real UUID immediately instead of
     * waiting for the next migration pass.
     */
    val id: String = "",
    /**
     * Per-entry on/off switch (#274): lets a user isolate/disable one provider without deleting
     * its configuration (model, base URL, credential). Filtered out by
     * [ProviderChain.capableEntriesFor] regardless of kind capability when false. Defaults true
     * so every entry saved before this field existed -- and every entry a user adds without
     * touching the switch -- behaves exactly as it did before this field existed.
     */
    val enabled: Boolean = true,
    /**
     * Whether this entry participates in TRANSCRIPTION routing (#274) -- independent of
     * [ProviderKind.supportsTranscription], which remains a hard capability ceiling: a kind that
     * cannot transcribe at all (ANTHROPIC, OMNIROUTE) can never be flipped true for this in the
     * UI (validation, not user override), but a kind that CAN transcribe may still be excluded
     * here by user choice (e.g. "this OpenAI-compatible account is for cleanup only"). Defaults
     * to the kind's own capability so every entry saved before this field existed keeps routing
     * exactly as it did before -- zero behavior change on upgrade.
     */
    val useForTranscription: Boolean = kind.supportsTranscription(),
    /** Cleanup counterpart to [useForTranscription]. Defaults to [ProviderKind.supportsCleanup],
     *  which is true for every kind today, so this is also a zero-behavior-change default. */
    val useForCleanup: Boolean = kind.supportsCleanup(),
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
     * #274: an entry must also be [ProviderChainEntry.enabled] and opted in to the requested task
     * ([ProviderChainEntry.useForTranscription] / [ProviderChainEntry.useForCleanup]) -- on top of
     * the kind-level capability check this already did. Both gates default true/to-capability, so
     * this is a strict narrowing of the old kind-only filter, not a behavior change for anyone who
     * has never touched the new per-entry controls.
     *
     * Contract: this function does NOT itself append or guarantee a LOCAL entry. If none of
     * [entries] support the requested feature, it returns an empty list -- it is the caller's
     * responsibility to have a LOCAL floor present (by construction, since LOCAL is meant to be
     * undeletable) or to explicitly check for and handle the empty case (e.g. "cleanup
     * disabled", mirroring today's empty-waterfall behavior in [CleanupWaterfall]).
     */
    fun capableEntriesFor(needsTranscription: Boolean): List<ProviderChainEntry> =
        entries.filter { entry ->
            if (!entry.enabled) return@filter false
            if (needsTranscription) entry.useForTranscription && entry.kind.supportsTranscription()
            else entry.useForCleanup && entry.kind.supportsCleanup()
        }

    /**
     * Returns a copy of this chain with exactly one [ProviderKind.LOCAL] entry present, using
     * [model] as its model id, appended after every existing non-LOCAL entry (or replacing the
     * one already there, in its existing position, if one exists) -- never touching any other
     * entry (#37 follow-up, real regression Trevor hit live: [CleanupActivity]'s simple
     * Local/Cloud picker used to persist its choice as a full chain OVERWRITE, which silently
     * deleted every configured cloud provider the moment "Local" was tapped). This is the
     * non-destructive alternative: it guarantees the LOCAL floor this class's kdoc already
     * describes as supposed to exist ("a guaranteed, undeletable floor beneath every chain"),
     * while leaving cloud entries completely alone. [CloudFeatureToggle.cleanupEnabled]
     * (see [ProviderChainRuntime.effectiveChainForCleanup]) is the correct, already-existing
     * mechanism for the actual "prefer local vs cloud" decision -- it filters non-LOCAL entries
     * out of the *effective* chain used for one cleanup call without ever mutating the persisted
     * chain itself. Appending (not prepending) the LOCAL entry means an enabled cloud chain still
     * tries its configured cloud providers first, falling through to on-device automatically if
     * they all fail -- a strict improvement over today's behavior, not just a bug fix.
     */
    fun withLocalFloor(model: String): ProviderChain {
        val localEntry = ProviderChainEntry(ProviderKind.LOCAL, model)
        val existingIndex = entries.indexOfFirst { it.kind == ProviderKind.LOCAL }
        return if (existingIndex >= 0) {
            ProviderChain(entries.toMutableList().apply { set(existingIndex, localEntry) })
        } else {
            ProviderChain(entries + localEntry)
        }
    }

    companion object {
        /**
         * Default chain for a fresh install or any state that hasn't been explicitly configured
         * yet: a single OPENAI entry using [PostProcessor.DEFAULT_MODEL] -- the zero-config behavior
         * where cloud transcription + cleanup both go through one OpenAI key, expressed as a normal
         * OPENAI entry in the unified model.
         */
        val DEFAULT_SINGLE_OPENAI_ENTRY = ProviderChain(
            entries = listOf(ProviderChainEntry(ProviderKind.OPENAI, PostProcessor.DEFAULT_MODEL))
        )
    }
}
