package com.trevornk.ramblr

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat

/**
 * "Audio files" screen (#285): transcribe a file from the phone, or record a note now and transcribe
 * it later, and see what is queued / running / done. Same shell as the other Settings sub-screens
 * ([BaseSettingsActivity] rows, plain views). The list refreshes by polling the persisted job
 * store once a second while visible, which is cheap (a handful of JSON lines) and keeps the
 * screen correct even when the worker runs in another component.
 */
class AudioFilesActivity : BaseSettingsActivity() {

    private lateinit var listContainer: LinearLayout
    private lateinit var recordRowTitle: TextView
    private lateinit var recordRowSub: TextView
    private val ui = Handler(Looper.getMainLooper())
    private var pendingAfterPermission: (() -> Unit)? = null
    private var lastSavedCount = AudioRecorderService.savedCount

    private val pickAudio = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) {
            val added = AudioJobs.enqueueImported(this, uris, getString(R.string.audio_files_shared_name))
            if (added > 0) toast(getString(R.string.audio_files_added))
            if (uris.size > added) toast(getString(R.string.audio_files_share_too_many, AudioJobs.MAX_FILES_PER_SHARE))
            refresh()
        }
    }

    private val askPermissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
        // Notifications are a courtesy (the work runs either way); only the mic gates recording.
        val proceed = pendingAfterPermission
        pendingAfterPermission = null
        if (proceed != null) proceed()
        else if (grants[Manifest.permission.RECORD_AUDIO] == false) toast(getString(R.string.audio_files_mic_denied))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AudioJobs.ensureRecovered(this)

        val root = vertical(0, 0)
        root.addView(TextView(this).apply {
            text = getString(R.string.audio_files_title)
            textSize = 32f
            setPadding(dp(24), dp(64), dp(24), dp(24))
        })
        root.addView(TextView(this).apply {
            text = getString(R.string.audio_files_intro)
            textSize = 14f
            setTextColor(attrColor(android.R.attr.textColorSecondary))
            setPadding(dp(24), 0, dp(24), dp(8))
        })

        root.addView(settingsRow(getString(R.string.audio_files_pick), getString(R.string.audio_files_pick_sub)) {
            ensureNotificationPermission {
                try {
                    pickAudio.launch(arrayOf("audio/*", "application/ogg"))
                } catch (_: android.content.ActivityNotFoundException) {
                    toast(getString(R.string.audio_files_picker_none))
                }
            }
        })

        val recordRow = settingsRow(getString(R.string.audio_files_record), getString(R.string.audio_files_record_sub)) { onRecordTapped() }
        recordRowTitle = recordRow.findViewWithTag("title")
        recordRowSub = recordRow.findViewWithTag("subtitle")
        root.addView(recordRow)

        root.addView(sectionHeader(getString(R.string.audio_files_section_jobs)))
        listContainer = vertical(0, 0)
        root.addView(listContainer)

        setContentView(ScrollView(this).apply {
            setBackgroundColor(attrColor(android.R.attr.colorBackground))
            addView(root)
        })
        refresh()
    }

    private val tick = object : Runnable {
        override fun run() {
            refresh()
            ui.postDelayed(this, 1_000)
        }
    }

    override fun onResume() {
        super.onResume()
        AudioJobs.resumeIfQueued(this)
        ui.post(tick)
    }

    override fun onPause() {
        ui.removeCallbacks(tick)
        super.onPause()
    }

    // ---- actions --------------------------------------------------------------------------------

    private fun hasPerm(p: String) = ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

    /** Asks for the notification grant once, then runs [then] whatever the answer is. */
    private fun ensureNotificationPermission(then: () -> Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !hasPerm(Manifest.permission.POST_NOTIFICATIONS)) {
            pendingAfterPermission = then
            askPermissions.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS))
        } else {
            then()
        }
    }

    private fun onRecordTapped() {
        if (AudioRecorderService.state.recording) {
            AudioRecorderService.stop(this)
            ui.postDelayed({ refresh() }, 600)
            return
        }
        val needed = buildList {
            if (!hasPerm(Manifest.permission.RECORD_AUDIO)) add(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !hasPerm(Manifest.permission.POST_NOTIFICATIONS)) add(Manifest.permission.POST_NOTIFICATIONS)
        }
        val begin: () -> Unit = {
            if (!hasPerm(Manifest.permission.RECORD_AUDIO)) toast(getString(R.string.audio_files_mic_denied))
            else try {
                AudioRecorderService.start(this)
                ui.postDelayed({ refresh() }, 600)
            } catch (_: Exception) {
                toast(getString(R.string.audio_files_record_failed))
            }
        }
        if (needed.isEmpty()) begin() else { pendingAfterPermission = begin; askPermissions.launch(needed.toTypedArray()) }
    }

    private fun onJobTapped(job: AudioJob) {
        val b = android.app.AlertDialog.Builder(this).setTitle(job.displayName)
        when {
            job.status == JobStatus.SAVED -> b
                .setPositiveButton(R.string.audio_action_transcribe) { _, _ -> ensureNotificationPermission { AudioJobs.transcribe(this, job.id); refresh() } }
                .setNegativeButton(R.string.audio_action_delete) { _, _ -> confirmDelete(job) }
            job.status.isActive -> b
                .setPositiveButton(R.string.audio_action_cancel) { _, _ -> AudioJobs.cancel(this, job.id); refresh() }
                .setNegativeButton(R.string.audio_action_close, null)
            job.status == JobStatus.DONE -> {
                val ts = job.historyTimestamp
                if (ts != null) {
                    b.setPositiveButton(R.string.audio_action_copy) { _, _ -> copyResult(ts) }
                    b.setNeutralButton(R.string.audio_action_open_history) { _, _ ->
                        startActivity(Intent(this, DataLogsActivity::class.java).putExtra(DataLogsActivity.EXTRA_SHOW_HISTORY, true))
                    }
                }
                b.setNegativeButton(R.string.audio_action_delete) { _, _ -> AudioJobs.delete(this, job.id); refresh() }
            }
            else -> {
                if (AudioJobMachine.canRetry(job)) {
                    b.setPositiveButton(R.string.audio_action_retry) { _, _ -> ensureNotificationPermission { AudioJobs.retry(this, job.id); refresh() } }
                }
                b.setNegativeButton(R.string.audio_action_delete) { _, _ -> confirmDelete(job) }
            }
        }
        b.show()
    }

    private fun copyResult(historyTimestamp: Long) {
        val e = DictationHistoryStore.forContext(this).all().firstOrNull { it.timestamp == historyTimestamp }
        if (e == null) {
            toast(getString(R.string.audio_files_not_found))
        } else {
            ClipboardUtil.copy(this, e.cleanedText ?: e.rawText)
            toast(getString(R.string.audio_files_copied))
        }
    }

    private fun confirmDelete(job: AudioJob) {
        android.app.AlertDialog.Builder(this)
            .setTitle(R.string.audio_delete_title)
            .setMessage(R.string.audio_delete_message)
            .setPositiveButton(R.string.audio_action_delete) { _, _ -> AudioJobs.delete(this, job.id); refresh() }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    // ---- rendering ------------------------------------------------------------------------------

    private fun refresh() {
        val rec = AudioRecorderService.state
        recordRowTitle.text = getString(if (rec.recording) R.string.audio_files_recording_now else R.string.audio_files_record)
        recordRowSub.text = getString(R.string.audio_files_record_sub)
        if (AudioRecorderService.failedToStart) {
            AudioRecorderService.failedToStart = false
            toast(getString(R.string.audio_files_record_failed))
        }
        if (AudioRecorderService.savedCount != lastSavedCount) {
            lastSavedCount = AudioRecorderService.savedCount
            toast(getString(R.string.audio_files_saved))
        }

        val jobs = AudioJobs.store(this).all().sortedByDescending { it.createdAt }
        listContainer.removeAllViews()
        if (jobs.isEmpty()) {
            listContainer.addView(TextView(this).apply {
                text = getString(R.string.audio_files_empty)
                setTextColor(attrColor(android.R.attr.textColorSecondary))
                setPadding(dp(24), dp(8), dp(24), dp(16))
            })
            return
        }
        jobs.forEach { j ->
            listContainer.addView(settingsRow(j.displayName, statusText(this, j)) { onJobTapped(j) }.also {
                it.findViewWithTag<TextView>("subtitle").maxLines = 2
            })
        }
    }

    companion object {
        fun statusText(ctx: Context, j: AudioJob): String = when (j.status) {
            JobStatus.SAVED -> ctx.getString(R.string.audio_status_saved)
            JobStatus.QUEUED -> ctx.getString(R.string.audio_status_queued)
            JobStatus.IMPORTING -> ctx.getString(R.string.audio_status_importing)
            JobStatus.DECODING -> ctx.getString(R.string.audio_status_decoding, j.decodePercent)
            JobStatus.TRANSCRIBING -> ctx.getString(R.string.audio_status_transcribing, j.chunkIndex + 1, j.chunkTotal.coerceAtLeast(1))
            JobStatus.CLEANING -> ctx.getString(R.string.audio_status_cleaning)
            JobStatus.DONE -> ctx.getString(
                when {
                    j.copiedInsteadOfSaved -> R.string.audio_status_done_copied
                    j.cleanupFailed -> R.string.audio_status_done_cleanup_failed
                    else -> R.string.audio_status_done
                }
            )
            JobStatus.FAILED -> ctx.getString(R.string.audio_status_failed, AudioJobNotifications.failureLabel(ctx, j.failure))
            JobStatus.CANCELLED -> ctx.getString(R.string.audio_status_cancelled)
        }

        /** Main-screen row subtitle. */
        fun rowSubtitle(ctx: Context): String {
            val jobs = AudioJobs.store(ctx).all()
            val active = jobs.count { it.status.isActive }
            val saved = jobs.count { it.status == JobStatus.SAVED }
            return if (active == 0 && saved == 0) ctx.getString(R.string.audio_files_row_idle)
            else ctx.getString(R.string.audio_files_row_status, active, saved)
        }
    }
}
