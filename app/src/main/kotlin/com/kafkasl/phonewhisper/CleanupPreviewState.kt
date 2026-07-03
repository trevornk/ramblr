package com.kafkasl.phonewhisper

/**
 * Outcome of a resolved preview (#40). [textToInject] is what should actually land in the
 * target field; [committed] is true only for an explicit commit. Both an explicit discard and
 * the safety timeout fall back to [CleanupPreviewState.rawText] rather than dropping the
 * dictation — see the class doc below for why that's the deliberate default.
 */
data class PreviewResolution(val textToInject: String, val committed: Boolean)

/**
 * Pure commit/discard/timeout state machine for the preview-before-inject stage (#40). One
 * instance covers exactly one cleanup candidate: [rawText] is the pre-cleanup transcript (the
 * safe fallback), [candidateText] is what cleanup produced.
 *
 * Discarding and timing out both resolve to [rawText], never to nothing — per the issue's
 * guidance to "err toward 'safe timeout falls back to raw injection' rather than losing the
 * dictation entirely," a distracted user who never taps still gets their raw transcript injected
 * instead of losing the dictation outright.
 *
 * Resolution is one-shot: once [resolution] is set, further calls to [commit]/[discard]/[timeout]
 * are no-ops that return the original outcome. This matters for the real caller
 * ([WhisperAccessibilityService]), which can't always guarantee a stray timeout callback won't
 * fire after a tap already resolved the preview, or vice versa.
 */
class CleanupPreviewState(val rawText: String, val candidateText: String) {
    var resolution: PreviewResolution? = null
        private set

    val isResolved: Boolean get() = resolution != null

    /** User tapped to accept the cleaned-up candidate. */
    fun commit(): PreviewResolution = resolve(PreviewResolution(candidateText, committed = true))

    /** User explicitly declined the candidate; falls back to the raw transcript. */
    fun discard(): PreviewResolution = resolve(PreviewResolution(rawText, committed = false))

    /** No decision arrived in time; falls back to the raw transcript (see class doc). */
    fun timeout(): PreviewResolution = resolve(PreviewResolution(rawText, committed = false))

    private fun resolve(outcome: PreviewResolution): PreviewResolution {
        resolution?.let { return it }
        resolution = outcome
        return outcome
    }
}
