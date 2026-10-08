package com.trevornk.ramblr

import android.app.Service
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * #285: front door for audio-file jobs, shared by the share target, the picker screen and the
 * notification actions so they cannot drift.
 */
internal object AudioJobs {
    private const val TAG = "AudioJob"
    const val MAX_FILES_PER_SHARE = 20

    private val recovered = AtomicBoolean(false)

    fun store(ctx: Context): AudioJobStore = AudioJobStore.forFilesDir(ctx.applicationContext.filesDir)

    /** Once per process: a job that claimed to be running when the previous process died is not. */
    fun ensureRecovered(ctx: Context) {
        if (recovered.compareAndSet(false, true)) {
            runCatching { store(ctx).recoverInterrupted() }
        }
    }

    /** Creates a QUEUED job per URI (capped) and starts the worker. Returns how many were added. */
    fun enqueueImported(ctx: Context, uris: List<Uri>, fallbackName: String): Int {
        ensureRecovered(ctx)
        val accepted = uris.distinct().take(MAX_FILES_PER_SHARE)
        val now = System.currentTimeMillis()
        accepted.forEachIndexed { i, uri ->
            // Keep a long-lived grant when the provider offers one (SAF picker); harmless otherwise.
            runCatching { ctx.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            store(ctx).upsert(
                AudioJob(
                    id = UUID.randomUUID().toString(),
                    displayName = AudioJobRunner.displayNameOf(ctx, uri, fallbackName),
                    kind = JobKind.IMPORTED,
                    status = JobStatus.QUEUED,
                    createdAt = now + i,
                    sourceUri = uri.toString(),
                )
            )
        }
        if (accepted.isNotEmpty()) startWorker(ctx, accepted)
        return accepted.size
    }

    /** SAVED recording -> QUEUED. */
    fun transcribe(ctx: Context, id: String) {
        if (store(ctx).update(id, JobEvent.Enqueue)?.status == JobStatus.QUEUED) startWorker(ctx, emptyList())
    }

    fun retry(ctx: Context, id: String) {
        if (store(ctx).update(id, JobEvent.Retry)?.status == JobStatus.QUEUED) startWorker(ctx, emptyList())
    }

    fun cancel(ctx: Context, id: String) = AudioTranscriptionService.requestCancel(ctx, id)

    /** Removes a finished/failed/saved item and any audio it still owns. Active jobs are cancelled first. */
    fun delete(ctx: Context, id: String) {
        val job = store(ctx).get(id) ?: return
        if (job.status.isActive) {
            cancel(ctx, id)
            return
        }
        AudioJobFiles.sourceFile(ctx.filesDir, id).delete()
        store(ctx).delete(id)
    }

    /** Re-arms the worker if work is waiting (e.g. after the process was killed). */
    fun resumeIfQueued(ctx: Context) {
        ensureRecovered(ctx)
        if (store(ctx).nextQueued() != null && !AudioTranscriptionService.isRunning) startWorker(ctx, emptyList())
    }

    private fun startWorker(ctx: Context, grants: List<Uri>) {
        // Forward the share/picker grant with the start: the worker may import a file well after
        // the originating Activity is gone. Granting on to the service requires this app to hold
        // the grant itself; if it somehow doesn't, the start is retried bare (the process-wide
        // grant the share already gave still applies, and an import that cannot read its file
        // fails as UNREADABLE rather than crashing).
        if (grants.isNotEmpty() && tryStart(ctx, Intent(ctx, AudioTranscriptionService::class.java).also { intent ->
                intent.clipData = ClipData.newRawUri("audio", grants.first()).also { clip ->
                    grants.drop(1).forEach { clip.addItem(ClipData.Item(it)) }
                }
                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            })
        ) return
        tryStart(ctx, Intent(ctx, AudioTranscriptionService::class.java))
    }

    private fun tryStart(ctx: Context, intent: Intent): Boolean = try {
        ContextCompat.startForegroundService(ctx, intent)
        true
    } catch (e: Exception) {
        // SecurityException (no grant to forward) or ForegroundServiceStartNotAllowedException.
        // Queued jobs are picked up the next time the Audio files screen opens.
        Log.w(TAG, "Couldn't start audio worker: ${e.javaClass.simpleName}")
        false
    }
}

/**
 * #285: foreground service that works through the QUEUED audio jobs, one at a time, and posts a
 * notification as each finishes.
 *
 * Type `dataSync`, the type + `FOREGROUND_SERVICE_DATA_SYNC` permission the app already declares
 * for model downloads and dictation hold (ADR-0002): processing a user-initiated payload that may
 * involve a network transfer. `mediaProcessing` (API 35+ only, new permission, minSdk here is 30)
 * and `shortService` (3 min) do not fit, and `microphone` is unrelated to decoding a file. The
 * service starts only from a visible Activity (the share target or the Audio files screen), so the
 * Android 12+ background-start restriction never applies. API 35's dataSync budget (~6 h/day) ends
 * in [onTimeout]: the running job is failed as TIMED_OUT with its audio kept, so it can be retried.
 */
class AudioTranscriptionService : Service() {

    private val main = Handler(Looper.getMainLooper())
    private val running = AtomicBoolean(false)
    @Volatile private var latestStartId = 0
    private var wakeLock: PowerManager.WakeLock? = null
    private var lastNotifyMs = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        AudioJobs.ensureRecovered(this)
        instance = this
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        latestStartId = startId
        // startForeground MUST follow every startForegroundService, even if we stop right away.
        if (!enterForeground(null, 0)) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (running.compareAndSet(false, true)) {
            thread(name = "AudioTranscriptionWorker") { workLoop() }
        }
        return START_NOT_STICKY
    }

    private fun workLoop() {
        acquireWakeLock()
        try {
            val store = AudioJobs.store(this)
            val runner = AudioJobRunner(this, store, { id -> id in cancelRequests }, ::onJobStatus)
            AudioTranscriptionService.runner = runner
            while (true) {
                val job = synchronized(running) {
                    store.nextQueued().also { if (it == null) running.set(false) }
                } ?: break
                currentJobId = job.id
                runner.run(job)
                cancelRequests.remove(job.id)
                currentJobId = null
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Worker crashed: ${t.javaClass.simpleName}")
            running.set(false)
        } finally {
            AudioTranscriptionService.runner = null
            currentJobId = null
            releaseWakeLock()
            main.post { finishService() }
        }
    }

    private fun finishService() {
        // A start that arrived after the worker saw an empty queue re-armed `running`; its own
        // startId then differs from the one we stop with, so stopSelf(id) leaves it alone.
        if (running.get()) return
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        // Progress updates are posted with notify() under the foreground id; make sure none is left
        // behind as a stale "transcribing" notification.
        runCatching { NotificationManagerCompat.from(this).cancel(AudioJobNotifications.ONGOING_ID) }
        stopSelf(latestStartId)
    }

    /** Called from the worker thread on every job change. */
    private fun onJobStatus(job: AudioJob) {
        val kind = audioNoticeFor(job)
        if (kind != null) {
            AudioJobNotifications.postResult(this, job, kind)
            return
        }
        if (!job.status.isActive) return
        val now = android.os.SystemClock.elapsedRealtime()
        // The platform rate-limits notification updates; progress ticks are cosmetic.
        if (job.status == JobStatus.DECODING && now - lastNotifyMs < 1_000) return
        lastNotifyMs = now
        val waiting = AudioJobs.store(this).all().count { it.status == JobStatus.QUEUED && it.id != job.id }
        try {
            NotificationManagerCompat.from(this).notify(AudioJobNotifications.ONGOING_ID, AudioJobNotifications.ongoing(this, job, waiting))
        } catch (_: SecurityException) {
        }
    }

    private fun enterForeground(current: AudioJob?, waiting: Int): Boolean = try {
        AudioJobNotifications.ensureChannels(this)
        ServiceCompat.startForeground(
            this, AudioJobNotifications.ONGOING_ID, AudioJobNotifications.ongoing(this, current, waiting),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0,
        )
        true
    } catch (e: Exception) {
        Log.w(TAG, "Couldn't enter foreground: ${e.javaClass.simpleName}")
        false
    }

    /** API 35: the daily dataSync budget is spent. Keep the audio; the user can retry later. */
    override fun onTimeout(startId: Int, fgsType: Int) = handleTimeout()

    /** API 34. */
    override fun onTimeout(startId: Int) = handleTimeout()

    private fun handleTimeout() {
        Log.w(TAG, "System timed out the audio worker")
        val id = currentJobId
        if (id != null) {
            AudioJobs.store(this).update(id, JobEvent.Failed(JobFailure.TIMED_OUT))?.let { onJobStatus(it) }
            cancelRequests.add(id)
            runner?.abortInFlight()
        }
        // The worker unwinds and stops the service itself; make sure it does even if it is stuck.
        main.postDelayed({
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            runCatching { NotificationManagerCompat.from(this).cancel(AudioJobNotifications.ONGOING_ID) }
            stopSelf()
        }, 3_000)
    }

    private fun acquireWakeLock() {
        runCatching {
            wakeLock = (getSystemService(Context.POWER_SERVICE) as PowerManager)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ramblr:audio-transcription")
                .apply { setReferenceCounted(false); acquire(WAKELOCK_TIMEOUT_MS) }
        }
    }

    private fun releaseWakeLock() {
        runCatching { wakeLock?.takeIf { it.isHeld }?.release() }
    }

    override fun onDestroy() {
        releaseWakeLock()
        if (instance === this) instance = null
        super.onDestroy()
    }

    companion object {
        private const val TAG = "AudioJob"

        /** A backstop for a leaked lock, not a job limit: refreshed on every worker start. */
        private const val WAKELOCK_TIMEOUT_MS = 6L * 60 * 60 * 1000

        private val cancelRequests: MutableSet<String> = ConcurrentHashMap.newKeySet()
        @Volatile private var runner: AudioJobRunner? = null
        @Volatile private var currentJobId: String? = null
        @Volatile private var instance: AudioTranscriptionService? = null

        val isRunning: Boolean get() = instance != null

        /**
         * Cancel [id]. A job the worker has not picked up yet is cancelled directly; the running
         * one is flagged and its in-flight request aborted, and the worker unwinds it.
         */
        fun requestCancel(ctx: Context, id: String) {
            val store = AudioJobs.store(ctx)
            val job = store.get(id) ?: return
            if (!job.status.isActive) return
            if (currentJobId == id) {
                cancelRequests.add(id)
                runner?.abortInFlight()
                return
            }
            val after = store.update(id, JobEvent.Cancel)
            if (after != null && !AudioJobMachine.keepsAudio(after)) {
                File(ctx.filesDir, "audio_jobs/$id.src").delete()
            }
        }
    }
}
