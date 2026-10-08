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
