package com.trevornk.ramblr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Row order + style-accordion state of the floating icon's long-press menu (pure, no Android). */
class StyleMenuRowsTest {
    private val personas = listOf(CleanupPersonas.FORMAL, CleanupPersonas.CASUAL, CleanupPersonas.NOTES)
    private val current = CleanupPersonas.CASUAL

    private fun rows(shortcut: Boolean = false, hide: Boolean = false, expanded: Boolean = false) =
        styleMenuRows(shortcut, hide, personas, current, expanded)

    @Test fun `shortcut row is first when set`() {
        assertEquals(StyleMenuRow.Shortcut, rows(shortcut = true).first())
    }

    @Test fun `shortcut row is absent when unset`() {
        assertFalse(rows(shortcut = false).contains(StyleMenuRow.Shortcut))
        assertEquals(StyleMenuRow.Cleanup, rows(shortcut = false).first())
    }

    @Test fun `collapsed shows exactly one style row with the current title and no choices`() {
        val r = rows(expanded = false)
        assertEquals(listOf(StyleMenuRow.StyleSummary(current.title, expanded = false)), r.filter { it is StyleMenuRow.StyleSummary })
        assertTrue(r.none { it is StyleMenuRow.StyleChoice })
    }

    @Test fun `expanded shows every style directly after the style row with the current one selected`() {
        val r = rows(expanded = true)
        val at = r.indexOf(StyleMenuRow.StyleSummary(current.title, expanded = true))
        assertTrue(at >= 0)
        assertEquals(
            personas.map { StyleMenuRow.StyleChoice(it, it == current) },
            r.subList(at + 1, at + 1 + personas.size),
        )
        assertEquals(StyleMenuRow.Translate, r[at + 1 + personas.size])
    }

    @Test fun `full order with everything on`() {
        assertEquals(
            listOf(
                StyleMenuRow.Shortcut,
                StyleMenuRow.Cleanup,
                StyleMenuRow.StyleSummary(current.title, false),
                StyleMenuRow.Translate,
                StyleMenuRow.LivePreview,
                StyleMenuRow.OpenSettings,
                StyleMenuRow.HideIcon,
            ),
            rows(shortcut = true, hide = true),
        )
    }

    @Test fun `hide icon row only when enabled`() {
        assertFalse(rows(hide = false).contains(StyleMenuRow.HideIcon))
        assertEquals(StyleMenuRow.HideIcon, rows(hide = true).last())
    }

    @Test fun `tapping the style row toggles and choosing a style collapses`() {
        assertTrue(styleAccordionNext(false, StyleAccordionEvent.SUMMARY_TAPPED))
        assertFalse(styleAccordionNext(true, StyleAccordionEvent.SUMMARY_TAPPED))
        assertFalse(styleAccordionNext(true, StyleAccordionEvent.STYLE_CHOSEN))
        assertFalse(styleAccordionNext(false, StyleAccordionEvent.STYLE_CHOSEN))
    }
}
