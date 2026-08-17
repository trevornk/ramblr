package com.trevornk.ramblr

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Covers the inset arithmetic that edge-to-edge under targetSdk 36 depends on. The Android
 * plumbing (reading real insets, applying padding) is not testable here by design -- it is
 * deliberately kept trivial in [BaseSettingsActivity] so that everything with a decision in it
 * lives in [WindowInsetsMath] and can be pinned on the JVM.
 */
class WindowInsetsMathTest {

    /** Typical portrait phone: status bar top, gesture pill bottom, keyboard hidden. */
    @Test
    fun `portrait with no keyboard pads for status bar and gesture pill`() {
        val p = WindowInsetsMath.resolve(
            barsLeft = 0, barsTop = 66, barsRight = 0, barsBottom = 48,
        )
        assertEquals(0, p.left)
        assertEquals(66, p.top)
        assertEquals(0, p.right)
        assertEquals(48, p.bottom)
    }

    /**
     * The regression this file exists to prevent. The keyboard is drawn *over* the navigation bar,
     * so summing them would lift the focused field about a gesture-pill's height too far. Settings
     * has 27 EditText fields, so this is the common case, not an edge case.
     */
    @Test
    fun `keyboard and navigation bar do not stack`() {
        val p = WindowInsetsMath.resolve(
            barsLeft = 0, barsTop = 66, barsRight = 0, barsBottom = 48,
            imeBottom = 900,
        )
        assertEquals("IME must win outright, not add to the nav bar", 900, p.bottom)
    }

    /** With the keyboard hidden the IME inset is 0 and must not shrink the nav-bar padding. */
    @Test
    fun `hidden keyboard leaves navigation bar padding intact`() {
        val p = WindowInsetsMath.resolve(
            barsLeft = 0, barsTop = 66, barsRight = 0, barsBottom = 48,
            imeBottom = 0,
        )
        assertEquals(48, p.bottom)
    }

    /**
     * Landscape with a notch on the left. In portrait the cutout hides inside the status bar, but
     * rotated there is no system bar on that edge -- so the cutout is the only thing keeping text
     * out from under the camera.
     */
    @Test
    fun `landscape cutout wins on an edge with no system bar`() {
        val p = WindowInsetsMath.resolve(
            barsLeft = 0, barsTop = 0, barsRight = 48, barsBottom = 0,
            cutoutLeft = 132, cutoutRight = 0,
        )
        assertEquals("cutout must not be ignored just because barsLeft is 0", 132, p.left)
        assertEquals(48, p.right)
    }

    /** Where a bar is larger than the cutout on the same edge, the bar wins; they never sum. */
    @Test
    fun `overlapping bar and cutout take the larger, never the sum`() {
        val p = WindowInsetsMath.resolve(
            barsLeft = 80, barsTop = 0, barsRight = 0, barsBottom = 0,
            cutoutLeft = 44,
        )
        assertEquals(80, p.left)
    }

    /** A device with no bars, no cutout and no keyboard must get no padding at all. */
    @Test
    fun `no insets yields no padding`() {
        val p = WindowInsetsMath.resolve(0, 0, 0, 0)
        assertEquals(WindowInsetsMath.ContentPadding(0, 0, 0, 0), p)
    }
}
