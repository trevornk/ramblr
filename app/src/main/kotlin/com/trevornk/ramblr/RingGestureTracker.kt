package com.trevornk.ramblr

import kotlin.math.hypot

/**
 * How one touch gesture on the floating ring ended (#322). Pure classification, no Android
 * dependencies, so every tap / hold / drag decision is unit-testable.
 */
enum class RingGestureOutcome {
    /** A short, still touch: toggle recording. */
    TAP,

    /** The finger crossed the drag slop at some point: reposition + snap to the edge. Never a tap. */
    DRAG,

    /** The long-press already ran (or its timer was due): nothing more to do on lift. Never a tap. */
    HOLD_CONSUMED,

    /** The hold outlasted the long-press time but the timer had not run yet (main thread was
     *  busy): the caller must run the long-press action now. Never a tap. */
    HOLD_DUE,
}

/** What a MOVE did to the gesture. */
enum class RingMoveResult {
    /** Still inside the slop: the ring window must NOT move (a held thumb rolls ~20-35 px). */
    HOLDING,

    /** This MOVE crossed the slop: begin the drag. [RingGestureTracker.dragStartedAfterLongPress]
     *  says whether a long-press action had already fired (so its menu needs dismissing, #57). */
    DRAG_STARTED,

    /** Already dragging: keep following the finger. */
    DRAGGING,
}

/**
 * Tap / long-press / drag disambiguation for the floating ring.
 *
 * Why this exists (#322): the old logic compared the finger's displacement from ACTION_DOWN only
 * at two instants -- when the 500 ms long-press timer fired and again on ACTION_UP. A held thumb
 * rolls ~20 px within 500 ms and ~35 px within a second (Samsung Z Fold8 trace), which straddles a
 * 10 dp (22.5 px) threshold: the long-press was suppressed or its menu immediately dismissed, and
 * because the thumb rolls back as it lifts, displacement at ACTION_UP was small again so the
 * gesture ended as a TAP and started recording. This tracker instead:
 *  - remembers a crossed drag slop for the rest of the gesture ([RingGestureOutcome.DRAG] is
 *    sticky -- dragging out and back to the start is still a drag, never a tap);
 *  - never resolves a hold that reached the long-press time as a tap while a long-press action is
 *    armed;
 *  - uses Euclidean distance with a slop bigger than natural thumb roll, and a larger slop again
 *    for turning an already-fired long-press into a drag (#57's hold-then-drag still works: a
 *    deliberate drag covers far more than [postLongPressSlopPx]).
 *
 * @param dragSlopPx displacement from the DOWN point that turns a pending touch into a drag.
 * @param postLongPressSlopPx displacement that turns a touch whose long-press already fired into a drag.
 * @param longPressTimeoutMs hold time after which the touch can no longer be a tap (when armed);
 *   a `var` so the caller can re-read the system long-press setting on every ACTION_DOWN.
 */
class RingGestureTracker(
    private val dragSlopPx: Float,
    private val postLongPressSlopPx: Float,
    var longPressTimeoutMs: Long,
) {
    private var downX = 0f
    private var downY = 0f
    private var downTimeMs = 0L
    private var armed = false
    private var timerResolved = false

    var isDragging = false
        private set
    var longPressFired = false
        private set

    /** True when the drag began after a long-press action had already fired (#57). */
    var dragStartedAfterLongPress = false
        private set

    /** Largest displacement from the DOWN point seen so far, for the UP log line. */
    var maxDisplacementPx = 0f
        private set

    fun onDown(x: Float, y: Float, timeMs: Long, longPressArmed: Boolean) {
        downX = x; downY = y; downTimeMs = timeMs
        armed = longPressArmed
        timerResolved = false
        isDragging = false
        longPressFired = false
        dragStartedAfterLongPress = false
        maxDisplacementPx = 0f
    }

    fun displacementPx(x: Float, y: Float): Float = hypot(x - downX, y - downY)

    fun onMove(x: Float, y: Float): RingMoveResult {
        val d = displacementPx(x, y)
        if (d > maxDisplacementPx) maxDisplacementPx = d
        if (isDragging) return RingMoveResult.DRAGGING
        val slop = if (longPressFired) postLongPressSlopPx else dragSlopPx
        if (d < slop) return RingMoveResult.HOLDING
        isDragging = true
        dragStartedAfterLongPress = longPressFired
        return RingMoveResult.DRAG_STARTED
    }

    /**
     * The long-press timer ran. Returns true when the caller should now perform the long-press
     * action; false when the gesture is already a drag (drag wins, #57). Either way the touch can
     * no longer end as a tap.
     */
    fun onLongPressTimer(): Boolean {
        timerResolved = true
        return !isDragging
    }

    /** The caller actually performed a long-press action (it may have decided there was none). */
    fun markLongPressFired() {
        longPressFired = true
    }

    fun heldMs(timeMs: Long): Long = timeMs - downTimeMs

    /** Classifies the lift. [x]/[y] are only logged by callers; the decision uses history. */
    fun onUp(x: Float, y: Float, timeMs: Long): RingGestureOutcome {
        val d = displacementPx(x, y)
        if (d > maxDisplacementPx) maxDisplacementPx = d
        if (isDragging) return RingGestureOutcome.DRAG
        if (longPressFired || (armed && timerResolved)) return RingGestureOutcome.HOLD_CONSUMED
        if (armed && heldMs(timeMs) >= longPressTimeoutMs) return RingGestureOutcome.HOLD_DUE
        return RingGestureOutcome.TAP
    }

    /** ACTION_CANCEL: true when the system cancelled mid-drag, so the caller should still settle
     *  the ring at its edge instead of leaving it wherever the finger stopped. */
    fun onCancel(): Boolean = isDragging

    companion object {
        /** Drag slop in dp (~2.5 mm): above the natural roll of a held thumb. */
        const val DRAG_SLOP_DP = 16f

        /** Slop once a long-press already fired (~5 mm): a deliberate hold-then-drag clears it
         *  easily, a held thumb does not, so the menu is not dismissed by thumb roll. */
        const val POST_LONG_PRESS_SLOP_DP = 32f

        /** Floor for the long-press time; the system's own timeout wins when it is longer
         *  (accessibility long-press delay), never shorter -- Pixel's 400 ms would otherwise
         *  turn slow taps into long-presses. */
        const val MIN_LONG_PRESS_MS = 500L

        fun longPressTimeoutMs(systemLongPressMs: Int): Long =
            maxOf(MIN_LONG_PRESS_MS, systemLongPressMs.toLong())

        fun dragSlopPx(density: Float, scaledTouchSlopPx: Int): Float =
            maxOf(DRAG_SLOP_DP * density, scaledTouchSlopPx.toFloat())

        fun postLongPressSlopPx(density: Float, scaledTouchSlopPx: Int): Float =
            maxOf(POST_LONG_PRESS_SLOP_DP * density, 2f * scaledTouchSlopPx)
    }
}
