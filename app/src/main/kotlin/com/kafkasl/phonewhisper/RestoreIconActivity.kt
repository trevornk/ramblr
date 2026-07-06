package com.kafkasl.phonewhisper

import android.app.Activity
import android.os.Bundle

/**
 * No-UI trampoline reached via the "icon hidden" notification's [android.app.PendingIntent]
 * (Feature B, [IconVisibilityNotifications]). Deliberately an Activity rather than a
 * BroadcastReceiver: tapping a notification whose PendingIntent starts an Activity is what lets
 * the system animate the shade collapsing in the normal, immediate way. A broadcast-only
 * PendingIntent still fires, but Android has nothing to launch-transition to, so the shade lingers
 * and collapses on its own separate timer instead -- the "hangs then goes away" feel this replaces.
 *
 * Flips [IconHiddenState] back to false, nudges the live overlay's visibility immediately if the
 * accessibility service happens to be running (via [WhisperAccessibilityService.instance], the
 * same static-reach pattern already used by [WhisperAccessibilityService.setMainActivityForeground]),
 * cancels the notification, then finishes immediately without ever rendering a frame
 * (@android:style/Theme.Translucent.NoTitleBar in the manifest keeps it fully invisible).
 */
class RestoreIconActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        IconHiddenState.setHidden(this, false)
        WhisperAccessibilityService.instance?.applyOverlayVisibility()
        IconVisibilityNotifications.cancel(this)
        finish()
        overridePendingTransition(0, 0)
    }
}
