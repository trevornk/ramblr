package com.trevornk.ramblr

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat

/**
 * #284: a short-lived foreground service whose only job is to keep Ramblr's process at foreground
 * priority while a dictation is being transcribed and cleaned, so switching apps (or the screen
 * going off) mid-transcription no longer lets Android reclaim the process and silently lose the
 * work. It owns no pipeline state: [DictationRuntime] keeps doing all the work, exactly as before,
 * and asks for this service through [BackgroundWork]. See docs/adr/0002-background-transcription.md.
 *
 * Lifecycle (lazy, ADR-0002): started only when the IME host reports the user is LEAVING the field
 * while a transcription is in flight (onFinishInput / onWindowHidden) -- an allowed background
 * start because the app is still the current input method -- and stopped the moment the pipeline
 * reaches a terminal state. A dictation that stays in its field never starts it, so the daily path
 * has no notification, status icon or active-apps entry. It also stops itself [MAX_RUN_MS] after
 * the latest hold was requested (just past the runtime's own 400 s watchdog) and when the OS fires
 * [onTimeout], so it can never linger. The accessibility host does not use it at all.
 *
 * Type: `dataSync` (already declared for the model-download worker; the permission is already
 * held). It is the closest honest fit for "process/transfer a user-initiated payload" and, unlike
 * `microphone`, is not subject to the while-in-use background-start restriction.
 */
class BackgroundTranscriptionService : Service() {

    private val handler = Handler(Looper.getMainLooper())
    private val selfTimeout = Runnable {
        Log.w(TAG, "Background transcription service hit its hard cap; stopping")
        stopNow()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // startForeground MUST run once for every startForegroundService, even when we are about
        // to stop again, or the platform kills the process for not honoring the contract.
        if (!enterForeground()) {
            Companion.onServiceStartFailed()
            stopSelf()
            return START_NOT_STICKY
        }
        Companion.onServiceUp(this)
        armSelfCap()
        // Everything finished between the start request and now (very short dictation).
        if (Companion.heldCount() == 0) stopNow()
        // Never restart after process death: a restarted service would have no dictation to hold.
        return START_NOT_STICKY
    }

    /** Android 14: the system's own short timeout for this service type. */
    override fun onTimeout(startId: Int) {
        Log.w(TAG, "System timed out background transcription service")
        stopNow()
    }

    /** Android 15+: the per-type timeout (dataSync is budgeted per day). */
    override fun onTimeout(startId: Int, fgsType: Int) {
        Log.w(TAG, "System timed out background transcription service (type=$fgsType)")
        stopNow()
    }

    override fun onDestroy() {
        handler.removeCallbacks(selfTimeout)
        Companion.onServiceGone(this)
        super.onDestroy()
    }

    /** (Re)starts the hard cap. Called on start and again whenever another dictation takes a hold
     *  on this live instance, so a hold taken late in an earlier hold's window still gets the full
     *  window instead of being cut off by a cap that started for someone else. */
    internal fun armSelfCap() {
        handler.removeCallbacks(selfTimeout)
        handler.postDelayed(selfTimeout, MAX_RUN_MS)
    }

    internal fun stopNow() {
        handler.removeCallbacks(selfTimeout)
        // Forget ourselves immediately, not at onDestroy: a dictation that begins between this
        // stopSelf() and the platform destroying us must request a fresh start rather than assume
        // this dying instance will protect it. (A hold still counted at this point -- hard cap or
        // platform timeout -- is not stranded: every acquire re-evaluates, see startIfStillWanted.)
        Companion.onServiceGone(this)
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun enterForeground(): Boolean = try {
        BackgroundDictationNotifications.ensureChannels(this)
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        } else {
            0
        }
        ServiceCompat.startForeground(
            this,
            BackgroundDictationNotifications.ONGOING_ID,
            BackgroundDictationNotifications.ongoing(this),
            type,
        )
        true
    } catch (e: Exception) {
        // E.g. ForegroundServiceStartNotAllowedException, or a missing type permission. The
        // dictation itself carries on exactly as it did before #284; it just isn't protected.
        Log.w(TAG, "Couldn't enter foreground for background transcription", e)
        false
    }

    companion object {
        private const val TAG = "BgTranscription"

        /** Just past [DictationRuntime]'s 400 s watchdog, which is the pipeline's own hard cap. */
        internal const val MAX_RUN_MS = 430_000L

        private val main = Handler(Looper.getMainLooper())

        @Volatile private var appContext: Context? = null

        /** Main thread only. The live service, once it has called startForeground. */
        private var instance: BackgroundTranscriptionService? = null

        /** Main thread only. When a start was requested and onStartCommand hasn't run yet, else 0.
         *  A timestamp rather than a flag so a start the platform silently dropped can never wedge
         *  every later dictation: after [START_GRACE_MS] a new request is allowed again. */
        private var startRequestedAtMs = 0L
        private const val START_GRACE_MS = 10_000L

        private val holds = BackgroundWorkHolds(
            onAcquire = { onMain { startIfStillWanted() } },
            onLastRelease = { onMain { stopIfNotWanted() } },
        )

        internal fun heldCount(): Int = holds.held()

        /** The shipped [BackgroundWork]: every runtime shares one process-wide hold counter, so
         *  two overlapping releases/acquires (sequential dictations) can never stop the service
         *  under a dictation that still needs it. */
        internal fun work(context: Context): BackgroundWork {
            val app = context.applicationContext
            return object : BackgroundWork {
                override fun begin() {
                    appContext = app
                    holds.acquire()
                }

                override fun end() {
                    holds.release()
                }
            }
        }

        private fun onMain(block: () -> Unit) {
            if (Looper.myLooper() == Looper.getMainLooper()) block() else main.post(block)
        }

        /** Runs on every acquire and is idempotent: with a live instance it just refreshes that
         *  instance's cap; otherwise it requests exactly one start (a pending request suppresses
         *  duplicates for [START_GRACE_MS]). */
        private fun startIfStillWanted() {
            if (holds.held() == 0) return
            instance?.let { it.armSelfCap(); return }
            val now = android.os.SystemClock.elapsedRealtime()
            if (startRequestedAtMs != 0L && now - startRequestedAtMs < START_GRACE_MS) return
            val ctx = appContext ?: return
            startRequestedAtMs = now
            try {
                ctx.startForegroundService(Intent(ctx, BackgroundTranscriptionService::class.java))
            } catch (e: Exception) {
                // E.g. ForegroundServiceStartNotAllowedException. The dictation proceeds exactly
                // as it did before #284; it just isn't process-protected.
                startRequestedAtMs = 0L
                Log.w(TAG, "Couldn't start background transcription service", e)
            }
        }

        private fun stopIfNotWanted() {
            if (holds.held() != 0) return
            // If the service hasn't come up yet, onStartCommand sees heldCount() == 0 and stops.
            instance?.stopNow()
        }

        internal fun onServiceUp(service: BackgroundTranscriptionService) {
            instance = service
            startRequestedAtMs = 0L
        }

        internal fun onServiceStartFailed() {
            startRequestedAtMs = 0L
        }

        /** Deliberately leaves [startRequestedAtMs] alone: a start for a NEW instance may already be
         *  pending when an old one is torn down (stopNow, then the platform's later onDestroy), and
         *  clearing it here would let a second start be requested for the same hold. */
        internal fun onServiceGone(service: BackgroundTranscriptionService) {
            if (instance === service) instance = null
        }

        /** Test seam: drop all process-wide state between tests. */
        internal fun resetForTest() {
            holds.resetForTest()
            instance = null
            startRequestedAtMs = 0L
        }
    }
}

/**
 * #284: notifications for background dictation. Two channels, deliberately split so the user can
 * silence the routine one without losing the one that reports lost work:
 *  - [WORK_CHANNEL_ID] (IMPORTANCE_LOW): the ongoing "transcribing" notification the foreground
 *    service requires. Silent, no badge.
 *  - [RESULT_CHANNEL_ID] (IMPORTANCE_DEFAULT): "your text is on the clipboard" / "dictation
 *    failed". Shown only for dictation the user can no longer see; never for the normal path.
 *
 * No notification ever carries transcript text: they appear on lock screens and in the system's
 * notification history. The pattern (channel + SecurityException guard + activity PendingIntent so
 * the shade collapses normally) follows [ServiceRecoveryNotifications].
 */
internal object BackgroundDictationNotifications {
    const val WORK_CHANNEL_ID = "background_dictation_work"
    const val RESULT_CHANNEL_ID = "background_dictation_result"

    /** Distinct from IconVisibilityNotifications' 0x1C04 and ServiceRecoveryNotifications' 0x1C05. */
    const val ONGOING_ID = 0x1C06
    const val RESULT_ID = 0x1C07

    fun ensureChannels(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(
            NotificationChannel(WORK_CHANNEL_ID, "Dictation in progress", NotificationManager.IMPORTANCE_LOW)
        )
        nm.createNotificationChannel(
            NotificationChannel(RESULT_CHANNEL_ID, "Dictation results", NotificationManager.IMPORTANCE_DEFAULT)
        )
    }

    private fun contentIntent(ctx: Context, opensHistory: Boolean): PendingIntent {
        val intent = if (opensHistory) {
            Intent(ctx, DataLogsActivity::class.java).putExtra(DataLogsActivity.EXTRA_SHOW_HISTORY, true)
        } else {
            Intent(ctx, MainActivity::class.java)
        }.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return PendingIntent.getActivity(
            ctx,
            if (opensHistory) 1 else 0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    fun ongoing(ctx: Context): Notification = NotificationCompat.Builder(ctx, WORK_CHANNEL_ID)
        .setSmallIcon(R.drawable.ic_mic)
        .setContentTitle("Ramblr is transcribing")
        .setContentText("Finishing your dictation in the background.")
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .setCategory(NotificationCompat.CATEGORY_PROGRESS)
        // Android 12+: the notification only appears if the service is still running after ~10 s,
        // so a normal short dictation shows nothing at all (#284: no new noise on the daily path).
        .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_DEFERRED)
        .setProgress(0, 0, true)
        .setContentIntent(contentIntent(ctx, opensHistory = false))
        .build()

    fun result(ctx: Context, notice: BackgroundNotice): Notification =
        NotificationCompat.Builder(ctx, RESULT_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_mic)
            .setContentTitle(notice.title)
            .setContentText(notice.text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(notice.text))
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setContentIntent(contentIntent(ctx, notice.opensHistory))
            .build()

    /** App-level notifications on AND the result channel itself not switched off. The channel
     *  check matters because muting only "Dictation results" leaves areNotificationsEnabled() true
     *  while every notify() to it silently vanishes. A missing channel (not created yet / read
     *  failure) fails open to "can show", which is the pre-existing behavior. */
    private fun canShowResult(nm: NotificationManagerCompat): Boolean {
        if (!nm.areNotificationsEnabled()) return false
        val channel = nm.getNotificationChannelCompat(RESULT_CHANNEL_ID) ?: return true
        return channel.importance != NotificationManagerCompat.IMPORTANCE_NONE
    }

    /** Never allowed to fail the caller: a missing POST_NOTIFICATIONS grant must not affect the
     *  dictation, which has already finished or failed on its own terms. A single fixed id means a
     *  second background result replaces the first instead of stacking. */
    fun postResult(ctx: Context, notice: BackgroundNotice) {
        try {
            ensureChannels(ctx)
            val nm = NotificationManagerCompat.from(ctx)
            if (!canShowResult(nm)) {
                // POST_NOTIFICATIONS denied, or the result channel muted on its own: the user must
                // still not be left guessing, so fall back to a toast, the one surface that needs
                // no grant. (A muted channel makes notify() a silent no-op.)
                Handler(Looper.getMainLooper()).post {
                    android.widget.Toast.makeText(ctx, "${notice.title}. ${notice.text}", android.widget.Toast.LENGTH_LONG).show()
                }
                return
            }
            nm.notify(RESULT_ID, result(ctx, notice))
        } catch (_: SecurityException) {
        } catch (e: Exception) {
            Log.w("BgTranscription", "Couldn't post background dictation notice", e)
        }
    }
}
