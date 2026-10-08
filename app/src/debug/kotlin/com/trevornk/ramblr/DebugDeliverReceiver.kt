package com.trevornk.ramblr

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Debug-only test seam for on-device verification of insertion behaviour without speaking.
 * Lives in app/src/debug (see the manifest overlay), so it is compiled into debug builds only and
 * never reaches a release APK. The receiver requires `android.permission.DUMP`, which only the
 * shell (adb) and system hold, so no installed app can drive it -- debug builds are daily-driven,
 * and an open receiver would let any app type into the focused field or log on-screen text.
 *
 * Deliver a canned transcript through the same path a finished dictation takes
 * ([DictationRuntime.finalizeForDelivery] then `RuntimeListener.deliverText`):
 *
 *     adb shell am broadcast -n com.trevornk.ramblr/.DebugDeliverReceiver \
 *         -a com.trevornk.ramblr.DEBUG_DELIVER --es text "hello world"
 *
 * Log the editable nodes of the active window (class, focus, selection, hint flags, text length,
 * first 40 chars of text) under tag PhoneWhisper:
 *
 *     adb shell am broadcast -n com.trevornk.ramblr/.DebugDeliverReceiver -a com.trevornk.ramblr.DEBUG_DUMP
 */
class DebugDeliverReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val service = WhisperAccessibilityService.instance
        if (service == null) {
            Log.w("PhoneWhisper", "DebugDeliverReceiver: accessibility service not running")
            return
        }
        when (intent.action) {
            "com.trevornk.ramblr.DEBUG_DELIVER" -> {
                val text = intent.getStringExtra("text") ?: return
                service.runtimeListener.deliverText(
                    service.runtime.finalizeForDelivery(text),
                    rawText = null,
                    paidFallbackGroup = null,
                    cleanupError = null,
                    feedbackDurationMs = 2000,
                )
            }
            "com.trevornk.ramblr.DEBUG_DUMP" -> {
                val root = service.rootInActiveWindow ?: return
                walk(root)
            }
        }
    }

    private fun walk(node: AccessibilityNodeInfo) {
        if (node.isEditable || node.className?.contains("EditText") == true) {
            Log.i(
                "PhoneWhisper",
                "DUMP class=${node.className} focused=${node.isFocused} editable=${node.isEditable} " +
                    "showingHint=${node.isShowingHintText} hint=${node.hintText} " +
                    "sel=${node.textSelectionStart}/${node.textSelectionEnd} len=${node.text?.length} " +
                    "text=${node.text?.toString()?.take(40)?.replace("\n", "\\n")}",
            )
        }
        for (i in 0 until node.childCount) node.getChild(i)?.let { walk(it) }
    }
}
