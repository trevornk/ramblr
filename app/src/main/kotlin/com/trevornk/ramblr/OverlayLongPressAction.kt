package com.trevornk.ramblr

/**
 * What a long-press on the overlay ring should do (#53). Exactly one of TRANSCRIBING or
 * IDLE-with-a-pending-injection was already spoken for before this change (see
 * WhisperAccessibilityService's LONG_PRESS_CANCEL_MS / cancelTranscription / undoLastInjection);
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
 */
fun overlayLongPressActionFor(
    state: RecordingStateMachine.State,
    hasPendingInjection: Boolean
): OverlayLongPressAction = when (state) {
    RecordingStateMachine.State.TRANSCRIBING -> OverlayLongPressAction.CANCEL_TRANSCRIPTION
    RecordingStateMachine.State.IDLE ->
        if (hasPendingInjection) OverlayLongPressAction.UNDO_INJECTION else OverlayLongPressAction.SHOW_STYLE_MENU
    RecordingStateMachine.State.RECORDING -> OverlayLongPressAction.NONE
}

/**
 * Whether the long-press timer firing with [action] already decided should actually take effect
 * given how far the touch has moved since ACTION_DOWN (real feedback from Trevor's Pixel 10 Pro
 * Fold, #57): holding the ring to drag it is far more common than holding it stationary to open
 * the style menu, and a drag that's underway reads as "moved past the tap/drag threshold" well
 * before ACTION_UP -- so [SHOW_STYLE_MENU] must not fire once that's true, letting the touch
 * continue as a plain drag-to-reposition instead. [CANCEL_TRANSCRIPTION]/[UNDO_INJECTION] are
 * instant one-shot actions rather than a persistent overlay, so movement doesn't gate them.
 */
fun shouldFireLongPress(action: OverlayLongPressAction, movedPastThreshold: Boolean): Boolean =
    action != OverlayLongPressAction.SHOW_STYLE_MENU || !movedPastThreshold
