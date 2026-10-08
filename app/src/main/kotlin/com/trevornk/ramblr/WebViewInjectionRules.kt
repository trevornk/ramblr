package com.trevornk.ramblr

/**
 * Rules for dictating into WebView-hosted fields (Sable, Element, web chat composers, ...).
 *
 * A Chromium WebView exposes the *page* as a tree: the `android.webkit.WebView` container, generic
 * `android.view.View` nodes for page content, and every web input as an `android.widget.EditText`.
 * When the user has not tapped the input, input focus sits on the container or on a page `View`,
 * not on the EditText. `ACTION_PASTE` on those nodes reports success (`performAction` returns
 * true) but inserts nothing, so the dictation silently vanished and the user had to tap the field
 * first. `ACTION_FOCUS` followed by `ACTION_SET_TEXT` on the *EditText* works without any tap, so
 * the fix is purely in candidate choice: never let a focused, non-field WebView node pre-empt the
 * real field.
 */

/**
 * True for a node that lives in a WebView page but is not itself a text field: the WebView
 * container, or a generic page node under one. Editable nodes and `EditText`-classed nodes are real
 * fields and never match. [hasWebViewAncestor] is only consulted for nodes that are neither.
 * Native (non-WebView) nodes never match, so their behaviour is unchanged.
 */
fun isNonFieldWebViewNode(className: CharSequence?, isEditable: Boolean, hasWebViewAncestor: () -> Boolean): Boolean {
    if (isEditable) return false
    val name = className?.toString().orEmpty()
    if (name.contains("EditText")) return false
    return name.contains("WebView") || hasWebViewAncestor()
}

/**
 * Score penalty for such a node: it stays in the candidate list as a last resort (the existing
 * paste fallback, for a page with no field at all) but ranks below any real field, including an
 * unfocused EditText (whose own score is at most 60 + 20).
 */
const val BARE_WEBVIEW_SCORE_PENALTY = 200

/**
 * Corrects the selection end a Chromium WebView composer reports for "select all" (#300).
 *
 * Measured on a Pixel 10a in Sable v2: with the composer's whole draft selected (Ctrl+A or the
 * long-press "Select all"), the `EditText` node reports `sel=0/1` whatever the text length -- 5, 26
 * or 54 chars, one line or three. Chromium resolves the selection's focus to a DOM position on the
 * editable root and surfaces its child offset (1) instead of a character offset (the text length).
 * Ranges that do not end at the root, and the drag-selected word case (`14/16`), are reported
 * correctly, as is a Shift+Right selection from the start (`0/5`).
 *
 * The consequence was that [resolveReplacementSpan] trusted `0..1` as a genuine ranged selection
 * and the dictation replaced only the first character, leaving the rest of the old text in place
 * (`"hello there friend"` + dictation `REPLACED` -> `"REPLACEDello there friend"`).
 *
 * The signature -- an anchor at 0 and an end of exactly 1 against longer text, inside a WebView --
 * is returned as "everything selected" ([textLength]). Accepted collateral: a user who deliberately
 * selected only the first character of a multi-character WebView field and dictates will have the
 * field replaced rather than that one character (undo, #27, restores it). Native fields are never
 * touched: [inWebView] is only consulted when the signature matches, and returns false for them.
 */
fun correctWebViewSelectAllEnd(
    selStart: Int,
    selEnd: Int,
    textLength: Int,
    inWebView: () -> Boolean,
): Int =
    if (selStart == 0 && selEnd == 1 && textLength > 1 && inWebView()) textLength else selEnd
