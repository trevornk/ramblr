package com.trevornk.ramblr

/**
 * Whether the on-device batch recognizer ([LocalTranscriber]) can ever be asked to transcribe
 * under the user's current settings (#310). It is a ~0.8-1.1 GB native resident for the larger
 * models, so a cloud-only setup must not pay for it at accessibility-service connect / IME show.
 *
 * The batch recognizer is reached from exactly two places in [DictationRuntime]:
 *  1. `use_local == true` -- the direct on-device path (`continueTranscription`), which also
 *     covers "on-device first, cloud as the `allow_cloud_fallback` retry".
 *  2. A [ProviderKind.LOCAL] entry in the cloud walk (`transcribeApi`), i.e. a LOCAL candidate
 *     that survives [ProviderChainRuntime.transcriptionCandidates] (enabled, opted in to
 *     transcription, and `allow_local_fallback` not turned off).
 *
 * Anything else is cloud-only. `allow_cloud_fallback` never matters here: it only governs a
 * local-first dictation retrying in the cloud, which needs `use_local == true` anyway.
 *
 * This decides only whether to *preload*. Both paths above load on demand through
 * [DictationRuntime.awaitLocalTranscriber] (#280), so a setting this predicate disagrees with
 * degrades to a cold load, never to a failed dictation.
 */
internal object BatchRecognizerPolicy {
    /** Preference keys the answer depends on; a change to any of them re-evaluates the preload. */
    val DEPENDENT_PREF_KEYS: Set<String> = setOf(
        "use_local",
        "allow_local_fallback",
        ProviderChainStore.KEY_ENTRIES,
    )

    fun canBeUsed(useLocal: Boolean, chain: ProviderChain, allowLocalFallback: Boolean): Boolean =
        useLocal || ProviderChainRuntime.transcriptionCandidates(chain, allowLocalFallback)
            .any { it.kind == ProviderKind.LOCAL }
}
