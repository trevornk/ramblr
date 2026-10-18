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
import kotlin.concurrent.thread

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
    private lateinit var historyViewerRowSub: TextView
    private lateinit var qualityLogEnabledSwitch: com.google.android.material.materialswitch.MaterialSwitch
    private lateinit var debugVisibilitySwitch: com.google.android.material.materialswitch.MaterialSwitch
    private lateinit var backupRowSub: TextView

    /** The screens this activity's rows are split across since the settings regroup (see
     *  [EXTRA_SECTION]); no section shows all three, under the old "Data & Logs" title. */
    enum class Section(val title: String) {
        HISTORY("History"),
        BACKUP("Backup & restore"),
        DIAGNOSTICS("Diagnostics"),
    }

    /** SAF file picker for restore (#103): OpenDocument (not GetContent) so the returned Uri
     *  grants a stable, permission-scoped read -- matches the Activity Result API idiom already
     *  used for [OverlayAppearanceActivity]'s pickOverlayIcon. */
    private val pickRestoreFile = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) confirmRestore(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = vertical(0, 0)
        // Notifications and Audio files open the viewer with EXTRA_SHOW_HISTORY alone; they land
        // on the History screen.
        val shown = intent.getStringExtra(EXTRA_SECTION)?.let { name -> Section.entries.firstOrNull { it.name == name } }
            ?: Section.HISTORY.takeIf { intent.getBooleanExtra(EXTRA_SHOW_HISTORY, false) }

        root.addView(TextView(this).apply {
            text = shown?.title ?: "Data & Logs"
            textSize = 32f
            setPadding(dp(24), dp(64), dp(24), dp(24))
        })

        val history = vertical(0, 0)
        val backup = vertical(0, 0)
        val diagnostics = vertical(0, 0)

        val isHistoryEnabled = prefs().getBoolean(KEY_HISTORY_ENABLED, true)
        historyEnabledSwitch = com.google.android.material.materialswitch.MaterialSwitch(this).apply {
            isChecked = isHistoryEnabled
            isClickable = false
        }
        history.addView(settingsRow(
            "Save dictation history",
            "Keeps your past dictations on this phone, so nothing is lost if inserting fails",
            historyEnabledSwitch
        ) { onHistoryToggle(!historyEnabledSwitch.isChecked) })

        // Stays tappable with saving off: turning it off can keep what was saved, and that
        // must remain viewable (and deletable) without turning saving back on.
        val historyGroup = nestedGroup()
        val historyViewerRow = settingsRow("Dictation history", "Find an old dictation and tap it to copy", indent = 0) { showHistory() }
        historyViewerRowSub = historyViewerRow.findViewWithTag("subtitle")
        historyGroup.content.addView(historyViewerRow)
        history.addView(historyGroup.outer)

        // --- Backup / restore (#103) ---
        if (shown == null) backup.addView(sectionHeader("Backup & restore"))
        backup.addView(explainer(
            "Saves history, the benchmark log and settings to a file. API keys are never " +
                "included: they're encrypted with this phone's hardware Keystore, so on a new " +
                "phone you enter them again. The quality log holds your real dictated text, so " +
                "it never leaves this phone either."
        ))

        val backupRow = settingsRow(
            "Backup all data",
            "Save history, logs and settings to a file you can send anywhere"
        ) { createAndShareBackup() }
        backupRowSub = backupRow.findViewWithTag("subtitle")
        backup.addView(backupRow)

        backup.addView(settingsRow(
            "Restore from backup",
            "Load a backup file. Replaces your current history, logs and settings"
        ) { pickRestoreFile.launch(arrayOf("application/zip", "application/octet-stream")) })

        // --- Diagnostics: the debug details toggle (moved from the former Fine-tuning screen)
        // and the benchmark / quality logs ---
        if (shown == null) diagnostics.addView(sectionHeader("Diagnostics"))
        debugVisibilitySwitch = com.google.android.material.materialswitch.MaterialSwitch(this).apply {
            isChecked = DebugVisibilityToggle.isEnabled(this@DataLogsActivity)
            isClickable = false
        }
        diagnostics.addView(settingsRow(
            "Debug / visibility",
            "Shows extra technical details, such as which dictations used a paid cleanup fallback",
            debugVisibilitySwitch
        ) {
            val newVal = !debugVisibilitySwitch.isChecked
            DebugVisibilityToggle.setEnabled(this, newVal)
            debugVisibilitySwitch.isChecked = newVal
        })

        diagnostics.addView(sectionHeader("Diagnostic logs"))

        diagnostics.addView(settingsRow(
            "Share benchmark log",
            "Send speed measurements to any app. Only timings and model ids, no dictated text"
        ) { shareBenchmarkLog() })

        qualityLogEnabledSwitch = com.google.android.material.materialswitch.MaterialSwitch(this).apply {
            isChecked = QualityLogger.isEnabled(this@DataLogsActivity)
            isClickable = false
        }
        diagnostics.addView(settingsRow(
            "Save quality log",
            "Keeps the raw and cleaned text of each dictation on this phone, to compare providers. Off by default",
            qualityLogEnabledSwitch
        ) { onQualityLogToggle(!qualityLogEnabledSwitch.isChecked) })

        // Always tappable, as before the regroup: with nothing saved yet it says so.
        diagnostics.addView(settingsRow(
            "Share quality log",
            "Send the saved text pairs and provider/model ids to any app. Contains your real text"
        ) { shareQualityLog() })

        for ((section, container) in listOf(Section.HISTORY to history, Section.BACKUP to backup, Section.DIAGNOSTICS to diagnostics)) {
            if (shown == null || shown == section) root.addView(container)
        }

        setContentView(ScrollView(this).apply {
            setBackgroundColor(attrColor(android.R.attr.colorBackground))
            addView(root)
        })

        refresh()

        // Launched from MainActivity's top-level History row (#283): open the viewer straight
        // away. Only when history is on -- if it's off the user lands here on the toggle, and
        // nothing is read or shown. Cleared so a rotation doesn't re-pop the dialog.
        if (savedInstanceState == null &&
            intent.getBooleanExtra(EXTRA_SHOW_HISTORY, false) &&
            prefs().getBoolean(KEY_HISTORY_ENABLED, true)
        ) {
            // Closing the viewer deliberately leaves the user here, on the "Save dictation history"
            // switch: finishing straight back to the main screen made that switch unreachable from
            // the History row.
            showHistory()
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        val historyOn = prefs().getBoolean(KEY_HISTORY_ENABLED, true)
        historyEnabledSwitch.isChecked = historyOn
        qualityLogEnabledSwitch.isChecked = QualityLogger.isEnabled(this)
        debugVisibilitySwitch.isChecked = DebugVisibilityToggle.isEnabled(this)
        historyViewerRowSub.text = if (historyOn) "Find an old dictation and tap it to copy"
            else "Saving is off. Shows what was kept from before, if anything"
    }

    // --- Backup / restore (#103) ---

    /** Zips the four backup-eligible files (see [BackupManager]) into a `backups/` subdirectory
     *  of app-private files storage, then hands the result to a share-Intent chooser via the
     *  same FileProvider authority/paths entry [shareBenchmarkLog]/[shareQualityLog] already
     *  use. Deliberately under [android.content.Context.getFilesDir] rather than cacheDir:
     *  res/xml/benchmark_log_file_paths.xml's existing `files-path name="benchmark_log" path="."`
     *  entry exposes the whole filesDir root (confirmed by reading that file), so writing here
     *  needs no new FileProvider paths entry -- cacheDir has no matching `cache-path` entry and
     *  would 404 on share.
     *
     *  Runs off the UI thread (GH #124): zipping benchmark/quality logs and dictation history can
     *  be several MB, and doing that synchronously from a click listener risks a visible freeze
     *  or an ANR. Matches the thread { ... } + runOnUiThread { ... } pattern already used
     *  elsewhere in this app (e.g. OverlayAppearanceActivity's pickOverlayIcon callback).
     *
     *  Also prunes old backups down to [BackupManager.MAX_RETAINED_BACKUPS] (GH #123): previously
     *  a new timestamped zip was written on every tap and only ever deleted if the result was
     *  empty, so non-empty backups accumulated on disk forever. */
    private fun createAndShareBackup() {
        thread {
            val backupsDir = java.io.File(filesDir, "backups").apply { mkdirs() }
            val destination = java.io.File(backupsDir, "ramblr_backup_${System.currentTimeMillis()}.zip")
            val written = try {
                BackupManager.createBackup(this, destination)
            } catch (e: Exception) {
                runOnUiThread { toast("Couldn't create backup: ${e.message}") }
                return@thread
            }
            if (written.isEmpty()) {
                destination.delete()
                runOnUiThread { toast("Nothing to back up yet — dictate a few times first") }
                return@thread
            }
            BackupManager.pruneOldBackups(backupsDir)
            runOnUiThread {
                if (isDestroyed || isFinishing) return@runOnUiThread
                val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", destination)
                val intent = Intent(Intent.ACTION_SEND).apply {
                    type = "application/zip"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                startActivity(Intent.createChooser(intent, "Share backup"))
                backupRowSub.text = "Backed up: ${written.joinToString(", ")}"
            }
        }
    }

    private fun confirmRestore(uri: Uri) {
        android.app.AlertDialog.Builder(this)
            .setTitle("Restore from backup?")
            .setMessage(
                "This overwrites the dictation history, benchmark log, and " +
                    "settings currently on this device with the contents of the selected file. " +
                    "This can't be undone."
            )
            .setPositiveButton("Restore") { _, _ -> performRestore(uri) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** Runs off the UI thread (GH #124): restoring can involve reading/unzipping a multi-MB
     *  file and committing SharedPreferences synchronously, which risks a visible freeze or an
     *  ANR if run directly from the click listener. Matches the thread { ... } +
     *  runOnUiThread { ... } pattern used elsewhere in this app. */
    private fun performRestore(uri: Uri) {
        thread {
            val result = try {
                contentResolver.openInputStream(uri)?.use { input -> BackupManager.restoreBackup(this, input) }
            } catch (e: Exception) {
                runOnUiThread { toast("Couldn't restore: ${e.message}") }
                return@thread
            }
            if (result == null) {
                runOnUiThread { toast("Couldn't open that file") }
                return@thread
            }
            if (result.restoredEntries.isEmpty()) {
                runOnUiThread { toast("That file didn't contain any recognized backup data") }
                return@thread
            }
            runOnUiThread {
                if (isDestroyed || isFinishing) return@runOnUiThread
                toast("Restored: ${result.restoredEntries.joinToString(", ")}")
                refresh()
            }
        }
    }

    // --- Quality log opt-in (#191) ---

    /** Mirrors [onHistoryToggle]'s shape: enabling is immediate, disabling offers to also delete
     *  the transcript text already on disk — the log stores verbatim dictations, so turning the
     *  feature off is exactly when a user is most likely to want the existing file gone too. */
    private fun onQualityLogToggle(enabled: Boolean) {
        if (enabled) {
            QualityLogger.setEnabled(this, true)
            refresh()
            return
        }
        android.app.AlertDialog.Builder(this)
            .setTitle("Turn off quality logging")
            .setMessage("New dictations won't be added to the quality log. Delete the transcript text already saved in it?")
            .setPositiveButton("Delete log") { _, _ ->
                QualityLogger.setEnabled(this, false)
                QualityLogger.logFile(this).delete()
                refresh()
            }
            .setNegativeButton("Keep log") { _, _ ->
                QualityLogger.setEnabled(this, false)
                refresh()
            }
            .setOnCancelListener { refresh() }
            .show()
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
            // "Clear all" already dismissed the viewer; backing out returns to it rather
            // than leaving the user on the History screen.
            .setNegativeButton("Cancel") { _, _ -> showHistory() }
            .setOnCancelListener { showHistory() }
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

        /** Boolean extra: open the dictation history viewer on launch (#283). */
        const val EXTRA_SHOW_HISTORY = "show_history"

        /** [Section] name to show; absent shows every section (or History, with
         *  [EXTRA_SHOW_HISTORY]). */
        const val EXTRA_SECTION = "com.trevornk.ramblr.extra.DATA_SECTION"

        fun intent(context: android.content.Context, section: Section): Intent =
            Intent(context, DataLogsActivity::class.java).putExtra(EXTRA_SECTION, section.name)

        /** Subtitle for MainActivity's top-level History row (#283). */
        fun historyRowSubtitle(context: android.content.Context): String {
            val enabled = context.getSharedPreferences("ramblr", MODE_PRIVATE)
                .getBoolean(KEY_HISTORY_ENABLED, true)
            // Don't touch the history file at all when the feature is off.
            val count = if (enabled) DictationHistoryStore.forContext(context).all().size else 0
            return historyMainRowSubtitleText(enabled, count)
        }

        /** Category subtitle for the former Data & Logs row (#104). */
        fun subtitle(context: android.content.Context): String {
            val enabled = context.getSharedPreferences("ramblr", MODE_PRIVATE)
                .getBoolean(KEY_HISTORY_ENABLED, true)
            return if (enabled) "History on · backup, benchmark & quality logs" else "History off · backup, benchmark & quality logs"
        }

        /** Subtitle for MainActivity's Diagnostics row: what's switched on, else what's there. */
        fun diagnosticsSubtitle(context: android.content.Context): String {
            val on = listOfNotNull(
                "debug details on".takeIf { DebugVisibilityToggle.isEnabled(context) },
                "quality log on".takeIf { QualityLogger.isEnabled(context) },
            )
            return if (on.isEmpty()) "Debug details, benchmark and quality logs"
            else on.joinToString(" · ").replaceFirstChar { it.uppercase() } + " · benchmark log"
        }
    }
}
