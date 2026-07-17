package com.trevornk.ramblr

import android.app.Notification
import android.content.Context
import android.content.pm.ServiceInfo
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Runs [ModelDownloader.download] as WorkManager work instead of a bare
 * `Thread` bound to an Activity. WorkManager persists the request, so it
 * survives Activity recreation and (short of the OS reclaiming the whole
 * process) app backgrounding, and [enqueue] uses a name unique per model
 * archive so a second tap while one is already pending/running is a no-op
 * at the WorkManager level, not just in the UI.
 */
class ModelDownloadWorker(ctx: Context, params: WorkerParameters) : Worker(ctx, params) {

    override fun doWork(): Result {
        val archive = inputData.getString(KEY_ARCHIVE)
            ?: return Result.failure(errorData("Missing archive name"))
        val model = (MODEL_CATALOG + STREAMING_MODEL_CATALOG + LOCAL_CLEANUP_MODEL_CATALOG + VAD_MODEL_CATALOG).firstOrNull { it.archive == archive }
            ?: return Result.failure(errorData("Unknown model: $archive"))

        DownloadNotifications.ensureChannel(applicationContext)
        var lastNotifiedPercent = -100
        var lastProgressPercent = -100
        var failure: DownloadState.Error? = null
        ModelDownloader.download(applicationContext, model, isCancelled = { isStopped }) { state ->
            when (state) {
                is DownloadState.Downloading -> {
                    val percent = (state.progress * 100).toInt().coerceIn(0, 100)
                    // Same 5%-step throttle the notification uses (#86): unthrottled, this was
                    // one Room write per 16 KB chunk (~29k writes for a 465 MB model).
                    if (DownloadNotifications.shouldPostUpdate(lastProgressPercent, percent)) {
                        lastProgressPercent = percent
                        setProgressAsync(
                            Data.Builder()
                                .putString(KEY_PHASE, PHASE_DOWNLOADING)
                                .putFloat(KEY_PROGRESS, state.progress)
                                .build()
                        )
                    }
                    if (DownloadNotifications.shouldPostUpdate(lastNotifiedPercent, percent)) {
                        lastNotifiedPercent = percent
                        postForeground(archive, DownloadNotifications.progress(applicationContext, model.name, percent))
                    }
                }
                is DownloadState.Extracting -> {
                    setProgressAsync(Data.Builder().putString(KEY_PHASE, PHASE_EXTRACTING).build())
                    postForeground(archive, DownloadNotifications.extracting(applicationContext, model.name))
                }
                is DownloadState.Done -> {}
                is DownloadState.Error -> failure = state
            }
        }

        // A user-cancelled download surfaces as a terminal DownloadCancelledException; WorkManager
        // has already marked the work CANCELLED, so return quietly without a failure notification.
        if (isStopped) return Result.failure()

        val failed = failure ?: run {
            DownloadNotifications.postSuccess(applicationContext, archive, model.name)
            if (model.isLocalCleanup) {
                // A re-download replaces the GGUF in place, but LocalCleanupModelHolder still holds
                // an mmap of the previous file keyed by the (unchanged) path and would serve stale
                // weights until the 5-minute idle unload. Drop the held instance so the next cleanup
                // loads the freshly downloaded file (L9).
                LocalCleanupModelHolder.releaseAsync()
            }
            notifyServiceModelReady(archive)
            return Result.success()
        }

        // Transient network trouble is exactly what WorkManager's backoff exists to absorb
        // (#86) -- and the resume support keeps the bytes already on disk, so a retry is cheap.
        // Terminal failures (checksum mismatch, not enough space, catalog bugs) fail fast with
        // a notification as before. Retries stay silent: the foreground notification simply
        // reappears when the next attempt starts.
        if (shouldRetry(failed.cause, runAttemptCount)) {
            return Result.retry()
        }
        DownloadNotifications.postFailure(applicationContext, archive, model.name, failed.message)
        return Result.failure(errorData(failed.message))
    }

    /** Promotes this Worker to a foreground service showing [notification], so a download that
     *  outlives MainActivity being on-screen still stays visible (see [DownloadNotifications]).
     *  Never allowed to fail [doWork]: a missing POST_NOTIFICATIONS grant (or any other
     *  notification-layer error) just means the download proceeds without one, exactly as it did
     *  before this change. */
    private fun postForeground(archive: String, notification: Notification) {
        try {
            setForegroundAsync(
                ForegroundInfo(
                    DownloadNotifications.notificationId(archive),
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                )
            )
        } catch (_: Exception) {
        }
    }

    private fun errorData(message: String) = Data.Builder().putString(KEY_ERROR, message).build()

    /**
     * Tell the running accessibility service (if any) to load a model that just finished
     * downloading (#H5). Before this, the only things that reloaded a model into the live service
     * were service-connect, an explicit settings-screen reload, and a memory-trim warm-up -- so a
     * model whose download completed while nothing was observing WorkManager (most importantly an
     * onboarding download after the wizard was closed) never activated, and a fresh local-mode user
     * got "Local model still downloading" until they happened to reopen a settings screen. The
     * service re-reads its own selected-model prefs on reload, so this changes no user selection.
     * A LOCAL_CLEANUP model needs no reload -- it's loaded on demand by file path at cleanup time.
     * No-op when the service isn't running (the next service connect loads it anyway).
     */
    private fun notifyServiceModelReady(archive: String) {
        val service = WhisperAccessibilityService.instance ?: return
        when (reloadKindFor(archive)) {
            ModelReloadKind.TRANSCRIPTION -> service.reloadModel()
            ModelReloadKind.STREAMING -> service.reloadStreamingModel()
            ModelReloadKind.LOCAL_CLEANUP, ModelReloadKind.VAD, null -> {}
        }
    }

    /** Which running-service reload a freshly-downloaded model needs, keyed by which catalog it's
     *  in. TRANSCRIPTION and STREAMING are held in native slots that must be reloaded;
     *  LOCAL_CLEANUP and VAD are loaded on demand by path so they need none. */
    enum class ModelReloadKind { TRANSCRIPTION, STREAMING, LOCAL_CLEANUP, VAD }

    companion object {
        const val KEY_ARCHIVE = "archive"
        const val KEY_PHASE = "phase"
        const val KEY_PROGRESS = "progress"
        const val KEY_ERROR = "error"
        const val PHASE_DOWNLOADING = "downloading"
        const val PHASE_EXTRACTING = "extracting"

        /** Unique WorkManager work name for a model's archive. Enqueuing under this
         *  name with [ExistingWorkPolicy.KEEP] gives single-flight semantics for
         *  free: a second enqueue while a download/extract for the same archive is
         *  already pending or running is silently dropped by WorkManager. */
        fun workName(archive: String): String = "model-download:$archive"

        /** True if [state] represents a download already in flight for a model row.
         *  Used by the UI to skip re-enqueuing (WorkManager's KEEP policy would
         *  ignore it anyway) and to keep a stray tap from resetting live progress. */
        fun isInFlight(state: WorkInfo.State?): Boolean =
            state == WorkInfo.State.ENQUEUED || state == WorkInfo.State.RUNNING

        /** Which running-service reload (if any) a freshly-downloaded [archive] needs (#H5),
         *  determined by which catalog owns it. Streaming and local-cleanup catalogs are checked
         *  first since their archive names are disjoint from the offline catalog; an unknown
         *  archive returns null (nothing to reload). */
        fun reloadKindFor(archive: String): ModelReloadKind? = when {
            STREAMING_MODEL_CATALOG.any { it.archive == archive } -> ModelReloadKind.STREAMING
            LOCAL_CLEANUP_MODEL_CATALOG.any { it.archive == archive } -> ModelReloadKind.LOCAL_CLEANUP
            VAD_MODEL_CATALOG.any { it.archive == archive } -> ModelReloadKind.VAD
            MODEL_CATALOG.any { it.archive == archive } -> ModelReloadKind.TRANSCRIPTION
            else -> null
        }

        /** Give up after this many attempts of a transient failure -- bounded so a
         *  persistently-broken URL (404 reads as an IOException) can't retry forever (#86). */
        const val MAX_ATTEMPTS = 3

        /**
         * Pure retry classification (#86): retry only transient, IO-shaped failures, and only
         * while attempts remain. [ChecksumMismatchException] (complete-but-wrong bytes) and
         * [NotEnoughSpaceException] are terminal IOExceptions; anything non-IO (catalog bugs,
         * traversal rejection) is terminal by definition. [runAttemptCount] is 0-based on the
         * first attempt, matching WorkManager's Worker.getRunAttemptCount.
         */
        fun shouldRetry(cause: Exception?, runAttemptCount: Int): Boolean {
            if (runAttemptCount >= MAX_ATTEMPTS - 1) return false
            return when (cause) {
                // DownloadCancelledException is terminal: a user cancel must not be retried by the
                // backoff (L10). ChecksumMismatch/NotEnoughSpace are terminal for their own reasons.
                is DownloadCancelledException, is ChecksumMismatchException, is NotEnoughSpaceException -> false
                is IOException -> true
                else -> false
            }
        }

        fun enqueue(ctx: Context, model: Model) {
            val request = OneTimeWorkRequestBuilder<ModelDownloadWorker>()
                .setInputData(Data.Builder().putString(KEY_ARCHIVE, model.archive).build())
                // Don't start (or restart) a 30s-20min download with no network: enqueued
                // offline, the worker previously ran immediately, failed in OkHttp, and posted
                // a terminal failure notification (#86).
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(ctx)
                .enqueueUniqueWork(workName(model.archive), ExistingWorkPolicy.KEEP, request)
        }

        /** Cancels an in-flight (or queued) download for [model]. WorkManager sets the worker's
         *  isStopped, which [ModelDownloader.download]'s per-chunk check honors to abort promptly;
         *  the partial file is kept so a later re-download resumes it (L10). */
        fun cancel(ctx: Context, model: Model) {
            WorkManager.getInstance(ctx).cancelUniqueWork(workName(model.archive))
        }
    }
}
