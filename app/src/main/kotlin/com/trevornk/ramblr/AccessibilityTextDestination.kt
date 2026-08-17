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
 */
class AccessibilityTextDestination(val node: AccessibilityNodeInfo) : TextDestination {

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

    override fun selectionEnd(): Int = node.textSelectionEnd

    override fun prepareForWrite() {
        logNode("Trying node", node)
        node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
    }

    override fun replaceAllText(text: String): Boolean {
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        val ok = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        Log.i(TAG, "ACTION_SET_TEXT => $ok")
        if (ok) nudgeSelectionToEnd(node, text.length)
        return ok
    }

    override fun pasteFromClipboard(): Boolean {
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
         */
        private fun nudgeSelectionToEnd(node: AccessibilityNodeInfo, textLength: Int) {
            val args = Bundle().apply {
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, textLength)
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, textLength)
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
            Log.d(
                TAG,
                "$prefix package=${node.packageName} class=${node.className} focused=${node.isFocused} editable=${node.isEditable} actions=[$actions]"
            )
        }
    }
}
