package com.trevornk.ramblr

import android.app.Notification
import android.content.Context
import android.content.pm.ServiceInfo
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Downloads, verifies, and (subject to the [SelfUpdateInstallGate]) silently installs the APK
 * asset from the currently-cached [UpdateCheckResult.UpdateAvailable] (github distribution flavor
 * only -- see [SelfUpdateChecker]'s AGENTS note). Structurally mirrors [ModelDownloadWorker]:
 * a plain WorkManager [Worker] (not CoroutineWorker) with a foreground-service progress
 * notification, single-flight [enqueue]/[cancel] under one unique work name, and terminal vs.
 * retryable failure classification -- but this Worker additionally gates its own final step (the
 * actual install) on [SelfUpdateInstallGate], which [ModelDownloadWorker] has no equivalent of.
 *
 * NOT wired to any trigger yet: [enqueue] is a plain public entry point for Part 5's periodic
 * checker to call once it exists (see the `// TODO(Part 5)` in [SelfUpdatePrefs.setAutoInstallEnabled]).
 * Nothing in this codebase calls [enqueue] today.
 */
class SelfUpdateInstallWorker(ctx: Context, params: WorkerParameters) : Worker(ctx, params) {

    override fun doWork(): Result {
        val update = SelfUpdateChecker.cachedResult(applicationContext) as? UpdateCheckResult.UpdateAvailable
            ?: return Result.failure(errorData("No cached UpdateAvailable result"))

        SelfUpdateNotifications.ensureChannel(applicationContext)

        val apkFile = apkFile(applicationContext, update.versionCode)

        // Resume-friendly: if a previous attempt already downloaded and verified this exact
        // version's bytes but was deferred by the install gate (see the retry() path below,
        // which deliberately does NOT delete the file), skip straight to the gate instead of
        // re-downloading tens of MB for nothing every retry tick.
        if (!(apkFile.exists() && SelfUpdateInstallGate.checksumMatches(update.sha256, ModelDownloader.sha256(apkFile)))) {
            val downloadResult = downloadApk(update, apkFile)
            if (downloadResult != null) return downloadResult
        }

        return attemptGatedInstall(update, apkFile)
    }

    /** Downloads [update]'s APK asset to [dest] with a foreground progress notification. Returns
     *  null on success (caller proceeds to the install gate); returns a terminal [Result] (retry
     *  or failure) if the download itself didn't complete cleanly. */
    private fun downloadApk(update: UpdateCheckResult.UpdateAvailable, dest: File): Result? {
        dest.parentFile?.mkdirs()
        val tmp = File(dest.parentFile, dest.name + ".part")

        try {
            val request = Request.Builder().url(update.downloadUrl).get().build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw IOException("HTTP ${response.code} downloading update APK")
                }
                val body = response.body ?: throw IOException("Empty response body downloading update APK")
                val total = body.contentLength().takeIf { it > 0 } ?: update.sizeBytes
                var lastNotifiedPercent = -100
                tmp.outputStream().use { out ->
                    body.byteStream().use { input ->
                        val buf = ByteArray(16384)
                        var readSoFar = 0L
                        var n: Int
                        while (input.read(buf).also { n = it } != -1) {
                            if (isStopped) throw DownloadCancelledException()
                            out.write(buf, 0, n)
                            readSoFar += n
                            if (total > 0) {
                                val percent = ((readSoFar * 100) / total).toInt().coerceIn(0, 100)
                                if (DownloadNotifications.shouldPostUpdate(lastNotifiedPercent, percent)) {
                                    lastNotifiedPercent = percent
                                    postForeground(SelfUpdateNotifications.progress(applicationContext, update.versionName, percent))
                                }
                            }
                        }
                    }
                }
            }
        } catch (e: DownloadCancelledException) {
            tmp.delete()
            return Result.failure()
        } catch (e: IOException) {
            tmp.delete()
            return if (ModelDownloadWorker.shouldRetry(e, runAttemptCount)) {
                Result.retry()
            } else {
                SelfUpdateNotifications.postInstallFailure(applicationContext, update.versionName, e.message ?: "Download failed")
                Result.failure(errorData(e.message ?: "Download failed"))
            }
        }

        // Verify BEFORE renaming into the "real" file name checked at the top of doWork -- a
        // half-verified file must never be mistaken for a good one by a future retry's resume-skip.
        val actualSha256 = ModelDownloader.sha256(tmp)
        if (!SelfUpdateInstallGate.checksumMatches(update.sha256, actualSha256)) {
            // Mirrors ChecksumMismatchException's terminal-failure intent (ModelDownloader.kt
            // ~line 293): complete-but-wrong bytes can't be fixed by retrying the same URL, so
            // this is a hard failure, not a WorkManager retry.
            tmp.delete()
            SelfUpdateNotifications.postInstallFailure(
                applicationContext, update.versionName,
                "Checksum mismatch: expected ${update.sha256}, got $actualSha256",
            )
            return Result.failure(errorData("Checksum mismatch"))
        }

        dest.delete()
        if (!tmp.renameTo(dest)) {
            tmp.delete()
            SelfUpdateNotifications.postInstallFailure(applicationContext, update.versionName, "Failed to finalize downloaded update file")
            return Result.failure(errorData("rename failed"))
        }
        return null
    }

    /** The install gate (quiet hours + idle debounce, see [SelfUpdateInstallGate]) plus, if it
     *  passes, the actual [PackageInstaller]-based install attempt. [apkFile] is already
     *  downloaded and checksum-verified by the time this runs. [manual] (from [inputData]'s
     *  [KEY_MANUAL], set by [enqueueManual]) skips the quiet-hours requirement -- a user who just
     *  tapped "Install now" is by definition looking at the phone right now, so waiting for the
     *  overnight window would just be confusing (see [SelfUpdateInstallGate.shouldAttemptManualInstallNow]).
     *  The idle/not-mid-dictation check still applies either way. */
    private fun attemptGatedInstall(update: UpdateCheckResult.UpdateAvailable, apkFile: File): Result {
        val manual = inputData.getBoolean(KEY_MANUAL, false)
        val recordingStateFirst = WhisperAccessibilityService.currentRecordingState()
        val allowedFirst = if (manual) {
            SelfUpdateInstallGate.shouldAttemptManualInstallNow(recordingStateFirst)
        } else {
            SelfUpdateInstallGate.shouldAttemptInstallNow(SelfUpdateInstallGate.isWithinQuietHours(currentHour()), recordingStateFirst)
        }
        if (!allowedFirst) {
            // Not a failure -- just not a good moment. Keep the verified file on disk so the next
            // periodic retry (Part 5) doesn't need to re-download. No failure notification either:
            // this is expected, routine "try again later" behavior, not something to alarm about.
            return Result.retry()
        }

        // Idle-debounce: wait, then re-check. A WorkManager Worker already runs on a background
        // thread pool (see ModelDownloadWorker's identical synchronous-blocking precedent), so a
        // plain Thread.sleep here is consistent with this codebase's existing pattern rather than
        // introducing a new coroutine-based Worker just for this one wait.
        Thread.sleep(IDLE_DEBOUNCE_MS)
        if (isStopped) return Result.failure()

        val recordingStateSecond = WhisperAccessibilityService.currentRecordingState()
        val allowedSecond = if (manual) {
            SelfUpdateInstallGate.shouldAttemptManualInstallNow(recordingStateSecond)
        } else {
            SelfUpdateInstallGate.shouldAttemptInstallNow(SelfUpdateInstallGate.isWithinQuietHours(currentHour()), recordingStateSecond)
        }
        if (!allowedSecond) {
            return Result.retry()
        }

        // KNOWN LIMITATION (documented, not eliminated): there is still a real -- if narrow --
        // race between this second idle check and PackageInstaller actually committing the
        // session a few lines below. Dictation could start in that window. This reduces the
        // chance of a mid-dictation install to a small fraction of a second rather than an
        // entire ~30s debounce window, but does NOT make it zero. A future part could add a
        // best-effort recheck immediately before commitSession() too, but even that only shrinks
        // the window further, it can't remove it -- there is no atomic "check-and-install"
        // primitive across the accessibility-service boundary.
        return try {
            SelfUpdateInstaller.install(applicationContext, apkFile)
            // The staged APK has been successfully handed off to (and committed by)
            // PackageInstaller -- it's no longer needed on disk. Mirrors the downloadApk()
            // path's tmp.delete() cleanup for the .part file: a successful terminal outcome
            // must never leave staged bytes behind under filesDir/self_update/ forever.
            apkFile.delete()
            Result.success()
        } catch (e: Exception) {
            SelfUpdateNotifications.postInstallFailure(applicationContext, update.versionName, e.message ?: "Install failed")
            Result.failure(errorData(e.message ?: "Install failed"))
        }
    }

    private fun currentHour(): Int = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)

    /** Mirrors [ModelDownloadWorker.postForeground]'s never-fail-doWork contract exactly: a
     *  missing POST_NOTIFICATIONS grant must not block the download/install itself. */
    private fun postForeground(notification: Notification) {
        try {
            setForegroundAsync(
                ForegroundInfo(
                    INSTALL_NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
                )
            )
        } catch (_: Exception) {
        }
    }

    private fun errorData(message: String) = androidx.work.Data.Builder().putString(KEY_ERROR, message).build()

    companion object {
        private const val KEY_ERROR = "error"

        /** Stable notification id for this Worker's own foreground-progress notification, kept
         *  out of [SelfUpdateNotifications.notificationId]'s per-version id space (that's for the
         *  "update available" notification) so the two can never collide. */
        private const val INSTALL_NOTIFICATION_ID = 0x5E1F_0001

        /** How long to wait between the two idle-debounce recording-state checks in
         *  [attemptGatedInstall]. 30s (not [ModelDownloadWorker]'s 30s *network-retry* backoff,
         *  which is a different concern entirely -- this is a real-time wait inside a single
         *  attempt, not a WorkManager backoff between attempts) balances giving a just-finished
         *  dictation time to actually settle against not stalling a single Worker execution for
         *  an excessive amount of time before it either proceeds or defers to the next retry. */
        const val IDLE_DEBOUNCE_MS = 30_000L

        /** WorkManager backoff before the *next* attempt when the gate itself defers an install
         *  (Result.retry() from a failed quiet-hours/idle check, not a download failure). This is
         *  deliberately much longer than [ModelDownloadWorker]'s 30s network-retry backoff: a
         *  quiet-hours miss or an in-progress dictation isn't a transient blip that resolves in
         *  seconds, it's "come back near the next quiet-hours window" -- retrying every 30s all
         *  day would just burn battery/CPU on checks that are almost certain to fail again
         *  immediately. 30 minutes is short enough to still catch same-night quiet-hours windows
         *  (the default window is 4 hours wide) if the first attempt was deferred by an
         *  in-progress dictation, without hammering the check every half-minute all day long. */
        val GATE_RETRY_BACKOFF = 30L to TimeUnit.MINUTES

        private val client by lazy {
            NetworkClients.shared.newBuilder()
                .callTimeout(10, TimeUnit.MINUTES)
                .build()
        }

        /** Where the downloaded (and checksum-verified) APK is staged before install: private
         *  app storage (`filesDir`), never the public Downloads collection -- avoids
         *  FileProvider/scoped-storage complications entirely, mirroring where
         *  [ModelDownloader.modelDir] stages its own model files under `filesDir`. Keyed by
         *  versionCode so a stale prior-version download can never be mistaken for the current
         *  one. Pure path computation split from the [Context]-reading [apkFile] the same way
         *  [ModelDownloader.modelDirPath] is split from [ModelDownloader.modelDir], so it's
         *  testable without a real Android Context. */
        fun apkFilePath(filesDir: File, versionCode: Int): File =
            File(filesDir, "self_update/ramblr-update-$versionCode.apk")

        fun apkFile(ctx: Context, versionCode: Int): File = apkFilePath(ctx.filesDir, versionCode)

        fun workName(): String = "self-update-install"

        /** [Data] key for whether this run was triggered by an explicit user tap (see
         *  [enqueueManual]) vs. the periodic/auto-install path (see [enqueue]) -- read by
         *  [attemptGatedInstall] to decide which [SelfUpdateInstallGate] rule applies. */
        private const val KEY_MANUAL = "manual"

        fun enqueue(ctx: Context) {
            val request = OneTimeWorkRequestBuilder<SelfUpdateInstallWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(androidx.work.BackoffPolicy.LINEAR, GATE_RETRY_BACKOFF.first, GATE_RETRY_BACKOFF.second)
                .build()
            WorkManager.getInstance(ctx)
                .enqueueUniqueWork(workName(), ExistingWorkPolicy.KEEP, request)
        }

        /** Manual "Install now" entry point (Settings row / notification action): same worker,
         *  same download/checksum/PackageInstaller pipeline as [enqueue], but tagged so
         *  [attemptGatedInstall] skips the overnight quiet-hours requirement -- see
         *  [SelfUpdateInstallGate.shouldAttemptManualInstallNow]'s kdoc for why that's safe.
         *  [ExistingWorkPolicy.REPLACE] (not [ExistingWorkPolicy.KEEP] like [enqueue]) so a
         *  user who taps "Install now" while a quiet-hours-gated automatic attempt is still
         *  sitting in backoff doesn't have to wait for that one's next retry tick -- the manual
         *  tap always wins and runs immediately. */
        fun enqueueManual(ctx: Context) {
            val request = OneTimeWorkRequestBuilder<SelfUpdateInstallWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setInputData(androidx.work.Data.Builder().putBoolean(KEY_MANUAL, true).build())
                .build()
            WorkManager.getInstance(ctx)
                .enqueueUniqueWork(workName(), ExistingWorkPolicy.REPLACE, request)
        }

        fun cancel(ctx: Context) {
            WorkManager.getInstance(ctx).cancelUniqueWork(workName())
        }
    }
}
