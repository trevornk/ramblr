package com.trevornk.ramblr

import android.os.Bundle
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo

/**
 * The accessibility-service implementation of [TextDestination]: a single
 * [AccessibilityNodeInfo] candidate in whatever app the user is dictating into.
 *
 * This is a move, not a rewrite. Every method body here came out of
 * `WhisperAccessibilityService` unchanged, including the hard-won behaviours from #42, #47, #111,
 * #140 and #144 -- the placeholder/hint resolution, the selection-based placeholder signal, the
 * post-`ACTION_SET_TEXT` selection nudge, and direct-before-paste ordering. Changing any of them
 * here would regress real-device-validated fixes.
 *
 * Node ownership stays with the caller: this class never obtains or recycles [node]. The service's
 * candidate scan already owns that lifecycle, and a destination that recycled its own node would
 * break the undo snapshot (#27), which keeps a separate obtained copy.
 *
 * [beforePaste] runs right before any paste attempt with the payload about to be pasted, so the caller can put it on the
 * clipboard only when a paste actually needs it, instead of on every injection.
 */
class AccessibilityTextDestination(
    val node: AccessibilityNodeInfo,
    // Declared before [beforePaste] so a trailing lambda still binds to it.
    private val insertAtCaret: ((String) -> Boolean)? = null,
    private val beforePaste: (String) -> Unit = {},
) : TextDestination {

    override fun acceptsDirectWrite(): Boolean =
        node.isEditable || node.className?.toString()?.contains("EditText") == true

    override fun refresh(): Boolean =
        try {
            node.refresh()
        } catch (e: Exception) {
            Log.w(TAG, "Node refresh failed; treating destination as stale", e)
            false
        }

    override fun readText(): String = resolveRealText(
        node.text?.toString(),
        node.isShowingHintText,
        node.textSelectionStart,
        node.textSelectionEnd,
        node.isEditable,
        node.isFocused,
    )

    override fun selectionStart(): Int = node.textSelectionStart

    // #300: Chromium reports select-all in a WebView composer as 0/1 whatever the text length; see
    // correctWebViewSelectAllEnd. The ancestor walk only runs when that exact signature matches.
    override fun selectionEnd(): Int = correctWebViewSelectAllEnd(
        node.textSelectionStart,
        node.textSelectionEnd,
        node.text?.length ?: 0,
    ) { hasWebViewAncestor(node) }

    override fun prepareForWrite() {
        logNode("Trying node", node)
        node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
    }

    override fun replaceAllText(text: String): Boolean {
        // Last line of defence: a password node reports its text as "" so a write would also
        // wipe what the user typed. Never write to one.
        if (node.isPasswordField()) return false
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        val ok = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        Log.i(TAG, "ACTION_SET_TEXT => $ok")
        return ok
    }

    override fun placeCaret(position: Int) = nudgeSelection(node, position)

    // #367: only a WebView text field (Chrome <textarea>), whose view ignores accessibility
    // selection changes for scrolling. Elsewhere the whole-field write is unchanged.
    override fun insertAtCaretNatively(text: String): Boolean {
        val insert = insertAtCaret ?: return false
        if (node.isPasswordField() || !hasWebViewAncestor(node)) return false
        // The input connection belongs to whichever editor has input focus, not to this node.
        if (!refresh() || !node.isFocused) return false
        return insert(text)
    }

    // #376: the WebView-ancestor walk only runs for an editable multi-line node.
    override fun wholeFieldWriteIsLossy(): Boolean {
        val editable = acceptsDirectWrite()
        val multiLine = node.isMultiLine
        return isLossyWholeFieldDestination(
            isEditable = editable,
            inWebView = editable && multiLine && hasWebViewAncestor(node),
            isMultiLine = multiLine,
            // Raw node values, never readText(): resolveRealText blanks a focused editable at selection
            // -1/-1 (the #140 placeholder heuristic), which would make a populated body look empty.
            currentTextIsEmpty = rawTextIsEmpty(node),
        )
    }

    override fun pasteFromClipboard(payload: String): Boolean {
        if (node.isPasswordField()) return false
        beforePaste(payload)
        findCustomPasteAction(node)?.let { action ->
            val ok = node.performAction(action.id)
            Log.i(TAG, "Custom action '${action.label}' (${action.id}) => $ok")
            if (ok) return true
        }
        val pasteOk = node.performAction(AccessibilityNodeInfo.ACTION_PASTE)
        Log.i(TAG, "ACTION_PASTE => $pasteOk")
        return pasteOk
    }

    companion object {
        private const val TAG = "PhoneWhisper"

        /** Same bound the service uses to decide whether a node lives in a WebView page. */
        private const val WEBVIEW_ANCESTOR_DEPTH = 40

        /** Emptiness from raw node values (never [resolveRealText]); see [isLossyWholeFieldDestination]. */
        private fun rawTextIsEmpty(node: AccessibilityNodeInfo): Boolean =
            node.isShowingHintText || node.text.isNullOrBlank()

        /** True when [node] sits under an `android.webkit.WebView` container. Does not recycle [node]. */
        private fun hasWebViewAncestor(node: AccessibilityNodeInfo): Boolean {
            var current = node.parent
            var depth = 0
            while (current != null && depth < WEBVIEW_ANCESTOR_DEPTH) {
                if (current.className?.contains("WebView") == true) {
                    current.recycle()
                    return true
                }
                val next = current.parent
                current.recycle()
                current = next
                depth++
            }
            current?.recycle()
            return false
        }

        /**
         * Defensive, app-agnostic nudge after a successful ACTION_SET_TEXT (#quirk-compat): the
         * platform contract already says ACTION_SET_TEXT places the cursor at the end and the
         * widget "should" fire TYPE_VIEW_TEXT_CHANGED, so on a spec-compliant EditText this is a
         * no-op. Observed on-device with Google Keep though: a large bulk ACTION_SET_TEXT (as
         * opposed to incremental per-keystroke input, which is what Keep's own rendering path is
         * normally exercised by) can land text that's present in the node's reported .text and
         * still selectable/deletable, but never gets repainted -- independently corroborated by
         * user reports of Keep's own editor going invisible mid-typing, unrelated to any
         * accessibility service. This is *not* Keep-specific code: it's the same explicit
         * ACTION_SET_SELECTION call for every node on every app, on the theory that some custom
         * text renderers only recompute/repaint their layout in response to an explicit
         * selection-change action rather than trusting ACTION_SET_TEXT alone. Best-effort --
         * failure here doesn't invalidate the SET_TEXT that already succeeded.
         *
         * MUST NOT be sent straight behind the write: a Compose text field (Hermes Relay's
         * composer) applies SET_TEXT on its next recomposition, and a selection action that
         * arrives first is built from the field's still-stale value and replaces the new text with
         * the old one -- while ACTION_SET_TEXT has already reported true. Callers place the caret
         * only once the write has been observed to land ([DictationTextWriter]).
         */
        private fun nudgeSelection(node: AccessibilityNodeInfo, position: Int) {
            val args = Bundle().apply {
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, position)
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, position)
            }
            try {
                node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, args)
            } catch (e: Exception) {
                // Best-effort only; some nodes reject selection args entirely (e.g. non-text views
                // that happened to report isEditable=true). Never let this affect injection success.
                Log.d(TAG, "ACTION_SET_SELECTION rejected by node; injection already succeeded", e)
            }
        }

        /** Some Compose-based fields expose no ACTION_PASTE but do publish a labelled paste action. */
        fun findCustomPasteAction(node: AccessibilityNodeInfo): AccessibilityNodeInfo.AccessibilityAction? =
            node.actionList.firstOrNull { action ->
                action.label?.toString()?.contains("paste", ignoreCase = true) == true
            }

        /**
         * Debug-only structural trace of an injection candidate. Never logs
         * [AccessibilityNodeInfo.getText] or [AccessibilityNodeInfo.getContentDescription] -- those
         * carry the on-screen contents of whatever app the user is dictating into, so they must not
         * reach logcat, even in debug builds.
         */
        private fun logNode(prefix: String, node: AccessibilityNodeInfo) {
            if (!BuildConfig.DEBUG) return
            val actions = node.actionList.joinToString { action ->
                action.label?.toString() ?: action.id.toString()
            }
            // Walk the ancestors once and reuse it for both the log and the classifier. Counts only:
            // the device capture that settles whether the read flattens or the write normalizes (#376).
            val inWebView = hasWebViewAncestor(node)
            val children = node.childCount
            val text = node.text
            val lossy = isLossyWholeFieldDestination(
                isEditable = AccessibilityTextDestination(node).acceptsDirectWrite(),
                inWebView = inWebView,
                isMultiLine = node.isMultiLine,
                currentTextIsEmpty = rawTextIsEmpty(node),
            )
            // A role name (e.g. "textbox"), never field content.
            val chromeRole = node.extras?.getCharSequence("AccessibilityNodeInfo.chromeRole")
            Log.d(
                TAG,
                "$prefix package=${node.packageName} class=${node.className} focused=${node.isFocused} editable=${node.isEditable} multiLine=${node.isMultiLine} textLength=${text?.length ?: 0} newlines=${text?.count { it == '\n' } ?: 0} childCount=$children chromeRole=$chromeRole inWebView=$inWebView lossy=$lossy actions=[$actions]"
            )
        }
    }
}
