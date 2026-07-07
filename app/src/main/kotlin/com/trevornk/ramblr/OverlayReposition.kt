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
