package com.trevornk.ramblr

/**
 * What a long-press on the overlay ring should do (#53). Exactly one of TRANSCRIBING or
 * IDLE-with-a-pending-injection was already spoken for before this change (see
 * WhisperAccessibilityService's long-press timer / cancelTranscription / undoLastInjection);
 * [SHOW_STYLE_MENU] only claims the one remaining reachable state -- IDLE with nothing pending --
 * so none of the pre-existing behavior shifts. RECORDING never gets a long-press action, matching
 * pre-#53 behavior where the touch listener never even armed the long-press timer for it.
 */
enum class OverlayLongPressAction { CANCEL_TRANSCRIPTION, UNDO_INJECTION, SHOW_STYLE_MENU, NONE }

/**
 * Pure decision, no Android dependencies: given the current recording state and whether an
 * undoable injection (#27) is pending, what should a long-press on the overlay do?
 *
 * final state -> long-press action map (kept unambiguous per #53's constraint):
 *   TRANSCRIBING                       -> CANCEL_TRANSCRIPTION (pre-existing, #20)
 *   IDLE, hasPendingInjection = true   -> UNDO_INJECTION (pre-existing, #27)
 *   IDLE, hasPendingInjection = false  -> SHOW_STYLE_MENU (new, #53)
 *   RECORDING                          -> NONE (unchanged: a long hold-then-release while
 *                                         recording is still just a tap that stops it)
 *
 * With the "Long-press cancels and undoes" setting off ([cancelAndUndoEnabled] = false, #361) the two
 * destructive actions are removed: TRANSCRIBING -> NONE (so the long-press is not even armed) and
 * IDLE -> SHOW_STYLE_MENU regardless of [hasPendingInjection].
 */
fun overlayLongPressActionFor(
    state: RecordingStateMachine.State,
    hasPendingInjection: Boolean,
    cancelAndUndoEnabled: Boolean = true,
): OverlayLongPressAction = when (state) {
    RecordingStateMachine.State.TRANSCRIBING ->
        if (cancelAndUndoEnabled) OverlayLongPressAction.CANCEL_TRANSCRIPTION else OverlayLongPressAction.NONE
    RecordingStateMachine.State.IDLE ->
        if (hasPendingInjection && cancelAndUndoEnabled) OverlayLongPressAction.UNDO_INJECTION
        else OverlayLongPressAction.SHOW_STYLE_MENU
    RecordingStateMachine.State.RECORDING -> OverlayLongPressAction.NONE
}

// Whether a fired long-press may actually take effect given finger movement (#57) is now decided by
// RingGestureTracker (#322): drag is sticky and wins over a long-press that has not fired yet, and a
// long-press that already fired is only converted to a drag past a larger slop.
