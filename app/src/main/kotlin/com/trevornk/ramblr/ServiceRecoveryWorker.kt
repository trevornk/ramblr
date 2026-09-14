package com.trevornk.ramblr

import android.content.Context
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/**
 * #254: the periodic tick that notices Ramblr's accessibility service is off when nobody is
 * looking, and either repairs it (advanced tier) or says so (base tier).
 *
 * WHY A WORKER AND NOT A LIFECYCLE HOOK
 *
 * The detection this drives ([InvocationGuardRail.staleComponentAction]) already existed from
 * #258, but its only caller was MainActivity.refresh() -- so it ran exactly when the user opened
 * Ramblr, which in the #254 automation scenario is precisely when they don't. The failure happens
 * while the user is in a banking app with Ramblr's service torn down; there is no Ramblr process
 * to notice, and no in-app screen being looked at.
 *
 * A Worker is the only thing that still runs in that state. It cannot be the accessibility
 * service itself (dead -- that IS the condition being detected), nor a receiver on some broadcast
 * (nothing reliably fires on "a Secure setting changed"; ContentObserver requires a live process).
 *
 * ## Interval choice: 15 minutes
 *
 * WorkManager's own periodic floor, and appropriate here rather than merely permitted: the whole
 * complaint is a user discovering Ramblr is dead only when they try to dictate and nothing
 * happens. Every tick is a few Settings.Secure reads and a SharedPreferences read -- no network,
 * no wakelock, no I/O of consequence -- so the floor costs essentially nothing. A longer interval
 * would just extend the silent-dead window for no saving. Contrast [SelfUpdateCheckWorker]'s 6
 * hours, where each tick is a real network GET and same-day notice is fine.
 *
 * Note the platform will not honor 15 minutes exactly under Doze; that is fine. This is a safety
 * net, not a realtime watchdog -- [WhisperAccessibilityService.onServiceConnected] handles the
 * good case the moment the service is back.
 */
class ServiceRecoveryWorker(ctx: Context, params: WorkerParameters) : Worker(ctx, params) {

    override fun doWork(): Result {
        val context = applicationContext
        val action = InvocationGuardRail.staleComponentAction(context)

        // Advanced tier: repair silently. This is the same call MainActivity makes, deliberately
        // reused rather than reimplemented -- it preserves other services' entries (Tasker's)
        // verbatim and returns false if the grant vanished mid-flight, in which case the
        // notification below still fires.
        val repairSucceeded = action == StaleComponentAction.REPAIR_TO_ACTIVE &&
            InvocationSecureSettings.repairToActiveComponent(context)

        if (shouldPostServiceRecoveryNotification(
                action = action,
                repairSucceeded = repairSucceeded,
                alreadyPosted = ServiceRecoveryNotifications.isPosted(context),
                userDismissedNotification = InvocationGuardRail.recoveryNotificationDismissed(context),
            )
        ) {
            ServiceRecoveryNotifications.post(context)
        }

        // Cancel a stale notification once the condition has cleared -- whether this tick repaired
        // it or the user fixed it in Settings. Without this, a notification posted before a manual
        // recovery would linger in the shade claiming Ramblr is off while it is running.
        if (action == StaleComponentAction.NONE || repairSucceeded) {
            ServiceRecoveryNotifications.cancel(context)
        }

        // Always success(): "the service is currently fine" is a normal outcome, not a transient
        // infra failure to retry with backoff. The next tick is the right cadence. Mirrors
        // SelfUpdateCheckWorker's reasoning for the same choice.
        return Result.success()
    }

    companion object {
        private const val WORK_NAME = "service-recovery-check"

        /**
         * Enqueues the periodic check, idempotently. [ExistingPeriodicWorkPolicy.KEEP] mirrors
         * [SelfUpdateCheckWorker.schedule]'s idiom: re-calling this from MainActivity.onCreate
         * and from the accessibility service's own connect is a harmless no-op rather than
         * restarting the cycle from zero.
         */
        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<ServiceRecoveryWorker>(
                MIN_PERIODIC_INTERVAL_MINUTES, TimeUnit.MINUTES,
            ).build()
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
        }

        /** WorkManager's own floor for periodic work; see the class kdoc for why the floor is the
         *  right choice here rather than merely the smallest legal value. */
        const val MIN_PERIODIC_INTERVAL_MINUTES = 15L
    }
}
