package com.trevornk.ramblr

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RichTextDestinationRulesTest {
    private fun lossy(
        editable: Boolean = true,
        inWebView: Boolean = true,
        multiLine: Boolean = true,
        empty: Boolean = false,
    ) = isLossyWholeFieldDestination(editable, inWebView, multiLine, empty)

    @Test fun `an editable multi-line WebView field with content is lossy`() {
        assertTrue(lossy())
    }

    @Test fun `an empty rich composer keeps the direct write`() {
        assertFalse(lossy(empty = true))
    }

    @Test fun `a single-line WebView input is not lossy`() {
        assertFalse(lossy(multiLine = false))
    }

    @Test fun `a native multi-line EditText outside a WebView is not lossy`() {
        assertFalse(lossy(inWebView = false))
    }

    @Test fun `a non-editable node is not lossy`() {
        assertFalse(lossy(editable = false))
    }
}
