package com.trevornk.ramblr

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RichTextDestinationRulesTest {
    private fun lossy(
        editable: Boolean = true,
        inWebView: Boolean = true,
        children: Int = 2,
        empty: Boolean = false,
    ) = isLossyWholeFieldDestination(editable, inWebView, children, empty)

    @Test fun `an editable WebView contenteditable with content is lossy`() {
        assertTrue(lossy())
    }

    @Test fun `an empty rich composer keeps the direct write`() {
        assertFalse(lossy(empty = true))
    }

    @Test fun `a WebView textarea or input has no children and is not lossy`() {
        assertFalse(lossy(children = 0))
    }

    @Test fun `a native EditText with children outside a WebView is not lossy`() {
        assertFalse(lossy(inWebView = false))
    }

    @Test fun `a non-editable node is not lossy`() {
        assertFalse(lossy(editable = false))
    }
}
