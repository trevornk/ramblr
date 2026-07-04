package com.kafkasl.phonewhisper

/**
 * Whether the shared OpenAI API key row should be shown at all (#49). The key is read by two
 * independent consumers -- cloud transcription, and the "Cloud" simple cleanup choice (the LEGACY
 * waterfall step) -- so it can't collapse under either feature's own Local/Cloud toggle alone: it
 * stays visible whenever *either* consumer actually needs it, and only hides when neither does.
 */
fun shouldShowOpenAiKeyRow(
    useLocalTranscription: Boolean,
    cleanupEnabled: Boolean,
    cleanupChoice: SimpleCleanupChoice
): Boolean {
    if (!useLocalTranscription) return true
    return cleanupEnabled && cleanupChoice != SimpleCleanupChoice.LOCAL
}

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
