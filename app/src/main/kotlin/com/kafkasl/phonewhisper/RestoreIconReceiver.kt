package com.kafkasl.phonewhisper

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Reached via the "icon hidden" notification's PendingIntent (Feature B, [IconVisibilityNotifications]).
 * Flips [IconHiddenState] back to false, nudges the live overlay's visibility immediately if the
 * accessibility service happens to be running (via [WhisperAccessibilityService.instance], the
 * same static-reach pattern already used by [WhisperAccessibilityService.setMainActivityForeground]),
 * and cancels the notification. Not exported -- only reachable via this app's own explicit
 * PendingIntent, never from outside the app.
 */
class RestoreIconReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        IconHiddenState.setHidden(context, false)
        WhisperAccessibilityService.instance?.applyOverlayVisibility()
        IconVisibilityNotifications.cancel(context)
    }
}
