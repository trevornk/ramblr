package com.trevornk.ramblr

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Backs the "Install" action button on the update-available notification
 * ([SelfUpdateNotifications.buildNotification]). Manifest-declared (not context-registered) for
 * the same reason as [SelfUpdateInstallReceiver]: the notification, and therefore this action,
 * can be tapped long after the app process that originally posted it has been killed.
 *
 * Just forwards to [SelfUpdateInstallWorker.enqueueManual] -- the same manual, quiet-hours-free
 * install path [SelfUpdateSettingsActivity]'s "Install now" row uses, so a user never has to
 * open the app at all to act on the notification. Deliberately does nothing if there's no
 * currently-cached [UpdateCheckResult.UpdateAvailable] (e.g. a stale notification tapped after
 * a newer check already superseded it) -- [SelfUpdateInstallWorker.doWork] already handles that
 * "no cached UpdateAvailable" case as a clean [androidx.work.ListenableWorker.Result.failure]
 * rather than crashing, so simply enqueueing unconditionally here is safe.
 */
class SelfUpdateInstallActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        SelfUpdateInstallWorker.enqueueManual(context.applicationContext)
    }
}
