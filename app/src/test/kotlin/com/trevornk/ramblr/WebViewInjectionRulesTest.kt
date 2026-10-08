package com.trevornk.ramblr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WebViewInjectionRulesTest {
    private fun nonField(cls: String?, editable: Boolean, ancestor: Boolean = false) =
        isNonFieldWebViewNode(cls, editable) { ancestor }

    @Test fun `a non-editable WebView container is a non-field node`() {
        assertTrue(nonField("android.webkit.WebView", editable = false))
    }

    @Test fun `a generic page View under a WebView is a non-field node`() {
        assertTrue(nonField("android.view.View", editable = false, ancestor = true))
    }

    @Test fun `an editable node or an EditText is a real field even inside a WebView`() {
        assertFalse(nonField("android.webkit.WebView", editable = true))
        assertFalse(nonField("android.view.View", editable = true, ancestor = true))
        assertFalse(nonField("android.widget.EditText", editable = false, ancestor = true))
        assertFalse(nonField("android.widget.EditText", editable = true, ancestor = true))
    }

    @Test fun `native nodes without a WebView ancestor are never non-field WebView nodes`() {
        assertFalse(nonField("android.view.View", editable = false, ancestor = false))
        assertFalse(nonField("android.widget.TextView", editable = false, ancestor = false))
        assertFalse(nonField(null, editable = false, ancestor = false))
    }

    @Test fun `the ancestor walk is skipped for fields and for WebView nodes themselves`() {
        var walks = 0
        isNonFieldWebViewNode("android.widget.EditText", false) { walks++; true }
        isNonFieldWebViewNode("android.view.View", true) { walks++; true }
        isNonFieldWebViewNode("android.webkit.WebView", false) { walks++; true }
        assertEquals(0, walks)
    }

    @Test fun `penalty ranks a focused non-field WebView node below an unfocused EditText and below zero`() {
        val focusedPageNode = 40 - BARE_WEBVIEW_SCORE_PENALTY
        val unfocusedEditText = 20
        assertTrue(focusedPageNode < unfocusedEditText)
        assertTrue(focusedPageNode < 0)
    }
}
