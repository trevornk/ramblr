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

    // -- install-progress/result notifications (Part 4, SelfUpdateInstallWorker) --
    //
    // Reuses this same CHANNEL_ID rather than a separate channel: DownloadNotifications (model
    // downloads) is the one precedent in this codebase for one channel covering both progress
    // and terminal (success/failure) notifications, and an in-flight self-update download/install
    // is squarely the same "app updates" subject as the existing "update available" notification
    // above -- a user who cares about one cares about the other, and a second channel would just
    // be one more row in Android's per-app notification settings for no real benefit.

    /** Stable id for the self-update download/install progress notification, distinct from
     *  [notificationId]'s per-version "update available" id space so a live download in progress
     *  can never overwrite (or be overwritten by) the separate "update available" notification for
     *  the same release. */
    private const val INSTALL_PROGRESS_NOTIFICATION_ID = 0x5E1F_0002

    fun progress(ctx: Context, versionName: String, percent: Int): Notification =
        NotificationCompat.Builder(ctx, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_mic)
            .setContentTitle("Downloading update v$versionName")
            .setContentText("$percent%")
            .setProgress(100, percent, false)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()

    /** Posted when the download or install fails outright (checksum mismatch, terminal I/O
     *  failure, or a [PackageInstaller] failure) -- never posted for a gate deferral (quiet-hours
     *  miss / dictation in progress), which is routine "try again later" behavior, not a failure
     *  (see [SelfUpdateInstallWorker.attemptGatedInstall]). Never throws, mirrors
     *  [postUpdateAvailable]/[DownloadNotifications.post]. */
    fun postInstallFailure(ctx: Context, versionName: String, error: String) {
        ensureChannel(ctx)
        val notification = NotificationCompat.Builder(ctx, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_mic)
            .setContentTitle("Update v$versionName failed to install")
            .setContentText(error)
            .setStyle(NotificationCompat.BigTextStyle().bigText(error))
            .setAutoCancel(true)
            .build()
        try {
            NotificationManagerCompat.from(ctx).notify(INSTALL_PROGRESS_NOTIFICATION_ID, notification)
        } catch (_: SecurityException) {
        }
    }
}
