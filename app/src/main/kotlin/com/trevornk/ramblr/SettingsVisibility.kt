package com.trevornk.ramblr

/**
 * Whether TranscriptionActivity's OpenAI API key row should be shown (#93 restructure of #49):
 * true exactly when Transcription itself is set to Cloud, which always needs the key with
 * nothing else to check.
 */
fun shouldShowOpenAiKeyRowForTranscription(useLocalTranscription: Boolean): Boolean =
    !useLocalTranscription

/**
 * Whether CleanupActivity's OpenAI API key row should be shown (#93 restructure of #49): true
 * when Cleanup is on and its simple choice is Cloud (the LEGACY waterfall step) -- independent of
 * whatever Transcription is set to, since this is now CleanupActivity's own contextual copy of
 * the row rather than a single physically-shared one a Cloud-Cleanup user had to leave the
 * screen to find.
 */
fun shouldShowOpenAiKeyRowForCleanup(cleanupEnabled: Boolean, cleanupChoice: SimpleCleanupChoice): Boolean =
    cleanupEnabled && cleanupChoice == SimpleCleanupChoice.CLOUD

/**
 * Whether the OpenAI API key is needed *somewhere* right now (#49) -- the key is read by two
 * independent consumers, cloud transcription and the "Cloud" simple cleanup choice, so it can't
 * collapse under either feature's own Local/Cloud toggle alone. Pre-#93 this drove a single
 * physically-shared row on MainActivity; post-#93 each consumer shows its own contextual copy of
 * the row (see [shouldShowOpenAiKeyRowForTranscription]/[shouldShowOpenAiKeyRowForCleanup]) and
 * this combined form is kept only for anything that still needs the OR (e.g. Status/readiness
 * checks that don't care which screen the key lives on).
 *
 * NOT a plain OR of the two split functions above: those are scoped to what each per-category
 * screen's own simple choice means (CUSTOM means "go to Advanced", so CleanupActivity itself
 * hides its row for CUSTOM). This combined check instead preserves the original, more
 * conservative pre-#93 behavior for any caller that doesn't have a specific screen in mind --
 * an unknown/CUSTOM waterfall might still be using OpenAI directly, so a generic "is the key
 * needed at all" caller should not assume it's safe to hide.
 */
fun shouldShowOpenAiKeyRow(
    useLocalTranscription: Boolean,
    cleanupEnabled: Boolean,
    cleanupChoice: SimpleCleanupChoice
): Boolean =
    shouldShowOpenAiKeyRowForTranscription(useLocalTranscription) ||
        (cleanupEnabled && cleanupChoice != SimpleCleanupChoice.LOCAL)

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
