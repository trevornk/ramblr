package com.trevornk.ramblr

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
 * "Live Preview" category screen (#93 restructure): streaming preview switch + streaming model
 * catalog, split out of the old single-Activity Settings ScrollView. Always local (#29) -- no
 * cloud option, no interaction with the OpenAI key.
 */
class LivePreviewActivity : BaseSettingsActivity() {

    private lateinit var streamingPreviewSwitch: MaterialSwitch
    private lateinit var streamingPreviewRowSub: TextView

    private data class ModelRowViews(
        val radio: MaterialRadioButton,
        val progress: LinearProgressIndicator,
        val subtitle: TextView,
        val dlBtn: MaterialButton,
        val deleteBtn: MaterialButton
    )

    private val streamingModelRows = mutableMapOf<String, ModelRowViews>()
    private val streamingModelDownloadState = mutableMapOf<String, WorkInfo.State>()
    private val streamingModelDownloadGate = DownloadCompletionGate()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = vertical(0, 0)

        val header = TextView(this).apply {
            text = "Live Preview"
            textSize = 32f
            setPadding(dp(24), dp(64), dp(24), dp(24))
        }
        root.addView(header)

        root.addView(TextView(this).apply {
            text = "Shows live partial text in the focused field as you speak, using a separate " +
                "on-device streaming model. The final text (after you tap to stop) still comes " +
                "from your regular transcription + cleanup settings — this only changes " +
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

        val streamingModelGroup = nestedGroup()
        streamingModelGroup.content.addView(subsectionHeader("Streaming preview model", indent = 0))
        for (m in STREAMING_MODEL_CATALOG) streamingModelGroup.content.addView(buildStreamingModelRow(m))
        root.addView(streamingModelGroup.outer)

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
        streamingPreviewSwitch.isChecked = shouldUseStreamingPreview(
            settingEnabled = prefs().getBoolean(KEY_STREAMING_PREVIEW, false),
            streamingModelInstalled = ModelDownloader.isInstalled(this, selectedStreamingModel())
        )
        streamingPreviewRowSub.text = streamingPreviewSubtitle()
        refreshAllStreamingRows()
    }

    private fun selectedStreamingModel(): Model =
        ModelDownloader.resolveActiveModel(STREAMING_MODEL_CATALOG, prefs().getString(KEY_STREAMING_MODEL_NAME, "") ?: "")

    private fun streamingPreviewSubtitle(): String =
        if (!ModelDownloader.isInstalled(this, selectedStreamingModel())) "Download the streaming model below first"
        else "Shows live partial results in the field as you speak"

    private fun onStreamingPreviewToggle(enabling: Boolean) {
        if (enabling && !ModelDownloader.isInstalled(this, selectedStreamingModel())) {
            toast("Download the streaming model first")
            return
        }
        prefs().edit().putBoolean(KEY_STREAMING_PREVIEW, enabling).apply()
        WhisperAccessibilityService.instance?.reloadStreamingModel()
        refresh()
    }

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
        if (info != null && !info.state.isFinished) streamingModelDownloadGate.onInFlight(info.id.toString())

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
                if (streamingModelDownloadGate.shouldActOnSuccess(info.id.toString())) {
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

    companion object {
        private const val LP_MATCH = LinearLayout.LayoutParams.MATCH_PARENT
        private const val KEY_STREAMING_PREVIEW = "streaming_preview_enabled"
        private const val KEY_STREAMING_MODEL_NAME = "streaming_model_name"

        /** Category subtitle for MainActivity's Live Preview row (#93), e.g. "Off" or "On ·
         *  Streaming Zipformer2". */
        fun subtitle(context: android.content.Context): String {
            val prefs = context.getSharedPreferences("ramblr", android.content.Context.MODE_PRIVATE)
            val archive = prefs.getString(KEY_STREAMING_MODEL_NAME, "") ?: ""
            val model = ModelDownloader.resolveActiveModel(STREAMING_MODEL_CATALOG, archive)
            val enabled = shouldUseStreamingPreview(
                settingEnabled = prefs.getBoolean(KEY_STREAMING_PREVIEW, false),
                streamingModelInstalled = ModelDownloader.isInstalled(context, model)
            )
            return if (enabled) "On · ${model.name}" else "Off"
        }
    }
}
