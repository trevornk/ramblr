package com.trevornk.ramblr

import org.junit.Assert.assertEquals
import org.junit.Test

class CleanupWaterfallEditingTest {

    private val a = CleanupStep(CleanupStepGroup.OMNIROUTE, "a")
    private val b = CleanupStep(CleanupStepGroup.OMNIROUTE, "b")
    private val c = CleanupStep(CleanupStepGroup.OPENAI_DIRECT, "c")

    @Test fun `moveUp swaps a middle step with its predecessor`() {
        assertEquals(listOf(b, a, c), CleanupWaterfallEditing.moveUp(listOf(a, b, c), 1))
    }

    @Test fun `moveUp on the first step is a no-op`() {
        val steps = listOf(a, b, c)
        assertEquals(steps, CleanupWaterfallEditing.moveUp(steps, 0))
    }

    @Test fun `moveUp with an out-of-range index is a no-op`() {
        val steps = listOf(a, b, c)
        assertEquals(steps, CleanupWaterfallEditing.moveUp(steps, 5))
        assertEquals(steps, CleanupWaterfallEditing.moveUp(steps, -1))
    }

    @Test fun `moveDown swaps a middle step with its successor`() {
        assertEquals(listOf(a, c, b), CleanupWaterfallEditing.moveDown(listOf(a, b, c), 1))
    }

    @Test fun `moveDown on the last step is a no-op`() {
        val steps = listOf(a, b, c)
        assertEquals(steps, CleanupWaterfallEditing.moveDown(steps, 2))
    }

    @Test fun `moveDown with an out-of-range index is a no-op`() {
        val steps = listOf(a, b, c)
        assertEquals(steps, CleanupWaterfallEditing.moveDown(steps, 5))
    }

    @Test fun `remove drops the step at the given index`() {
        assertEquals(listOf(a, c), CleanupWaterfallEditing.remove(listOf(a, b, c), 1))
    }

    @Test fun `remove with an out-of-range index is a no-op`() {
        val steps = listOf(a, b, c)
        assertEquals(steps, CleanupWaterfallEditing.remove(steps, 5))
    }

    @Test fun `replace substitutes the step at the given index`() {
        val d = CleanupStep(CleanupStepGroup.ANTHROPIC_DIRECT, "d")
        assertEquals(listOf(a, d, c), CleanupWaterfallEditing.replace(listOf(a, b, c), 1, d))
    }

    @Test fun `replace with an out-of-range index is a no-op`() {
        val steps = listOf(a, b, c)
        assertEquals(steps, CleanupWaterfallEditing.replace(steps, 5, a))
    }

    @Test fun `a single-item list is unaffected by moveUp or moveDown`() {
        val single = listOf(a)
        assertEquals(single, CleanupWaterfallEditing.moveUp(single, 0))
        assertEquals(single, CleanupWaterfallEditing.moveDown(single, 0))
    }
}
