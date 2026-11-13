package com.trevornk.ramblr

import android.view.accessibility.AccessibilityNodeInfo
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** [AccessibilityTextDestination.wholeFieldWriteIsLossy] over real (shadowed) node structure, #376. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AccessibilityRichTextDestinationTest {

    private fun webView(): AccessibilityNodeInfo =
        AccessibilityNodeInfo.obtain().apply { className = "android.webkit.WebView" }

    private fun field(
        inWebView: Boolean,
        children: Int,
        body: String?,
        configure: AccessibilityNodeInfo.() -> Unit = {},
    ): AccessibilityNodeInfo {
        val node = AccessibilityNodeInfo.obtain().apply {
            className = "android.widget.EditText"
            isEditable = true
            text = body
            configure()
        }
        if (inWebView) shadowOf(webView()).addChild(node)
        repeat(children) { shadowOf(node).addChild(AccessibilityNodeInfo.obtain()) }
        return node
    }

    @Test fun `an editable WebView node with children and text is lossy`() {
        assertTrue(AccessibilityTextDestination(field(inWebView = true, children = 2, body = "one\ntwo")).wholeFieldWriteIsLossy())
    }

    @Test fun `an empty WebView composer is not lossy, including Chromium's newline-only empty`() {
        assertFalse(AccessibilityTextDestination(field(inWebView = true, children = 1, body = "")).wholeFieldWriteIsLossy())
        assertFalse(AccessibilityTextDestination(field(inWebView = true, children = 1, body = "\n")).wholeFieldWriteIsLossy())
    }

    @Test fun `a focused populated WebView editor reporting selection -1 -1 is still lossy`() {
        // resolveRealText treats a focused editable at -1/-1 as a placeholder and reads "", which
        // must not make a populated contenteditable look empty (one SET_TEXT would wipe the body).
        val node = field(inWebView = true, children = 2, body = "one\ntwo") {
            isFocused = true
            setTextSelection(-1, -1)
        }
        val destination = AccessibilityTextDestination(node)
        assertTrue(destination.readText().isEmpty())
        assertTrue(destination.wholeFieldWriteIsLossy())
    }

    @Test fun `a hint-showing WebView editor is not lossy`() {
        val node = field(inWebView = true, children = 1, body = "Write a message") { isShowingHintText = true }
        assertFalse(AccessibilityTextDestination(node).wholeFieldWriteIsLossy())
    }

    @Test fun `a WebView textarea without children is not lossy`() {
        assertFalse(AccessibilityTextDestination(field(inWebView = true, children = 0, body = "one\ntwo")).wholeFieldWriteIsLossy())
    }

    @Test fun `a native EditText with children outside a WebView is not lossy`() {
        assertFalse(AccessibilityTextDestination(field(inWebView = false, children = 2, body = "one\ntwo")).wholeFieldWriteIsLossy())
    }

    @Test fun `a password-flagged rich node refuses the paste without staging the clipboard`() {
        var staged = false
        val node = field(inWebView = true, children = 2, body = "one") { isPassword = true }
        val destination = AccessibilityTextDestination(node) { staged = true }

        assertFalse(destination.pasteFromClipboard(" dictated"))
        assertFalse(staged)
    }

    @Test fun `the paste hands the payload to beforePaste`() {
        var seen: String? = null
        val destination = AccessibilityTextDestination(field(inWebView = true, children = 2, body = "one")) { seen = it }
        destination.pasteFromClipboard(" dictated")
        assertTrue(seen == " dictated")
    }
}
