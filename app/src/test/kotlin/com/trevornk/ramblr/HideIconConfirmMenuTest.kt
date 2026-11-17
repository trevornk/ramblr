package com.trevornk.ramblr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HideIconConfirmMenuTest {
    private val rows = HideIconConfirmMenu.rows

    @Test fun `header question is not clickable`() {
        val header = rows.single { it.kind == ConfirmRowKind.HEADER }
        assertEquals("Hide the floating icon?", header.title)
        assertFalse("tapping the question must not look or act actionable (#373)", header.clickable)
    }

    @Test fun `only Hide it and Cancel are actionable`() {
        assertEquals(listOf("Hide it", "Cancel"), rows.filter { it.clickable }.map { it.title })
    }

    @Test fun `Hide it is the single primary action`() {
        assertEquals("Hide it", rows.single { it.kind == ConfirmRowKind.PRIMARY }.title)
        assertTrue(rows.first().kind == ConfirmRowKind.HEADER)
    }
}
