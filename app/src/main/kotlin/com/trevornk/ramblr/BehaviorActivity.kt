package com.trevornk.ramblr

import android.os.Bundle
import android.text.InputType
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
    private lateinit var automationOffHookSwitch: MaterialSwitch
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
    private lateinit var vocabSuggestionsSwitch: MaterialSwitch
    private lateinit var suggestionsContainer: LinearLayout
    private lateinit var dismissedSuggestionsRow: LinearLayout
    private lateinit var localThreadsRowSub: TextView
    private lateinit var canaryLanguageRowSub: TextView
    private lateinit var compressedUploadSwitch: MaterialSwitch
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

        // #257: opt-in automation hook. Placed next to the other invocation-adjacent toggles
        // rather than in Advanced, because the people who want it (automation users trying to
        // keep Ramblr out of banking apps) are looking at behavior, not internals.
        automationOffHookSwitch = MaterialSwitch(this).apply {
            isChecked = AutomationOffHookToggle.isEnabled(this@BehaviorActivity)
            isClickable = false
        }
        root.addView(settingsRow(
            "Let automation apps turn Ramblr off",
            "Adds a broadcast MacroDroid or Tasker can send to switch the accessibility service " +
                "off — more reliable than having them edit the accessibility setting directly. " +
                "Any app on your phone can send it, so leave this off unless you use it",
            automationOffHookSwitch
        ) {
            val newVal = !automationOffHookSwitch.isChecked
            AutomationOffHookToggle.setEnabled(this, newVal)
            automationOffHookSwitch.isChecked = newVal
            if (newVal) showAutomationOffHookHelp()
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

        // Compressed (AAC/M4A) cloud upload (#109). Off by default (see CompressedUploadToggle's
        // kdoc for why) and a genuine behavior change to the audio actually sent to a cloud
        // transcription provider, so it gets an honest description instead of pretending it's
        // already proven -- mirrors silenceAutoStopRow's "needs real-world validation" framing
        // above.
        compressedUploadSwitch = MaterialSwitch(this).apply {
            isChecked = CompressedUploadToggle.isEnabled(this@BehaviorActivity)
            isClickable = false
        }
        root.addView(settingsRow(
            "Compress audio before cloud upload",
            "Encodes to AAC/M4A before sending to OpenAI or Gemini for transcription -- primarily useful on cellular or slow connections. Transcription quality with compressed audio hasn't been verified in real-world use yet, so leave off for dictations where accuracy really matters until you've tried it",
            compressedUploadSwitch
        ) {
            val newVal = !compressedUploadSwitch.isChecked
            CompressedUploadToggle.setEnabled(this, newVal)
            compressedUploadSwitch.isChecked = newVal
        })

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
        val vocabularyRow = settingsRow("Personal vocabulary", VocabularyEditor.rowSummary(this)) {
            VocabularyEditor.prompt(this) { refresh() }
        }
        vocabularyRowSub = vocabularyRow.findViewWithTag("subtitle")
        root.addView(vocabularyRow)

        // Smart vocabulary suggestions (#216): master toggle plus the dynamic Suggested-terms
        // section and the Dismissed-suggestions review row. Turning the toggle off clears all
        // accumulated candidate counters (off = nothing retained -- see
        // VocabularySuggestionsToggle's kdoc); the dismissed list survives it, being user
        // decisions rather than collected counters.
        vocabSuggestionsSwitch = MaterialSwitch(this).apply {
            isChecked = VocabularySuggestionsToggle.isEnabled(this@BehaviorActivity)
            isClickable = false
        }
        root.addView(settingsRow(
            "Smart vocabulary suggestions",
            "Notices words dictation keeps correcting or that keep recurring, and suggests adding them to your vocabulary. Everything stays on this device; turning this off also deletes what's been noticed so far",
            vocabSuggestionsSwitch
        ) {
            val newVal = !vocabSuggestionsSwitch.isChecked
            VocabularySuggestionsToggle.setEnabled(this, newVal)
            vocabSuggestionsSwitch.isChecked = newVal
            refresh()
        })

        // Rebuilt from scratch on every refresh(): suggestions change between resumes (new
        // dictations) and after every Add/Dismiss, and the row count varies, so declarative
        // rebuild beats trying to patch child views in place.
        suggestionsContainer = vertical(0, 0)
        root.addView(suggestionsContainer)

        dismissedSuggestionsRow = settingsRow(
            "Dismissed suggestions",
            "Checking...",
            indent = 1
        ) { promptDismissedSuggestions() }
        root.addView(dismissedSuggestionsRow)

        // Local transcription thread count (#107): a developer-ish tuning knob, not a mainstream
        // everyday setting, so it lives down here rather than cluttering Transcription's main
        // local-model picker. See LocalTranscriptionThreads' kdoc for why the default stays 2.
        root.addView(sectionHeader("Advanced tuning"))
        val localThreadsRow = settingsRow("Local transcription threads", localThreadsSummary()) {
            promptLocalTranscriptionThreads()
        }
        localThreadsRowSub = localThreadsRow.findViewWithTag("subtitle")
        root.addView(localThreadsRow)

        // Canary source language (#177): same developer-ish tuning tier as the threads knob
        // above. Only the Canary model reads this -- see CanaryLanguage's kdoc for why the
        // wrong language token doesn't just degrade output but collapses it entirely.
        val canaryLanguageRow = settingsRow("Canary language", canaryLanguageSummary()) {
            promptCanaryLanguage()
        }
        canaryLanguageRowSub = canaryLanguageRow.findViewWithTag("subtitle")
        root.addView(canaryLanguageRow)

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
        automationOffHookSwitch.isChecked = AutomationOffHookToggle.isEnabled(this)
        autoPeekSwitch.isChecked = AutoPeekToggle.isEnabled(this)
        singleTapRestoreSwitch.isChecked = SingleTapRestoreToggle.isEnabled(this)
        rawTextRetrySwitch.isChecked = RawTextRetryToggle.isEnabled(this)
        silenceAutoStopSwitch.isChecked = SilenceAutoStopToggle.isEnabled(this)
        silenceAutoStopThresholdRow.findViewWithTag<TextView>("subtitle").text = silenceAutoStopThresholdSummary()
        refreshSilenceAutoStopSummary()
        compressedUploadSwitch.isChecked = CompressedUploadToggle.isEnabled(this)
        vocabularyRowSub.text = VocabularyEditor.rowSummary(this)
        vocabSuggestionsSwitch.isChecked = VocabularySuggestionsToggle.isEnabled(this)
        refreshSuggestionSections()
        localThreadsRowSub.text = localThreadsSummary()
        canaryLanguageRowSub.text = canaryLanguageSummary()
        autoPeekDelayRow.findViewWithTag<TextView>("subtitle").text = autoPeekDelaySummary()
        peekSizeRow.findViewWithTag<TextView>("subtitle").text = peekSizeSummary()
    }

    // --- Smart vocabulary suggestions (#216) ---

    /**
     * Rebuilds the Suggested-terms section and the Dismissed-suggestions row. The section only
     * exists when the toggle is on AND there is at least one over-threshold suggestion; the
     * dismissed row shows whenever the dismissed list is non-empty (even with the toggle off,
     * so the user can always review what they've dismissed).
     */
    private fun refreshSuggestionSections() {
        suggestionsContainer.removeAllViews()
        if (VocabularySuggestionsToggle.isEnabled(this)) {
            val suggestions = VocabularySuggestionStore.pendingSuggestions(this, VocabularyEditor.terms(this))
            if (suggestions.isNotEmpty()) {
                suggestionsContainer.addView(subsectionHeader("Suggested terms"))
                for (suggestion in suggestions) {
                    suggestionsContainer.addView(settingsRow(
                        suggestion.term,
                        suggestion.evidenceLine(),
                        indent = 1
                    ) { promptSuggestion(suggestion) })
                }
            }
        }
        dismissedSuggestionsRow.findViewWithTag<TextView>("subtitle").text =
            dismissedSuggestionsSummary()
        dismissedSuggestionsRow.visibility =
            if (VocabularySuggestionStore.dismissedTerms(this).isEmpty()) View.GONE else View.VISIBLE
    }

    private fun dismissedSuggestionsSummary(): String {
        val count = VocabularySuggestionStore.dismissedTerms(this).size
        return "$count term${if (count == 1) "" else "s"} you chose not to add. Tap to review or restore"
    }

    /**
     * #257: shown once when the user opts in, because a broadcast hook is useless if you don't
     * know the exact intent to send. Copy-to-clipboard rather than prose-only: the component
     * name is long and mistyping it fails silently (an unmatched broadcast is a no-op, not an
     * error), which would look exactly like the feature not working.
     */
    private fun showAutomationOffHookHelp() {
        val command = "am broadcast -a ${AutomationOffReceiver.ACTION_TURN_OFF} " +
            "-n $packageName/.AutomationOffReceiver"
        android.app.AlertDialog.Builder(this)
            .setTitle("Automation off-hook enabled")
            .setMessage(
                "Ramblr now responds to this broadcast by turning its accessibility service " +
                    "off:\n\n$command\n\nIn MacroDroid or Tasker, use a \u201CShell\u201D / \u201CRun " +
                    "command\u201D action with that line — it needs ADB or root privileges, the " +
                    "same as your existing accessibility actions.\n\nThere is no matching " +
                    "\u201Cturn on\u201D broadcast: re-enabling an accessibility service needs a " +
                    "permission apps aren't given, so that stays a manual step."
            )
            .setPositiveButton("Copy command") { _, _ ->
                val clipboard = getSystemService(android.content.ClipboardManager::class.java)
                clipboard?.setPrimaryClip(
                    android.content.ClipData.newPlainText("Ramblr off-hook", command)
                )
                toast("Command copied")
            }
            .setNegativeButton("Close", null)
            .show()
    }

    /** Add/Dismiss decision dialog for one suggestion. Add goes through [VocabularyEditor.addTerm]
     *  so the term lands exactly like a manually typed one; both paths drop the candidate's
     *  counters ([VocabularySuggestionStore.dismiss] / [VocabularySuggestionStore.removeCandidate]). */
    private fun promptSuggestion(suggestion: VocabularySuggestionStore.Suggestion) {
        android.app.AlertDialog.Builder(this)
            .setTitle("Add \u201C${suggestion.term}\u201D to vocabulary?")
            .setMessage(
                suggestion.evidenceLine() + ".\n\nAdding it helps transcription and cleanup " +
                    "get it right. Dismissing stops it from being suggested again."
            )
            .setPositiveButton("Add") { _, _ ->
                VocabularyEditor.addTerm(this, suggestion.term)
                VocabularySuggestionStore.removeCandidate(this, suggestion.term)
                refresh()
            }
            .setNegativeButton("Dismiss") { _, _ ->
                VocabularySuggestionStore.dismiss(this, suggestion.term)
                refresh()
            }
            .setNeutralButton("Cancel", null)
            .show()
    }

    /** The Dismissed-suggestions review list: each entry restorable with one tap (#216). A
     *  restored term becomes an eligible candidate again, counters restarting from zero. */
    private fun promptDismissedSuggestions() {
        val dismissed = VocabularySuggestionStore.dismissedTerms(this)
        if (dismissed.isEmpty()) return
        android.app.AlertDialog.Builder(this)
            .setTitle("Dismissed suggestions")
            .setItems(dismissed.map { "$it \u2014 tap to restore" }.toTypedArray()) { _, which ->
                val term = dismissed[which]
                VocabularySuggestionStore.restore(this, term)
                toastShort("\u201C$term\u201D can be suggested again")
                refresh()
            }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun toastShort(message: String) =
        android.widget.Toast.makeText(this, message, android.widget.Toast.LENGTH_SHORT).show()

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

    // Personal vocabulary (#26) editor + summaries moved to [VocabularyEditor] (#217), shared
    // with MainActivity's top-level row.

    // --- Local transcription thread count (#107) ---

    private fun localThreadsSummary(): String {
        val threads = LocalTranscriptionThreads.threadsOrDefault(this)
        return "$threads threads for on-device transcription. Higher may transcribe faster " +
            "but uses more CPU/battery -- compare with the benchmark log in Data & Logs"
    }

    /** Presets-only picker (#107): the issue's whole point is letting Trevor A/B 2/4/6 himself
     *  using his own real-usage [BenchmarkLogger] data, not offering a free-form "Custom…" input
     *  the way [promptSilenceAutoStopThreshold] does for a user-experience threshold -- there's
     *  no forgiving range to fall back to here, just three concrete values to compare. */
    private fun promptLocalTranscriptionThreads() {
        // AlertDialog.Builder's message and single-choice-item list share the same content area
        // and are mutually exclusive -- calling both silently drops the list with no error, which
        // is exactly what happened here: the dialog rendered title+message+Cancel but never the
        // three thread presets, making the setting effectively unreachable (GH bug report,
        // 2026-07-17). Fixed using the documented workaround: a custom title view carrying both
        // the heading and the explanatory copy (in place of setTitle()+setMessage()), leaving the
        // content area free for setSingleChoiceItems() to actually render.
        val presets = LocalTranscriptionThreads.PRESET_THREADS
        val labels = presets.map { "$it threads" }.toTypedArray()
        val current = LocalTranscriptionThreads.threadsOrDefault(this)
        val checkedIndex = presets.indexOf(current).let { if (it < 0) 0 else it }
        val titleView = vertical(dp(20), dp(20)).apply {
            addView(TextView(this@BehaviorActivity).apply {
                text = "Local transcription threads"
                textSize = 20f
                setTextColor(attrColor(android.R.attr.textColorPrimary))
            })
            addView(TextView(this@BehaviorActivity).apply {
                text = "How many CPU threads on-device transcription uses to decode. The " +
                    "default (2) is unchanged from before this setting existed -- try 4 or 6 " +
                    "and compare against the benchmark log (Data & Logs > Share benchmark log) " +
                    "to see what's actually faster on your device."
                textSize = 14f
                setTextColor(attrColor(android.R.attr.textColorSecondary))
                setPadding(0, dp(8), 0, 0)
            })
        }
        android.app.AlertDialog.Builder(this)
            .setCustomTitle(titleView)
            .setSingleChoiceItems(labels, checkedIndex) { dialog, which ->
                LocalTranscriptionThreads.setThreads(this, presets[which])
                refresh()
                dialog.dismiss()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // --- Canary source language (#177) ---

    private val canaryLanguageLabels = mapOf(
        "en" to "English (en)",
        "es" to "Spanish (es)",
        "de" to "German (de)",
        "fr" to "French (fr)",
    )

    private fun canaryLanguageSummary(): String {
        val lang = CanaryLanguage.languageOrDefault(this)
        val label = canaryLanguageLabels[lang] ?: lang
        return "$label -- the language you speak when dictating with the Canary local model. " +
            "Only affects Canary; other models ignore this"
    }

    /** Fixed-list picker modeled on [promptLocalTranscriptionThreads]: exactly the four languages
     *  the shipped canary-180m-flash model supports, no free-form entry. */
    private fun promptCanaryLanguage() {
        // Same AlertDialog gotcha as promptLocalTranscriptionThreads: setMessage() and
        // setSingleChoiceItems() share the content area, so the explanatory copy must ride in a
        // custom title view or the language list silently never renders.
        val languages = CanaryLanguage.SUPPORTED
        val labels = languages.map { canaryLanguageLabels[it] ?: it }.toTypedArray()
        val current = CanaryLanguage.languageOrDefault(this)
        val checkedIndex = languages.indexOf(current).let { if (it < 0) 0 else it }
        val titleView = vertical(dp(20), dp(20)).apply {
            addView(TextView(this@BehaviorActivity).apply {
                text = "Canary language"
                textSize = 20f
                setTextColor(attrColor(android.R.attr.textColorPrimary))
            })
            addView(TextView(this@BehaviorActivity).apply {
                text = "The language you speak when dictating with the Canary local model. " +
                    "Canary needs to be told the source language -- with the wrong one it " +
                    "produces garbage instead of text. Only affects the Canary model; other " +
                    "local models detect or fix their language on their own."
                textSize = 14f
                setTextColor(attrColor(android.R.attr.textColorSecondary))
                setPadding(0, dp(8), 0, 0)
            })
        }
        android.app.AlertDialog.Builder(this)
            .setCustomTitle(titleView)
            .setSingleChoiceItems(labels, checkedIndex) { dialog, which ->
                CanaryLanguage.setLanguage(this, languages[which])
                refresh()
                dialog.dismiss()
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
