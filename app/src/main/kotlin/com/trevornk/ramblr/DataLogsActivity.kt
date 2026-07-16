package com.trevornk.ramblr

import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider

/**
 * "Data & Logs" settings screen (#104 restructure): dictation history toggle + viewer,
 * backup/restore (#103), share benchmark log, share quality log -- everything on the previous
 * single long AdvancedActivity list that reads or exports on-device data files, as opposed to
 * behavior toggles ([BehaviorActivity]) or overlay looks ([OverlayAppearanceActivity]).
 *
 * The history toggle/viewer/share rows are moved verbatim from AdvancedActivity (same prefs key,
 * same DictationHistoryStore/BenchmarkLogger/QualityLogger calls, same FileProvider pattern) --
 * only the backup/restore rows below are new (#103).
 */
class DataLogsActivity : BaseSettingsActivity() {

    private lateinit var historyEnabledSwitch: com.google.android.material.materialswitch.MaterialSwitch
    private lateinit var backupRowSub: TextView

    /** SAF file picker for restore (#103): OpenDocument (not GetContent) so the returned Uri
     *  grants a stable, permission-scoped read -- matches the Activity Result API idiom already
     *  used for [OverlayAppearanceActivity]'s pickOverlayIcon. */
    private val pickRestoreFile = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) confirmRestore(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = vertical(0, 0)

        root.addView(TextView(this).apply {
            text = "Data & Logs"
            textSize = 32f
            setPadding(dp(24), dp(64), dp(24), dp(24))
        })

        val isHistoryEnabled = prefs().getBoolean(KEY_HISTORY_ENABLED, true)
        historyEnabledSwitch = com.google.android.material.materialswitch.MaterialSwitch(this).apply {
            isChecked = isHistoryEnabled
            isClickable = false
        }
        root.addView(settingsRow(
            "Save dictation history",
            "Keeps past transcripts on-device so a failed insertion isn't lost",
            historyEnabledSwitch
        ) { onHistoryToggle(!historyEnabledSwitch.isChecked) })

        val historyGroup = nestedGroup()
        historyGroup.content.addView(
            settingsRow("Dictation history", "Recover past transcripts, tap to copy", indent = 0) { showHistory() }
        )
        root.addView(historyGroup.outer)

        // --- Backup / restore (#103) ---
        root.addView(sectionHeader("Backup & restore"))
        root.addView(TextView(this).apply {
            text = "Saves dictation history, benchmark log, quality log, and settings to a file " +
                "you choose where to send. API keys are never included -- they're encrypted to " +
                "this device's hardware Keystore and can't be restored elsewhere, so you'll " +
                "re-enter them once on a new device."
            textSize = 14f
            setTextColor(attrColor(android.R.attr.textColorSecondary))
            setPadding(dp(24), 0, dp(24), dp(8))
        })

        val backupRow = settingsRow(
            "Backup all data",
            "Share a backup file containing history, logs, and settings"
        ) { createAndShareBackup() }
        backupRowSub = backupRow.findViewWithTag("subtitle")
        root.addView(backupRow)

        root.addView(settingsRow(
            "Restore from backup",
            "Pick a backup file to restore. This overwrites existing history, logs, and settings"
        ) { pickRestoreFile.launch(arrayOf("application/zip", "application/octet-stream")) })

        // --- Benchmark / quality logs ---
        root.addView(sectionHeader("Diagnostic logs"))

        root.addView(settingsRow(
            "Share benchmark log",
            "Send the on-device transcription/cleanup benchmark log (timings and model ids only, no dictation text) to any app"
        ) { shareBenchmarkLog() })

        root.addView(settingsRow(
            "Share quality log",
            "Send the on-device raw/cleaned transcript pairs (real text) and provider/model ids to any app"
        ) { shareQualityLog() })

        setContentView(ScrollView(this).apply {
            setBackgroundColor(attrColor(android.R.attr.colorBackground))
            addView(root)
        })

        refresh()
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        historyEnabledSwitch.isChecked = prefs().getBoolean(KEY_HISTORY_ENABLED, true)
    }

    // --- Backup / restore (#103) ---

    /** Zips the four backup-eligible files (see [BackupManager]) into a `backups/` subdirectory
     *  of app-private files storage, then hands the result to a share-Intent chooser via the
     *  same FileProvider authority/paths entry [shareBenchmarkLog]/[shareQualityLog] already
     *  use. Deliberately under [android.content.Context.getFilesDir] rather than cacheDir:
     *  res/xml/benchmark_log_file_paths.xml's existing `files-path name="benchmark_log" path="."`
     *  entry exposes the whole filesDir root (confirmed by reading that file), so writing here
     *  needs no new FileProvider paths entry -- cacheDir has no matching `cache-path` entry and
     *  would 404 on share. */
    private fun createAndShareBackup() {
        val backupsDir = java.io.File(filesDir, "backups").apply { mkdirs() }
        val destination = java.io.File(backupsDir, "ramblr_backup_${System.currentTimeMillis()}.zip")
        val written = try {
            BackupManager.createBackup(this, destination)
        } catch (e: Exception) {
            toast("Couldn't create backup: ${e.message}")
            return
        }
        if (written.isEmpty()) {
            destination.delete()
            toast("Nothing to back up yet — dictate a few times first")
            return
        }
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", destination)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "application/zip"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(intent, "Share backup"))
        backupRowSub.text = "Backed up: ${written.joinToString(", ")}"
    }

    private fun confirmRestore(uri: Uri) {
        android.app.AlertDialog.Builder(this)
            .setTitle("Restore from backup?")
            .setMessage(
                "This overwrites the dictation history, benchmark log, quality log, and " +
                    "settings currently on this device with the contents of the selected file. " +
                    "This can't be undone."
            )
            .setPositiveButton("Restore") { _, _ -> performRestore(uri) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun performRestore(uri: Uri) {
        val result = try {
            contentResolver.openInputStream(uri)?.use { input -> BackupManager.restoreBackup(this, input) }
        } catch (e: Exception) {
            toast("Couldn't restore: ${e.message}")
            return
        }
        if (result == null) {
            toast("Couldn't open that file")
            return
        }
        if (result.restoredEntries.isEmpty()) {
            toast("That file didn't contain any recognized backup data")
            return
        }
        toast("Restored: ${result.restoredEntries.joinToString(", ")}")
        refresh()
    }

    // --- Dictation History (#25) ---

    private fun onHistoryToggle(enabled: Boolean) {
        if (enabled) {
            prefs().edit().putBoolean(KEY_HISTORY_ENABLED, true).apply()
            refresh()
            return
        }
        android.app.AlertDialog.Builder(this)
            .setTitle("Turn off dictation history")
            .setMessage("New dictations won't be saved. Clear the transcripts already saved on this device?")
            .setPositiveButton("Clear history") { _, _ ->
                prefs().edit().putBoolean(KEY_HISTORY_ENABLED, false).apply()
                DictationHistoryStore.forContext(this).clear()
                refresh()
            }
            .setNegativeButton("Keep history") { _, _ ->
                prefs().edit().putBoolean(KEY_HISTORY_ENABLED, false).apply()
                refresh()
            }
            .setOnCancelListener { refresh() }
            .show()
    }

    private fun showHistory() {
        val store = DictationHistoryStore.forContext(this)
        val entries = store.all()

        lateinit var dialog: android.app.AlertDialog
        val onChanged = { dialog.dismiss(); showHistory() }

        val list = vertical(0)
        if (entries.isEmpty()) {
            list.addView(TextView(this).apply {
                text = "No dictations yet"
                setPadding(dp(24), dp(16), dp(24), dp(16))
                setTextColor(attrColor(android.R.attr.textColorSecondary))
            })
        } else {
            entries.forEach { list.addView(historyRow(store, it, onChanged)) }
        }

        val builder = android.app.AlertDialog.Builder(this)
            .setTitle("Dictation history")
            .setView(ScrollView(this).apply { addView(list) })
            .setNegativeButton("Close", null)
        if (entries.isNotEmpty()) {
            builder.setNeutralButton("Clear all") { _, _ -> confirmClearAllHistory(store, onChanged) }
        }
        dialog = builder.show()
    }

    private fun confirmClearAllHistory(store: DictationHistoryStore, onCleared: () -> Unit) {
        android.app.AlertDialog.Builder(this)
            .setTitle("Clear all history?")
            .setMessage("Removes every saved transcript from this device. This can't be undone.")
            .setPositiveButton("Clear") { _, _ ->
                store.clear()
                toast("History cleared")
                onCleared()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun historyRow(store: DictationHistoryStore, entry: DictationHistoryEntry, onDeleted: () -> Unit): View {
        val text = entry.cleanedText ?: entry.rawText
        val badge = paidFallbackBadgeOrNull(entry)
        val row = settingsRow(historyTimestampFormat.format(java.util.Date(entry.timestamp)), text, badge) {
            ClipboardUtil.copy(this, text)
            toast("Copied to clipboard")
        }
        row.setOnLongClickListener {
            confirmDeleteHistoryEntry(store, entry, onDeleted)
            true
        }
        val subtitle = row.findViewWithTag<TextView>("subtitle")
        subtitle.maxLines = 2
        subtitle.ellipsize = android.text.TextUtils.TruncateAt.END
        return row
    }

    private fun paidFallbackBadgeOrNull(entry: DictationHistoryEntry): TextView? {
        if (!shouldShowPaidFallbackBadge(DebugVisibilityToggle.isEnabled(this), entry)) return null
        val group = entry.paidFallbackGroup ?: return null
        return TextView(this).apply {
            text = "Paid fallback · ${groupLabel(group)}"
            textSize = 11f
            setTextColor(0xFFFFFFFF.toInt())
            setPadding(dp(8), dp(4), dp(8), dp(4))
            background = GradientDrawable().apply {
                cornerRadius = dp(10).toFloat()
                setColor(0xFFB05A00.toInt())
            }
        }
    }

    private fun confirmDeleteHistoryEntry(store: DictationHistoryStore, entry: DictationHistoryEntry, onDeleted: () -> Unit) {
        android.app.AlertDialog.Builder(this)
            .setTitle("Delete this transcript?")
            .setPositiveButton("Delete") { _, _ ->
                store.delete(entry.timestamp)
                toast("Deleted")
                onDeleted()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private val historyTimestampFormat by lazy {
        java.text.SimpleDateFormat("MMM d, h:mm a", java.util.Locale.getDefault())
    }

    private fun groupLabel(group: CleanupStepGroup) = when (group) {
        CleanupStepGroup.OMNIROUTE -> "OmniRoute"
        CleanupStepGroup.OPENAI_DIRECT -> "Direct OpenAI"
        CleanupStepGroup.ANTHROPIC_DIRECT -> "Direct Anthropic"
        CleanupStepGroup.GEMINI_DIRECT -> "Direct Gemini"
        CleanupStepGroup.LOCAL_LLM -> "Local (on-device)"
    }

    /** Shares files/benchmark_log.jsonl (#100) via a standard chooser Intent, using a
     *  [androidx.core.content.FileProvider] content:// URI (see the "benchmark_log" provider
     *  section of AndroidManifest.xml + res/xml/benchmark_log_file_paths.xml) so no storage
     *  permission is needed and the target app never gets a raw file:// path into app-private
     *  storage. */
    private fun shareBenchmarkLog() {
        val file = BenchmarkLogger.logFile(this)
        if (!file.exists() || file.length() == 0L) {
            toast("No benchmark log yet — dictate a few times first")
            return
        }
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "application/jsonl"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(intent, "Share benchmark log"))
    }

    /** Shares files/quality_log.jsonl (#102) via the same FileProvider pattern as
     *  [shareBenchmarkLog]. */
    private fun shareQualityLog() {
        val file = QualityLogger.logFile(this)
        if (!file.exists() || file.length() == 0L) {
            toast("No quality log yet — dictate a few times first")
            return
        }
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "application/jsonl"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(intent, "Share quality log"))
    }

    companion object {
        private const val KEY_HISTORY_ENABLED = "dictation_history_enabled"

        /** Category subtitle for AdvancedActivity's Data & Logs row (#104). */
        fun subtitle(context: android.content.Context): String {
            val enabled = context.getSharedPreferences("ramblr", MODE_PRIVATE)
                .getBoolean(KEY_HISTORY_ENABLED, true)
            return if (enabled) "History on · backup, benchmark & quality logs" else "History off · backup, benchmark & quality logs"
        }
    }
}
