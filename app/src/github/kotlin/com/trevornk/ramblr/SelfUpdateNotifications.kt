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

    /** XOR mask applied to [notificationId] to derive [installActionPendingIntent]'s distinct
     *  request code -- keeps it deterministic per-version (so re-posting the same release's
     *  notification reuses/updates the same PendingIntent instead of leaking a new one every
     *  time) while guaranteeing it never collides with [releasePendingIntent]'s own request
     *  code for that same version. */
    private const val INSTALL_ACTION_REQUEST_CODE_MASK = 0x1000_0000

    private fun releasePendingIntent(ctx: Context, update: UpdateCheckResult.UpdateAvailable): PendingIntent {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(update.releaseUrl))
        return PendingIntent.getActivity(
            ctx,
            notificationId(update.versionCode),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    /** Fires [SelfUpdateInstallActionReceiver], which enqueues the same manual,
     *  quiet-hours-free install pipeline as [SelfUpdateSettingsActivity]'s "Install now" row --
     *  see that receiver's kdoc. A distinct request code (offset from [notificationId]'s own
     *  per-version id space, mirroring [SelfUpdateInstallWorker]'s INSTALL_NOTIFICATION_ID vs.
     *  notificationId() separation) so this PendingIntent can never collide with
     *  [releasePendingIntent]'s for the same release. */
    private fun installActionPendingIntent(ctx: Context, update: UpdateCheckResult.UpdateAvailable): PendingIntent {
        val intent = Intent(ctx, SelfUpdateInstallActionReceiver::class.java)
        return PendingIntent.getBroadcast(
            ctx,
            notificationId(update.versionCode) xor INSTALL_ACTION_REQUEST_CODE_MASK,
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
            .addAction(0, "Install", installActionPendingIntent(ctx, update))
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

    /** Posted when a fully downloaded, checksum-verified update is staged on disk but the install
     *  gate deferred it (outside quiet hours, or dictation in progress -- see
     *  [SelfUpdateInstallGate]). Distinct from [postInstallFailure]: nothing has gone wrong, the
     *  bytes are ready and waiting for a safe moment.
     *
     *  This exists because a deferral was previously invisible. The only notification a user saw
     *  was "Update available / Tap to view the release on GitHub", which understates reality once
     *  the APK is already downloaded, and a deferred install could then sit for hours (the
     *  default quiet-hours window is 1am-5am) with no indication of what it was waiting for. The
     *  "Install now" action is the same manual, quiet-hours-free path as the Settings row, so a
     *  user who doesn't want to wait for the overnight window has a one-tap way out.
     *
     *  Shares [INSTALL_PROGRESS_NOTIFICATION_ID] with the progress and failure notifications: all
     *  three are states of the same single install attempt, so replacing rather than stacking is
     *  correct -- the download-progress notification becoming "waiting" is the accurate story.
     *  Never throws, mirrors [postUpdateAvailable]. */
    fun postInstallDeferred(ctx: Context, update: UpdateCheckResult.UpdateAvailable, reason: String) {
        ensureChannel(ctx)
        val notification = NotificationCompat.Builder(ctx, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_mic)
            .setContentTitle("Update v${update.versionName} ready to install")
            .setContentText(reason)
            .setStyle(NotificationCompat.BigTextStyle().bigText(reason))
            .addAction(0, "Install now", installActionPendingIntent(ctx, update))
            .setAutoCancel(true)
            .build()
        try {
            NotificationManagerCompat.from(ctx).notify(INSTALL_PROGRESS_NOTIFICATION_ID, notification)
        } catch (_: SecurityException) {
        }
    }

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

    /**
     * Terminal install-failure notification for the ASYNCHRONOUS PackageInstaller callback (#253),
     * as opposed to [postInstallFailure]'s synchronous worker-side throw.
     *
     * Two things distinguish it from [postInstallFailure] and are the entire reason it exists:
     *
     *  - It takes a versionCode, so it can CANCEL the per-version "update available" notification
     *    ([notificationId]) that [postUpdateAvailable] left up. Without that, a user whose install
     *    just failed keeps a cheerful "Update available -- tap to view the release" sitting in the
     *    shade forever, which is the specific user-visible defect #253 reports: the failure was
     *    detected and logged correctly and then only ever visible over adb.
     *  - It offers the release page as the manual fallback, because by this point the automatic
     *    path has definitively failed and re-running it is not going to help.
     *
     * Never throws (POST_NOTIFICATIONS may be denied), mirroring [postInstallFailure].
     */
    fun postInstallFailedAsync(
        ctx: Context,
        versionName: String,
        versionCode: Int,
        releaseUrl: String?,
        reason: String,
    ) {
        ensureChannel(ctx)
        val builder = NotificationCompat.Builder(ctx, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_mic)
            .setContentTitle("Update v$versionName failed to install")
            .setContentText(reason)
            .setStyle(NotificationCompat.BigTextStyle().bigText(reason))
            .setAutoCancel(true)
        if (!releaseUrl.isNullOrBlank()) {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(releaseUrl))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            val pending = PendingIntent.getActivity(
                ctx,
                notificationId(versionCode) xor RELEASE_FALLBACK_REQUEST_CODE_MASK,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            builder.setContentIntent(pending).addAction(0, "Open release page", pending)
        }
        try {
            val manager = NotificationManagerCompat.from(ctx)
            // Clear the stale "update available" prompt for THIS version first: leaving it up
            // next to a failure notification for the same release is contradictory, and tapping
            // it does nothing useful now.
            manager.cancel(notificationId(versionCode))
            manager.notify(INSTALL_PROGRESS_NOTIFICATION_ID, builder.build())
        } catch (_: SecurityException) {
        }
    }

    /** Distinct request-code mask for [postInstallFailedAsync]'s release-page PendingIntent, so it
     *  can never collide with [releasePendingIntent] or [installActionPendingIntent] for the same
     *  version -- same reasoning as [INSTALL_ACTION_REQUEST_CODE_MASK]. */
    private const val RELEASE_FALLBACK_REQUEST_CODE_MASK = 0x2000_0000

    /** Clears the per-version "update available" notification (#253). Used when that prompt has
     *  become actively wrong -- the update it advertises is now installed, or has definitively
     *  failed to install. Never throws, for the same POST_NOTIFICATIONS reason as the posters. */
    fun cancelUpdateAvailable(ctx: Context, versionCode: Int) {
        try {
            NotificationManagerCompat.from(ctx).cancel(notificationId(versionCode))
        } catch (_: SecurityException) {
        }
    }

    /** [Intent] to Android's own "Install unknown apps" settings screen for this app, scoped with
     *  [Uri] `package:<applicationId>` the same way [Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES]'s
     *  own docs require -- without the package-scheme data Uri, the system opens the generic list
     *  of every app instead of jumping straight to this one. `FLAG_ACTIVITY_NEW_TASK` since this
     *  can be launched from a notification tap (no existing Activity context) exactly like
     *  [SelfUpdateInstallReceiver]'s own confirmation-Intent launch. */
    private fun manageUnknownAppSourcesIntent(ctx: Context): Intent =
        Intent(
            android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            Uri.parse("package:${ctx.packageName}"),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    private fun manageUnknownAppSourcesPendingIntent(ctx: Context, versionCode: Int): PendingIntent =
        PendingIntent.getActivity(
            ctx,
            notificationId(versionCode) xor INSTALL_ACTION_REQUEST_CODE_MASK,
            manageUnknownAppSourcesIntent(ctx),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    /** Posted when [SelfUpdateInstallGate.canAttemptInstall] blocks an install before it starts
     *  (#253): the app cannot proceed until the user grants "Install unknown apps" access for
     *  itself. Distinct from [postInstallFailure] -- nothing has been attempted or failed yet --
     *  and distinct from [postInstallDeferred] -- this doesn't resolve on its own with time, the
     *  user must act. Tapping the notification (and its action button) both jump directly to the
     *  system settings screen where that access is granted, via [manageUnknownAppSourcesIntent].
     *  Shares [INSTALL_PROGRESS_NOTIFICATION_ID] with the other install-attempt states for the
     *  same reason [postInstallFailure] does. Never throws, mirrors [postInstallFailure]. */
    fun postInstallPermissionNeeded(ctx: Context, update: UpdateCheckResult.UpdateAvailable) {
        ensureChannel(ctx)
        val reason = SelfUpdateStatusFormatter.permissionNeededReason()
        val settingsIntent = manageUnknownAppSourcesPendingIntent(ctx, update.versionCode)
        val notification = NotificationCompat.Builder(ctx, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_mic)
            .setContentTitle("Update v${update.versionName} needs a permission to install")
            .setContentText(reason)
            .setStyle(NotificationCompat.BigTextStyle().bigText(reason))
            .setContentIntent(settingsIntent)
            .addAction(0, "Open settings", settingsIntent)
            .setAutoCancel(true)
            .build()
        try {
            NotificationManagerCompat.from(ctx).notify(INSTALL_PROGRESS_NOTIFICATION_ID, notification)
        } catch (_: SecurityException) {
        }
    }
}
