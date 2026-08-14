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
        val intent = Intent(ctx, RestoreIconActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
        }
        return PendingIntent.getActivity(
            ctx, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    /**
     * Ongoing, not auto-cancel (#135): this notification is the primary way back from a hidden
     * icon, so it must not be a notification the user can casually lose. With setAutoCancel(true)
     * and no ongoing flag it was removed by "Clear all" and swiped away like any other -- and
     * once gone, the ring was unrecoverable except via BehaviorActivity's [iconHiddenRow], which
     * is View.GONE until the icon is already hidden and therefore cannot be discovered in advance.
     * That combination stranded a real user (a hidden ring surviving reboots with no visible way
     * back).
     *
     * setOngoing(true) keeps it out of "Clear all" and makes it non-dismissable while the phone is
     * locked. That is the whole of its effect. It is explicitly NOT a defense against dismissal:
     * on Android 14+ an individual swipe removes an ongoing notification, confirmed on-device after
     * the first pass at #135 shipped (the user swiped this exact notification away and was stranded
     * again -- the very bug this was meant to fix). Foreground-service notifications are not exempt
     * either; see [shouldRepostHiddenNotification] for why the Tesla-style FGS approach was
     * investigated and rejected.
     *
     * Durability therefore comes from re-posting ([repostIfMissing]), not from any builder flag.
     * setOngoing is retained only for its two real effects above, which cover the common accidental
     * cases and avoid a pointless re-post cycle.
     */
    private fun build(ctx: Context): Notification = NotificationCompat.Builder(ctx, CHANNEL_ID)
        .setSmallIcon(R.drawable.ic_mic)
        .setContentTitle("Ramblr icon hidden")
        .setContentText("Tap to show it again")
        .setOngoing(true)
        .setContentIntent(restoreIntent(ctx))
        .build()

    /**
     * Whether this app's own "icon hidden" notification is currently in the shade.
     *
     * [NotificationManager.getActiveNotifications] is scoped to the calling package and needs no
     * special permission (unlike NotificationListenerService, which would be a wildly
     * disproportionate ask for this). Returns false on any failure so a query problem can never
     * suppress a re-post -- the safe direction is a redundant post, not a missing recovery path.
     */
    fun isPosted(ctx: Context): Boolean = try {
        ctx.getSystemService(NotificationManager::class.java)
            ?.activeNotifications
            ?.any { it.id == NOTIFICATION_ID }
            ?: false
    } catch (_: Exception) {
        false
    }

    /**
     * Self-heal (#135): re-post the recovery notification if the icon is hidden but the
     * notification is gone -- the state a swipe-dismissal leaves behind, in which the user has no
     * on-screen way back to the ring. Called from the accessibility service at low-frequency
     * checkpoints (connect/unlock); see [shouldRepostHiddenNotification] for the rule and the
     * rationale for not reacting instantly to dismissal.
     *
     * [overlayConnected] is passed in rather than inferred here so this stays a plain notification
     * helper with no knowledge of the service's lifecycle.
     */
    fun repostIfMissing(ctx: Context, overlayConnected: Boolean) {
        if (!shouldRepostHiddenNotification(
                iconHidden = IconHiddenState.isHidden(ctx),
                notificationPosted = isPosted(ctx),
                overlayConnected = overlayConnected,
            )
        ) return
        postHidden(ctx)
    }

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
