package com.trevornk.ramblr

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

/**
 * #254: the out-of-app surface for "Ramblr's accessibility service went off and Ramblr didn't do
 * it".
 *
 * WHY THIS IS NEEDED AT ALL
 *
 * #258 shipped a detector and a repair path ([InvocationGuardRail.staleComponentAction]), but its
 * only consumer was MainActivity.refresh() -- so the entire recovery story required the user to
 * open Ramblr. That is backwards for this failure: the whole point of the automation flows in
 * #254/#257 is that the user never opens Ramblr; they open a banking app, and a macro toggles the
 * service around them. When the re-enable half fails, Ramblr is silently off and nothing says so.
 * The reporter's own account is exactly this -- "many times the accessibility setting does not
 * turn back on after the restore and I have to manually go re-enable it from time to time." They
 * discovered it by trying to dictate and finding nothing happened.
 *
 * Users without WRITE_SECURE_SETTINGS (the common case -- it is an adb-only grant) get
 * [StaleComponentAction.OFFER_RECOVERY], a recovery path that until now they could not see.
 *
 * WHY A NOTIFICATION AND NOT A FIX
 *
 * Ramblr cannot repair this for a base-tier user: putting itself back into
 * `enabled_accessibility_services` requires WRITE_SECURE_SETTINGS. On the advanced tier
 * [ServiceRecoveryWorker] repairs silently and never posts anything. This notification is the
 * base-tier fallback: tell the user, and make the fix one tap.
 *
 * Mirrors [IconVisibilityNotifications]'s pattern (dedicated IMPORTANCE_* channel, try/catch
 * SecurityException around notify(), activity PendingIntent so the shade collapses normally).
 */
object ServiceRecoveryNotifications {
    const val CHANNEL_ID = "service_recovery"
    const val NOTIFICATION_ID = 0x1C05 // stable, distinct from IconVisibilityNotifications' 0x1C04

    /**
     * IMPORTANCE_DEFAULT, deliberately higher than [IconVisibilityNotifications]'s IMPORTANCE_LOW.
     * A hidden icon is a convenience the user chose; this is a feature the user did NOT choose to
     * lose, and silently failing to notice it is the entire complaint in #254. DEFAULT makes it
     * appear in the shade and peek, without sound (no [NotificationCompat.setDefaults] call), so
     * it is noticeable but not an alarm.
     */
    fun ensureChannel(ctx: Context) {
        ctx.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Service turned off", NotificationManager.IMPORTANCE_DEFAULT)
        )
    }

    private fun recoveryIntent(ctx: Context): PendingIntent {
        val intent = Intent(ctx, ServiceRecoveryActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
        }
        return PendingIntent.getActivity(
            ctx, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    /** Delivered when the user swipes the notification away -- see [ServiceRecoveryDismissReceiver]
     *  for why a dismissal must be remembered rather than silently re-posted next tick. */
    private fun dismissIntent(ctx: Context): PendingIntent {
        val intent = Intent(ctx, ServiceRecoveryDismissReceiver::class.java)
            .setAction(ServiceRecoveryDismissReceiver.ACTION_DISMISSED)
        return PendingIntent.getBroadcast(
            ctx, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    /**
     * setAutoCancel(true), and deliberately NOT setOngoing(true) -- the opposite of
     * [IconVisibilityNotifications]'s choice, for a reason worth stating since the two sit next to
     * each other.
     *
     * That one is ongoing because it is the ONLY way back from a hidden icon; losing it strands
     * the user. This one is not: Ramblr's own screen still shows the #258 banner, and the service
     * can always be re-enabled from Android's Accessibility settings. Making it non-dismissable
     * would be a sticky notification the user cannot clear for a state they may be fine with (they
     * might be mid-banking-session and want Ramblr off). Auto-cancel on tap, dismissable by swipe,
     * and re-posted by the next [ServiceRecoveryWorker] tick if the condition still holds and the
     * user hasn't told us to stop.
     */
    private fun build(ctx: Context): Notification = NotificationCompat.Builder(ctx, CHANNEL_ID)
        .setSmallIcon(R.drawable.ic_mic)
        .setContentTitle("Ramblr was turned off")
        .setContentText("Its accessibility service is off. Tap to turn it back on.")
        .setStyle(
            NotificationCompat.BigTextStyle().bigText(
                "Ramblr's accessibility service was switched off by something other than Ramblr, " +
                    "so dictation won't work until it's back on. If an automation app turns Ramblr " +
                    "off for banking apps, its re-enable step may not have taken effect."
            )
        )
        .setAutoCancel(true)
        .setContentIntent(recoveryIntent(ctx))
        .setDeleteIntent(dismissIntent(ctx))
        .build()

    /** Whether this app's own recovery notification is currently in the shade. Scoped to the
     *  calling package, no permission needed. Returns false on any failure so a query problem can
     *  never suppress a post -- see [IconVisibilityNotifications.isPosted]. */
    fun isPosted(ctx: Context): Boolean = try {
        ctx.getSystemService(NotificationManager::class.java)
            ?.activeNotifications
            ?.any { it.id == NOTIFICATION_ID }
            ?: false
    } catch (_: Exception) {
        false
    }

    /** Never allowed to fail the caller: a missing POST_NOTIFICATIONS grant must not crash a
     *  background worker tick. */
    fun post(ctx: Context) {
        ensureChannel(ctx)
        try {
            NotificationManagerCompat.from(ctx).notify(NOTIFICATION_ID, build(ctx))
        } catch (_: SecurityException) {
        }
    }

    fun cancel(ctx: Context) {
        try {
            NotificationManagerCompat.from(ctx).cancel(NOTIFICATION_ID)
        } catch (_: SecurityException) {
        }
    }
}
