package com.trevornk.ramblr

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

/** What the user is told when a job reaches a terminal state. No transcript text, ever. */
internal enum class AudioNoticeKind { DONE, DONE_COPIED, DONE_CLEANUP_FAILED, FAILED }

/** Pure: the one notice (if any) a job's new state calls for. */
internal fun audioNoticeFor(job: AudioJob): AudioNoticeKind? = when (job.status) {
    JobStatus.DONE -> when {
        job.copiedInsteadOfSaved -> AudioNoticeKind.DONE_COPIED
        job.cleanupFailed -> AudioNoticeKind.DONE_CLEANUP_FAILED
        else -> AudioNoticeKind.DONE
    }
    JobStatus.FAILED -> AudioNoticeKind.FAILED
    // CANCELLED / SAVED (a cancelled recording going back to the list) are the user's own action.
    else -> null
}

/** Stable per-job notification id so two finished jobs don't replace each other. */
internal fun audioResultNotificationId(jobId: String): Int = 0x1D000 + (jobId.hashCode() and 0xFFF)

/**
 * #285 notifications: three channels, so each can be muted on its own (the ongoing progress and the
 * recording indicator are routine; the result is the thing the user is waiting for).
 * Pattern follows [BackgroundDictationNotifications]: activity PendingIntents so the shade
 * collapses, SecurityException guards, and a toast fallback when notifications are off so the
 * user is never left guessing.
 */
internal object AudioJobNotifications {
    const val PROGRESS_CHANNEL = "audio_job_progress"
    const val RESULT_CHANNEL = "audio_job_result"
    const val RECORDING_CHANNEL = "audio_job_recording"

    /** Distinct from 0x1C04..0x1C07 used by the other notifications. */
    const val ONGOING_ID = 0x1C08
    const val RECORDING_ID = 0x1C09

    const val ACTION_COPY = "com.trevornk.ramblr.action.AUDIO_COPY_RESULT"
    const val ACTION_CANCEL = "com.trevornk.ramblr.action.AUDIO_CANCEL_JOB"
    const val EXTRA_JOB_ID = "job_id"
    const val EXTRA_HISTORY_TS = "history_ts"

    fun ensureChannels(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(NotificationChannel(PROGRESS_CHANNEL, ctx.getString(R.string.audio_channel_progress), NotificationManager.IMPORTANCE_LOW))
        nm.createNotificationChannel(NotificationChannel(RESULT_CHANNEL, ctx.getString(R.string.audio_channel_result), NotificationManager.IMPORTANCE_DEFAULT))
        nm.createNotificationChannel(NotificationChannel(RECORDING_CHANNEL, ctx.getString(R.string.audio_channel_recording), NotificationManager.IMPORTANCE_LOW))
    }

    private fun activityIntent(ctx: Context, requestCode: Int, intent: Intent): PendingIntent =
        PendingIntent.getActivity(
            ctx, requestCode, intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    private fun openList(ctx: Context) = activityIntent(ctx, 10, Intent(ctx, AudioFilesActivity::class.java))

    private fun openHistory(ctx: Context) = activityIntent(
        ctx, 11, Intent(ctx, DataLogsActivity::class.java).putExtra(DataLogsActivity.EXTRA_SHOW_HISTORY, true),
    )

    private fun broadcast(ctx: Context, action: String, requestCode: Int, fill: Intent.() -> Unit): PendingIntent =
        PendingIntent.getBroadcast(
            ctx, requestCode,
            Intent(ctx, AudioJobActionReceiver::class.java).setAction(action).apply(fill),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    fun ongoing(ctx: Context, current: AudioJob?, waiting: Int): Notification {
        val b = NotificationCompat.Builder(ctx, PROGRESS_CHANNEL)
            .setSmallIcon(R.drawable.ic_mic)
            .setContentTitle(ctx.getString(R.string.audio_notif_working_title))
            .setContentText(
                if (waiting > 0) ctx.getString(R.string.audio_notif_working_text_many, waiting)
                else ctx.getString(R.string.audio_notif_working_text_one)
            )
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setContentIntent(openList(ctx))
        if (current != null && current.status == JobStatus.TRANSCRIBING && current.chunkTotal > 0) {
            b.setProgress(current.chunkTotal, current.chunkIndex, false)
            b.setSubText(ctx.getString(R.string.audio_notif_working_progress, current.chunkIndex + 1, current.chunkTotal))
        } else if (current != null && current.status == JobStatus.DECODING) {
            b.setProgress(100, current.decodePercent, false)
        } else {
            b.setProgress(0, 0, true)
        }
        if (current != null) {
            b.addAction(0, ctx.getString(R.string.audio_action_cancel), broadcast(ctx, ACTION_CANCEL, 20) { putExtra(EXTRA_JOB_ID, current.id) })
        }
        return b.build()
    }

    fun recording(ctx: Context, startedAtWallMs: Long): Notification =
        NotificationCompat.Builder(ctx, RECORDING_CHANNEL)
            .setSmallIcon(R.drawable.ic_mic)
            .setContentTitle(ctx.getString(R.string.audio_notif_recording_title))
            .setContentText(ctx.getString(R.string.audio_notif_recording_text))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setUsesChronometer(true)
            .setWhen(startedAtWallMs)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setContentIntent(openList(ctx))
            .addAction(
                0, ctx.getString(R.string.audio_action_stop),
                PendingIntent.getService(
                    ctx, 30, Intent(ctx, AudioRecorderService::class.java).setAction(AudioRecorderService.ACTION_STOP),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                ),
            )
            .build()

    fun result(ctx: Context, job: AudioJob, kind: AudioNoticeKind): Notification {
        val failed = kind == AudioNoticeKind.FAILED
        val text = when (kind) {
            AudioNoticeKind.DONE -> ctx.getString(R.string.audio_notif_done_text)
            AudioNoticeKind.DONE_COPIED -> ctx.getString(R.string.audio_notif_done_text_copied)
            AudioNoticeKind.DONE_CLEANUP_FAILED -> ctx.getString(R.string.audio_notif_done_text_cleanup_failed)
            AudioNoticeKind.FAILED -> ctx.getString(R.string.audio_notif_failed_text, failureLabel(ctx, job.failure).replaceFirstChar { it.uppercase() })
        }
        val b = NotificationCompat.Builder(ctx, RESULT_CHANNEL)
            .setSmallIcon(R.drawable.ic_mic)
            .setContentTitle(ctx.getString(if (failed) R.string.audio_notif_failed_title else R.string.audio_notif_done_title))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
        val ts = job.historyTimestamp
        if (!failed && ts != null) {
            b.setContentIntent(openHistory(ctx))
            b.addAction(0, ctx.getString(R.string.audio_action_copy), broadcast(ctx, ACTION_COPY, audioResultNotificationId(job.id)) { putExtra(EXTRA_HISTORY_TS, ts) })
            b.addAction(0, ctx.getString(R.string.audio_action_open_history), openHistory(ctx))
        } else {
            b.setContentIntent(openList(ctx))
        }
        return b.build()
    }

    private fun canShowResult(nm: NotificationManagerCompat): Boolean {
        if (!nm.areNotificationsEnabled()) return false
        val channel = nm.getNotificationChannelCompat(RESULT_CHANNEL) ?: return true
        return channel.importance != NotificationManagerCompat.IMPORTANCE_NONE
    }

    /** Never allowed to fail the job that already finished. */
    fun postResult(ctx: Context, job: AudioJob, kind: AudioNoticeKind) {
        try {
            ensureChannels(ctx)
            val nm = NotificationManagerCompat.from(ctx)
            if (!canShowResult(nm)) {
                val msg = if (kind == AudioNoticeKind.FAILED) R.string.audio_notif_failed_title else R.string.audio_notif_done_title
                Handler(Looper.getMainLooper()).post { Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show() }
                return
            }
            nm.notify(audioResultNotificationId(job.id), result(ctx, job, kind))
        } catch (_: SecurityException) {
        } catch (e: Exception) {
            Log.w("AudioJob", "Couldn't post result notice", e)
        }
    }

    fun failureLabel(ctx: Context, f: JobFailure?): String = ctx.getString(
        when (f) {
            JobFailure.UNREADABLE -> R.string.audio_failure_unreadable
            JobFailure.NO_SPEECH -> R.string.audio_failure_no_speech
            JobFailure.NO_PROVIDER -> R.string.audio_failure_no_provider
            JobFailure.LOCAL_UNAVAILABLE -> R.string.audio_failure_local_unavailable
            JobFailure.STORAGE -> R.string.audio_failure_storage
            JobFailure.INTERRUPTED -> R.string.audio_failure_interrupted
            JobFailure.TIMED_OUT -> R.string.audio_failure_timed_out
            JobFailure.TRANSCRIPTION_FAILED, null -> R.string.audio_failure_transcription_failed
        }
    )
}
