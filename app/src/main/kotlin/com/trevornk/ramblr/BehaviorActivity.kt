package com.trevornk.ramblr

import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.google.android.material.materialswitch.MaterialSwitch

/**
 * "Behavior" settings screen (#104 restructure): debug/visibility toggle, per-app persona,
 * hide-icon toggle, auto-hide (toggle + delay + peek size), raw-text-retry, and personal
 * vocabulary -- rows previously spread across AdvancedActivity's single long list, grouped here
 * because they all shape how dictation *behaves* day to day, as opposed to how the overlay
 * *looks* ([OverlayAppearanceActivity]) or on-device *data* ([DataLogsActivity]). Moved verbatim
 * -- same prefs keys, same toggle logic, same dialogs -- this is a pure UI reorganization.
 */
class BehaviorActivity : BaseSettingsActivity() {

    private lateinit var debugVisibilitySwitch: MaterialSwitch
    private lateinit var perAppPersonaSwitch: MaterialSwitch
    private lateinit var hideIconSwitch: MaterialSwitch
    private lateinit var autoPeekSwitch: MaterialSwitch
    private lateinit var autoPeekDelayRow: LinearLayout
    private lateinit var peekSizeRow: LinearLayout
    private lateinit var singleTapRestoreSwitch: MaterialSwitch
    private lateinit var rawTextRetrySwitch: MaterialSwitch
    private lateinit var silenceAutoStopSwitch: MaterialSwitch
    private lateinit var silenceAutoStopRow: LinearLayout
    private lateinit var silenceAutoStopThresholdRow: LinearLayout
    /** Latest WorkManager state for the on-demand Silero VAD model download (#108), used to avoid
     *  double-enqueuing while one is already pending/running. */
    private var silenceAutoStopVadDownloadState: WorkInfo.State? = null
    /** True from the moment the user turns the toggle on with no model installed yet, until the
     *  triggered download resolves (success or failure) -- see [onSilenceAutoStopToggled]. Lets
     *  [onSilenceAutoStopVadWorkInfos] tell "a download this toggle triggered just finished" apart
     *  from an unrelated download (or one already in flight before this screen opened). */
    private var silenceAutoStopPendingEnable = false
    private lateinit var vocabularyRowSub: TextView
    // Built unconditionally and shown/hidden in refresh() (#L16): building it only when hidden at
    // onCreate meant hiding the icon via the overlay while this screen was paused left no way back
    // on resume, and it relied on recreate() as a refresh hammer.
    private lateinit var iconHiddenRow: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = vertical(0, 0)

        root.addView(TextView(this).apply {
            text = "Behavior"
            textSize = 32f
            setPadding(dp(24), dp(64), dp(24), dp(24))
        })

        debugVisibilitySwitch = MaterialSwitch(this).apply {
            isChecked = DebugVisibilityToggle.isEnabled(this@BehaviorActivity)
            isClickable = false
        }
        root.addView(settingsRow(
            "Debug / visibility",
            "Shows extra under-the-hood detail, like which dictations used a paid cleanup fallback",
            debugVisibilitySwitch
        ) {
            val newVal = !debugVisibilitySwitch.isChecked
            DebugVisibilityToggle.setEnabled(this, newVal)
            debugVisibilitySwitch.isChecked = newVal
        })

        perAppPersonaSwitch = MaterialSwitch(this).apply {
            isChecked = PerAppPersonaToggle.isEnabled(this@BehaviorActivity)
            isClickable = false
        }
        root.addView(settingsRow(
            "Remember cleanup style per app",
            "Auto-selects the last style you picked for each app instead of always using your global default",
            perAppPersonaSwitch
        ) {
            val newVal = !perAppPersonaSwitch.isChecked
            PerAppPersonaToggle.setEnabled(this, newVal)
            perAppPersonaSwitch.isChecked = newVal
        })

        hideIconSwitch = MaterialSwitch(this).apply {
            isChecked = HideIconToggle.isEnabled(this@BehaviorActivity)
            isClickable = false
        }
        root.addView(settingsRow(
            "Allow hiding the floating icon",
            "Adds a 'Hide icon' option to the long-press menu, so you can fully hide the icon and bring it back from a notification",
            hideIconSwitch
        ) {
            val newVal = !hideIconSwitch.isChecked
            HideIconToggle.setEnabled(this, newVal)
            hideIconSwitch.isChecked = newVal
        })

        autoPeekSwitch = MaterialSwitch(this).apply {
            isChecked = AutoPeekToggle.isEnabled(this@BehaviorActivity)
            isClickable = false
        }
        root.addView(settingsRow(
            "Auto-hide icon when idle",
            "Slides the icon toward the screen edge after a few seconds of inactivity. Turn off to keep it fully visible at all times",
            autoPeekSwitch
        ) {
            val newVal = !autoPeekSwitch.isChecked
            AutoPeekToggle.setEnabled(this, newVal)
            autoPeekSwitch.isChecked = newVal
            if (!newVal) WhisperAccessibilityService.instance?.restoreFromPeekIfPeeked()
        })

        autoPeekDelayRow = settingsRow(
            "Auto-hide delay",
            autoPeekDelaySummary(),
            indent = 1
        ) { promptAutoPeekDelay() }
        root.addView(autoPeekDelayRow)

        peekSizeRow = settingsRow(
            "Peeked sliver size",
            peekSizeSummary(),
            indent = 1
        ) { promptPeekSize() }
        root.addView(peekSizeRow)

        singleTapRestoreSwitch = MaterialSwitch(this).apply {
            isChecked = SingleTapRestoreToggle.isEnabled(this@BehaviorActivity)
            isClickable = false
        }
        root.addView(settingsRow(
            "Single-tap restore and record",
            "While peeked, one tap both brings the icon back and starts recording. Off by default, which keeps the first tap just restoring the icon and a second tap starting recording",
            singleTapRestoreSwitch,
            indent = 1
        ) {
            val newVal = !singleTapRestoreSwitch.isChecked
            SingleTapRestoreToggle.setEnabled(this, newVal)
            singleTapRestoreSwitch.isChecked = newVal
        })

        rawTextRetrySwitch = MaterialSwitch(this).apply {
            isChecked = RawTextRetryToggle.isEnabled(this@BehaviorActivity)
            isClickable = false
        }
        root.addView(settingsRow(
            "Offer raw text after cleanup",
            "Shows a \"Tap to use raw text\" bubble for a few seconds after cleanup changes your wording, so you can undo it with one tap",
            rawTextRetrySwitch
        ) {
            val newVal = !rawTextRetrySwitch.isChecked
            RawTextRetryToggle.setEnabled(this, newVal)
            rawTextRetrySwitch.isChecked = newVal
        })

        // Silence-based auto-stop (#108, mode 1). Off by default (see SilenceAutoStopToggle's
        // kdoc for why) and a genuine behavior change, so it gets its own paired toggle+threshold
        // rows following autoPeekSwitch/autoPeekDelayRow's existing pattern above.
        silenceAutoStopSwitch = MaterialSwitch(this).apply {
            isChecked = SilenceAutoStopToggle.isEnabled(this@BehaviorActivity)
            isClickable = false
        }
        silenceAutoStopRow = settingsRow(
            "Auto-stop after silence",
            silenceAutoStopSummary(),
            silenceAutoStopSwitch
        ) {
            onSilenceAutoStopToggled(!silenceAutoStopSwitch.isChecked)
        }
        root.addView(silenceAutoStopRow)

        silenceAutoStopThresholdRow = settingsRow(
            "Silence threshold",
            silenceAutoStopThresholdSummary(),
            indent = 1
        ) { promptSilenceAutoStopThreshold() }
        root.addView(silenceAutoStopThresholdRow)

        // Fallback restore path (#Feature B): if the icon is currently hidden -- including for
        // someone who turns the toggle above off while already hidden -- give them a way back
        // that doesn't depend on the notification still being around.
        iconHiddenRow = settingsRow("Icon is currently hidden", "Tap to show it again") {
            IconHiddenState.setHidden(this, false)
            WhisperAccessibilityService.instance?.applyOverlayVisibility()
            IconVisibilityNotifications.cancel(this)
            refresh()
        }
        root.addView(iconHiddenRow)

        root.addView(sectionHeader("Vocabulary"))
        val vocabularyRow = settingsRow("Personal vocabulary", vocabularySummary()) { promptVocabulary() }
        vocabularyRowSub = vocabularyRow.findViewWithTag("subtitle")
        root.addView(vocabularyRow)

        setContentView(ScrollView(this).apply {
            setBackgroundColor(attrColor(android.R.attr.colorBackground))
            addView(root)
        })

        observeSilenceAutoStopVadDownload()
        refresh()
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        iconHiddenRow.visibility = if (IconHiddenState.isHidden(this)) View.VISIBLE else View.GONE
        debugVisibilitySwitch.isChecked = DebugVisibilityToggle.isEnabled(this)
        perAppPersonaSwitch.isChecked = PerAppPersonaToggle.isEnabled(this)
        hideIconSwitch.isChecked = HideIconToggle.isEnabled(this)
        autoPeekSwitch.isChecked = AutoPeekToggle.isEnabled(this)
        singleTapRestoreSwitch.isChecked = SingleTapRestoreToggle.isEnabled(this)
        rawTextRetrySwitch.isChecked = RawTextRetryToggle.isEnabled(this)
        silenceAutoStopSwitch.isChecked = SilenceAutoStopToggle.isEnabled(this)
        silenceAutoStopThresholdRow.findViewWithTag<TextView>("subtitle").text = silenceAutoStopThresholdSummary()
        refreshSilenceAutoStopSummary()
        vocabularyRowSub.text = vocabularySummary()
        autoPeekDelayRow.findViewWithTag<TextView>("subtitle").text = autoPeekDelaySummary()
        peekSizeRow.findViewWithTag<TextView>("subtitle").text = peekSizeSummary()
    }

    // --- Auto-hide delay (Feature A follow-up) ---

    private fun autoPeekDelaySummary(): String {
        val seconds = AutoPeekDelay.secondsOrDefault(this)
        return "$seconds second${if (seconds == 1) "" else "s"} of inactivity before it slides to the edge"
    }

    private fun promptAutoPeekDelay() {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(AutoPeekDelay.secondsOrDefault(this@BehaviorActivity).toString())
            setSelection(text.length)
        }
        android.app.AlertDialog.Builder(this)
            .setTitle("Auto-hide delay")
            .setMessage("Seconds of inactivity before the icon slides to the edge (${AutoPeekDelay.MIN_SECONDS}-${AutoPeekDelay.MAX_SECONDS}).")
            .setView(input.apply { setPadding(dp(24), dp(8), dp(24), dp(8)) })
            .setPositiveButton("Save") { _, _ ->
                val seconds = input.text.toString().toIntOrNull()
                if (seconds != null) AutoPeekDelay.setSeconds(this, seconds)
                refresh()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun peekSizeSummary(): String {
        val dpValue = PeekVisibleSize.dpOrDefault(this)
        return "${dpValue}dp of the icon stays visible/tappable at the edge once peeked. Bigger is easier to hit"
    }

    private fun promptPeekSize() {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(PeekVisibleSize.dpOrDefault(this@BehaviorActivity).toString())
            setSelection(text.length)
        }
        android.app.AlertDialog.Builder(this)
            .setTitle("Peeked sliver size")
            .setMessage("How many dp of the icon stay visible and tappable at the edge once peeked (${PeekVisibleSize.MIN_DP}-${PeekVisibleSize.MAX_DP}). Bigger is easier to tap but shows more of the icon.")
            .setView(input.apply { setPadding(dp(24), dp(8), dp(24), dp(8)) })
            .setPositiveButton("Save") { _, _ ->
                val dpValue = input.text.toString().toIntOrNull()
                if (dpValue != null) PeekVisibleSize.setDp(this, dpValue)
                refresh()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // --- Silence-based auto-stop (#108, mode 1) ---

    private fun silenceAutoStopSummary(): String {
        if (!SilenceAutoStopToggle.isEnabled(this)) {
            return "Off. Stops recording automatically after a pause in speech"
        }
        if (ModelDownloader.vadModelFile(this, SILERO_VAD_MODEL) == null) {
            return if (ModelDownloadWorker.isInFlight(silenceAutoStopVadDownloadState)) {
                "Downloading silence-detection model…"
            } else {
                "Will activate once the silence-detection model finishes downloading"
            }
        }
        return "On. Stops after ${SilenceAutoStopThreshold.formatSeconds(SilenceAutoStopThreshold.decisecondsOrDefault(this))} of silence"
    }

    private fun refreshSilenceAutoStopSummary() {
        silenceAutoStopRow.findViewWithTag<TextView>("subtitle").text = silenceAutoStopSummary()
        silenceAutoStopThresholdRow.visibility =
            if (SilenceAutoStopToggle.isEnabled(this)) View.VISIBLE else View.GONE
    }

    private fun silenceAutoStopThresholdSummary(): String {
        val deciseconds = SilenceAutoStopThreshold.decisecondsOrDefault(this)
        return "Auto-stop after ${SilenceAutoStopThreshold.formatSeconds(deciseconds)} of silence"
    }

    /**
     * Turning ON with no VAD model installed yet triggers the on-demand download (mirroring
     * [CleanupActivity.onCleanupModelAction]'s enqueue pattern) but does NOT flip the toggle
     * true yet -- [SilenceAutoStopToggle] is only set once the model actually finishes
     * downloading, in [onSilenceAutoStopVadWorkInfos]. This is a deliberate choice over silently
     * claiming the feature is on: [WhisperAccessibilityService.startRecording] already checks
     * both the toggle AND [ModelDownloader.vadModelFile] before activating VAD, so leaving the
     * toggle off during the download is the only way the switch's visual state never lies about
     * whether the feature would actually engage on the next recording. The row subtitle
     * ([silenceAutoStopSummary]) tells the user a download is in progress in the meantime.
     *
     * Turning OFF is always immediate -- there's no download or async step to wait on.
     */
    private fun onSilenceAutoStopToggled(newVal: Boolean) {
        if (!newVal) {
            SilenceAutoStopToggle.setEnabled(this, false)
            silenceAutoStopPendingEnable = false
            refresh()
            return
        }
        if (ModelDownloader.vadModelFile(this, SILERO_VAD_MODEL) != null) {
            // Already installed (e.g. a previous enable/disable cycle, or downloaded via another
            // path) -- no download needed, flip on immediately.
            SilenceAutoStopToggle.setEnabled(this, true)
            silenceAutoStopPendingEnable = false
            refresh()
            return
        }
        silenceAutoStopPendingEnable = true
        if (!ModelDownloadWorker.isInFlight(silenceAutoStopVadDownloadState)) {
            ModelDownloadWorker.enqueue(this, SILERO_VAD_MODEL)
        }
        toast("Downloading silence-detection model…")
        refresh()
    }

    private fun observeSilenceAutoStopVadDownload() {
        WorkManager.getInstance(this)
            .getWorkInfosForUniqueWorkLiveData(ModelDownloadWorker.workName(SILERO_VAD_MODEL.archive))
            .observe(this) { infos -> onSilenceAutoStopVadWorkInfos(infos) }
    }

    private fun onSilenceAutoStopVadWorkInfos(infos: List<WorkInfo>) {
        val info = infos.firstOrNull { !it.state.isFinished } ?: infos.firstOrNull()
        silenceAutoStopVadDownloadState = info?.state
        if (silenceAutoStopPendingEnable && info != null) {
            when (info.state) {
                WorkInfo.State.SUCCEEDED -> {
                    silenceAutoStopPendingEnable = false
                    // Re-check the file, not just the WorkInfo state: SUCCEEDED confirms the
                    // worker returned success, but reading the real installed-file state keeps
                    // this in lockstep with exactly what startRecording() itself checks.
                    if (ModelDownloader.vadModelFile(this, SILERO_VAD_MODEL) != null) {
                        SilenceAutoStopToggle.setEnabled(this, true)
                        toast("Silence detection ready")
                    } else {
                        toast("Silence-detection model download finished but the file is missing — try again")
                    }
                    refresh()
                }
                WorkInfo.State.FAILED, WorkInfo.State.CANCELLED -> {
                    // Never claim the feature is on when the model isn't there (see this
                    // function's kdoc): the toggle was never set true, so there's nothing to
                    // revert -- just tell the user it didn't work.
                    silenceAutoStopPendingEnable = false
                    toast("Silence-detection model download failed — auto-stop stays off")
                    refresh()
                }
                else -> {}
            }
        } else if (info == null || info.state.isFinished) {
            refresh()
        }
    }

    private fun promptSilenceAutoStopThreshold() {
        val presets = SilenceAutoStopThreshold.PRESET_DECISECONDS
        val labels = presets.map { SilenceAutoStopThreshold.formatSeconds(it) }.toTypedArray()
        val current = SilenceAutoStopThreshold.decisecondsOrDefault(this)
        val checkedIndex = presets.indexOf(current).let { if (it < 0) presets.size else it }
        val items = labels + "Custom…"
        android.app.AlertDialog.Builder(this)
            .setTitle("Silence threshold")
            .setSingleChoiceItems(items, checkedIndex) { dialog, which ->
                if (which < presets.size) {
                    SilenceAutoStopThreshold.setDeciseconds(this, presets[which])
                    refresh()
                    dialog.dismiss()
                } else {
                    dialog.dismiss()
                    promptSilenceAutoStopCustomThreshold()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun promptSilenceAutoStopCustomThreshold() {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            setText(SilenceAutoStopThreshold.formatSeconds(SilenceAutoStopThreshold.decisecondsOrDefault(this@BehaviorActivity)).removeSuffix("s"))
            setSelection(text.length)
        }
        val minLabel = SilenceAutoStopThreshold.formatSeconds(SilenceAutoStopThreshold.MIN_DECISECONDS)
        val maxLabel = SilenceAutoStopThreshold.formatSeconds(SilenceAutoStopThreshold.MAX_DECISECONDS)
        android.app.AlertDialog.Builder(this)
            .setTitle("Custom silence threshold")
            .setMessage("Seconds of silence before auto-stop ($minLabel-$maxLabel).")
            .setView(input.apply { setPadding(dp(24), dp(8), dp(24), dp(8)) })
            .setPositiveButton("Save") { _, _ ->
                val seconds = input.text.toString().toFloatOrNull()
                if (seconds != null) SilenceAutoStopThreshold.setDeciseconds(this, (seconds * 10).toInt())
                refresh()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // --- Personal vocabulary (#26) ---

    private fun vocabularyTerms() = VocabularyTerms.parse(
        prefs().getString("custom_vocabulary_terms", VocabularyTerms.DEFAULT_SERIALIZED)
    )

    private fun vocabularySummary(): String {
        val terms = vocabularyTerms()
        return if (terms.isEmpty()) "No custom terms" else terms.joinToString(", ")
    }

    private fun promptVocabulary() {
        val input = EditText(this).apply {
            hint = "One term per line, e.g. FastHTML"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 3
            gravity = Gravity.TOP or Gravity.START
            setText(VocabularyTerms.serialize(vocabularyTerms()))
        }
        android.app.AlertDialog.Builder(this)
            .setTitle("Personal vocabulary")
            .setMessage("Project names or jargon the cleanup step often mishears. One per line.")
            .setView(input.apply { setPadding(dp(24), dp(8), dp(24), dp(8)) })
            .setPositiveButton("Save") { _, _ ->
                val terms = VocabularyTerms.parse(input.text.toString())
                prefs().edit().putString("custom_vocabulary_terms", VocabularyTerms.serialize(terms)).apply()
                refresh()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    companion object {
        /** Category subtitle for AdvancedActivity's Behavior row (#104). */
        fun subtitle(context: android.content.Context): String =
            "Auto-hide, per-app style, vocabulary, and more"
    }
}
