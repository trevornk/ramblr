package com.trevornk.ramblr

/**
 * True when a screen-size change looks like a fold/unfold transition -- a genuinely different
 * screen size -- rather than an ordinary rotation, which merely swaps width and height. Rotation
 * has never repositioned the overlay and isn't part of this fix (#41), so a plain W/H swap is left
 * alone to avoid an unrelated, unrequested jump.
 */
fun isFoldSizeChange(oldW: Int, oldH: Int, newW: Int, newH: Int): Boolean {
    if (oldW == newW && oldH == newH) return false
    val isRotationSwap = oldW == newH && oldH == newW
    return !isRotationSwap
}

/**
 * Recomputes the ring's x after a fold/unfold screen-size change (#41), pinning it to the same
 * edge (left or right) it was closest to on the OLD screen -- the same rule the drag-release
 * handler already applies -- rather than re-testing the stale absolute x against the NEW screen's
 * midpoint, which would misclassify a ring pinned to the right edge of a small screen as
 * left-of-center once the screen is much wider.
 */
fun snappedXForScreenChange(oldX: Int, oldScreenW: Int, newScreenW: Int, ringSize: Int, margin: Int): Int {
    val wasNearRightEdge = oldX + ringSize / 2 > oldScreenW / 2
    return if (wasNearRightEdge) newScreenW - ringSize - margin else margin
}

/**
 * Recomputes the ring's peeked window x after a fold/unfold screen-size change, given the
 * previous docked (fully visible) x it was peeking from. [snappedXForScreenChange] only knows how
 * to re-snap a DOCKED position to the new screen's edge; a peeked ring's actual window x is offset
 * from that docked position (see [RingPeek.peekedX]), so re-snapping the peeked x directly would
 * misclassify the edge for a screen size change (peeked windows sit almost entirely off-screen, so
 * their raw x is nowhere near either edge's midpoint test). This re-snaps the underlying docked x
 * instead, then re-derives the peeked x from that for the new screen -- keeping the ring visually
 * peeked, on the correct edge, at the correct sliver position after the transition.
 *
 * Returns the new (peeked x, new docked x) pair so the caller can update its own peek-restore
 * state ([prePeekX]) to match, instead of leaving it referencing the old screen's docked position.
 */
fun peekedPositionForScreenChange(
    oldDockedX: Int,
    oldScreenW: Int,
    newScreenW: Int,
    ringSize: Int,
    margin: Int,
    peekVisiblePx: Int,
): Pair<Int, Int> {
    val newDockedX = snappedXForScreenChange(oldDockedX, oldScreenW, newScreenW, ringSize, margin)
    val newPeekedX = RingPeek.peekedX(newDockedX, newScreenW, ringSize, peekVisiblePx)
    return newPeekedX to newDockedX
}

/**
 * Preserves the ring's vertical position proportionally across a fold/unfold screen-size change
 * (#41) -- e.g. a ring 30% down the old screen stays roughly 30% down the new one -- instead of
 * resetting to a hardcoded center, so a fold transition never fights a position the user dragged
 * to manually. Clamped to stay fully on-screen.
 */
fun proportionalYForScreenChange(oldY: Int, oldScreenH: Int, newScreenH: Int, ringSize: Int, margin: Int): Int {
    if (oldScreenH <= 0) return oldY
    val oldCenterY = oldY + ringSize / 2
    val ratio = oldCenterY.toFloat() / oldScreenH
    val newCenterY = (ratio * newScreenH).toInt()
    val maxY = (newScreenH - ringSize - margin).coerceAtLeast(margin)
    return (newCenterY - ringSize / 2).coerceIn(margin, maxY)
}

/**
 * Clamps a persisted (SharedPreferences-restored) overlay x/y to the CURRENT screen's bounds
 * before it's used at overlay-creation time (service-recreation persistence fix). The saved
 * position was valid for whatever screen size was current when it was written, but the service
 * can be recreated after the screen size changed (app reinstalled onto a different device, a
 * fold-state change that raced the save, or a stale/corrupt saved value) -- using it unclamped
 * could park the ring partially or fully off-screen with no on-screen affordance to recover it.
 * Mirrors the same [Int.coerceIn] pattern already used by the drag-release y clamp (see the
 * ACTION_UP handler in WhisperAccessibilityService) rather than introducing new clamping rules.
 */
fun clampRestoredPosition(x: Int, y: Int, screenW: Int, screenH: Int, ringSize: Int, margin: Int): Pair<Int, Int> {
    val maxX = (screenW - ringSize - margin).coerceAtLeast(margin)
    val maxY = (screenH - ringSize - margin).coerceAtLeast(margin)
    return x.coerceIn(margin, maxX) to y.coerceIn(margin, maxY)
}
