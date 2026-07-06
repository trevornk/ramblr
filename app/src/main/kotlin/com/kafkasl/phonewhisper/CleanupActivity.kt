package com.kafkasl.phonewhisper

import android.content.Intent
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
 * "Cleanup" category screen (#93 restructure, updated #95 Phase 3, #103 Style manager): on/off
 * switch, a link into the Style manager, the simple Local/Cloud choice (+ local model catalog),
 * preview-before-inject toggle. Cloud provider/credential management now lives entirely on the
 * unified CloudProviderActivity -- this screen just links to it (see [CloudProviderActivity.subtitle])
 * rather than duplicating any key-entry UI.
 *
 * The flat built-in-only prompt-preset radio list (#3, #40) that used to live directly on this
 * screen has been replaced by a single summary row linking to [StyleManagerActivity] (#103): full
 * persona CRUD (create/edit/delete/quick-menu selection) didn't fit as an inline radio list once
 * custom personas joined the five built-ins, so it moved to its own screen. Day-to-day switching
 * between styles now happens via the floating icon's long-press quick menu, not here.
 */
class CleanupActivity : BaseSettingsActivity() {

    private lateinit var postProcessSwitch: MaterialSwitch
    private lateinit var postProcessRowSub: TextView
    private lateinit var cleanupDetailContainer: LinearLayout
    private lateinit var styleSummaryRow: LinearLayout
    private lateinit var styleSummaryRowTitle: TextView
    private lateinit var cleanupLocalRadio: MaterialRadioButton
    private lateinit var cleanupCloudRadio: MaterialRadioButton
    private lateinit var cleanupChoiceCaption: TextView
    private lateinit var cleanupLocalGroup: View
    private lateinit var cleanupCloudGroup: View
    private lateinit var cloudLinkRowSub: TextView
    private lateinit var previewBeforeInjectSwitch: MaterialSwitch

    private var pendingLocalCleanupSelection = false
    private val cleanupModelRows = mutableMapOf<String, ModelRowViews>()
    private val cleanupModelDownloadState = mutableMapOf<String, WorkInfo.State>()
    private val cleanupModelDownloadGate = DownloadCompletionGate()

    private data class ModelRowViews(
        val radio: MaterialRadioButton,
        val progress: LinearProgressIndicator,
        val subtitle: TextView,
        val dlBtn: MaterialButton,
        val deleteBtn: MaterialButton
    )

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

        val styleGroup = nestedGroup()
        styleGroup.content.addView(subsectionHeader("Style", indent = 0))
        styleSummaryRow = settingsRow(
            currentPersonaSummary(),
            "Manage styles, quick-menu selection, and custom prompts",
            indent = 0
        ) { startActivity(Intent(this, StyleManagerActivity::class.java)) }
        styleSummaryRowTitle = styleSummaryRow.findViewWithTag("title")
        styleGroup.content.addView(styleSummaryRow)
        cleanupDetailContainer.addView(styleGroup.outer)

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
            settingsRow("Cloud", "Configure providers and keys under Cloud", cleanupCloudRadio) {
                onSelectSimpleCleanup(SimpleCleanupChoice.CLOUD)
            }
        )

        val cleanupCloudNested = nestedGroup()

        // #95 Phase 3: the contextual OpenAI key row here is replaced by a link into the new
        // unified CloudProviderActivity -- provider/credential management for cleanup no longer
        // lives on this screen at all, it's one shared surface for both features.
        val cloudLinkRow = settingsRow("Cloud provider chain", CloudProviderActivity.subtitle(this), indent = 0) {
            startActivity(Intent(this, CloudProviderActivity::class.java))
        }
        cloudLinkRowSub = cloudLinkRow.findViewWithTag("subtitle")
        cleanupCloudNested.content.addView(cloudLinkRow)

        cleanupCloudGroup = cleanupCloudNested.outer
        cleanupDetailContainer.addView(cleanupCloudGroup)

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

        cloudLinkRowSub.text = CloudProviderActivity.subtitle(this)
        styleSummaryRowTitle.text = currentPersonaSummary()

        refreshWaterfallDependentUi()
        refreshAllCleanupRows()
    }

    /** Everything downstream of the waterfall that needs recomputing after any edit to it
     *  (choice radios, group visibility, caption, key row) -- mirrors MainActivity's original
     *  saveWaterfallSteps() comment on why a full refresh (not just the step list) matters: an
     *  edit changes the effective simple choice, and with it the radios, subtitle, and the
     *  OpenAI-key row's visibility (#81).
     *
     *  The active LOCAL/CLOUD radio choice reads [CloudFeatureToggle.cleanupEnabled] directly
     *  (#37 follow-up), not the chain's shape: since [onSelectSimpleCleanup] now maintains a
     *  permanent LOCAL floor entry alongside whatever cloud providers exist (see
     *  [ProviderChain.withLocalFloor]), the chain routinely has more than one entry even in the
     *  simple "Local" or "Cloud" state, so [simpleCleanupChoiceForChain]'s single-entry-shape
     *  classification no longer reflects the user's actual choice here -- it would misreport
     *  CUSTOM the moment any cloud provider is configured. */
    private fun refreshWaterfallDependentUi() {
        if (!CloudFeatureToggle.cleanupEnabled(this)) pendingLocalCleanupSelection = false
        val persistedChoice = if (CloudFeatureToggle.cleanupEnabled(this)) SimpleCleanupChoice.CLOUD else SimpleCleanupChoice.LOCAL
        val choice = displayedCleanupChoice(persistedChoice, pendingLocalCleanupSelection)
        cleanupLocalRadio.isChecked = choice == SimpleCleanupChoice.LOCAL
        cleanupCloudRadio.isChecked = choice == SimpleCleanupChoice.CLOUD
        cleanupChoiceCaption.visibility = View.GONE
        cleanupLocalGroup.visibility = if (choice == SimpleCleanupChoice.LOCAL) View.VISIBLE else View.GONE
        cleanupCloudGroup.visibility = if (choice == SimpleCleanupChoice.CLOUD) View.VISIBLE else View.GONE
    }

    /** Applies a change to the local/cleanup toggles, inserting the one-time consent dialog from
     *  #23 when the change would first combine local transcription with cleanup. */
    private fun applyLocalCleanupChange(useLocal: Boolean, usePostProcessing: Boolean) {
        val hasConsented = prefs().getBoolean(KEY_LOCAL_CLEANUP_CONSENT, false)
        val cleanupIsLocalOnly = !CloudFeatureToggle.cleanupEnabled(this)
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

    // --- Style summary (#103) ---

    /** Title text for the Style summary row: the currently active global persona's name,
     *  resolved through [PersonaRegistry] so a custom or seeded-legacy persona shows its real
     *  name instead of falling back to "Formal". Mirrors [WhisperAccessibilityService]'s own
     *  global-persona resolution so the Settings screen and the long-press quick menu never
     *  disagree about what's currently selected. */
    private fun currentPersonaSummary(): String {
        val prompt = prefs().getString("post_processing_prompt", PostProcessor.DEFAULT_PROMPT) ?: PostProcessor.DEFAULT_PROMPT
        val persona = PersonaRegistry.currentPersona(this, prefs().getString("cleanup_style", null), prompt)
        return "Style: ${persona.title}"
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
        val isActive = !CloudFeatureToggle.cleanupEnabled(this) &&
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
        val wasActive = !CloudFeatureToggle.cleanupEnabled(this) &&
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
                ensureLocalFloor(model.archive)
                CloudFeatureToggle.setCleanupEnabled(this, false)
                refresh()
            }
            SimpleCleanupChoice.CLOUD -> {
                pendingLocalCleanupSelection = false
                // Seed a default OpenAI entry only if the chain has no cloud-capable entry at
                // all yet (fresh install / a user who never visited Cloud Providers) -- if
                // Trevor already configured real providers there, switching Cleanup to "Cloud"
                // must use exactly those, never silently add or replace anything of his.
                val chain = ProviderChainStore.load(this)
                if (chain.capableEntriesFor(needsTranscription = false).none { it.kind != ProviderKind.LOCAL }) {
                    ProviderChainStore.save(this, ProviderChain(chain.entries + ProviderChainEntry(ProviderKind.OPENAI, PostProcessor.DEFAULT_MODEL)))
                }
                CloudFeatureToggle.setCleanupEnabled(this, true)
                refresh()
            }
            SimpleCleanupChoice.CUSTOM -> return
        }
        refreshWaterfallDependentUi()
    }

    /**
     * Ensures the persisted [ProviderChain] has a [ProviderKind.LOCAL] entry using [modelArchive]
     * (#37 follow-up, real regression Trevor hit live: the previous fix persisted "Local" as a
     * full chain OVERWRITE -- `ProviderChain(listOf(ProviderChainEntry(LOCAL, model)))` -- which
     * silently deleted every cloud provider entry the instant "Local" was tapped, even though
     * those providers, their credentials, and their order in the Cloud Providers screen were
     * never something this simple picker should be allowed to touch). Uses
     * [ProviderChain.withLocalFloor] to add/update only the LOCAL entry, leaving every other
     * entry exactly as CloudProviderActivity left it; [CloudFeatureToggle] is what actually
     * decides whether cleanup prefers local or cloud right now, not the chain's contents.
     */
    private fun ensureLocalFloor(modelArchive: String) {
        ProviderChainStore.save(this, ProviderChainStore.load(this).withLocalFloor(modelArchive))
    }

    companion object {
        private const val LP_MATCH = LinearLayout.LayoutParams.MATCH_PARENT
        private const val KEY_LOCAL_CLEANUP_CONSENT = "local_cleanup_consent_seen"
        private const val KEY_LOCAL_CLEANUP_MODEL_NAME = "local_cleanup_model_name"

        /** Category subtitle for MainActivity's Cleanup row (#93), e.g. "Off", "Local ·
         *  LFM2.5 350M", or "Cloud · GPT-4o mini" (#37 follow-up: reads
         *  [CloudFeatureToggle.cleanupEnabled] directly rather than the chain's shape -- see
         *  [refreshWaterfallDependentUi]'s kdoc for why the chain can no longer be classified by
         *  entry count once a permanent LOCAL floor coexists with cloud entries). */
        fun subtitle(context: android.content.Context): String {
            val prefs = context.getSharedPreferences("phonewhisper", android.content.Context.MODE_PRIVATE)
            if (!prefs.getBoolean("use_post_processing", false)) return "Off"
            return if (!CloudFeatureToggle.cleanupEnabled(context)) {
                val archive = prefs.getString(KEY_LOCAL_CLEANUP_MODEL_NAME, "") ?: ""
                val model = LOCAL_CLEANUP_MODEL_CATALOG.firstOrNull { it.archive == archive }
                    ?: LOCAL_CLEANUP_MODEL_CATALOG.first()
                "Local · ${model.name}"
            } else {
                val model = prefs.getString("cleanup_model", PostProcessor.DEFAULT_MODEL) ?: PostProcessor.DEFAULT_MODEL
                "Cloud · $model"
            }
        }
    }
}
