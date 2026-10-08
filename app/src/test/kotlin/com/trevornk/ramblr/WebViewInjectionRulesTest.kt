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

    // --- #300: Chromium reports select-all as 0/1 regardless of text length ---

    private fun corrected(start: Int, end: Int, len: Int, inWebView: Boolean = true) =
        correctWebViewSelectAllEnd(start, end, len) { inWebView }

    @Test fun `webview 0 to 1 against longer text is treated as select all`() {
        // Measured: "hello there friend" (18 chars) selected with Ctrl+A reports sel=0/1.
        assertEquals(18, corrected(0, 1, 18))
        assertEquals(51, corrected(0, 1, 51))
        assertEquals(2, corrected(0, 1, 2))
    }

    @Test fun `webview selections that are not the 0 to 1 signature are left alone`() {
        assertEquals(5, corrected(0, 5, 17)) // Shift+Right x5 from the start
        assertEquals(16, corrected(14, 16, 53)) // drag-selected word
        assertEquals(17, corrected(0, 17, 17)) // already correct
        assertEquals(0, corrected(0, 0, 17)) // collapsed caret at start
        assertEquals(1, corrected(1, 1, 17)) // collapsed caret after first char
        assertEquals(1, corrected(1, 1, 1))
        assertEquals(-1, corrected(-1, -1, 17)) // unfocused/unreported
    }

    @Test fun `a one-character field is not inflated`() {
        // 0/1 over a single character already is the whole field.
        assertEquals(1, corrected(0, 1, 1))
        assertEquals(1, corrected(0, 1, 0))
    }

    @Test fun `native fields reporting 0 to 1 keep their real selection`() {
        assertEquals(1, corrected(0, 1, 18, inWebView = false))
    }

    @Test fun `the webview ancestor walk only runs for the 0 to 1 signature`() {
        var walks = 0
        correctWebViewSelectAllEnd(0, 5, 17) { walks++; true }
        correctWebViewSelectAllEnd(-1, -1, 17) { walks++; true }
        correctWebViewSelectAllEnd(0, 1, 1) { walks++; true }
        assertEquals(0, walks)
        correctWebViewSelectAllEnd(0, 1, 17) { walks++; true }
        assertEquals(1, walks)
    }

    @Test fun `select all in a webview composer is replaced by the dictation (#300)`() {
        val current = "hello there friend"
        val end = corrected(0, 1, current.length)
        assertEquals("Dictated words", composeOneShotInjection(current, 0, end, "Dictated words"))
    }

    @Test fun `without the correction the old text survived (#300 regression guard)`() {
        // What the service did before: it trusted 0/1 and replaced only the first character.
        assertEquals(
            "Dictated wordsello there friend",
            composeOneShotInjection("hello there friend", 0, 1, "Dictated words"),
        )
    }

    @Test fun `unfocused webview composer still appends after the draft`() {
        val current = "hello there friend"
        val end = corrected(-1, -1, current.length)
        assertEquals("hello there friend Dictated", composeOneShotInjection(current, -1, end, "Dictated"))
    }

    @Test fun `whitespace-only webview composer is still treated as empty`() {
        // Sable's empty composer reports "\n" with sel=0/0 (#296); it must resolve to "" and insert plainly.
        val real = resolveRealText("\n", false, 0, 0, isEditable = true, isFocused = true)
        assertEquals("", real)
        assertEquals("Dictated", composeOneShotInjection(real, 0, corrected(0, 0, real.length), "Dictated"))
    }
}
