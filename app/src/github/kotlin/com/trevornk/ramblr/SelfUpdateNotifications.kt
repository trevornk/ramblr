package com.trevornk.ramblr

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

/**
 * Notification support for the GitHub self-update feature (Part 3), mirroring
 * [DownloadNotifications]'s channel/post/never-throw structure exactly. Lives in src/github/ for
 * the same Google Play policy reason as [SelfUpdateChecker] -- see its AGENTS note.
 *
 * Unlike [DownloadNotifications.CHANNEL_ID] (routine progress feedback, IMPORTANCE_LOW), an
 * available self-update is something Trevor should actually notice, so this channel uses
 * IMPORTANCE_DEFAULT.
 */
object SelfUpdateNotifications {
    const val CHANNEL_ID = "app_updates"

    fun ensureChannel(ctx: Context) {
        ctx.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "App updates", NotificationManager.IMPORTANCE_DEFAULT)
        )
    }

    /** Stable per-version notification id so re-checking against the same still-unactioned
     *  release re-posts (updates) the same notification instead of stacking duplicates, while a
     *  genuinely newer release still gets its own id. Masks off the sign bit the same way
     *  [DownloadNotifications.notificationId] does. */
    fun notificationId(versionCode: Int): Int = versionCode and 0x7FFFFFFF

    private fun releasePendingIntent(ctx: Context, update: UpdateCheckResult.UpdateAvailable): PendingIntent {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(update.releaseUrl))
        return PendingIntent.getActivity(
            ctx,
            notificationId(update.versionCode),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun buildNotification(ctx: Context, update: UpdateCheckResult.UpdateAvailable): Notification =
        NotificationCompat.Builder(ctx, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_mic)
            .setContentTitle("Update available: v${update.versionName}")
            .setContentText("Tap to view the release on GitHub")
            .setContentIntent(releasePendingIntent(ctx, update))
            .setAutoCancel(true)
            .build()

    /** Posts (or re-posts) the "update available" notification. Ensures the channel exists first
     *  since this can be the very first notification this app has ever posted on this channel
     *  (e.g. right after a manual "Check now" -- see SelfUpdateSettingsActivity). Never throws:
     *  a missing POST_NOTIFICATIONS grant must not affect the check itself, mirrors
     *  [DownloadNotifications.post]. */
    fun postUpdateAvailable(ctx: Context, update: UpdateCheckResult.UpdateAvailable) {
        ensureChannel(ctx)
        try {
            NotificationManagerCompat.from(ctx).notify(notificationId(update.versionCode), buildNotification(ctx, update))
        } catch (_: SecurityException) {
        }
    }
}
