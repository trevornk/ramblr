package com.trevornk.ramblr

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

/**
 * Notification support for [ModelDownloadWorker] (#56). Progress was previously only visible via
 * a live WorkManager LiveData observation in MainActivity, which only fires while the Activity is
 * actually on-screen -- once backgrounded, a multi-hundred-MB download gave zero feedback about
 * whether it was still running, finished, or had failed.
 */
object DownloadNotifications {
    const val CHANNEL_ID = "model_downloads"

    /** IMPORTANCE_LOW: this is routine progress feedback, not something that should interrupt or
     *  make sound, same bar as e.g. a file-copy progress notification. Failures are posted to the
     *  same channel rather than a separate higher-importance one -- a failed background download
     *  is worth surfacing, but not urgent enough to justify a second channel in this first pass. */
    fun ensureChannel(ctx: Context) {
        ctx.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Model downloads", NotificationManager.IMPORTANCE_LOW)
        )
    }

    /** Stable per-archive notification id, so two different models downloading at once (e.g. an
     *  offline model and the streaming preview model, #29) get distinct notifications instead of
     *  overwriting each other. Masks off the sign bit *and* the low bit rather than `abs()`:
     *  `abs(Int.MIN_VALUE)` is still negative (overflow), and keeping the id even guarantees
     *  [resultNotificationId]'s `+ 1` never overflows into a negative id either (L7). */
    fun notificationId(archive: String): Int = archive.hashCode() and 0x7FFFFFFE

    /** Terminal (success/failure) notifications use a distinct id from the in-progress one, so
     *  WorkManager tearing down the foreground service's own notification when doWork() returns
     *  can't race-cancel the "ready"/"failed" notification posted right before returning. */
    fun resultNotificationId(archive: String): Int = notificationId(archive) + 1

    /** True if a progress update from [lastPercent] to [currentPercent] is worth posting a new
     *  notification for. Steps by 5 points (or always on completion) so a fast download doesn't
     *  spam a notification post per byte-buffer read -- [ModelDownloadWorker] calls this once per
     *  [DownloadState.Downloading] callback, which can fire dozens of times a second. */
    fun shouldPostUpdate(lastPercent: Int, currentPercent: Int): Boolean =
        currentPercent >= lastPercent + 5 || currentPercent >= 100

    private fun builder(ctx: Context, title: String) = NotificationCompat.Builder(ctx, CHANNEL_ID)
        .setSmallIcon(R.drawable.ic_mic)
        .setContentTitle(title)
        .setOnlyAlertOnce(true)

    fun progress(ctx: Context, modelName: String, percent: Int): Notification =
        builder(ctx, "Downloading $modelName")
            .setContentText("$percent%")
            .setProgress(100, percent, false)
            .setOngoing(true)
            .build()

    fun extracting(ctx: Context, modelName: String): Notification =
        builder(ctx, "Installing $modelName")
            .setContentText("Extracting…")
            .setProgress(0, 0, true)
            .setOngoing(true)
            .build()

    fun postSuccess(ctx: Context, archive: String, modelName: String) {
        post(
            ctx, resultNotificationId(archive),
            builder(ctx, "$modelName ready").setAutoCancel(true).build()
        )
    }

    fun postFailure(ctx: Context, archive: String, modelName: String, error: String) {
        post(
            ctx, resultNotificationId(archive),
            builder(ctx, "$modelName download failed")
                .setContentText(error)
                .setStyle(NotificationCompat.BigTextStyle().bigText(error))
                .setAutoCancel(true)
                .build()
        )
    }

    /** Never allowed to fail the caller: a missing POST_NOTIFICATIONS grant (or any other
     *  notification-layer error) must not affect the download itself, which has already succeeded
     *  or failed on its own terms by the time this runs. */
    private fun post(ctx: Context, id: Int, notification: Notification) {
        try {
            NotificationManagerCompat.from(ctx).notify(id, notification)
        } catch (_: SecurityException) {
        }
    }
}
