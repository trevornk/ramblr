package com.trevornk.ramblr

/**
 * Auto-hide-to-peek pure logic: after idling (see [AutoPeekDelay] for the user-configurable
 * duration, defaulting to [IDLE_TIMEOUT_MS]) with no interaction with the floating mic ring, it
 * slides toward whichever edge it's snapped to, leaving a small on-screen sliver
 * ([PEEK_VISIBLE_DP]) instead of sitting at full width over whatever's underneath.
 *
 * Kept as a standalone object of pure functions (no Android/WindowManager dependency) so the
 * edge-detection and peek-position math are directly unit testable, mirroring the existing
 * [overlayShouldBeVisible]/[isFoldSizeChange] pattern rather than leaving this logic buried inline
 * in WhisperAccessibilityService's touch listener.
 */
object RingPeek {
    /** Default idle delay in millis before the ring auto-peeks, used when the user hasn't
     *  overridden it via [AutoPeekDelay] in Advanced settings. */
    const val IDLE_TIMEOUT_MS = 4_000L

    /** Default visible sliver size in dp once peeked, used when the user hasn't overridden it via
     *  [PeekVisibleSize] in Advanced settings. */
    const val PEEK_VISIBLE_DP = 20

    /** Duration of the slide animation in either direction. */
    const val ANIM_DURATION_MS = 220L

    /** Same left/right split WhisperAccessibilityService already uses to decide which side of the
     *  screen the ring/style-menu is closer to (see positionStyleMenu/ACTION_UP snap logic) --
     *  mirrored here rather than re-derived, so "which edge is the ring snapped to" always agrees
     *  with where drag-to-reposition would have snapped it. */
    fun isSnappedToRightEdge(x: Int, screenW: Int, ringSize: Int): Boolean = x + ringSize / 2 > screenW / 2

    /** Target window x while peeked: shifts the ring almost entirely past whichever edge it's
     *  snapped to, leaving exactly [peekVisiblePx] of it still on-screen, adjacent to that edge.
     *
     *  [ringInsetPx] (#128) is the transparent gap between the ring WINDOW's edge and the mic
     *  button actually drawn inside it -- the window is [RING_DP]-sized while the button is the
     *  smaller BTN_DP, centred, so every side carries ~6dp of nothing. Measuring the sliver
     *  against the window put that dead margin on-screen and left only
     *  (peekVisiblePx - ringInsetPx) of real, tappable ink: 14dp for the shipped 20dp default,
     *  which is exactly the too-small target [PeekVisibleSize]'s kdoc blames for the original
     *  peek-restore reliability bug. Offsetting by the inset makes [peekVisiblePx] mean what it
     *  says -- that much DRAWN ring on-screen -- without changing the ring's drawn size.
     *  Defaults to 0 so existing callers/tests keep the old window-measured behavior. */
    fun peekedX(x: Int, screenW: Int, ringSize: Int, peekVisiblePx: Int, ringInsetPx: Int = 0): Int =
        if (isSnappedToRightEdge(x, screenW, ringSize)) screenW - peekVisiblePx - ringInsetPx
        else peekVisiblePx - ringSize + ringInsetPx

    /** Peeking is only appropriate while the ring is truly idle -- never mid-recording or while
     *  cleanup/transcription is still running, so nothing important disappears mid-flow. */
    fun shouldAutoPeek(state: RecordingStateMachine.State): Boolean = state == RecordingStateMachine.State.IDLE
}
