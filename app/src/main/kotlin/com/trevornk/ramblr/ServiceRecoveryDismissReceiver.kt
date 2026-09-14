package com.trevornk.ramblr

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * #254: fires when the user swipes [ServiceRecoveryNotifications]'s notification away, so the
 * dismissal is remembered and [ServiceRecoveryWorker] stops re-posting it every tick.
 *
 * Without this the notification would be effectively undismissable: swipe it, and 15 minutes
 * later it returns for the same unchanged condition. That is the nagging behavior the in-app
 * banners explicitly avoid ([InvocationGuardRail.KEY_BANNER_DISMISSED]), and it would be worse
 * here because a notification is more intrusive than a banner.
 *
 * The silence is scoped to the CURRENT loss, not forever:
 * [InvocationGuardRail.recordServiceConnected] clears the flag the next time the service actually
 * connects, so a later, genuinely new failure notifies again.
 *
 * `android:exported="false"` in the manifest -- only the system's notification manager delivers
 * the delete intent, and no other app has any business marking this dismissed.
 */
class ServiceRecoveryDismissReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_DISMISSED) return
        InvocationGuardRail.recordRecoveryNotificationDismissed(context)
    }

    companion object {
        const val ACTION_DISMISSED = "com.trevornk.ramblr.action.RECOVERY_NOTIFICATION_DISMISSED"
    }
}
