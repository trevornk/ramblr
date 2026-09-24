package com.trevornk.ramblr

import android.os.Bundle
import android.content.Intent
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
import android.content.res.ColorStateList

/**
 * "Transcription" category screen (#93 restructure, updated #95 Phase 3): local/cloud switch and
 * local model catalog. Cloud provider/credential management (which providers, keys, models) now
 * lives entirely on the unified CloudProviderActivity -- this screen just links to it when Cloud
 * is selected.
 */
class TranscriptionActivity : BaseSettingsActivity() {

    private lateinit var cloudSwitch: MaterialSwitch
    private lateinit var modelContainer: View
    private lateinit var localModelsHeader: TextView
    private lateinit var cloudLinkGroup: View
    private lateinit var cloudSectionHeader: TextView
    private lateinit var cloudLinkRowSub: TextView
    private lateinit var routingSummaryRowSub: TextView

    private data class ModelRowViews(
        val radio: MaterialRadioButton,
        val progress: LinearProgressIndicator,
        val subtitle: TextView,
        val dlBtn: MaterialButton,
        val deleteBtn: MaterialButton
    )

    private val modelRows = mutableMapOf<String, ModelRowViews>()
    private val modelDownloadState = mutableMapOf<String, WorkInfo.State>()
    // Gates the one-time completion side effects (auto-select + toast) behind having witnessed
    // the download live in THIS Activity instance -- WorkManager re-delivers retained SUCCEEDED
    // WorkInfo to every fresh observer, and rotation/fold recreates this Activity, so a plain
    // per-instance acked set re-selected stale downloads on every recreation (#76).
    private val modelDownloadGate = DownloadCompletionGate()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = vertical(0, 0)

        val header = TextView(this).apply {
            text = "Transcription"
            textSize = 32f
            setPadding(dp(24), dp(64), dp(24), dp(24))
        }
        root.addView(header)

        root.addView(TextView(this).apply {
            text = "Required — choose how speech becomes text. Local (default): fastest, fully " +
                "private, works with no internet. Cloud: uses your Cloud provider chain."
            textSize = 14f
            setTextColor(attrColor(android.R.attr.textColorSecondary))
            setPadding(dp(24), 0, dp(24), dp(8))
        })

        val isCloud = !prefs().getBoolean("use_local", true)

        cloudSwitch = MaterialSwitch(this).apply {
            isChecked = isCloud
            isClickable = false
        }
        val cloudRow = settingsRow("Use cloud transcription", "Uses the Cloud provider chain", cloudSwitch) {
            val newCloud = !cloudSwitch.isChecked
            val usePostProcessing = prefs().getBoolean("use_post_processing", false)
            applyLocalCleanupChange(useLocal = !newCloud, usePostProcessing = usePostProcessing)
        }
        root.addView(cloudRow)

        // Local models -- a SUBSECTION of Transcription (#49), shown while Local is selected OR
        // (#276) while Cloud is selected with "fall back to on-device if cloud fails" on, so
        // setting up the fallback model doesn't require flipping the switch back and forth. The
        // header text itself says which case this is -- see [refresh].
        val localModelsGroup = nestedGroup()
        localModelsHeader = subsectionHeader("Local models", indent = 0)
        localModelsGroup.content.addView(localModelsHeader)
        for (m in MODEL_CATALOG) localModelsGroup.content.addView(buildModelRow(m))
        modelContainer = localModelsGroup.outer
        root.addView(modelContainer)

        // #95 Phase 3: link into the unified CloudProviderActivity instead of a contextual
        // OpenAI-only key row -- provider/credential management for transcription is now shared
        // with cleanup on one screen. #276: also shown while Local is selected when "fall back to
        // cloud if on-device fails" is on, labeled via [cloudSectionHeader].
        val cloudLinkGroupNested = nestedGroup()
        cloudSectionHeader = subsectionHeader("Fallback: cloud providers", indent = 0).apply {
            visibility = View.GONE
        }
        cloudLinkGroupNested.content.addView(cloudSectionHeader)
        val cloudLinkRow = settingsRow("Cloud provider chain", CloudProviderActivity.subtitle(this), indent = 0) {
            startActivity(Intent(this, CloudProviderActivity::class.java))
        }
        cloudLinkRowSub = cloudLinkRow.findViewWithTag("subtitle")
        cloudLinkGroupNested.content.addView(cloudLinkRow)
        cloudLinkGroup = cloudLinkGroupNested.outer
        root.addView(cloudLinkGroup)

        // #276: read-only "what will actually happen" line, since the mode presets + per-feature
        // overrides + two fallback toggles on the Cloud screen can otherwise leave it unclear
        // which provider (if any) actually wins.
        root.addView(sectionHeader("Effective routing"))
        val routingSummaryRow = settingsRow("Transcription", effectiveTranscriptionRouting(), indent = 0)
        routingSummaryRowSub = routingSummaryRow.findViewWithTag("subtitle")
        root.addView(routingSummaryRow)

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
        val useLocal = prefs().getBoolean("use_local", true)
        prefetchVadModel(useLocal)
        cloudSwitch.isChecked = !useLocal

        // #276: don't hide the inactive side outright -- a fallback toggle can still route to it
        // at runtime, so keep it visible (labeled "Fallback") whenever that toggle is on.
        val localState = fallbackSectionState(
            isActiveSide = useLocal,
            fallbackAllowed = DictationModeToggle.allowLocalFallback(this),
        )
        modelContainer.visibility = if (localState == FallbackSectionState.HIDDEN) View.GONE else View.VISIBLE
        localModelsHeader.text = if (localState == FallbackSectionState.FALLBACK) "Fallback: on-device model" else "Local models"

        val allowCloudFallback = DictationModeToggle.allowCloudFallback(this)
        cloudLinkRowSub.text = CloudProviderActivity.subtitle(this)
        val showCloud = shouldShowOpenAiKeyRowForTranscription(useLocal, allowCloudFallback)
        cloudLinkGroup.visibility = if (showCloud) View.VISIBLE else View.GONE
        val cloudState = fallbackSectionState(isActiveSide = !useLocal, fallbackAllowed = allowCloudFallback)
        cloudSectionHeader.visibility = if (cloudState == FallbackSectionState.FALLBACK) View.VISIBLE else View.GONE

        routingSummaryRowSub.text = effectiveTranscriptionRouting()

        val cur = prefs().getString("model_name", "") ?: ""
        val curModel = MODEL_CATALOG.firstOrNull { it.archive == cur }
        if (cur.isBlank() || curModel == null || !ModelDownloader.isInstalled(this, curModel)) {
            MODEL_CATALOG.firstOrNull { ModelDownloader.isInstalled(this, it) }
                ?.let { selectModel(it.archive) }
        }
        refreshAllCards()
    }

    /** "Transcription: ..." routing summary (#276), delegating to the pure [EffectiveRouting]
     *  formatter so this row can never disagree with what [WhisperAccessibilityService] would
     *  actually do. */
    private fun effectiveTranscriptionRouting(): String = "Transcription: " + EffectiveRouting.transcription(
        chain = ProviderChainStore.load(this),
        useLocalTranscription = prefs().getBoolean("use_local", true),
        allowLocalFallback = DictationModeToggle.allowLocalFallback(this),
        allowCloudFallback = DictationModeToggle.allowCloudFallback(this),
        isConfigured = { ProviderCredentialStore.isConfigured(this, it) },
    )

    /**
     * Provisions the Silero VAD model whenever this screen shows on-device transcription as the
     * active mode (#169), covering the user who switches to local here rather than in onboarding.
     *
     * Called from [refresh] so it also catches the pre-existing local user who upgraded from a
     * build without this prefetch: their first visit to this screen provisions the model instead
     * of leaving it to a dictation that would decode unsegmented. Cheap to call repeatedly — the
     * predicate short-circuits once the model is on disk, and `ExistingWorkPolicy.KEEP` collapses
     * a duplicate enqueue during the download window.
     */
    private fun prefetchVadModel(useLocal: Boolean) {
        if (!VadModelProvisioning.shouldPrefetchForLocalMode(
                localTranscriptionSelected = useLocal,
                modelInstalled = ModelDownloader.vadModelFile(this, SILERO_VAD_MODEL) != null,
            )
        ) {
            return
        }
        android.util.Log.i(TAG, "Local mode active — prefetching VAD model for segmented decode (#169)")
        ModelDownloadWorker.enqueue(this, SILERO_VAD_MODEL)
    }

    /**
     * Applies a change to the local/cleanup toggles, inserting the one-time consent dialog from
     * #23 when the change would first combine local transcription with cleanup. Mirrors
     * MainActivity's original applyLocalCleanupChange verbatim -- Transcription's own switch can
     * flip Cleanup's underlying use_post_processing pref indirectly (turning cloud transcription
     * off never touches cleanup, but turning it back to local re-triggers the same consent check
     * cleanup's own switch uses), so this logic has to live wherever that switch lives.
     */
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

        // Declining consent here is declining a *transcription* mode change, not a cleanup change:
        // revert the transcription toggle and leave cleanup untouched, rather than silently turning
        // cleanup off (which was surprising and unannounced) (M11).
        val declineChange = {
            toast("Kept your current transcription mode — cleanup unchanged")
            refresh() // no pref written, so the switch snaps back to its persisted state
        }

        android.app.AlertDialog.Builder(this)
            .setTitle("Cleanup sends text off-device")
            .setMessage(
                "Local transcription keeps audio on your phone, but cleanup sends the " +
                    "transcribed text to ${CleanupDestination.consentHost(ProviderChainStore.load(this))} to fix " +
                    "grammar and punctuation. Switch to local transcription anyway?"
            )
            .setPositiveButton("Switch anyway") { _, _ ->
                prefs().edit()
                    .putBoolean("use_local", useLocal)
                    .putBoolean("use_post_processing", true)
                    .putBoolean(KEY_LOCAL_CLEANUP_CONSENT, true)
                    .apply()
                refresh()
            }
            .setNegativeButton("Cancel") { _, _ -> declineChange() }
            .setOnCancelListener { declineChange() }
            .show()
    }

    // --- Model Rows (verbatim from MainActivity, #93) ---

    private fun buildModelRow(model: Model): View {
        val radio = MaterialRadioButton(this).apply {
            isClickable = false
            buttonTintList = ColorStateList.valueOf(attrColor(com.google.android.material.R.attr.colorPrimary))
        }
        val dlBtn = MaterialButton(this, null, com.google.android.material.R.attr.materialIconButtonStyle).apply {
            text = "↓"
            textSize = 18f
            setTextColor(attrColor(com.google.android.material.R.attr.colorPrimary))
            setOnClickListener { onModelAction(model) }
        }
        val deleteBtn = MaterialButton(this, null, com.google.android.material.R.attr.materialIconButtonStyle).apply {
            text = "🗑"
            textSize = 16f
            setOnClickListener { confirmDeleteModel(model) }
        }

        val progress = LinearProgressIndicator(this).apply {
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(LP_MATCH, dp(4)).apply {
                topMargin = dp(8)
            }
        }

        val rightContainer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            addView(dlBtn)
            addView(deleteBtn)
            addView(radio)
        }

        val row = settingsRow(model.name, "${model.quality} · ${model.sizeMb} MB", rightContainer) {
            onModelAction(model)
        }

        val textContainer = row.getChildAt(0) as LinearLayout
        textContainer.addView(progress)

        modelRows[model.archive] = ModelRowViews(
            radio, progress, textContainer.findViewWithTag("subtitle"), dlBtn, deleteBtn
        )
        refreshCard(model)
        observeDownload(model)

        return row
    }

    private fun onModelAction(model: Model) {
        if (ModelDownloader.isInstalled(this, model)) {
            selectModel(model.archive)
            return
        }
        if (ModelDownloadWorker.isInFlight(modelDownloadState[model.archive])) return
        // Routed through the consent prompt (not enqueue directly) since #197 put the first
        // non-free entry in MODEL_CATALOG: enqueue's own consent chokepoint would correctly
        // refuse Parakeet Unified 0.6B without recorded consent, but silently -- a dead tap.
        // For every freely-licensed entry this is byte-for-byte the old enqueue behavior
        // (canDownload short-circuits true, no dialog).
        downloadModelWithLicenseConsent(model)
    }

    private fun observeDownload(model: Model) {
        WorkManager.getInstance(this)
            .getWorkInfosForUniqueWorkLiveData(ModelDownloadWorker.workName(model.archive))
            .observe(this) { infos -> onWorkInfos(model, infos) }
    }

    private fun onWorkInfos(model: Model, infos: List<WorkInfo>) {
        val views = modelRows[model.archive] ?: return
        val info = infos.firstOrNull { !it.state.isFinished } ?: infos.firstOrNull()
        modelDownloadState[model.archive] = info?.state ?: WorkInfo.State.CANCELLED
        if (info != null && !info.state.isFinished) modelDownloadGate.onInFlight(info.id.toString())

        when (info?.state) {
            WorkInfo.State.ENQUEUED, WorkInfo.State.RUNNING -> {
                views.dlBtn.isEnabled = false
                views.progress.visibility = View.VISIBLE
                val phase = info.progress.getString(ModelDownloadWorker.KEY_PHASE)
                if (phase == ModelDownloadWorker.PHASE_EXTRACTING) {
                    views.progress.isIndeterminate = true
                    views.subtitle.text = "Extracting..."
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
                if (modelDownloadGate.shouldActOnSuccess(info.id.toString())) {
                    selectModel(model.archive)
                    toast("${model.name} ready!")
                }
                refreshCard(model)
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
                refreshCard(model)
            }
        }
    }

    private fun selectModel(archive: String) {
        prefs().edit().putString("model_name", archive).apply()
        WhisperAccessibilityService.instance?.reloadModel()
        refreshAllCards(); refresh()
    }

    /** Confirms before uninstalling [model] (#51) -- names the currently-selected model
     *  explicitly so deleting it isn't a surprise. */
    private fun confirmDeleteModel(model: Model) {
        val isActive = prefs().getString("model_name", "") == model.archive
        val activeNote = if (isActive) " This is your currently selected model." else ""
        android.app.AlertDialog.Builder(this)
            .setTitle("Delete ${model.name}?")
            .setMessage("This frees ${model.sizeMb} MB of storage.$activeNote You can download it again later.")
            .setPositiveButton("Delete") { _, _ -> deleteModel(model) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun deleteModel(model: Model) {
        ModelDownloader.delete(this, model)
        val remaining = MODEL_CATALOG
            .filter { it.archive != model.archive && ModelDownloader.isInstalled(this, it) }
            .map { it.archive }
        val newSelection = ModelDownloader.resolveSelectionAfterDelete(
            prefs().getString("model_name", "") ?: "", model.archive, remaining
        )
        prefs().edit().putString("model_name", newSelection).apply()
        WhisperAccessibilityService.instance?.reloadModel()
        toast("${model.name} deleted")
        refreshAllCards(); refresh()
    }

    private fun refreshCard(model: Model) {
        val views = modelRows[model.archive] ?: return
        val active = prefs().getString("model_name", "") == model.archive
        val installed = ModelDownloader.isInstalled(this, model)

        views.radio.isChecked = active
        views.radio.visibility = if (installed) View.VISIBLE else View.GONE
        views.dlBtn.visibility = if (installed) View.GONE else View.VISIBLE
        views.deleteBtn.visibility = if (installed) View.VISIBLE else View.GONE

        if (views.progress.visibility == View.GONE) {
            views.subtitle.text = "${model.quality} · ${model.sizeMb} MB"
        }
    }

    private fun refreshAllCards() = MODEL_CATALOG.forEach { refreshCard(it) }

    companion object {
        private const val TAG = "TranscriptionActivity"
        private const val LP_MATCH = LinearLayout.LayoutParams.MATCH_PARENT
        private const val KEY_LOCAL_CLEANUP_CONSENT = "local_cleanup_consent_seen"

        /** Category subtitle for MainActivity's Transcription row (#93), e.g. "Local · Parakeet
         *  0.6B" or "Cloud · OpenAI · gpt-4o-transcribe" (#101: previously showed only "Cloud ·
         *  OpenAI" with no model, unlike the matching Cleanup row -- now uses
         *  [CleanupDestination.cloudTranscriptionSubtitleDetail] for the same "provider · model"
         *  detail at a glance). Pure/static so MainActivity can compute it without touching
         *  any view state owned by this Activity. */
        fun subtitle(context: android.content.Context): String {
            val prefs = context.getSharedPreferences("ramblr", android.content.Context.MODE_PRIVATE)
            val useLocal = prefs.getBoolean("use_local", true)
            if (!useLocal) {
                val chain = ProviderChainStore.load(context)
                val entry = CleanupDestination.firstCloudTranscription(chain)
                return if (entry != null) "Cloud · ${CleanupDestination.cloudTranscriptionSubtitleDetail(chain)}" else "Cloud · set up a provider"
            }
            val archive = prefs.getString("model_name", "") ?: ""
            val model = MODEL_CATALOG.firstOrNull { it.archive == archive && ModelDownloader.isInstalled(context, it) }
            return if (model != null) "Local · ${model.name}" else "Local · no model installed yet"
        }
    }
}
