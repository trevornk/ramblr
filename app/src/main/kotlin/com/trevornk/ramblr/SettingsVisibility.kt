package com.trevornk.ramblr

/**
 * Whether TranscriptionActivity's OpenAI API key row should be shown (#93 restructure of #49):
 * true exactly when Transcription itself is set to Cloud, which always needs the key with
 * nothing else to check.
 */
fun shouldShowOpenAiKeyRowForTranscription(useLocalTranscription: Boolean): Boolean =
    !useLocalTranscription

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
