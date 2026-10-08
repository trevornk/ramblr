package com.trevornk.ramblr

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.MediaRecorder
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import androidx.core.app.ServiceCompat
import java.io.File
import java.util.UUID

/**
 * #285: "record now, transcribe later". Captures microphone audio to an AAC `.m4a` in app-private
 * storage and, on stop, files it as a SAVED [AudioJob] the user transcribes with one tap.
 *
 * Foreground service of type `microphone` (Android 14+ requires the type and the matching
 * `FOREGROUND_SERVICE_MICROPHONE` permission, both declared in the manifest) so a recording keeps
 * running when the user leaves the app. It is only ever started from a visible Activity, which is
 * what the platform requires for a while-in-use type. Holds [ProcessDictationSessionLeaseRegistry]
 * for the duration so live dictation and a note can never fight over the microphone.
 *
 * Nothing leaves the device and nothing is transcribed here; no audio content is logged.
 */
class AudioRecorderService : Service() {

    private var recorder: MediaRecorder? = null
    private var file: File? = null
    private var jobId: String? = null
    private var startedElapsed = 0L
    private var startedWall = 0L
    private var lease: DictationSessionLease? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                if (recorder != null) finishRecording(save = true)
                else shutDown() // a stale Stop for a recording that no longer exists
            }
            else -> startRecording()
        }
        return START_NOT_STICKY
    }

    private fun enterForeground(): Boolean = try {
        AudioJobNotifications.ensureChannels(this)
        ServiceCompat.startForeground(
            this, AudioJobNotifications.RECORDING_ID,
            AudioJobNotifications.recording(this, if (startedWall == 0L) System.currentTimeMillis() else startedWall),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0,
        )
        true
    } catch (e: Exception) {
        Log.w(TAG, "Couldn't enter foreground for recording: ${e.javaClass.simpleName}")
        false
    }

    private fun startRecording() {
        if (recorder != null) { enterForeground(); return }
        startedWall = System.currentTimeMillis()
        if (!enterForeground()) { fail(); return }
        val acquired = ProcessDictationSessionLeaseRegistry.tryAcquire()
        if (acquired == null) { fail(); return } // live dictation owns the mic
        lease = acquired

        val id = UUID.randomUUID().toString()
        val out = AudioJobFiles.sourceFile(filesDir, id)
        val r = try {
            (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) MediaRecorder(this) else @Suppress("DEPRECATION") MediaRecorder()).apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                // 16 kHz is what every recognizer here resamples to anyway; 64 kbps is ~29 MB/hour.
                setAudioSamplingRate(SAMPLE_RATE)
                setAudioChannels(1)
                setAudioEncodingBitRate(BIT_RATE)
                setMaxFileSize(MAX_FILE_BYTES)
                setOnInfoListener { _, what, _ ->
                    if (what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_FILESIZE_REACHED) finishRecording(save = true)
                }
                setOnErrorListener { _, _, _ -> finishRecording(save = false) }
                setOutputFile(out.absolutePath)
                prepare()
                start()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Couldn't start recorder: ${e.javaClass.simpleName}")
            out.delete()
            fail()
            return
        }
        recorder = r
        file = out
        jobId = id
        startedElapsed = SystemClock.elapsedRealtime()
        state = RecorderState(true, startedWall)
    }

    private fun fail() {
        failedToStart = true
        shutDown()
    }

    private fun finishRecording(save: Boolean) {
        val r = recorder ?: return
        recorder = null
        val out = file
        val id = jobId
        val elapsed = SystemClock.elapsedRealtime() - startedElapsed
        var ok = save
        try {
            r.stop()
        } catch (e: Exception) {
            // stop() throws if nothing was captured (a tap-tap): there is no note.
            ok = false
        } finally {
            runCatching { r.release() }
        }
        if (ok && out != null && id != null && out.length() > 0 && elapsed >= MIN_NOTE_MS) {
            AudioJobs.store(this).upsert(
                AudioJob(
                    id = id,
                    displayName = getString(R.string.audio_files_recorded_name, android.text.format.DateFormat.format("MMM d, h:mm a", startedWall)),
                    kind = JobKind.RECORDED,
                    status = JobStatus.SAVED,
                    createdAt = startedWall,
                    hasLocalAudio = true,
                    durationMs = elapsed,
                )
            )
            savedCount++
        } else {
            out?.delete()
        }
        shutDown()
    }

    private fun shutDown() {
        lease?.let { ProcessDictationSessionLeaseRegistry.release(it) }
        lease = null
        state = RecorderState(false, 0L)
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        // Destroyed while recording (system kill / task removal): keep what was captured so far
        // if the container can still be finalized, otherwise drop it. Never leave the mic open.
        if (recorder != null) finishRecording(save = true)
        lease?.let { ProcessDictationSessionLeaseRegistry.release(it) }
        lease = null
        state = RecorderState(false, 0L)
        super.onDestroy()
    }

    data class RecorderState(val recording: Boolean, val startedWallMs: Long)

    companion object {
        private const val TAG = "AudioRecorder"
        const val ACTION_START = "com.trevornk.ramblr.action.AUDIO_RECORD_START"
        const val ACTION_STOP = "com.trevornk.ramblr.action.AUDIO_RECORD_STOP"
        private const val SAMPLE_RATE = 16_000
        private const val BIT_RATE = 64_000
        private const val MAX_FILE_BYTES = 1_000L * 1024 * 1024
        private const val MIN_NOTE_MS = 500L

        @Volatile var state = RecorderState(false, 0L)
            private set

        /** Set when the last start attempt failed (mic busy, recorder error); read-and-cleared by the UI. */
        @Volatile var failedToStart = false

        @Volatile var savedCount = 0

        fun start(ctx: Context) {
            failedToStart = false
            androidx.core.content.ContextCompat.startForegroundService(
                ctx, Intent(ctx, AudioRecorderService::class.java).setAction(ACTION_START),
            )
        }

        fun stop(ctx: Context) {
            ctx.startService(Intent(ctx, AudioRecorderService::class.java).setAction(ACTION_STOP))
        }
    }
}
