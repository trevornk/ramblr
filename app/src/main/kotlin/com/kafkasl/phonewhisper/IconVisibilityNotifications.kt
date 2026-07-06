package com.kafkasl.phonewhisper

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

/**
 * Low-importance, non-alerting notification support for the "Hide icon" long-press menu action
 * (Feature B). Mirrors [DownloadNotifications]'s pattern exactly (dedicated IMPORTANCE_LOW
 * channel, try/catch SecurityException around the actual notify() call) but lives in its own
 * object rather than being bolted onto [DownloadNotifications], since this is a conceptually
 * unrelated feature (icon visibility, not model downloads).
 */
object IconVisibilityNotifications {
    const val CHANNEL_ID = "icon_visibility"
    const val NOTIFICATION_ID = 0x1C04 // arbitrary stable id, distinct from DownloadNotifications' hash-based ids

    /** IMPORTANCE_LOW: restoring a hidden icon is a convenience shortcut, not something that
     *  should interrupt or make sound -- same bar as [DownloadNotifications.CHANNEL_ID]. */
    fun ensureChannel(ctx: Context) {
        ctx.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Icon visibility", NotificationManager.IMPORTANCE_LOW)
        )
    }

    private fun restoreIntent(ctx: Context): PendingIntent {
        val intent = Intent(ctx, RestoreIconReceiver::class.java)
        return PendingIntent.getBroadcast(
            ctx, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun build(ctx: Context): Notification = NotificationCompat.Builder(ctx, CHANNEL_ID)
        .setSmallIcon(R.drawable.ic_mic)
        .setContentTitle("Ramblr icon hidden")
        .setContentText("Tap to show it again")
        .setAutoCancel(true)
        .setContentIntent(restoreIntent(ctx))
        .build()

    /** Posts the "icon hidden" notification. Never allowed to fail the caller: a missing
     *  POST_NOTIFICATIONS grant (or any other notification-layer error) must not crash "Hide
     *  icon" itself, which has already taken effect on the overlay by the time this runs. */
    fun postHidden(ctx: Context) {
        ensureChannel(ctx)
        try {
            NotificationManagerCompat.from(ctx).notify(NOTIFICATION_ID, build(ctx))
        } catch (_: SecurityException) {
        }
    }

    /** Dismisses the "icon hidden" notification, e.g. once the icon has been restored via some
     *  other path (the Advanced screen fallback) so a stale notification doesn't linger. */
    fun cancel(ctx: Context) {
        try {
            NotificationManagerCompat.from(ctx).cancel(NOTIFICATION_ID)
        } catch (_: SecurityException) {
        }
    }
}
