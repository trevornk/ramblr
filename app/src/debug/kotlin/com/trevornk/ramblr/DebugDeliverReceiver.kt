package com.trevornk.ramblr

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Debug-only test seam: delivers a canned transcript through the exact same path a finished
 * dictation takes ([DictationRuntime.finalizeForDelivery] then `RuntimeListener.deliverText`), so
 * insertion behaviour can be exercised on a device without speaking.
 *
 *     adb shell am broadcast -n com.trevornk.ramblr/.DebugDeliverReceiver \
 *         -a com.trevornk.ramblr.DEBUG_DELIVER --es text "hello world"
 *
 * Lives in app/src/debug (see the manifest overlay), so it is compiled into debug builds only and
 * never reaches a release APK.
 */
class DebugDeliverReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val text = intent.getStringExtra("text") ?: return
        val service = WhisperAccessibilityService.instance
        if (service == null) {
            Log.w("PhoneWhisper", "DebugDeliverReceiver: accessibility service not running")
            return
        }
        service.runtimeListener.deliverText(
            service.runtime.finalizeForDelivery(text),
            rawText = null,
            paidFallbackGroup = null,
            cleanupError = null,
            feedbackDurationMs = 2000,
        )
    }
}
