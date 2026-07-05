package com.kafkasl.phonewhisper

import android.content.res.ColorStateList
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.google.android.material.button.MaterialButton
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.radiobutton.MaterialRadioButton

/**
 * "Cleanup" category screen (#93 restructure): on/off switch, style/prompt presets, the simple
 * Local/Cloud choice (+ local model catalog), preview-before-inject toggle, and -- the fix this
 * whole restructure exists for -- its own contextual OpenAI API key row shown right here when
 * Cloud cleanup needs it, instead of only under a separate Transcription screen. See
 * [shouldShowOpenAiKeyRowForCleanup]/BaseSettingsActivity.promptApiKey.
 */
class CleanupActivity : BaseSettingsActivity() {

    private lateinit var postProcessSwitch: MaterialSwitch
    private lateinit var postProcessRowSub: TextView
    private lateinit var cleanupDetailContainer: LinearLayout
    private lateinit var promptRow: LinearLayout
    private lateinit var promptRowSub: TextView
    private lateinit var promptContainer: LinearLayout
    private lateinit var cleanupLocalRadio: MaterialRadioButton
    private lateinit var cleanupCloudRadio: MaterialRadioButton
    private lateinit var cleanupChoiceCaption: TextView
    private lateinit var cleanupLocalGroup: View
    private lateinit var cleanupCloudGroup: View
    private lateinit var openAiKeyGroup: View
    private lateinit var keyRowSub: TextView
    private lateinit var previewBeforeInjectSwitch: MaterialSwitch

    private var pendingLocalCleanupSelection = false
    private val cleanupModelRows = mutableMapOf<String, ModelRowViews>()
    private val cleanupModelDownloadState = mutableMapOf<String, WorkInfo.State>()
    private val cleanupModelDownloadGate = DownloadCompletionGate()
    private val promptRows = mutableMapOf<String, PromptRowViews>()

    private data class ModelRowViews(
        val radio: MaterialRadioButton,
        val progress: LinearProgressIndicator,
        val subtitle: TextView,
        val dlBtn: MaterialButton,
        val deleteBtn: MaterialButton
    )

    private data class PromptRowViews(val radio: MaterialRadioButton, val subtitle: TextView)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = vertical(0, 0)

        val header = TextView(this).apply {
            text = "Cleanup"
            textSize = 32f
            setPadding(dp(24), dp(64), dp(24), dp(24))
        }
        root.addView(header)

        val isPostProcessing = prefs().getBoolean("use_post_processing", false)
        postProcessSwitch = MaterialSwitch(this).apply {
            isChecked = isPostProcessing
            isClickable = false
        }
        val postProcessRow = settingsRow("Improve my dictation with AI", cleanupSubtitle(), postProcessSwitch) {
            val newVal = !postProcessSwitch.isChecked
            val useLocal = prefs().getBoolean("use_local", true)
            applyLocalCleanupChange(useLocal, newVal)
        }
        postProcessRowSub = postProcessRow.findViewWithTag("subtitle")
        root.addView(postProcessRow)

        // Everything below only matters once cleanup is on -- shown/hidden as one unit in refresh().
        cleanupDetailContainer = vertical(0)

        val promptGroup = nestedGroup()
        promptGroup.content.addView(subsectionHeader("Style", indent = 0))
        for (preset in promptPresets()) promptGroup.content.addView(buildPromptRow(preset))
        promptContainer = promptGroup.content
        cleanupDetailContainer.addView(promptGroup.outer)

        promptRow = settingsRow("Edit current prompt", currentPrompt()) { promptPostProcessing() }
        promptRowSub = promptRow.findViewWithTag("subtitle")
        promptRowSub.maxLines = 2
        promptRowSub.ellipsize = android.text.TextUtils.TruncateAt.END
        cleanupDetailContainer.addView(promptRow)

        cleanupDetailContainer.addView(subsectionHeader("How cleanup runs", indent = 0))

        cleanupLocalRadio = MaterialRadioButton(this).apply {
            isClickable = false
            buttonTintList = ColorStateList.valueOf(attrColor(com.google.android.material.R.attr.colorPrimary))
        }
        cleanupDetailContainer.addView(
            settingsRow("Local", "Private, runs on-device, no API key needed", cleanupLocalRadio) {
                onSelectSimpleCleanup(SimpleCleanupChoice.LOCAL)
            }
        )
        val cleanupLocalNested = nestedGroup()
        for (m in LOCAL_CLEANUP_MODEL_CATALOG) cleanupLocalNested.content.addView(buildCleanupModelRow(m))
        cleanupLocalGroup = cleanupLocalNested.outer
        cleanupDetailContainer.addView(cleanupLocalGroup)

        cleanupCloudRadio = MaterialRadioButton(this).apply {
            isClickable = false
            buttonTintList = ColorStateList.valueOf(attrColor(com.google.android.material.R.attr.colorPrimary))
        }
        cleanupDetailContainer.addView(
            settingsRow("Cloud", "Uses your OpenAI API key by default, billed pay-per-use — Anthropic or OmniRoute available under Advanced", cleanupCloudRadio) {
                onSelectSimpleCleanup(SimpleCleanupChoice.CLOUD)
            }
        )

        val cleanupCloudNested = nestedGroup()

        // Contextual OpenAI API key row (#93 fix for #49's original bug): lives directly under
        // Cleanup's own cloud sub-section now, so a user configuring Cloud Cleanup finds the key
        // right here instead of having to go to a completely different Transcription screen.
        // TranscriptionActivity has its own equivalent copy for its own cloud sub-section.
        val keyRow = settingsRow("OpenAI API Key", "Tap to set", indent = 0) {
            promptApiKey { refresh() }
        }
        keyRowSub = keyRow.findViewWithTag("subtitle")
        cleanupCloudNested.content.addView(keyRow)

        cleanupCloudGroup = cleanupCloudNested.outer
        cleanupDetailContainer.addView(cleanupCloudGroup)
        openAiKeyGroup = keyRow

        cleanupChoiceCaption = TextView(this).apply {
            text = "Doesn't match a simple choice — your existing configuration is preserved under Advanced."
            textSize = 12f
            setTextColor(attrColor(android.R.attr.textColorSecondary))
            setPadding(dp(24), 0, dp(24), dp(8))
            visibility = View.GONE
        }
        cleanupDetailContainer.addView(cleanupChoiceCaption)

        previewBeforeInjectSwitch = MaterialSwitch(this).apply {
            isChecked = PreviewBeforeInjectToggle.isEnabled(this@CleanupActivity)
            isClickable = false
        }
        val previewBeforeInjectRow = settingsRow(
            "Preview before inserting",
            "Review the cleaned-up text and tap to insert it, instead of inserting automatically",
            previewBeforeInjectSwitch
        ) {
            val newVal = !previewBeforeInjectSwitch.isChecked
            PreviewBeforeInjectToggle.setEnabled(this, newVal)
            previewBeforeInjectSwitch.isChecked = newVal
        }
        cleanupDetailContainer.addView(previewBeforeInjectRow)
        root.addView(cleanupDetailContainer)

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
        val usePostProcessing = prefs().getBoolean("use_post_processing", false)
        postProcessSwitch.isChecked = usePostProcessing
        postProcessRowSub.text = cleanupSubtitle()
        previewBeforeInjectSwitch.isChecked = PreviewBeforeInjectToggle.isEnabled(this)
        cleanupDetailContainer.visibility = if (usePostProcessing) View.VISIBLE else View.GONE

        keyRowSub.text = apiKeyRowSubtitleText()
        val cleanupChoice = simpleCleanupChoiceFor(CleanupWaterfallStore.load(this))
        openAiKeyGroup.visibility = if (shouldShowOpenAiKeyRowForCleanup(usePostProcessing, cleanupChoice)) View.VISIBLE else View.GONE

        refreshWaterfallDependentUi()
        refreshAllCleanupRows()
        promptRowSub.text = currentPrompt()
        refreshPromptRows()
    }

    /** Everything downstream of the waterfall that needs recomputing after any edit to it
     *  (choice radios, group visibility, caption, key row) -- mirrors MainActivity's original
     *  saveWaterfallSteps() comment on why a full refresh (not just the step list) matters: an
     *  edit changes the effective simple choice, and with it the radios, subtitle, and the
     *  OpenAI-key row's visibility (#81). */
    private fun refreshWaterfallDependentUi() {
        val persistedChoice = simpleCleanupChoiceFor(CleanupWaterfallStore.load(this))
        if (persistedChoice == SimpleCleanupChoice.LOCAL) pendingLocalCleanupSelection = false
        val choice = displayedCleanupChoice(persistedChoice, pendingLocalCleanupSelection)
        cleanupLocalRadio.isChecked = choice == SimpleCleanupChoice.LOCAL
        cleanupCloudRadio.isChecked = choice == SimpleCleanupChoice.CLOUD
        cleanupChoiceCaption.visibility = if (persistedChoice == SimpleCleanupChoice.CUSTOM) View.VISIBLE else View.GONE
        cleanupLocalGroup.visibility = if (choice == SimpleCleanupChoice.LOCAL) View.VISIBLE else View.GONE
        cleanupCloudGroup.visibility = if (choice == SimpleCleanupChoice.CLOUD) View.VISIBLE else View.GONE
    }

    /** Applies a change to the local/cleanup toggles, inserting the one-time consent dialog from
     *  #23 when the change would first combine local transcription with cleanup. */
    private fun applyLocalCleanupChange(useLocal: Boolean, usePostProcessing: Boolean) {
        val hasConsented = prefs().getBoolean(KEY_LOCAL_CLEANUP_CONSENT, false)
        val cleanupIsLocalOnly = CleanupWaterfallStore.load(this).isLocalOnly()
        if (!LocalCleanupConsent.shouldPrompt(useLocal, usePostProcessing, hasConsented, cleanupIsLocalOnly)) {
            prefs().edit()
                .putBoolean("use_local", useLocal)
                .putBoolean("use_post_processing", usePostProcessing)
                .apply()
            refresh()
            return
        }

        val declineCleanup = {
            prefs().edit()
                .putBoolean("use_local", useLocal)
                .putBoolean("use_post_processing", false)
                .apply()
            refresh()
        }

        android.app.AlertDialog.Builder(this)
            .setTitle("Cleanup sends text off-device")
            .setMessage(
                "Local transcription keeps audio on your phone, but cleanup sends the " +
                    "transcribed text to ${PostProcessor.destinationHost(cleanupBaseUrl())} to fix " +
                    "grammar and punctuation. Enable cleanup anyway?"
            )
            .setPositiveButton("Enable cleanup") { _, _ ->
                prefs().edit()
                    .putBoolean("use_local", useLocal)
                    .putBoolean("use_post_processing", true)
                    .putBoolean(KEY_LOCAL_CLEANUP_CONSENT, true)
                    .apply()
                refresh()
            }
            .setNegativeButton("Keep cleanup off") { _, _ -> declineCleanup() }
            .setOnCancelListener { declineCleanup() }
            .show()
    }

    /** Subtitle for the cleanup toggle, always naming the actual network destination (#23, #4)
     *  and, when that destination is OpenAI's own API, why it needs a key and what it costs (#6). */
    private fun cleanupSubtitle(): String {
        val useLocal = prefs().getBoolean("use_local", true)
        val host = PostProcessor.destinationHost(cleanupBaseUrl())
        val base = if (useLocal) "Sends transcript over the network to $host, even though transcription stays on-device"
        else "Sends transcript to $host to fix grammar and punctuation"
        val costNote = if (cleanupBaseUrl() == PostProcessor.DEFAULT_BASE_URL)
            " Uses your OpenAI API key and is billed pay-per-use (typically a fraction of a cent per dictation)."
        else ""
        return base + costNote
    }

    private fun cleanupBaseUrl() = prefs().getString("cleanup_base_url", PostProcessor.DEFAULT_BASE_URL) ?: PostProcessor.DEFAULT_BASE_URL

    // --- Prompt Rows (verbatim from MainActivity, #93) ---

    private data class PromptPreset(val key: String, val title: String, val subtitle: String, val prompt: String)

    private fun promptPresets() = CleanupPersonas.BUILT_IN.map {
        PromptPreset(key = it.key, title = it.title, subtitle = it.subtitle, prompt = it.prompt)
    } + PromptPreset(
        key = "custom",
        title = "Custom",
        subtitle = customPromptSummary(),
        prompt = customPrompt()
    )

    private fun buildPromptRow(preset: PromptPreset): View {
        val radio = MaterialRadioButton(this).apply {
            isClickable = false
            buttonTintList = ColorStateList.valueOf(attrColor(com.google.android.material.R.attr.colorPrimary))
        }
        val row = settingsRow(preset.title, preset.subtitle, radio) { selectPrompt(preset.key) }
        promptRows[preset.key] = PromptRowViews(radio, row.findViewWithTag("subtitle"))
        refreshPromptRow(preset)
        return row
    }

    private fun selectPrompt(key: String) {
        if (key == "custom") {
            val saved = customPrompt()
            if (CleanupPersonas.BUILT_IN.any { it.prompt == saved }) {
                promptPostProcessing()
                return
            }
            prefs().edit().putString("post_processing_prompt", saved).apply()
        } else {
            val persona = CleanupPersonas.fromKey(key)
            prefs().edit()
                .putString("cleanup_style", persona.key)
                .putString("post_processing_prompt", CleanupPersonas.promptForExplicitSelection(persona))
                .apply()
        }
        refreshPromptRows(); refresh()
    }

    private fun refreshPromptRow(preset: PromptPreset) {
        val views = promptRows[preset.key] ?: return
        val current = currentPrompt()
        val active = when (preset.key) {
            "custom" -> CleanupPersonas.BUILT_IN.none { it.prompt == current }
            else -> current == preset.prompt
        }
        views.radio.isChecked = active
        views.subtitle.text = if (preset.key == "custom") customPromptSummary() else preset.subtitle
    }

    private fun refreshPromptRows() = promptPresets().forEach { refreshPromptRow(it) }

    private fun currentPrompt() = prefs().getString("post_processing_prompt", PostProcessor.DEFAULT_PROMPT) ?: PostProcessor.DEFAULT_PROMPT
    private fun customPrompt() = prefs().getString("custom_post_processing_prompt", PostProcessor.DEFAULT_PROMPT) ?: PostProcessor.DEFAULT_PROMPT

    private fun customPromptSummary(): String {
        val prompt = customPrompt()
        return if (prompt == PostProcessor.DEFAULT_PROMPT) "Your edited prompt"
        else prompt.replace("\n", " ")
    }

    private fun currentPersona(): CleanupPersona =
        CleanupPersonas.currentPersona(prefs().getString("cleanup_style", null), currentPrompt())

    private fun promptPostProcessing() {
        val input = android.widget.EditText(this).apply {
            hint = "Prompt"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 3
            gravity = Gravity.TOP or Gravity.START
            setText(currentPrompt())
        }
        android.app.AlertDialog.Builder(this)
            .setTitle("Edit current prompt")
            .setView(input.apply { setPadding(dp(24), dp(8), dp(24), dp(8)) })
            .setPositiveButton("Save") { _, _ ->
                val text = input.text.toString().trim()
                val customPrompt = if (text.isBlank()) PostProcessor.DEFAULT_PROMPT else text
                val active = CleanupPersonas.resolvePrompt(currentPersona(), customPrompt)
                prefs().edit()
                    .putString("custom_post_processing_prompt", customPrompt)
                    .putString("post_processing_prompt", active)
                    .apply()
                refresh()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // --- Simple Local/Cloud cleanup choice (#38, verbatim from MainActivity) ---

    private fun selectedCleanupModel(): Model =
        ModelDownloader.resolveActiveModel(LOCAL_CLEANUP_MODEL_CATALOG, prefs().getString(KEY_LOCAL_CLEANUP_MODEL_NAME, "") ?: "")

    private fun buildCleanupModelRow(model: Model): View {
        val radio = MaterialRadioButton(this).apply {
            isClickable = false
            buttonTintList = ColorStateList.valueOf(attrColor(com.google.android.material.R.attr.colorPrimary))
        }
        val dlBtn = MaterialButton(this, null, com.google.android.material.R.attr.materialIconButtonStyle).apply {
            text = "↓"
            textSize = 18f
            setTextColor(attrColor(com.google.android.material.R.attr.colorPrimary))
            setOnClickListener { onCleanupModelAction(model) }
        }
        val deleteBtn = MaterialButton(this, null, com.google.android.material.R.attr.materialIconButtonStyle).apply {
            text = "🗑"
            textSize = 16f
            setOnClickListener { confirmDeleteCleanupModel(model) }
        }
        val rightContainer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(dlBtn)
            addView(deleteBtn)
            addView(radio)
        }
        val progress = LinearProgressIndicator(this).apply {
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(LP_MATCH, dp(4)).apply { topMargin = dp(8) }
        }

        val row = settingsRow(model.name, "${model.quality} · ${model.sizeMb} MB", rightContainer) {
            onCleanupModelAction(model)
        }
        val textContainer = row.getChildAt(0) as LinearLayout
        textContainer.addView(progress)

        cleanupModelRows[model.archive] = ModelRowViews(
            radio, progress, textContainer.findViewWithTag("subtitle"), dlBtn, deleteBtn
        )
        observeCleanupModelDownload(model)
        refreshCleanupModelRow(model)
        return row
    }

    private fun onCleanupModelAction(model: Model) {
        if (ModelDownloader.isInstalled(this, model)) {
            selectCleanupModel(model.archive)
            return
        }
        if (ModelDownloadWorker.isInFlight(cleanupModelDownloadState[model.archive])) return
        ModelDownloadWorker.enqueue(this, model)
    }

    private fun selectCleanupModel(archive: String) {
        prefs().edit().putString(KEY_LOCAL_CLEANUP_MODEL_NAME, archive).apply()
        refreshAllCleanupRows(); refresh()
    }

    private fun confirmDeleteCleanupModel(model: Model) {
        val isActive = simpleCleanupChoiceFor(CleanupWaterfallStore.load(this)) == SimpleCleanupChoice.LOCAL &&
            selectedCleanupModel().archive == model.archive
        val activeNote = if (isActive) " Cleanup will switch back to Cloud." else ""
        android.app.AlertDialog.Builder(this)
            .setTitle("Delete ${model.name}?")
            .setMessage("This frees ${model.sizeMb} MB of storage.$activeNote You can download it again later.")
            .setPositiveButton("Delete") { _, _ -> deleteCleanupModel(model) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun deleteCleanupModel(model: Model) {
        val wasActive = simpleCleanupChoiceFor(CleanupWaterfallStore.load(this)) == SimpleCleanupChoice.LOCAL &&
            selectedCleanupModel().archive == model.archive
        ModelDownloader.delete(this, model)
        if (wasActive) onSelectSimpleCleanup(SimpleCleanupChoice.CLOUD)
        toast("${model.name} deleted")
        refreshAllCleanupRows(); refresh()
    }

    private fun observeCleanupModelDownload(model: Model) {
        WorkManager.getInstance(this)
            .getWorkInfosForUniqueWorkLiveData(ModelDownloadWorker.workName(model.archive))
            .observe(this) { infos -> onCleanupModelWorkInfos(model, infos) }
    }

    private fun onCleanupModelWorkInfos(model: Model, infos: List<WorkInfo>) {
        val views = cleanupModelRows[model.archive] ?: return
        val info = infos.firstOrNull { !it.state.isFinished } ?: infos.firstOrNull()
        cleanupModelDownloadState[model.archive] = info?.state ?: WorkInfo.State.CANCELLED
        if (info != null && !info.state.isFinished) cleanupModelDownloadGate.onInFlight(info.id.toString())

        when (info?.state) {
            WorkInfo.State.ENQUEUED, WorkInfo.State.RUNNING -> {
                views.dlBtn.isEnabled = false
                views.progress.visibility = View.VISIBLE
                val phase = info.progress.getString(ModelDownloadWorker.KEY_PHASE)
                if (phase == ModelDownloadWorker.PHASE_EXTRACTING) {
                    views.progress.isIndeterminate = true
                    views.subtitle.text = "Installing..."
                } else {
                    val pct = info.progress.getFloat(ModelDownloadWorker.KEY_PROGRESS, 0f)
                    views.progress.isIndeterminate = false
                    views.progress.progress = (pct * 100).toInt()
                    views.subtitle.text =
                        if (info.state == WorkInfo.State.ENQUEUED) "Starting download..."
                        else "Downloading: ${(pct * 100).toInt()}%"
                }
            }
            WorkInfo.State.SUCCEEDED -> {
                views.progress.visibility = View.GONE
                views.dlBtn.isEnabled = true
                if (cleanupModelDownloadGate.shouldActOnSuccess(info.id.toString())) {
                    selectCleanupModel(model.archive)
                    toast("${model.name} ready — tap Local above to use it for cleanup")
                }
                refreshCleanupModelRow(model)
            }
            WorkInfo.State.FAILED -> {
                views.progress.visibility = View.GONE
                views.dlBtn.isEnabled = true
                val err = info.outputData.getString(ModelDownloadWorker.KEY_ERROR) ?: "Unknown error"
                views.subtitle.text = "Error: $err"
            }
            else -> {
                views.progress.visibility = View.GONE
                views.dlBtn.isEnabled = true
                refreshCleanupModelRow(model)
            }
        }
    }

    private fun refreshCleanupModelRow(model: Model) {
        val views = cleanupModelRows[model.archive] ?: return
        val active = selectedCleanupModel().archive == model.archive
        val installed = ModelDownloader.isInstalled(this, model)

        views.radio.isChecked = active
        views.radio.visibility = if (installed) View.VISIBLE else View.GONE
        views.dlBtn.visibility = if (installed) View.GONE else View.VISIBLE
        views.deleteBtn.visibility = if (installed) View.VISIBLE else View.GONE

        if (views.progress.visibility == View.GONE) {
            views.subtitle.text = "${model.quality} · ${model.sizeMb} MB"
        }
    }

    private fun refreshAllCleanupRows() = LOCAL_CLEANUP_MODEL_CATALOG.forEach { refreshCleanupModelRow(it) }

    private fun onSelectSimpleCleanup(choice: SimpleCleanupChoice) {
        when (choice) {
            SimpleCleanupChoice.LOCAL -> {
                val model = selectedCleanupModel()
                if (!ModelDownloader.isInstalled(this, model)) {
                    pendingLocalCleanupSelection = true
                    toast("Download a local cleanup model below to finish switching")
                    refreshWaterfallDependentUi()
                    return
                }
                pendingLocalCleanupSelection = false
                saveWaterfallSteps(listOf(CleanupStep(CleanupStepGroup.LOCAL_LLM, model.archive)))
            }
            SimpleCleanupChoice.CLOUD -> {
                pendingLocalCleanupSelection = false
                saveWaterfallSteps(listOf(CleanupStep(CleanupStepGroup.LEGACY, PostProcessor.DEFAULT_MODEL)))
            }
            SimpleCleanupChoice.CUSTOM -> return
        }
        refreshWaterfallDependentUi()
    }

    private fun saveWaterfallSteps(steps: List<CleanupStep>) {
        CleanupWaterfallStore.save(this, CleanupWaterfall(steps))
        // Full refresh, not just the waterfall-dependent bits: mirrors MainActivity's original
        // saveWaterfallSteps() comment -- a waterfall edit changes the effective simple choice,
        // and with it the radios, the cleanup subtitle, and the OpenAI-key row's visibility (#81).
        refresh()
    }

    companion object {
        private const val LP_MATCH = LinearLayout.LayoutParams.MATCH_PARENT
        private const val KEY_LOCAL_CLEANUP_CONSENT = "local_cleanup_consent_seen"
        private const val KEY_LOCAL_CLEANUP_MODEL_NAME = "local_cleanup_model_name"

        /** Category subtitle for MainActivity's Cleanup row (#93), e.g. "Off", "Local ·
         *  LFM2.5 350M", or "Cloud · GPT-4o mini". */
        fun subtitle(context: android.content.Context): String {
            val prefs = context.getSharedPreferences("phonewhisper", android.content.Context.MODE_PRIVATE)
            if (!prefs.getBoolean("use_post_processing", false)) return "Off"
            return when (simpleCleanupChoiceFor(CleanupWaterfallStore.load(context))) {
                SimpleCleanupChoice.LOCAL -> {
                    val archive = prefs.getString(KEY_LOCAL_CLEANUP_MODEL_NAME, "") ?: ""
                    val model = LOCAL_CLEANUP_MODEL_CATALOG.firstOrNull { it.archive == archive }
                        ?: LOCAL_CLEANUP_MODEL_CATALOG.first()
                    "Local · ${model.name}"
                }
                SimpleCleanupChoice.CLOUD -> {
                    val model = prefs.getString("cleanup_model", PostProcessor.DEFAULT_MODEL) ?: PostProcessor.DEFAULT_MODEL
                    "Cloud · $model"
                }
                SimpleCleanupChoice.CUSTOM -> "Custom waterfall (see Advanced)"
            }
        }
    }
}
