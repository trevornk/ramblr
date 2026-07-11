package com.trevornk.ramblr

import android.content.Context
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/**
 * Part 5: the periodic WorkManager job that actually drives the github self-update feature --
 * everything built in Parts 2-4 ([SelfUpdateChecker], [SelfUpdateNotifications],
 * [SelfUpdateInstallWorker]) is otherwise inert without something calling them on a schedule.
 * Github distribution flavor only -- see [SelfUpdateChecker]'s AGENTS note.
 *
 * Structurally a plain WorkManager [Worker] (not CoroutineWorker), same as [ModelDownloadWorker]
 * and [SelfUpdateInstallWorker] -- no foreground notification needed here since this tick is a
 * quick network GET (or cache hit), not a multi-minute download.
 *
 * ## Interval choice: 6 hours
 *
 * WorkManager's own floor for periodic work is 15 minutes, but there is no reason to poll GitHub
 * that often for an app-update check -- a real release lands at most a few times a week (see
 * [SelfUpdateChecker.CACHE_TTL_MS]'s kdoc). 6 hours balances noticing a new release same-day
 * against being a good API citizen: 4x/day per install is negligible load on GitHub's API even
 * at scale, and combined with [SelfUpdateChecker]'s own 4-hour cache TTL, most ticks will still
 * do a fresh fetch (6h > 4h cache TTL) while never hammering the endpoint.
 */
class SelfUpdateCheckWorker(ctx: Context, params: WorkerParameters) : Worker(ctx, params) {

    override fun doWork(): Result {
        val result = SelfUpdateChecker.check(applicationContext)

        if (SelfUpdateCheckDecision.shouldNotify(result, SelfUpdatePrefs.isNotifyEnabled(applicationContext))) {
            SelfUpdateNotifications.postUpdateAvailable(applicationContext, result as UpdateCheckResult.UpdateAvailable)
        }
        if (SelfUpdateCheckDecision.shouldAutoInstall(result, SelfUpdatePrefs.isAutoInstallEnabled(applicationContext))) {
            SelfUpdateInstallWorker.enqueue(applicationContext)
        }

        // UpToDate and CheckFailed both fall through here doing nothing further. CheckFailed
        // deliberately does NOT use WorkManager's own retry/backoff machinery (Result.retry()):
        // that's for transient infra failures within a single Worker run, not "no update
        // available this tick" -- the next periodic tick 6h later is exactly the right cadence
        // to try again, so this always returns success() regardless of what SelfUpdateChecker
        // found.
        return Result.success()
    }

    companion object {
        private fun workName(): String = "self-update-check"

        /**
         * Enqueues the periodic check, idempotently. [ExistingPeriodicWorkPolicy.KEEP] (not
         * REPLACE/UPDATE) mirrors [ModelDownloadWorker]/[SelfUpdateInstallWorker]'s KEEP-based
         * single-flight idiom for one-time work: re-calling [schedule] while the periodic job is
         * already scheduled is a safe no-op rather than restarting its 6h cycle from zero, which
         * matters because both [SelfUpdatePrefs.setNotifyEnabled] and MainActivity's first-run
         * hook can each legitimately call this.
         */
        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<SelfUpdateCheckWorker>(6, TimeUnit.HOURS).build()
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(workName(), ExistingPeriodicWorkPolicy.KEEP, request)
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(workName())
        }
    }
}

/**
 * The single pure decision this Worker exists to make each tick: given the just-fetched
 * [UpdateCheckResult] and the two independent [SelfUpdatePrefs] toggles, should a notification be
 * posted, and should an install be triggered? Split out of [SelfUpdateCheckWorker.doWork] the same
 * way [SelfUpdateResolver]/[SelfUpdateInstallGate] are split from their Context-owning Workers --
 * no Android/WorkManager/network dependency, so every toggle combination is directly testable.
 *
 * Design decision -- notify and auto-install are independent, not auto-install-implies-notify:
 * [shouldAutoInstall] does NOT consult the notify toggle at all. Auto-install is its own explicit
 * opt-in (default off, see [SelfUpdatePrefs.KEY_AUTO_INSTALL_ENABLED]'s kdoc) that a user turned
 * on deliberately; turning notifications off separately is a "stop telling me" preference, not "stop
 * auto-installing for me too" -- silently disabling a user's auto-install opt-in as a side effect
 * of an unrelated notify toggle would be surprising and against the principle of least astonishment.
 * So UpdateAvailable + notify=false + autoInstall=true still auto-installs (no notification is
 * posted, but the update proceeds silently, which is exactly what "auto-install" opted into).
 */
object SelfUpdateCheckDecision {
    fun shouldNotify(result: UpdateCheckResult, notifyEnabled: Boolean): Boolean =
        result is UpdateCheckResult.UpdateAvailable && notifyEnabled

    fun shouldAutoInstall(result: UpdateCheckResult, autoInstallEnabled: Boolean): Boolean =
        result is UpdateCheckResult.UpdateAvailable && autoInstallEnabled
}
