package com.trevornk.ramblr

/**
 * Pure add/remove/reorder logic for the Settings waterfall step list (#32), split out from
 * MainActivity so it's directly unit-testable without any Android view/dialog scaffolding. ADR-
 * 0001 leaves the reorder mechanism as an implementation choice between drag handles and simple
 * up/down arrows for a 3-5 item list; this app has no RecyclerView/ItemTouchHelper usage anywhere
 * else, so up/down arrows (backed by [moveUp]/[moveDown]) were chosen to fit the existing
 * plain-LinearLayout-rows convention instead of introducing one just for this screen.
 */
object CleanupWaterfallEditing {
    /** Swaps [index] with its predecessor. No-op if [index] is already first or out of range. */
    fun moveUp(steps: List<CleanupStep>, index: Int): List<CleanupStep> {
        if (index !in steps.indices || index == 0) return steps
        return steps.toMutableList().apply { add(index - 1, removeAt(index)) }
    }

    /** Swaps [index] with its successor. No-op if [index] is already last or out of range. */
    fun moveDown(steps: List<CleanupStep>, index: Int): List<CleanupStep> {
        if (index !in steps.indices || index == steps.lastIndex) return steps
        return steps.toMutableList().apply { add(index + 1, removeAt(index)) }
    }

    /** Removes the step at [index]. No-op if out of range. */
    fun remove(steps: List<CleanupStep>, index: Int): List<CleanupStep> {
        if (index !in steps.indices) return steps
        return steps.toMutableList().apply { removeAt(index) }
    }

    /** Replaces the step at [index] with [step]. No-op if out of range. */
    fun replace(steps: List<CleanupStep>, index: Int, step: CleanupStep): List<CleanupStep> {
        if (index !in steps.indices) return steps
        return steps.toMutableList().apply { set(index, step) }
    }
}
