package com.kafkasl.phonewhisper

import android.content.Context
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters

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
        val model = MODEL_CATALOG.firstOrNull { it.archive == archive }
            ?: return Result.failure(errorData("Unknown model: $archive"))

        var failure: Data? = null
        ModelDownloader.download(applicationContext, model) { state ->
            when (state) {
                is DownloadState.Downloading -> setProgressAsync(
                    Data.Builder()
                        .putString(KEY_PHASE, PHASE_DOWNLOADING)
                        .putFloat(KEY_PROGRESS, state.progress)
                        .build()
                )
                is DownloadState.Extracting -> setProgressAsync(
                    Data.Builder().putString(KEY_PHASE, PHASE_EXTRACTING).build()
                )
                is DownloadState.Done -> {}
                is DownloadState.Error -> failure = errorData(state.message)
            }
        }

        return failure?.let { Result.failure(it) } ?: Result.success()
    }

    private fun errorData(message: String) = Data.Builder().putString(KEY_ERROR, message).build()

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

        fun enqueue(ctx: Context, model: Model) {
            val request = OneTimeWorkRequestBuilder<ModelDownloadWorker>()
                .setInputData(Data.Builder().putString(KEY_ARCHIVE, model.archive).build())
                .build()
            WorkManager.getInstance(ctx)
                .enqueueUniqueWork(workName(model.archive), ExistingWorkPolicy.KEEP, request)
        }
    }
}
