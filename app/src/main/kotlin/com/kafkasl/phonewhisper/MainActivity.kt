package com.kafkasl.phonewhisper

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.text.Spannable
import android.text.SpannableString
import android.text.style.ForegroundColorSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.google.android.material.button.MaterialButton
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.radiobutton.MaterialRadioButton
import kotlin.concurrent.thread

class MainActivity : AppCompatActivity() {

    private lateinit var statusSubtitle: TextView
    private lateinit var audioRowSub: TextView
    private lateinit var accRowSub: TextView
    private lateinit var keyRowSub: TextView
    private lateinit var baseUrlRowSub: TextView
    private lateinit var modelRowSub: TextView
    private lateinit var promptRowSub: TextView
    private lateinit var promptRow: LinearLayout
    private lateinit var vocabularyRowSub: TextView
    private lateinit var vocabularyRow: LinearLayout
    private lateinit var modelContainer: LinearLayout
    private lateinit var promptContainer: LinearLayout
    private lateinit var cloudSwitch: MaterialSwitch
    private lateinit var postProcessSwitch: MaterialSwitch
    private lateinit var postProcessRowSub: TextView
    private lateinit var previewBeforeInjectSwitch: MaterialSwitch
    private lateinit var historyEnabledSwitch: MaterialSwitch
    private lateinit var debugVisibilitySwitch: MaterialSwitch

    // Overlay appearance (#43/#53) -- rows read fresh from OverlayAppearancePrefs/OverlayIconStore
    // on every refreshOverlayAppearanceRows() call rather than caching values on these fields.
    private lateinit var overlaySizeRow: LinearLayout
    private lateinit var overlayBorderColorRow: LinearLayout
    private lateinit var overlayFillColorRow: LinearLayout
    private lateinit var overlayGlyphColorRow: LinearLayout
    private lateinit var overlayCustomIconRow: LinearLayout
    private lateinit var overlayRemoveCustomIconRow: LinearLayout

    // Modern Photo Picker (#43): falls back to ACTION_OPEN_DOCUMENT/ACTION_GET_CONTENT
    // automatically on API levels/devices where the system picker isn't available -- see
    // ActivityResultContracts.PickVisualMedia's own isPhotoPickerAvailable() check. Must be
    // registered unconditionally during field init (before onCreate), per the Activity Result API.
    private val pickOverlayIcon = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri == null) return@registerForActivityResult
        thread {
            val saved = OverlayIconStore.save(this, uri)
            runOnUiThread {
                if (saved) {
                    OverlayAppearancePrefs.setHasCustomIcon(this, true)
                    onOverlayAppearanceChanged()
                    toast("Custom icon set")
                } else {
                    toast("Couldn't load that image")
                }
            }
        }
    }

    // Tier 2 (#38) — everything below the top-level cleanup toggle, shown/hidden as one unit
    // instead of each row separately, since none of it matters while cleanup is off.
    private lateinit var cleanupDetailContainer: LinearLayout
    private lateinit var cleanupLocalRadio: MaterialRadioButton
    private lateinit var cleanupCloudRadio: MaterialRadioButton
    private lateinit var cleanupChoiceCaption: TextView
    private val cleanupModelRows = mutableMapOf<String, ModelRowViews>()
    private val cleanupModelDownloadState = mutableMapOf<String, WorkInfo.State>()
    private val cleanupModelDownloadAcked = mutableSetOf<String>()

    // "Advanced" waterfall disclosure (#38) — genuinely collapsed by default; the full #32/#37
    // editor underneath is untouched, just not front-and-center for a first-time user.
    private lateinit var advancedContainer: LinearLayout
    private lateinit var advancedChevron: TextView
    private var advancedExpanded = false

    // Streaming live preview (#29) — separate from the offline model rows above: this switch and
    // its model row never touch "model_name" (the offline model selection).
    private lateinit var streamingPreviewSwitch: MaterialSwitch
    private lateinit var streamingPreviewRowSub: TextView
    private val streamingModelRows = mutableMapOf<String, ModelRowViews>()
    private val streamingModelDownloadState = mutableMapOf<String, WorkInfo.State>()
    private val streamingModelDownloadAcked = mutableSetOf<String>()

    // Cleanup waterfall (#32) — credential rows + the reorderable step list container.
    private lateinit var omnirouteKeyRowSub: TextView
    private lateinit var openaiDirectKeyRowSub: TextView
    private lateinit var anthropicDirectKeyRowSub: TextView
    private lateinit var waterfallStepsContainer: LinearLayout

    private val modelRows = mutableMapOf<String, ModelRowViews>()
    private val promptRows = mutableMapOf<String, PromptRowViews>()

    // Latest WorkInfo.State observed per model archive, kept in sync by the
    // WorkManager LiveData observer registered in buildModelRow. Read by
    // onModelAction to make a duplicate tap on an in-flight download a no-op.
    private val modelDownloadState = mutableMapOf<String, WorkInfo.State>()
    // Archives we've already reacted to a SUCCEEDED WorkInfo for in this Activity
    // instance, so re-observing old WorkInfo (e.g. after rotation) doesn't
    // re-toast/re-select a model that finished downloading earlier.
    private val modelDownloadAcked = mutableSetOf<String>()

    // First-run wizard state (#6). Tracked in-memory so a dialog already on screen is never
    // duplicated by a stray onResume, and reset per Activity instance so a fresh launch always
    // re-shows the intro while setup remains incomplete.
    private var onboardingIntroShown = false
    private var onboardingDialog: android.app.AlertDialog? = null

    private data class ModelRowViews(
        val radio: MaterialRadioButton,
        val progress: LinearProgressIndicator,
        val subtitle: TextView,
        val dlBtn: MaterialButton,
        val deleteBtn: MaterialButton
    )

    private data class PromptRowViews(
        val radio: MaterialRadioButton,
        val subtitle: TextView
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = vertical(0, 0)

        // Top large header (like "Connected devices")
        val header = TextView(this).apply {
            text = "Ramblr"
            textSize = 32f
            setPadding(dp(24), dp(64), dp(24), dp(24))
        }
        root.addView(header)

        // Status row
        val statusRow = settingsRow("Status", "Checking...")
        statusSubtitle = statusRow.findViewWithTag("subtitle")
        root.addView(statusRow)

        // --- Setup Section ---
        root.addView(sectionHeader("Setup"))

        val audioRow = settingsRow("Audio permission", "Checking...") {
            if (!hasPerm(Manifest.permission.RECORD_AUDIO)) {
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), 1)
            }
        }
        audioRowSub = audioRow.findViewWithTag("subtitle")
        root.addView(audioRow)

        val accRow = settingsRow("Accessibility service", "Checking...") {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        accRowSub = accRow.findViewWithTag("subtitle")
        root.addView(accRow)

        // ============================================================
        // Tier 1 -- Transcription (#38): required, but has a working default. Local needs no
        // download beyond picking a model size below, and nothing here auto-downloads anything.
        // ============================================================
        root.addView(sectionHeader("Transcription"))
        root.addView(TextView(this).apply {
            text = "Required — choose how speech becomes text. Local (default): fastest, fully " +
                "private, works with no internet. Cloud: sends audio to OpenAI using your own API key."
            textSize = 14f
            setTextColor(attrColor(android.R.attr.textColorSecondary))
            setPadding(dp(24), 0, dp(24), dp(8))
        })

        val isCloud = !prefs().getBoolean("use_local", true)

        cloudSwitch = MaterialSwitch(this).apply {
            isChecked = isCloud
            isClickable = false
        }
        val cloudRow = settingsRow("Use cloud transcription", "Requires OpenAI API key", cloudSwitch) {
            val newCloud = !cloudSwitch.isChecked
            val usePostProcessing = prefs().getBoolean("use_post_processing", false)
            applyLocalCleanupChange(useLocal = !newCloud, usePostProcessing = usePostProcessing)
        }
        root.addView(cloudRow)

        // Local Models section
        modelContainer = vertical(0)
        modelContainer.addView(sectionHeader("Local models"))
        for (m in MODEL_CATALOG) modelContainer.addView(buildModelRow(m))
        root.addView(modelContainer)

        // Shared by cloud transcription above and cloud cleanup below -- one key, one place to set it.
        val keyRow = settingsRow("OpenAI API Key", "Tap to set") { promptApiKey() }
        keyRowSub = keyRow.findViewWithTag("subtitle")
        root.addView(keyRow)

        // ============================================================
        // Tier 2 -- Cleanup / Post-Processing (#38): fully optional, off by default. No model
        // download and no API key is required unless the user opts in below.
        // ============================================================
        root.addView(sectionHeader("Cleanup (optional)"))

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

        promptContainer = vertical(0)
        promptContainer.addView(sectionHeader("Style"))
        for (preset in promptPresets()) promptContainer.addView(buildPromptRow(preset))
        cleanupDetailContainer.addView(promptContainer)

        promptRow = settingsRow("Edit current prompt", currentPrompt()) { promptPostProcessing() }
        promptRowSub = promptRow.findViewWithTag("subtitle")
        promptRowSub.maxLines = 2
        promptRowSub.ellipsize = android.text.TextUtils.TruncateAt.END
        cleanupDetailContainer.addView(promptRow)

        // Simple primary choice (#38): Local vs Cloud, standing in front of the full waterfall
        // editor under Advanced below. See simpleCleanupChoiceFor().
        cleanupDetailContainer.addView(sectionHeader("How cleanup runs"))

        cleanupLocalRadio = MaterialRadioButton(this).apply {
            isClickable = false
            buttonTintList = ColorStateList.valueOf(attrColor(com.google.android.material.R.attr.colorPrimary))
        }
        cleanupDetailContainer.addView(
            settingsRow("Local", "Private, runs on-device, no API key needed", cleanupLocalRadio) {
                onSelectSimpleCleanup(SimpleCleanupChoice.LOCAL)
            }
        )
        for (m in LOCAL_CLEANUP_MODEL_CATALOG) cleanupDetailContainer.addView(buildCleanupModelRow(m))

        cleanupCloudRadio = MaterialRadioButton(this).apply {
            isClickable = false
            buttonTintList = ColorStateList.valueOf(attrColor(com.google.android.material.R.attr.colorPrimary))
        }
        cleanupDetailContainer.addView(
            settingsRow("Cloud", "Uses your OpenAI API key, billed pay-per-use", cleanupCloudRadio) {
                onSelectSimpleCleanup(SimpleCleanupChoice.CLOUD)
            }
        )

        val baseUrlRow = settingsRow("Cleanup API base URL", cleanupBaseUrl()) { promptCleanupBaseUrl() }
        baseUrlRowSub = baseUrlRow.findViewWithTag("subtitle")
        cleanupDetailContainer.addView(baseUrlRow)

        val modelRow = settingsRow("Cleanup model name", cleanupModel()) { promptCleanupModel() }
        modelRowSub = modelRow.findViewWithTag("subtitle")
        cleanupDetailContainer.addView(modelRow)

        cleanupChoiceCaption = TextView(this).apply {
            text = "Doesn't match a simple choice — your existing configuration is preserved under Advanced below."
            textSize = 12f
            setTextColor(attrColor(android.R.attr.textColorSecondary))
            setPadding(dp(24), 0, dp(24), dp(8))
            visibility = View.GONE
        }
        cleanupDetailContainer.addView(cleanupChoiceCaption)

        // Preview-before-inject (#40) — off by default so cleanup keeps auto-injecting exactly as
        // it always has; turning this on holds the cleaned-up candidate for a tap-to-commit instead.
        previewBeforeInjectSwitch = MaterialSwitch(this).apply {
            isChecked = PreviewBeforeInjectToggle.isEnabled(this@MainActivity)
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

        // --- Advanced: full cleanup waterfall (#32/#37) — genuinely collapsed by default (#38).
        // Additive and off by default: the simple choice above keeps working unchanged until the
        // user adds at least one waterfall step here (see ADR-0001's "zero behavior change").
        advancedChevron = TextView(this).apply {
            text = "▸"
            textSize = 18f
            setTextColor(attrColor(android.R.attr.textColorSecondary))
        }
        cleanupDetailContainer.addView(
            settingsRow("Advanced", "Multi-step waterfall, credentials, fallback order (power users)", advancedChevron) {
                toggleAdvanced()
            }
        )

        advancedContainer = vertical(0).apply { visibility = View.GONE }
        advancedContainer.addView(TextView(this).apply {
            text = "Try multiple cleanup providers in order, falling through on failure. Leave empty to keep using the simple choice above."
            textSize = 14f
            setTextColor(attrColor(android.R.attr.textColorSecondary))
            setPadding(dp(24), 0, dp(24), dp(8))
        })

        val omnirouteKeyRow = settingsRow("OmniRoute key", credentialSubtitle(CleanupCredentialSlot.OMNIROUTE)) {
            promptCleanupCredential(CleanupCredentialSlot.OMNIROUTE, "OmniRoute key")
        }
        omnirouteKeyRowSub = omnirouteKeyRow.findViewWithTag("subtitle")
        advancedContainer.addView(omnirouteKeyRow)

        val openaiDirectKeyRow = settingsRow("Direct OpenAI key (cleanup)", credentialSubtitle(CleanupCredentialSlot.OPENAI_DIRECT)) {
            promptCleanupCredential(CleanupCredentialSlot.OPENAI_DIRECT, "Direct OpenAI key")
        }
        openaiDirectKeyRowSub = openaiDirectKeyRow.findViewWithTag("subtitle")
        advancedContainer.addView(openaiDirectKeyRow)

        val anthropicDirectKeyRow = settingsRow("Direct Anthropic key", credentialSubtitle(CleanupCredentialSlot.ANTHROPIC_DIRECT)) {
            promptCleanupCredential(CleanupCredentialSlot.ANTHROPIC_DIRECT, "Direct Anthropic key")
        }
        anthropicDirectKeyRowSub = anthropicDirectKeyRow.findViewWithTag("subtitle")
        advancedContainer.addView(anthropicDirectKeyRow)

        waterfallStepsContainer = vertical(0)
        advancedContainer.addView(waterfallStepsContainer)

        advancedContainer.addView(settingsRow("Add waterfall step", "OmniRoute / Direct OpenAI / Direct Anthropic / Local") {
            promptAddOrEditStep(null) { newStep -> saveWaterfallSteps(waterfallSteps() + newStep) }
        })

        cleanupDetailContainer.addView(advancedContainer)
        root.addView(cleanupDetailContainer)

        // ============================================================
        // Tier 3 -- Everything else (#38): clearly optional, sensible defaults, not interleaved
        // with the required Transcription tier above.
        // ============================================================
        root.addView(sectionHeader("More (optional)"))

        vocabularyRow = settingsRow("Personal vocabulary", vocabularySummary()) { promptVocabulary() }
        vocabularyRowSub = vocabularyRow.findViewWithTag("subtitle")
        root.addView(vocabularyRow)

        val isHistoryEnabled = prefs().getBoolean(KEY_HISTORY_ENABLED, true)
        historyEnabledSwitch = MaterialSwitch(this).apply {
            isChecked = isHistoryEnabled
            isClickable = false
        }
        val historyToggleRow = settingsRow(
            "Save dictation history",
            "Keeps past transcripts on-device so a failed insertion isn't lost",
            historyEnabledSwitch
        ) { onHistoryToggle(!historyEnabledSwitch.isChecked) }
        root.addView(historyToggleRow)

        root.addView(settingsRow("Dictation history", "Recover past transcripts, tap to copy") { showHistory() })

        // Debug/visibility toggle (#33): off by default, so it doesn't clutter the common case.
        // Currently the only thing it gates is dictation history's "paid fallback" badge, but it's
        // named generically so future debug-ish affordances can share it (see ADR-0001).
        debugVisibilitySwitch = MaterialSwitch(this).apply {
            isChecked = DebugVisibilityToggle.isEnabled(this@MainActivity)
            isClickable = false
        }
        val debugVisibilityRow = settingsRow(
            "Debug / visibility",
            "Shows extra under-the-hood detail, like which dictations used a paid cleanup fallback",
            debugVisibilitySwitch
        ) {
            val newVal = !debugVisibilitySwitch.isChecked
            DebugVisibilityToggle.setEnabled(this, newVal)
            debugVisibilitySwitch.isChecked = newVal
        }
        root.addView(debugVisibilityRow)

        // --- Overlay appearance (#43/#53) ---
        root.addView(sectionHeader("Overlay appearance"))
        root.addView(TextView(this).apply {
            text = "Customize how the floating mic button looks. A custom icon image replaces " +
                "the built-in circle entirely, so border/fill colors stop applying once one is set."
            textSize = 14f
            setTextColor(attrColor(android.R.attr.textColorSecondary))
            setPadding(dp(24), 0, dp(24), dp(8))
        })

        overlaySizeRow = settingsRow("Icon size", "") { promptOverlaySize() }
        root.addView(overlaySizeRow)

        overlayBorderColorRow = settingsRow("Border color", "") {
            pickOverlayColor("Border color", OverlayAppearancePrefs.load(this).borderColor) { color ->
                OverlayAppearancePrefs.setBorderColor(this, color)
                onOverlayAppearanceChanged()
            }
        }
        root.addView(overlayBorderColorRow)

        overlayFillColorRow = settingsRow("Fill color (idle only)", "") {
            pickOverlayColor("Fill color", OverlayAppearancePrefs.load(this).fillColor) { color ->
                OverlayAppearancePrefs.setFillColor(this, color)
                onOverlayAppearanceChanged()
            }
        }
        root.addView(overlayFillColorRow)

        overlayGlyphColorRow = settingsRow("Microphone icon color", "") {
            pickOverlayColor("Microphone icon color", OverlayAppearancePrefs.load(this).glyphColor) { color ->
                OverlayAppearancePrefs.setGlyphColor(this, color)
                onOverlayAppearanceChanged()
            }
        }
        root.addView(overlayGlyphColorRow)

        overlayCustomIconRow = settingsRow("Custom icon image", "") {
            pickOverlayIcon.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        }
        root.addView(overlayCustomIconRow)

        overlayRemoveCustomIconRow = settingsRow("Remove custom icon", "Go back to the default mic icon") {
            OverlayIconStore.delete(this)
            OverlayAppearancePrefs.setHasCustomIcon(this, false)
            onOverlayAppearanceChanged()
        }
        root.addView(overlayRemoveCustomIconRow)

        refreshOverlayAppearanceRows()

        // --- Streaming live preview (#29) ---
        root.addView(sectionHeader("Streaming live preview"))
        root.addView(TextView(this).apply {
            text = "Shows live partial text in the focused field as you speak, using a separate " +
                "on-device streaming model. The final text (after you tap to stop) still comes " +
                "from your regular transcription + cleanup settings above — this only changes " +
                "what's shown while recording."
            textSize = 14f
            setTextColor(attrColor(android.R.attr.textColorSecondary))
            setPadding(dp(24), 0, dp(24), dp(8))
        })

        streamingPreviewSwitch = MaterialSwitch(this).apply { isClickable = false }
        val streamingPreviewRow = settingsRow(
            "Streaming live preview", streamingPreviewSubtitle(), streamingPreviewSwitch
        ) { onStreamingPreviewToggle(!streamingPreviewSwitch.isChecked) }
        streamingPreviewRowSub = streamingPreviewRow.findViewWithTag("subtitle")
        root.addView(streamingPreviewRow)

        for (m in STREAMING_MODEL_CATALOG) root.addView(buildStreamingModelRow(m))

        setContentView(ScrollView(this).apply {
            setBackgroundColor(attrColor(android.R.attr.colorBackground))
            addView(root)
        })

        // The wizard requests mic permission itself as one of its steps; only auto-request here
        // for a returning user who's already past onboarding.
        val alreadyOnboarded = !OnboardingWizard.shouldShow(
            accessibilityEnabled = WhisperAccessibilityService.instance != null,
            onboardingComplete = prefs().getBoolean(KEY_ONBOARDING_COMPLETE, false)
        )
        if (alreadyOnboarded && !hasPerm(Manifest.permission.RECORD_AUDIO)) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), 1)
        }

        refresh()
    }

    override fun onResume() {
        super.onResume()
        // The floating overlay must not cover this screen's own switches (#35).
        WhisperAccessibilityService.setMainActivityForeground(true)
        refresh()
        advanceOnboarding()
    }

    override fun onPause() {
        super.onPause()
        WhisperAccessibilityService.setMainActivityForeground(false)
    }

    override fun onRequestPermissionsResult(c: Int, p: Array<String>, r: IntArray) {
        super.onRequestPermissionsResult(c, p, r); refresh(); advanceOnboarding()
    }

    // --- Model Rows ---

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
            gravity = Gravity.CENTER_VERTICAL
            addView(dlBtn)
            addView(deleteBtn)
            addView(radio)
        }

        val row = settingsRow(
            if (model.recommended) model.name else model.name,
            "${model.quality} · ${model.sizeMb} MB",
            rightContainer
        ) {
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

        // Duplicate tap while a download for this archive is already
        // enqueued/running is a no-op -- WorkManager's unique work would drop
        // a re-enqueue anyway, but this also avoids resetting live progress
        // text back to "Starting download...".
        if (ModelDownloadWorker.isInFlight(modelDownloadState[model.archive])) return

        ModelDownloadWorker.enqueue(this, model)
    }

    /** Observes this model's WorkManager state for the lifetime of the Activity,
     *  so download progress survives rotation/recreation without any Activity
     *  reference being captured by the download work itself. */
    private fun observeDownload(model: Model) {
        WorkManager.getInstance(this)
            .getWorkInfosForUniqueWorkLiveData(ModelDownloadWorker.workName(model.archive))
            .observe(this) { infos -> onWorkInfos(model, infos) }
    }

    private fun onWorkInfos(model: Model, infos: List<WorkInfo>) {
        val views = modelRows[model.archive] ?: return
        val info = infos.firstOrNull { !it.state.isFinished } ?: infos.firstOrNull()
        modelDownloadState[model.archive] = info?.state ?: WorkInfo.State.CANCELLED

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
                if (modelDownloadAcked.add(model.archive)) {
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

    /** Confirms before uninstalling [model] (#51) -- a large download is easy to lose to a stray
     *  tap otherwise. Names the currently-selected model explicitly so deleting it isn't a surprise. */
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

    /** Removes [model]'s files via [ModelDownloader] and, if it was the selected offline model,
     *  falls back to another installed model or clears the selection (#51) -- see
     *  [ModelDownloader.resolveSelectionAfterDelete]. */
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

    // --- Streaming live preview (#29) ---

    /** The streaming-model tier the user has picked (#50), read from [KEY_STREAMING_MODEL_NAME] --
     *  falling back to the catalog's recommended entry when unset or when the named archive is no
     *  longer in the catalog. See [ModelDownloader.resolveActiveModel]. */
    private fun selectedStreamingModel(): Model =
        ModelDownloader.resolveActiveModel(STREAMING_MODEL_CATALOG, prefs().getString(KEY_STREAMING_MODEL_NAME, "") ?: "")

    private fun buildStreamingModelRow(model: Model): View {
        val radio = MaterialRadioButton(this).apply {
            isClickable = false
            buttonTintList = ColorStateList.valueOf(attrColor(com.google.android.material.R.attr.colorPrimary))
        }
        val dlBtn = MaterialButton(this, null, com.google.android.material.R.attr.materialIconButtonStyle).apply {
            text = "↓"
            textSize = 18f
            setTextColor(attrColor(com.google.android.material.R.attr.colorPrimary))
            setOnClickListener { onStreamingModelAction(model) }
        }
        val deleteBtn = MaterialButton(this, null, com.google.android.material.R.attr.materialIconButtonStyle).apply {
            text = "🗑"
            textSize = 16f
            setOnClickListener { confirmDeleteStreamingModel(model) }
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
            onStreamingModelAction(model)
        }
        val textContainer = row.getChildAt(0) as LinearLayout
        textContainer.addView(progress)

        streamingModelRows[model.archive] = ModelRowViews(
            radio, progress, textContainer.findViewWithTag("subtitle"), dlBtn, deleteBtn
        )
        observeStreamingDownload(model)
        refreshStreamingModelRow(model)
        return row
    }

    private fun onStreamingModelAction(model: Model) {
        if (ModelDownloader.isInstalled(this, model)) {
            selectStreamingModel(model.archive)
            return
        }
        if (ModelDownloadWorker.isInFlight(streamingModelDownloadState[model.archive])) return
        ModelDownloadWorker.enqueue(this, model)
    }

    private fun selectStreamingModel(archive: String) {
        prefs().edit().putString(KEY_STREAMING_MODEL_NAME, archive).apply()
        WhisperAccessibilityService.instance?.reloadStreamingModel()
        refreshAllStreamingRows(); refresh()
    }

    /** Confirms before uninstalling [model] (#51); deleting the currently-selected tier turns off
     *  live preview automatically the next time [shouldUseStreamingPreview] is checked, since that
     *  gate always re-reads install state fresh. */
    private fun confirmDeleteStreamingModel(model: Model) {
        val isActive = selectedStreamingModel().archive == model.archive
        val activeNote = if (isActive) " This turns off streaming live preview until you pick another downloaded model." else ""
        android.app.AlertDialog.Builder(this)
            .setTitle("Delete ${model.name}?")
            .setMessage("This frees ${model.sizeMb} MB of storage.$activeNote You can download it again later.")
            .setPositiveButton("Delete") { _, _ -> deleteStreamingModel(model) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun deleteStreamingModel(model: Model) {
        ModelDownloader.delete(this, model)
        WhisperAccessibilityService.instance?.reloadStreamingModel()
        toast("${model.name} deleted")
        refreshAllStreamingRows(); refresh()
    }

    private fun observeStreamingDownload(model: Model) {
        WorkManager.getInstance(this)
            .getWorkInfosForUniqueWorkLiveData(ModelDownloadWorker.workName(model.archive))
            .observe(this) { infos -> onStreamingWorkInfos(model, infos) }
    }

    private fun onStreamingWorkInfos(model: Model, infos: List<WorkInfo>) {
        val views = streamingModelRows[model.archive] ?: return
        val info = infos.firstOrNull { !it.state.isFinished } ?: infos.firstOrNull()
        streamingModelDownloadState[model.archive] = info?.state ?: WorkInfo.State.CANCELLED

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
                if (streamingModelDownloadAcked.add(model.archive)) {
                    selectStreamingModel(model.archive)
                    toast("${model.name} ready — enable streaming live preview above")
                }
                refreshStreamingModelRow(model)
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
                refreshStreamingModelRow(model)
            }
        }
    }

    private fun refreshStreamingModelRow(model: Model) {
        val views = streamingModelRows[model.archive] ?: return
        val active = selectedStreamingModel().archive == model.archive
        val installed = ModelDownloader.isInstalled(this, model)

        views.radio.isChecked = active
        views.radio.visibility = if (installed) View.VISIBLE else View.GONE
        views.dlBtn.visibility = if (installed) View.GONE else View.VISIBLE
        views.deleteBtn.visibility = if (installed) View.VISIBLE else View.GONE

        if (views.progress.visibility == View.GONE) {
            views.subtitle.text = "${model.quality} · ${model.sizeMb} MB"
        }
    }

    private fun refreshAllStreamingRows() = STREAMING_MODEL_CATALOG.forEach { refreshStreamingModelRow(it) }

    private fun streamingPreviewSubtitle(): String =
        if (!ModelDownloader.isInstalled(this, selectedStreamingModel())) "Download the streaming model below first"
        else "Shows live partial results in the field as you speak"

    /** Flips the opt-in streaming-preview setting (#29). Enabling while the selected streaming
     *  model isn't installed is refused with a nudge toward the download row below, rather than
     *  silently leaving the switch on with nothing to back it (see [shouldUseStreamingPreview]). */
    private fun onStreamingPreviewToggle(enabling: Boolean) {
        if (enabling && !ModelDownloader.isInstalled(this, selectedStreamingModel())) {
            toast("Download the streaming model first")
            return
        }
        prefs().edit().putBoolean(KEY_STREAMING_PREVIEW, enabling).apply()
        WhisperAccessibilityService.instance?.reloadStreamingModel()
        refresh()
    }

    // --- Simple Local/Cloud cleanup choice (#38) ---

    /** The local-cleanup tier the user has picked (#50), read from [KEY_LOCAL_CLEANUP_MODEL_NAME]
     *  -- falling back to the catalog's recommended entry when unset or when the named archive is
     *  no longer in the catalog. See [ModelDownloader.resolveActiveModel]. */
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

    /** Confirms before uninstalling [model] (#51), warning when it's both the currently-selected
     *  tier and backs the active "Local" cleanup choice, since deleting it reverts that choice
     *  back to Cloud below. */
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

    /** Removes [model] via [ModelDownloader] and, if it was the selected tier backing the active
     *  "Local" simple cleanup choice, falls back to Cloud (#51) via the same path a user tapping
     *  "Cloud" would take -- rather than leaving the waterfall's one step pointing at a deleted
     *  file (the executor already fails that gracefully per step, but a stale-but-unusable "Local"
     *  selection isn't a state worth leaving the user in). */
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
                if (cleanupModelDownloadAcked.add(model.archive)) {
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

    /** Applies the user's tap on the simple Local/Cloud cleanup radio (#38) by replacing the
     *  waterfall with the single matching step -- see [simpleCleanupChoiceFor] for how the choice
     *  is read back. A power user's existing multi-step/custom Advanced configuration is only
     *  ever touched by an explicit tap here, never silently overwritten by this restructure. */
    private fun onSelectSimpleCleanup(choice: SimpleCleanupChoice) {
        when (choice) {
            SimpleCleanupChoice.LOCAL -> {
                val model = selectedCleanupModel()
                if (!ModelDownloader.isInstalled(this, model)) {
                    toast("Download the local cleanup model first")
                    return
                }
                saveWaterfallSteps(listOf(CleanupStep(CleanupStepGroup.LOCAL_LLM, model.archive)))
            }
            SimpleCleanupChoice.CLOUD ->
                saveWaterfallSteps(listOf(CleanupStep(CleanupStepGroup.LEGACY, PostProcessor.DEFAULT_MODEL)))
            SimpleCleanupChoice.CUSTOM -> return
        }
        refreshSimpleCleanupChoice()
    }

    private fun refreshSimpleCleanupChoice() {
        val choice = simpleCleanupChoiceFor(CleanupWaterfallStore.load(this))
        cleanupLocalRadio.isChecked = choice == SimpleCleanupChoice.LOCAL
        cleanupCloudRadio.isChecked = choice == SimpleCleanupChoice.CLOUD
        cleanupChoiceCaption.visibility = if (choice == SimpleCleanupChoice.CUSTOM) View.VISIBLE else View.GONE
    }

    /** Expands/collapses the Advanced waterfall editor (#38); always starts collapsed each time
     *  Settings is opened, restored purely in-memory -- no persisted "was expanded" state. */
    private fun toggleAdvanced() {
        advancedExpanded = !advancedExpanded
        advancedContainer.visibility = if (advancedExpanded) View.VISIBLE else View.GONE
        advancedChevron.text = if (advancedExpanded) "▾" else "▸"
    }

    // --- Prompt Rows ---

    private fun buildPromptRow(preset: PromptPreset): View {
        val radio = MaterialRadioButton(this).apply {
            isClickable = false
            buttonTintList = ColorStateList.valueOf(attrColor(com.google.android.material.R.attr.colorPrimary))
        }

        val row = settingsRow(preset.title, preset.subtitle, radio) {
            selectPrompt(preset.key)
        }

        promptRows[preset.key] = PromptRowViews(radio, row.findViewWithTag("subtitle"))
        refreshPromptRow(preset)
        return row
    }

    private fun selectPrompt(key: String) {
        if (key == "custom") {
            // Reactivate the saved custom prompt without touching the remembered style, so
            // switching back to a style later still recalls where the user left off.
            prefs().edit().putString("post_processing_prompt", customPrompt()).apply()
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

    // --- Overlay appearance (#43/#53) ---

    private val overlaySizePresets = listOf("Small" to 44, "Medium (default)" to 56, "Large" to 76)

    private val overlayColorSwatches: List<Pair<String, Int?>> = listOf(
        "Default" to null,
        "White" to 0xFFFFFFFF.toInt(),
        "Black" to 0xFF000000.toInt(),
        "Emerald" to 0xFF34D399.toInt(),
        "Sky blue" to 0xFF38BDF8.toInt(),
        "Violet" to 0xFF8B5CF6.toInt(),
        "Amber" to 0xFFF59E0B.toInt(),
        "Rose" to 0xFFF43F5E.toInt(),
        "Slate" to 0xFF64748B.toInt(),
    )

    private fun colorSummary(color: Int?): String =
        if (color == null) "Default" else String.format("#%06X", 0xFFFFFF and color)

    private fun overlaySizeSummary(ringSizeDp: Int): String =
        overlaySizePresets.firstOrNull { it.second == ringSizeDp }?.first ?: "Custom (${ringSizeDp}dp)"

    private fun onOverlayAppearanceChanged() {
        WhisperAccessibilityService.instance?.applyOverlayAppearance()
        refreshOverlayAppearanceRows()
    }

    /** Border/fill/glyph color rows have no effect while a custom icon image is set (#53's
     *  documented decision that a custom image replaces the ring's whole look), so they're
     *  greyed out and disabled rather than left clickable with silently-ignored taps. */
    private fun refreshOverlayAppearanceRows() {
        val appearance = OverlayAppearancePrefs.load(this)
        val hasCustomIcon = appearance.hasCustomIcon && OverlayIconStore.exists(this)

        overlaySizeRow.findViewWithTag<TextView>("subtitle").text = overlaySizeSummary(appearance.ringSizeDp)

        setOverlayColorRowState(overlayBorderColorRow, appearance.borderColor, hasCustomIcon)
        setOverlayColorRowState(overlayFillColorRow, appearance.fillColor, hasCustomIcon)
        setOverlayColorRowState(overlayGlyphColorRow, appearance.glyphColor, hasCustomIcon)

        overlayCustomIconRow.findViewWithTag<TextView>("subtitle").text =
            if (hasCustomIcon) "Custom image set" else "Not set — using the default mic icon"
        overlayRemoveCustomIconRow.visibility = if (hasCustomIcon) View.VISIBLE else View.GONE
    }

    private fun setOverlayColorRowState(row: LinearLayout, color: Int?, disabledByCustomIcon: Boolean) {
        row.isEnabled = !disabledByCustomIcon
        row.alpha = if (disabledByCustomIcon) 0.4f else 1f
        row.findViewWithTag<TextView>("subtitle").text =
            if (disabledByCustomIcon) "Not used while a custom icon image is set" else colorSummary(color)
    }

    private fun promptOverlaySize() {
        val current = OverlayAppearancePrefs.load(this).ringSizeDp
        val checked = overlaySizePresets.indexOfFirst { it.second == current }.let { if (it < 0) 1 else it }
        android.app.AlertDialog.Builder(this)
            .setTitle("Icon size")
            .setSingleChoiceItems(overlaySizePresets.map { it.first }.toTypedArray(), checked) { dialog, which ->
                OverlayAppearancePrefs.setRingSizeDp(this, overlaySizePresets[which].second)
                onOverlayAppearanceChanged()
                dialog.dismiss()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** Curated swatch list rather than a full HSV picker -- no new UI dependency, and a colored
     *  bullet in a plain [android.app.AlertDialog.Builder.setItems] list is enough to pick a
     *  clearly-legible overlay color without building/maintaining a custom picker view. */
    private fun pickOverlayColor(title: String, current: Int?, onPick: (Int?) -> Unit) {
        val items = overlayColorSwatches.map { (label, color) ->
            SpannableString("●  $label").apply {
                setSpan(
                    ForegroundColorSpan(color ?: attrColor(android.R.attr.textColorSecondary)),
                    0, 1, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            }
        }
        android.app.AlertDialog.Builder(this)
            .setTitle(title)
            .setItems(items.toTypedArray()) { _, which -> onPick(overlayColorSwatches[which].second) }
            .show()
    }

    // --- State Updates ---

    private fun refresh() {
        val audio = hasPerm(Manifest.permission.RECORD_AUDIO)
        val acc = WhisperAccessibilityService.instance != null
        val useLocal = prefs().getBoolean("use_local", true)
        val usePostProcessing = prefs().getBoolean("use_post_processing", false)
        val hasKey = ApiKeyStore.getApiKey(this).isNotBlank()
        val hasModel = LocalTranscriber.availableModels(this).isNotEmpty()

        audioRowSub.text = if (audio) "Granted" else "Tap to grant permission"
        accRowSub.text = if (acc) "Enabled" else "Tap to enable in settings"

        cloudSwitch.isChecked = !useLocal
        postProcessSwitch.isChecked = usePostProcessing
        postProcessRowSub.text = cleanupSubtitle()
        previewBeforeInjectSwitch.isChecked = PreviewBeforeInjectToggle.isEnabled(this)
        historyEnabledSwitch.isChecked = prefs().getBoolean(KEY_HISTORY_ENABLED, true)
        debugVisibilitySwitch.isChecked = DebugVisibilityToggle.isEnabled(this)

        streamingPreviewSwitch.isChecked = shouldUseStreamingPreview(
            settingEnabled = prefs().getBoolean(KEY_STREAMING_PREVIEW, false),
            streamingModelInstalled = ModelDownloader.isInstalled(this, selectedStreamingModel())
        )
        streamingPreviewRowSub.text = streamingPreviewSubtitle()
        refreshAllStreamingRows()

        modelContainer.visibility = if (useLocal) View.VISIBLE else View.GONE
        cleanupDetailContainer.visibility = if (usePostProcessing) View.VISIBLE else View.GONE
        vocabularyRowSub.text = vocabularySummary()

        val apiKey = ApiKeyStore.getApiKey(this)
        keyRowSub.text = if (apiKey.isBlank()) "Tap to set" else ApiKeyStore.maskForDisplay(apiKey)
        baseUrlRowSub.text = cleanupBaseUrl()
        modelRowSub.text = cleanupModel()

        omnirouteKeyRowSub.text = credentialSubtitle(CleanupCredentialSlot.OMNIROUTE)
        openaiDirectKeyRowSub.text = credentialSubtitle(CleanupCredentialSlot.OPENAI_DIRECT)
        anthropicDirectKeyRowSub.text = credentialSubtitle(CleanupCredentialSlot.ANTHROPIC_DIRECT)
        refreshWaterfallSteps()
        refreshSimpleCleanupChoice()
        refreshAllCleanupRows()

        val prompt = currentPrompt()
        promptRowSub.text = prompt

        val cur = prefs().getString("model_name", "") ?: ""
        val curModel = MODEL_CATALOG.firstOrNull { it.archive == cur }
        if (cur.isBlank() || curModel == null || !ModelDownloader.isInstalled(this, curModel)) {
            MODEL_CATALOG.firstOrNull { ModelDownloader.isInstalled(this, it) }
                ?.let { selectModel(it.archive) }
        }

        // Ready logic
        val localReady = useLocal && hasModel
        val cloudReady = !useLocal && hasKey
        val postReady = !usePostProcessing || hasKey
        val ready = audio && acc && (localReady || cloudReady) && postReady

        statusSubtitle.text = if (ready) "Ready — tap the overlay dot to dictate" else "Setup required"
        statusSubtitle.setTextColor(if (ready) attrColor(com.google.android.material.R.attr.colorPrimary) else attrColor(android.R.attr.textColorSecondary))
        
        refreshAllCards()
        refreshPromptRows()
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

    /**
     * Applies a change to the local/cleanup toggles, inserting the one-time consent dialog from
     * #23 when the change would first combine local transcription with cleanup.
     */
    private fun applyLocalCleanupChange(useLocal: Boolean, usePostProcessing: Boolean) {
        val hasConsented = prefs().getBoolean(KEY_LOCAL_CLEANUP_CONSENT, false)
        if (!LocalCleanupConsent.shouldPrompt(useLocal, usePostProcessing, hasConsented)) {
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

    /** Applies a change to the "save dictation history" toggle. Turning it on just flips the
     *  pref; turning it off offers to clear what's already saved, since the user may be turning
     *  it off specifically because they don't want that history sitting on the device (#25). */
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

    private fun promptApiKey() {
        val existingKey = ApiKeyStore.getApiKey(this)
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            hint = if (existingKey.isBlank()) "sk-..." else ApiKeyStore.maskForDisplay(existingKey)
        }
        android.app.AlertDialog.Builder(this)
            .setTitle("OpenAI API Key")
            .setView(input.apply { setPadding(dp(24), dp(8), dp(24), dp(8)) })
            .setPositiveButton("Save") { _, _ ->
                val entered = input.text.toString().trim()
                if (entered.isNotBlank()) ApiKeyStore.setApiKey(this, entered)
                refresh()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** Lets the user point cleanup requests at a self-hosted OpenAI-compatible endpoint (e.g. OmniRoute). See #4. */
    private fun promptCleanupBaseUrl() {
        val input = EditText(this).apply {
            hint = PostProcessor.DEFAULT_BASE_URL
            setText(cleanupBaseUrl())
        }
        android.app.AlertDialog.Builder(this)
            .setTitle("Cleanup API base URL")
            .setMessage("OpenAI-compatible chat completions base URL. Leave blank to use OpenAI's API.")
            .setView(input.apply { setPadding(dp(24), dp(8), dp(24), dp(8)) })
            .setPositiveButton("Save") { _, _ ->
                val entered = input.text.toString().trim()
                if (entered.isBlank()) {
                    prefs().edit().remove("cleanup_base_url").apply()
                    refresh()
                    return@setPositiveButton
                }
                val normalized = PostProcessor.normalizeBaseUrl(entered)
                if (normalized == null) {
                    toast("Invalid URL — must start with http:// or https://")
                    return@setPositiveButton
                }
                prefs().edit().putString("cleanup_base_url", normalized).apply()
                refresh()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun promptCleanupModel() {
        val input = EditText(this).apply {
            hint = PostProcessor.DEFAULT_MODEL
            setText(cleanupModel())
        }
        android.app.AlertDialog.Builder(this)
            .setTitle("Cleanup model name")
            .setView(input.apply { setPadding(dp(24), dp(8), dp(24), dp(8)) })
            .setPositiveButton("Save") { _, _ ->
                val entered = input.text.toString().trim()
                if (entered.isBlank()) {
                    prefs().edit().remove("cleanup_model").apply()
                } else {
                    prefs().edit().putString("cleanup_model", entered).apply()
                }
                refresh()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // --- Cleanup Waterfall (#32) ---

    private fun credentialSubtitle(slot: CleanupCredentialSlot): String {
        val value = CleanupCredentialStore.get(this, slot)
        return if (value.isBlank()) "Tap to set" else CleanupCredentialStore.maskForDisplay(value)
    }

    private fun promptCleanupCredential(slot: CleanupCredentialSlot, title: String) {
        val existing = CleanupCredentialStore.get(this, slot)
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            hint = if (existing.isBlank()) "Paste key" else CleanupCredentialStore.maskForDisplay(existing)
        }
        android.app.AlertDialog.Builder(this)
            .setTitle(title)
            .setView(input.apply { setPadding(dp(24), dp(8), dp(24), dp(8)) })
            .setPositiveButton("Save") { _, _ ->
                val entered = input.text.toString().trim()
                if (entered.isNotBlank()) CleanupCredentialStore.set(this, slot, entered)
                refresh()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** The user-configured waterfall steps shown in Settings, i.e. [CleanupWaterfallStore.load]
     *  with the invisible [CleanupStepGroup.LEGACY] placeholder step filtered back out — a fresh
     *  install or an emptied-out step list both naturally render as "no steps configured" here. */
    private fun waterfallSteps(): List<CleanupStep> =
        CleanupWaterfallStore.load(this).steps.filter { it.group != CleanupStepGroup.LEGACY }

    private fun saveWaterfallSteps(steps: List<CleanupStep>) {
        CleanupWaterfallStore.save(this, CleanupWaterfall(steps))
        refreshWaterfallSteps()
    }

    private fun refreshWaterfallSteps() {
        waterfallStepsContainer.removeAllViews()
        val steps = waterfallSteps()
        if (steps.isEmpty()) {
            waterfallStepsContainer.addView(TextView(this).apply {
                text = "No waterfall steps configured yet."
                textSize = 14f
                setTextColor(attrColor(android.R.attr.textColorSecondary))
                setPadding(dp(24), dp(4), dp(24), dp(12))
            })
            return
        }
        steps.forEachIndexed { index, step ->
            waterfallStepsContainer.addView(buildWaterfallStepRow(step, index, steps.size))
        }
    }

    private fun buildWaterfallStepRow(step: CleanupStep, index: Int, total: Int): View {
        val row = vertical(0).apply { setPadding(dp(24), dp(10), dp(24), dp(10)) }

        val topLine = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        topLine.addView(View(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(12), dp(12)).apply { marginEnd = dp(12) }
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(colorForHealth(CleanupStepStatusStore.healthFor(this@MainActivity, step)))
            }
        })
        topLine.addView(TextView(this).apply {
            text = "${groupLabel(step.group)} · ${step.model}"
            textSize = 16f
            setTextColor(attrColor(android.R.attr.textColorPrimary))
            layoutParams = LinearLayout.LayoutParams(0, LP_WRAP, 1f)
            setOnClickListener {
                promptAddOrEditStep(step) { updated ->
                    saveWaterfallSteps(CleanupWaterfallEditing.replace(waterfallSteps(), index, updated))
                }
            }
        })
        row.addView(topLine)

        val buttonRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
        }
        buttonRow.addView(waterfallStepButton("Test") { testWaterfallStep(step) })
        buttonRow.addView(waterfallStepButton("▲") {
            saveWaterfallSteps(CleanupWaterfallEditing.moveUp(waterfallSteps(), index))
        }.apply { isEnabled = index > 0 })
        buttonRow.addView(waterfallStepButton("▼") {
            saveWaterfallSteps(CleanupWaterfallEditing.moveDown(waterfallSteps(), index))
        }.apply { isEnabled = index < total - 1 })
        buttonRow.addView(waterfallStepButton("✕") {
            saveWaterfallSteps(CleanupWaterfallEditing.remove(waterfallSteps(), index))
        })
        row.addView(buttonRow)

        return row
    }

    private fun waterfallStepButton(label: String, onClick: () -> Unit) =
        MaterialButton(this, null, com.google.android.material.R.attr.materialIconButtonStyle).apply {
            text = label
            textSize = 12f
            insetTop = 0
            insetBottom = 0
            setOnClickListener { onClick() }
        }

    private fun groupLabel(group: CleanupStepGroup) = when (group) {
        CleanupStepGroup.LEGACY -> "Legacy"
        CleanupStepGroup.OMNIROUTE -> "OmniRoute"
        CleanupStepGroup.OPENAI_DIRECT -> "Direct OpenAI"
        CleanupStepGroup.ANTHROPIC_DIRECT -> "Direct Anthropic"
        CleanupStepGroup.LOCAL_LLM -> "Local (on-device)"
    }

    private fun colorForHealth(health: CleanupStepHealth) = when (health) {
        CleanupStepHealth.UNTESTED -> 0xFF9E9E9E.toInt()
        CleanupStepHealth.SUCCESS -> 0xFF4CAF50.toInt()
        CleanupStepHealth.FAILURE -> 0xFFF44336.toInt()
    }

    /** Fires one real request through [PostProcessor.processWaterfall]/[CleanupWaterfallExecutor]
     *  for just [step], so a typo'd model string or bad key is caught here instead of silently
     *  falling through to a paid step later (see ADR-0001). Updates [CleanupStepStatusStore]
     *  with the real outcome regardless of pass/fail, unlike a real dictation cleanup call, which
     *  only ever attributes a *success* to a specific step (see
     *  WhisperAccessibilityService.recordWaterfallSuccess). */
    private fun testWaterfallStep(step: CleanupStep) {
        toast("Testing ${groupLabel(step.group)}...")
        PostProcessor.processWaterfall(
            context = this,
            text = "Testing waterfall connectivity.",
            prompt = PostProcessor.SIMPLE_PROMPT,
            waterfall = CleanupWaterfall(listOf(step)),
            cursor = CleanupWaterfallCursor(),
            cancelHolder = InFlightCall(),
            legacyApiKey = "",
        ) { result ->
            runOnUiThread {
                val success = !result.text.isNullOrBlank()
                CleanupStepStatusStore.record(this, step, if (success) CleanupStepHealth.SUCCESS else CleanupStepHealth.FAILURE)
                toast(if (success) "${groupLabel(step.group)} OK" else "${groupLabel(step.group)} failed: ${result.error ?: "unknown error"}")
                refreshWaterfallSteps()
            }
        }
    }

    /** Add/edit dialog for one waterfall step: provider group (radio), model (text), and an
     *  optional base URL override tucked behind an "Advanced" expand (only meaningful for
     *  [CleanupStepGroup.OPENAI_DIRECT] — see [CleanupStep.baseUrlOverride]), per ADR-0001. */
    private fun promptAddOrEditStep(existing: CleanupStep?, onSave: (CleanupStep) -> Unit) {
        val groupOptions = listOf(
            CleanupStepGroup.OMNIROUTE to "OmniRoute",
            CleanupStepGroup.OPENAI_DIRECT to "Direct OpenAI",
            CleanupStepGroup.ANTHROPIC_DIRECT to "Direct Anthropic",
            CleanupStepGroup.LOCAL_LLM to "Local (on-device, offline, no API key)",
        )

        val container = vertical(dp(24), dp(8))

        val radioGroup = RadioGroup(this).apply { orientation = LinearLayout.VERTICAL }
        val radioButtons = groupOptions.map { (group, label) ->
            MaterialRadioButton(this).apply {
                text = label
                id = View.generateViewId()
                isChecked = (existing?.group ?: CleanupStepGroup.OMNIROUTE) == group
            }
        }
        radioButtons.forEach { radioGroup.addView(it) }
        container.addView(radioGroup)

        // The LOCAL_LLM group ships exactly one curated model (#37 non-goals: no arbitrary
        // user-supplied GGUF support), so its model field is fixed and non-editable rather than
        // free text like the cloud groups.
        val modelInput = EditText(this).apply {
            hint = "Model, e.g. claude/claude-sonnet-4-6"
            setText(existing?.model ?: "")
        }
        container.addView(modelInput)
        val offlineCaption = TextView(this).apply {
            text = "Runs fully offline on this device — no network call, no API key required."
            textSize = 12f
            setTextColor(attrColor(android.R.attr.textColorSecondary))
            visibility = if ((existing?.group ?: CleanupStepGroup.OMNIROUTE) == CleanupStepGroup.LOCAL_LLM) View.VISIBLE else View.GONE
            setPadding(0, 0, 0, dp(4))
        }
        container.addView(offlineCaption)
        radioGroup.setOnCheckedChangeListener { _, checkedId ->
            val checkedGroup = groupOptions.getOrNull(radioButtons.indexOfFirst { it.id == checkedId })?.first
            val isLocal = checkedGroup == CleanupStepGroup.LOCAL_LLM
            offlineCaption.visibility = if (isLocal) View.VISIBLE else View.GONE
            modelInput.isEnabled = !isLocal
            if (isLocal) modelInput.setText(LocalCleanupProvider.MODEL.archive)
        }

        val baseUrlInput = EditText(this).apply {
            hint = "Base URL override (Direct OpenAI only)"
            setText(existing?.baseUrlOverride ?: "")
            visibility = if (existing?.baseUrlOverride.isNullOrBlank()) View.GONE else View.VISIBLE
        }
        container.addView(TextView(this).apply {
            text = "Advanced"
            setTextColor(attrColor(com.google.android.material.R.attr.colorPrimary))
            setPadding(0, dp(8), 0, dp(4))
            setOnClickListener { baseUrlInput.visibility = View.VISIBLE }
        })
        container.addView(baseUrlInput)

        android.app.AlertDialog.Builder(this)
            .setTitle(if (existing == null) "Add waterfall step" else "Edit waterfall step")
            .setView(ScrollView(this).apply { addView(container) })
            .setPositiveButton("Save") { _, _ ->
                val checkedIndex = radioButtons.indexOfFirst { it.isChecked }
                val group = groupOptions.getOrElse(checkedIndex) { groupOptions[0] }.first
                val model = modelInput.text.toString().trim()
                if (model.isBlank()) {
                    toast("Model can't be blank")
                    return@setPositiveButton
                }
                val baseUrlOverride = baseUrlInput.text.toString().trim()
                    .takeIf { it.isNotBlank() && group == CleanupStepGroup.OPENAI_DIRECT }
                onSave(CleanupStep(group, model, baseUrlOverride))
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun promptPostProcessing() {
        val input = EditText(this).apply {
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

    /** Lets the user edit their personal vocabulary — project names and jargon cleanup should
     *  preserve verbatim — one term per line, seeded from [VocabularyTerms.DEFAULTS] on first
     *  run so existing behavior doesn't regress (#26). */
    private fun promptVocabulary() {
        val input = EditText(this).apply {
            hint = "One term per line, e.g. FastHTML"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
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

    // --- Dictation History (#25) ---

    /** Local-only recovery list of past transcripts, so a failed injection is never a lost
     *  dictation: tap any row to re-copy it to the clipboard, long-press to delete it, or
     *  "Clear all" to wipe the whole list (#25). */
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

    /** Small "paid fallback" pill for a history row (#33) -- null (rendering nothing) unless the
     *  debug/visibility toggle is on and this entry was actually served by a paid group; see
     *  [shouldShowPaidFallbackBadge]. */
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

    // --- Onboarding Wizard (#6) ---

    /** Shows the next wizard dialog for the current permission/setup state, or does nothing if
     *  a dialog is already up or [OnboardingWizard] says setup is already done. Called from every
     *  point the Activity resumes control (onResume, permission results) so a step that requires
     *  leaving the app (mic prompt, Accessibility settings) picks back up automatically. */
    private fun advanceOnboarding() {
        if (onboardingDialog?.isShowing == true) return
        val accessibilityEnabled = WhisperAccessibilityService.instance != null
        val complete = prefs().getBoolean(KEY_ONBOARDING_COMPLETE, false)
        if (!OnboardingWizard.shouldShow(accessibilityEnabled, complete)) return

        when {
            !onboardingIntroShown -> {
                onboardingIntroShown = true
                showOnboardingIntro()
            }
            !hasPerm(Manifest.permission.RECORD_AUDIO) -> showOnboardingMicStep()
            !accessibilityEnabled -> showOnboardingAccessibilityStep()
            else -> showOnboardingModeStep()
        }
    }

    private fun dismissOnboarding() { onboardingDialog = null }

    private fun finishOnboarding() {
        prefs().edit().putBoolean(KEY_ONBOARDING_COMPLETE, true).apply()
        onboardingDialog = null
        refresh()
    }

    private fun showOnboardingIntro() {
        onboardingDialog = android.app.AlertDialog.Builder(this)
            .setTitle("Welcome to Ramblr")
            .setMessage(
                "Ramblr lets you dictate into any text field: tap the floating button, " +
                    "speak, tap again, and your words are inserted where you were typing.\n\n" +
                    "It needs two things to do that:\n" +
                    "• Microphone — to record what you say\n" +
                    "• Accessibility service — to insert the text into the focused field across apps. " +
                    "It doesn't read your screen or run background automation; it only acts after you " +
                    "tap the overlay button."
            )
            .setCancelable(false)
            .setPositiveButton("Get started") { _, _ -> dismissOnboarding(); advanceOnboarding() }
            .setNegativeButton("Not now") { _, _ -> dismissOnboarding() }
            .show()
    }

    private fun showOnboardingMicStep() {
        onboardingDialog = android.app.AlertDialog.Builder(this)
            .setTitle("Microphone access")
            .setMessage("Ramblr needs the microphone to record what you say before transcribing it.")
            .setCancelable(false)
            .setPositiveButton("Continue") { _, _ ->
                dismissOnboarding()
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), 1)
            }
            .setNegativeButton("Not now") { _, _ -> dismissOnboarding() }
            .show()
    }

    private fun showOnboardingAccessibilityStep() {
        onboardingDialog = android.app.AlertDialog.Builder(this)
            .setTitle("Turn on Accessibility")
            .setMessage(
                "Ramblr uses Android's Accessibility service for one narrow reason: to insert " +
                    "dictated text into whatever text field is currently focused. It doesn't replace your " +
                    "keyboard and doesn't run background automation.\n\n" +
                    "On the next screen, look for \"Ramblr\" in the list and turn it on."
            )
            .setCancelable(false)
            .setPositiveButton("Open Accessibility Settings") { _, _ ->
                dismissOnboarding()
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
            .setNegativeButton("Not now") { _, _ -> dismissOnboarding() }
            .show()
    }

    private fun showOnboardingModeStep() {
        val recommended = MODEL_CATALOG.firstOrNull { it.recommended } ?: MODEL_CATALOG.first()
        onboardingDialog = android.app.AlertDialog.Builder(this)
            .setTitle("Choose transcription mode")
            .setMessage(
                "On-device (recommended): downloads \"${recommended.name}\" and keeps your audio on " +
                    "this phone.\n\nCloud: uses OpenAI's API with your own key — no download, but audio " +
                    "leaves your device and OpenAI usage is billed to you."
            )
            .setCancelable(false)
            .setPositiveButton("Use on-device (recommended)") { _, _ ->
                dismissOnboarding()
                prefs().edit().putBoolean("use_local", true).apply()
                if (ModelDownloader.isInstalled(this, recommended)) {
                    selectModel(recommended.archive)
                } else {
                    ModelDownloadWorker.enqueue(this, recommended)
                    toast("Downloading ${recommended.name}...")
                }
                refresh()
                showOnboardingTryStep()
            }
            .setNegativeButton("Use cloud (needs API key)") { _, _ ->
                dismissOnboarding()
                prefs().edit().putBoolean("use_local", false).apply()
                refresh()
                promptOnboardingApiKey()
            }
            .setNeutralButton("Not now") { _, _ -> dismissOnboarding() }
            .show()
    }

    private fun promptOnboardingApiKey() {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            hint = "sk-..."
        }
        onboardingDialog = android.app.AlertDialog.Builder(this)
            .setTitle("OpenAI API Key")
            .setMessage("Used only to call OpenAI's transcription API directly from your phone — billed pay-per-use to your own account.")
            .setView(input.apply { setPadding(dp(24), dp(8), dp(24), dp(8)) })
            .setCancelable(false)
            .setPositiveButton("Save") { _, _ ->
                val entered = input.text.toString().trim()
                if (entered.isNotBlank()) ApiKeyStore.setApiKey(this, entered)
                refresh()
                showOnboardingTryStep()
            }
            .setNegativeButton("Skip") { _, _ -> dismissOnboarding(); showOnboardingTryStep() }
            .show()
    }

    private fun showOnboardingTryStep() {
        val testField = EditText(this).apply {
            hint = "Tap here, then use the floating button to dictate a test phrase"
            setPadding(dp(24), dp(16), dp(24), dp(16))
        }
        onboardingDialog = android.app.AlertDialog.Builder(this)
            .setTitle("Try it out (optional)")
            .setMessage(
                "Setup is done. If the floating button is visible, tap it, speak a test phrase, and " +
                    "tap it again to confirm the text lands in the field below."
            )
            .setView(testField)
            .setCancelable(false)
            .setPositiveButton("Finish setup") { _, _ -> finishOnboarding() }
            .show()
    }

    // --- UI Helpers ---

    private fun settingsRow(title: String, subtitle: String, widget: View? = null, onClick: (() -> Unit)? = null): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(24), dp(16), dp(24), dp(16))
            isClickable = onClick != null
            isFocusable = onClick != null
            if (onClick != null) {
                val outValue = TypedValue()
                context.theme.resolveAttribute(android.R.attr.selectableItemBackground, outValue, true)
                setBackgroundResource(outValue.resourceId)
                setOnClickListener { onClick() }
            }
        }

        val textContainer = vertical(0).apply {
            layoutParams = LinearLayout.LayoutParams(0, LP_WRAP, 1f)
        }
        
        textContainer.addView(TextView(this).apply {
            text = title
            textSize = 18f
            setTextColor(attrColor(android.R.attr.textColorPrimary))
        })
        
        textContainer.addView(TextView(this).apply {
            tag = "subtitle"
            text = subtitle
            textSize = 14f
            setTextColor(attrColor(android.R.attr.textColorSecondary))
            setPadding(0, dp(2), 0, 0)
        })

        row.addView(textContainer)
        if (widget != null) row.addView(widget)

        return row
    }

    private fun sectionHeader(title: String) = TextView(this).apply {
        text = title
        textSize = 14f
        setTypeface(typeface, Typeface.BOLD)
        setTextColor(attrColor(com.google.android.material.R.attr.colorPrimary)) // Neutral Android-like blue
        setPadding(dp(24), dp(24), dp(24), dp(8))
    }

    private fun vertical(padH: Int, padV: Int = padH) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(padH, padV, padH, padV)
    }

    private fun currentPrompt() = prefs().getString("post_processing_prompt", PostProcessor.DEFAULT_PROMPT) ?: PostProcessor.DEFAULT_PROMPT
    private fun customPrompt() = prefs().getString("custom_post_processing_prompt", PostProcessor.DEFAULT_PROMPT) ?: PostProcessor.DEFAULT_PROMPT
    private fun cleanupBaseUrl() = prefs().getString("cleanup_base_url", PostProcessor.DEFAULT_BASE_URL) ?: PostProcessor.DEFAULT_BASE_URL
    private fun cleanupModel() = prefs().getString("cleanup_model", PostProcessor.DEFAULT_MODEL) ?: PostProcessor.DEFAULT_MODEL

    private fun vocabularyTerms() = VocabularyTerms.parse(
        prefs().getString("custom_vocabulary_terms", VocabularyTerms.DEFAULT_SERIALIZED)
    )

    private fun vocabularySummary(): String {
        val terms = vocabularyTerms()
        return if (terms.isEmpty()) "No custom terms" else terms.joinToString(", ")
    }

    private fun customPromptSummary(): String {
        val prompt = customPrompt()
        return if (prompt == PostProcessor.DEFAULT_PROMPT) "Your edited prompt"
        else prompt.replace("\n", " ")
    }

    private data class PromptPreset(val key: String, val title: String, val subtitle: String, val prompt: String)

    private fun promptPresets() = CleanupPersonas.BUILT_IN.map {
        PromptPreset(key = it.key, title = it.title, subtitle = it.subtitle, prompt = it.prompt)
    } + PromptPreset(
        key = "custom",
        title = "Custom",
        subtitle = customPromptSummary(),
        prompt = customPrompt()
    )

    /** Currently selected persona, inferring one from the active prompt if none was saved yet
     *  (e.g. on upgrade) so existing users keep their prompt instead of resetting to the
     *  default persona (#3, #40). */
    private fun currentPersona(): CleanupPersona =
        CleanupPersonas.currentPersona(prefs().getString("cleanup_style", null), currentPrompt())

    private fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()
    private fun hasPerm(p: String) = ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED
    private fun attrColor(attr: Int): Int {
        val ta = obtainStyledAttributes(intArrayOf(attr))
        val color = ta.getColor(0, 0)
        ta.recycle()
        return color
    }
    private fun prefs() = getSharedPreferences("phonewhisper", MODE_PRIVATE)
    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    companion object {
        private const val LP_MATCH = LinearLayout.LayoutParams.MATCH_PARENT
        private const val LP_WRAP = LinearLayout.LayoutParams.WRAP_CONTENT
        private const val KEY_LOCAL_CLEANUP_CONSENT = "local_cleanup_consent_seen"
        private const val KEY_ONBOARDING_COMPLETE = "onboarding_complete"
        private const val KEY_HISTORY_ENABLED = "dictation_history_enabled"
        private const val KEY_STREAMING_PREVIEW = "streaming_preview_enabled"
        private const val KEY_STREAMING_MODEL_NAME = "streaming_model_name"
        private const val KEY_LOCAL_CLEANUP_MODEL_NAME = "local_cleanup_model_name"
    }
}
