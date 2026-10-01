package com.trevornk.ramblr

/**
 * How a screen should present one "side" of a local/cloud choice -- the currently-active side, or
 * the inactive side that a fallback toggle can still route to at runtime (#276).
 *
 * Before this existed, TranscriptionActivity/CleanupActivity hid the inactive side outright
 * (`modelContainer.visibility = if (useLocal) VISIBLE else GONE`), even though
 * [DictationModeToggle.allowLocalFallback]/[DictationModeToggle.allowCloudFallback] can make the
 * runtime actually use that hidden side as a fallback. Setting up a fallback therefore meant
 * flipping the primary switch back and forth to reach controls the UI then hid again. Now a
 * fallback-eligible inactive side stays visible, just labeled "Fallback" instead of shown as the
 * primary choice -- see [fallbackSectionState].
 */
enum class FallbackSectionState {
    /** This side is the feature's current active choice -- show it as the primary section. */
    PRIMARY,

    /** This side isn't active right now, but the matching fallback toggle is on, so the runtime
     *  can still fall through to it -- show it, labeled "Fallback". */
    FALLBACK,

    /** This side isn't active and no fallback toggle would ever reach it -- hide it, matching the
     *  pre-#276 behavior exactly. */
    HIDDEN,
}

/**
 * Pure decision for one local/cloud section's visibility+label (#276). [isActiveSide] is whether
 * this section is the feature's current primary choice (e.g. `useLocalTranscription` for the
 * local section, `!useLocalTranscription` for the cloud section); [fallbackAllowed] is the
 * matching direction's fallback toggle ([DictationModeToggle.allowLocalFallback] when this is the
 * local section, [DictationModeToggle.allowCloudFallback] when this is the cloud section).
 *
 * All 4 combinations of (isActiveSide x fallbackAllowed) are meaningful and covered by tests:
 * active sides are always PRIMARY regardless of the toggle (a fallback toggle only describes the
 * *inactive* side's reachability), and an inactive side is FALLBACK only when its toggle is on.
 */
fun fallbackSectionState(isActiveSide: Boolean, fallbackAllowed: Boolean): FallbackSectionState = when {
    isActiveSide -> FallbackSectionState.PRIMARY
    fallbackAllowed -> FallbackSectionState.FALLBACK
    else -> FallbackSectionState.HIDDEN
}

/**
 * Whether TranscriptionActivity's Cloud provider chain link row should be shown (#93 restructure
 * of #49, extended #276): true when Transcription itself is set to Cloud (nothing else to check),
 * or when it's set to Local but [allowCloudFallback] (#100) means a failed local transcription
 * can still retry in the cloud -- in which case the row stays reachable, labeled "Fallback" by the
 * caller via [fallbackSectionState]. [allowCloudFallback] defaults to false so every pre-#276
 * call site (and its existing single-arg tests) keeps its exact original behavior.
 */
fun shouldShowOpenAiKeyRowForTranscription(useLocalTranscription: Boolean, allowCloudFallback: Boolean = false): Boolean =
    fallbackSectionState(isActiveSide = !useLocalTranscription, fallbackAllowed = allowCloudFallback) != FallbackSectionState.HIDDEN

/**
 * The Local/Cloud choice to actually *display* for the cleanup radios and their nested model
 * groups (#55), which can briefly disagree with [persisted] (the real, [simpleCleanupChoiceFor]-
 * derived active waterfall step). Deleting the model backing an active Local choice falls the
 * waterfall back to Cloud (see MainActivity.deleteCleanupModel, #51) -- if the radio/group strictly
 * mirrored that persisted choice, tapping "Local" again to re-download would find no model
 * installed, bail out before writing a waterfall step, and never reveal the model list (and its
 * download buttons) again. [pendingLocalSelection] lets that tap flip the *displayed* choice to
 * LOCAL regardless, so the list stays reachable; it's cleared once a real model install lets Local
 * actually activate, or the user picks Cloud instead.
 */
fun displayedCleanupChoice(persisted: SimpleCleanupChoice, pendingLocalSelection: Boolean): SimpleCleanupChoice =
    if (pendingLocalSelection) SimpleCleanupChoice.LOCAL else persisted
