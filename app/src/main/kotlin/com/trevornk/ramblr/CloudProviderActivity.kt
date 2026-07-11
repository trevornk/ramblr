package com.trevornk.ramblr

import android.content.res.ColorStateList
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import com.google.android.material.button.MaterialButton
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.radiobutton.MaterialRadioButton

/**
 * The unified "Cloud" provider-chain screen (#95 Phase 3): the single surface that replaces the
 * scattered credential UI previously spread across CleanupActivity's Cloud radio + contextual
 * OpenAI key row, TranscriptionActivity's cloud switch + contextual OpenAI key row, and
 * AdvancedActivity's separate waterfall editor (3 hardcoded credential rows + step list).
 *
 * Architecture (Opus-authored, approved by Trevor): one ordered [ProviderChain], shared across
 * transcription + cleanup, resolved by capability (see [ProviderChain.capableEntriesFor] /
 * [ProviderChainRuntime]). A normal user's chain has one entry and it just works for both
 * features; a power user adds/reorders more entries and that ordering IS the failover waterfall
 * -- there is no separate editor. [ProviderKind.LOCAL] is the undeletable floor, never a row a
 * user can add/remove here.
 *
 * Registered as its own Activity (not folded into CleanupActivity/TranscriptionActivity) because
 * the whole point of this phase is that credential/chain management is no longer a per-feature
 * concern -- giving it a dedicated top-level MainActivity category row matches the #94 restructure's
 * existing Activity-per-category convention and is the most direct expression of "one Cloud
 * screen, not scattered per-feature bits".
 */
class CloudProviderActivity : BaseSettingsActivity() {

    private lateinit var chainContainer: LinearLayout
    private lateinit var emptyStateView: View
    private lateinit var cloudTranscriptionSwitch: MaterialSwitch
    private lateinit var cloudTranscriptionRowSub: TextView
    private lateinit var cloudCleanupSwitch: MaterialSwitch
    private lateinit var cloudCleanupRowSub: TextView
    private lateinit var modeRadioGroup: RadioGroup
    private lateinit var modeRadioButtons: Map<DictationMode, MaterialRadioButton>
    private lateinit var modeCustomNote: TextView
    private lateinit var localFallbackSwitch: MaterialSwitch
    private lateinit var localFallbackRowSub: TextView
    private lateinit var cloudFallbackSwitch: MaterialSwitch
    private lateinit var cloudFallbackRowSub: TextView

    /** Provider kinds a user can manually add here. LOCAL is the implicit floor (never a
     *  manually-added row). OMNIROUTE only appears once a base URL has been supplied via
     *  local.properties (see OmniRoute.kt) -- on a public/unconfigured build there's nothing
     *  useful to add it as, so it's simply not offered rather than shown as a dead option. An
     *  already-existing (e.g. migrated) OMNIROUTE entry can still be edited even when hidden
     *  from "Add provider", since [existing] bypasses this list entirely for edit dialogs. */
    private val addableKinds = listOfNotNull(
        ProviderKind.OPENAI,
        ProviderKind.ANTHROPIC,
        ProviderKind.GEMINI,
        ProviderKind.OMNIROUTE.takeIf { OmniRoute.isConfigured },
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = vertical(0, 0)

        root.addView(TextView(this).apply {
            text = "Cloud"
            textSize = 32f
            setPadding(dp(24), dp(64), dp(24), dp(24))
        })

        root.addView(TextView(this).apply {
            text = "One ordered list of cloud providers, shared by transcription and cleanup. " +
                "Each feature automatically uses the first entry in the list that supports it. " +
                "On-device always works underneath, with no setup needed."
            textSize = 14f
            setTextColor(attrColor(android.R.attr.textColorSecondary))
            setPadding(dp(24), 0, dp(24), dp(8))
        })

        root.addView(sectionHeader("Dictation mode"))
        root.addView(TextView(this).apply {
            text = "Pick a preset, or fine-tune with the toggles below. Each mode controls both " +
                "how your speech is transcribed and how the text gets cleaned up."
            textSize = 14f
            setTextColor(attrColor(android.R.attr.textColorSecondary))
            setPadding(dp(24), 0, dp(24), dp(8))
        })

        modeRadioGroup = RadioGroup(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(4), dp(24), dp(8))
        }
        modeRadioButtons = DICTATION_MODE_OPTIONS.associateWith { mode ->
            MaterialRadioButton(this).apply {
                text = modeLabel(mode)
                id = View.generateViewId()
                buttonTintList = ColorStateList.valueOf(attrColor(com.google.android.material.R.attr.colorPrimary))
                setOnClickListener { applyMode(mode) }
            }
        }
        modeRadioButtons.values.forEach { modeRadioGroup.addView(it) }
        root.addView(modeRadioGroup)

        modeCustomNote = TextView(this).apply {
            text = ""
            textSize = 13f
            setTextColor(attrColor(android.R.attr.textColorSecondary))
            setPadding(dp(24), 0, dp(24), dp(12))
            visibility = View.GONE
        }
        root.addView(modeCustomNote)

        root.addView(sectionHeader("Fallback behavior"))
        root.addView(TextView(this).apply {
            text = "These control what happens automatically when your current mode's provider fails."
            textSize = 14f
            setTextColor(attrColor(android.R.attr.textColorSecondary))
            setPadding(dp(24), 0, dp(24), dp(8))
        })

        localFallbackSwitch = MaterialSwitch(this).apply { isClickable = false }
        val localFallbackRow = settingsRow(
            "Fall back to on-device if cloud fails", localFallbackSubtitle(), localFallbackSwitch
        ) { onToggleLocalFallback(!localFallbackSwitch.isChecked) }
        localFallbackRowSub = localFallbackRow.findViewWithTag("subtitle")
        root.addView(localFallbackRow)

        cloudFallbackSwitch = MaterialSwitch(this).apply { isClickable = false }
        val cloudFallbackRow = settingsRow(
            "Fall back to cloud if on-device fails", cloudFallbackSubtitle(), cloudFallbackSwitch
        ) { onToggleCloudFallback(!cloudFallbackSwitch.isChecked) }
        cloudFallbackRowSub = cloudFallbackRow.findViewWithTag("subtitle")
        root.addView(cloudFallbackRow)

        root.addView(sectionHeader("Cloud provider chain"))

        chainContainer = vertical(0)
        root.addView(chainContainer)

        emptyStateView = TextView(this).apply {
            text = "No cloud providers configured yet. On-device transcription and cleanup are used instead."
            textSize = 14f
            setTextColor(attrColor(android.R.attr.textColorSecondary))
            setPadding(dp(24), dp(4), dp(24), dp(12))
        }
        root.addView(emptyStateView)

        root.addView(settingsRow("Add provider", addableKindsSummary(), indent = 0) {
            promptAddOrEditEntry(null) { newEntry -> saveChain(ProviderChainEditing.addCloud(currentChain().entries, newEntry)) }
        })

        root.addView(sectionHeader("Advanced: per-feature override"))
        root.addView(TextView(this).apply {
            text = "Usually set together by the mode preset above. Only change these individually " +
                "if you want a mix, e.g. cloud transcription with on-device cleanup."
            textSize = 14f
            setTextColor(attrColor(android.R.attr.textColorSecondary))
            setPadding(dp(24), 0, dp(24), dp(8))
        })

        cloudTranscriptionSwitch = MaterialSwitch(this).apply { isClickable = false }
        val transcriptionRow = settingsRow(
            "Transcription", cloudTranscriptionSubtitle(), cloudTranscriptionSwitch
        ) { onToggleCloudTranscription(!cloudTranscriptionSwitch.isChecked) }
        cloudTranscriptionRowSub = transcriptionRow.findViewWithTag("subtitle")
        root.addView(transcriptionRow)

        cloudCleanupSwitch = MaterialSwitch(this).apply { isClickable = false }
        val cleanupRow = settingsRow(
            "Cleanup", cloudCleanupSubtitle(), cloudCleanupSwitch
        ) { onToggleCloudCleanup(!cloudCleanupSwitch.isChecked) }
        cloudCleanupRowSub = cleanupRow.findViewWithTag("subtitle")
        root.addView(cleanupRow)

        root.addView(sectionHeader("Live Preview"))
        root.addView(settingsRow(
            "Live Preview",
            "Always on-device — a cloud round trip would defeat the point of showing text live while you speak"
        ).apply {
            isEnabled = false
            alpha = 0.6f
        })

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

    private fun currentChain(): ProviderChain = ProviderChainStore.load(this)

    private fun saveChain(entries: List<ProviderChainEntry>) {
        ProviderChainStore.save(this, ProviderChain(entries))
        refresh()
    }

    private fun refresh() {
        val chain = currentChain()
        refreshChainRows(chain)

        cloudTranscriptionSwitch.isChecked = !prefs().getBoolean("use_local", true)
        cloudTranscriptionRowSub.text = cloudTranscriptionSubtitle()

        cloudCleanupSwitch.isChecked = CloudFeatureToggle.cleanupEnabled(this)
        cloudCleanupRowSub.text = cloudCleanupSubtitle()

        localFallbackSwitch.isChecked = DictationModeToggle.allowLocalFallback(this)
        localFallbackRowSub.text = localFallbackSubtitle()
        cloudFallbackSwitch.isChecked = DictationModeToggle.allowCloudFallback(this)
        cloudFallbackRowSub.text = cloudFallbackSubtitle()

        refreshModeSelection()
    }

    private fun currentMode(): DictationMode = DictationMode.resolve(
        useLocalTranscription = prefs().getBoolean("use_local", true),
        cloudCleanupEnabled = CloudFeatureToggle.cleanupEnabled(this),
    )

    private fun refreshModeSelection() {
        val mode = currentMode()
        modeRadioButtons.forEach { (candidate, button) -> button.isChecked = candidate == mode }
        if (mode == DictationMode.CUSTOM) {
            modeCustomNote.visibility = View.VISIBLE
            modeCustomNote.text = "Custom — transcription and cleanup are set independently below."
        } else {
            modeCustomNote.visibility = View.GONE
        }
    }

    /** Applies a mode preset by flipping the two underlying per-feature toggles together --
     *  [DictationMode] itself has no persisted state of its own, it's purely derived from
     *  ([prefs] "use_local", [CloudFeatureToggle.cleanupEnabled]) via [DictationMode.resolve]. */
    private fun applyMode(mode: DictationMode) {
        when (mode) {
            DictationMode.CLOUD -> {
                prefs().edit().putBoolean("use_local", false).apply()
                CloudFeatureToggle.setCleanupEnabled(this, true)
            }
            DictationMode.LOCAL -> {
                prefs().edit().putBoolean("use_local", true).apply()
                CloudFeatureToggle.setCleanupEnabled(this, false)
            }
            DictationMode.FASTEST -> {
                prefs().edit().putBoolean("use_local", true).apply()
                CloudFeatureToggle.setCleanupEnabled(this, true)
            }
            DictationMode.CUSTOM -> Unit // not directly selectable -- no matching radio button
        }
        refresh()
    }

    private fun modeLabel(mode: DictationMode): String = when (mode) {
        DictationMode.CLOUD -> "Cloud — cloud transcription + cloud cleanup"
        DictationMode.LOCAL -> "Local — on-device transcription + on-device cleanup, fully offline"
        DictationMode.FASTEST -> "Fastest — on-device transcription (near-instant) + cloud cleanup"
        DictationMode.CUSTOM -> "Custom"
    }

    /** Only cloud-capable entries are ever shown as rows here: LOCAL is the undeletable floor
     *  (see kdoc above and [ProviderChain.withLocalFloor]) and this screen is specifically titled
     *  "Cloud provider chain" -- rendering it alongside real cloud entries with the same
     *  edit/reorder/remove controls previously let it be deleted here by mistake (Trevor hit this
     *  live: removing "Local" from this list broke on-device cleanup, since the LOCAL entry *is*
     *  what makes Local cleanup/transcription work, not a redundant display of it). Local's
     *  presence is instead communicated by the always-visible explainer text above
     *  ("On-device always works underneath, with no setup needed.") -- no separate row needed. */
    private fun refreshChainRows(chain: ProviderChain) {
        chainContainer.removeAllViews()
        // Pair each cloud entry with its real index in the full (unfiltered) chain -- edit/move/
        // remove all operate on ProviderChainEditing against currentChain().entries, so they need
        // the true index, not cloudEntries' own filtered position.
        val cloudEntries = chain.entries.withIndex().filter { it.value.kind != ProviderKind.LOCAL }
        if (cloudEntries.isEmpty()) {
            emptyStateView.visibility = View.VISIBLE
            return
        }
        emptyStateView.visibility = View.GONE
        cloudEntries.forEachIndexed { displayPosition, (realIndex, entry) ->
            chainContainer.addView(buildChainEntryRow(entry, realIndex, displayPosition, cloudEntries.size))
        }
    }

    private fun buildChainEntryRow(entry: ProviderChainEntry, index: Int, displayPosition: Int, total: Int): View {
        val row = vertical(0).apply { setPadding(dp(24), dp(10), dp(24), dp(10)) }

        val topLine = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        topLine.addView(TextView(this).apply {
            text = "${displayPosition + 1}. ${providerLabel(entry.kind)} \u00b7 ${entry.model}" +
                if (entry.kind.supportsTranscription()) " \u00b7 STT: ${entry.transcriptionModel ?: transcriptionDefaultFor(entry.kind)}" else ""
            textSize = 16f
            setTextColor(attrColor(android.R.attr.textColorPrimary))
            layoutParams = LinearLayout.LayoutParams(0, LP_WRAP, 1f)
            setOnClickListener {
                promptAddOrEditEntry(entry) { updated ->
                    saveChain(ProviderChainEditing.replace(currentChain().entries, index, updated))
                }
            }
        })
        row.addView(topLine)

        row.addView(TextView(this).apply {
            text = "key: ${credentialSummary(entry.kind)}"
            textSize = 13f
            setTextColor(attrColor(android.R.attr.textColorSecondary))
            setPadding(0, dp(2), 0, dp(2))
        })

        row.addView(TextView(this).apply {
            text = ProviderChainEditing.capabilityBadgeText(entry.kind)
            textSize = 13f
            setTextColor(attrColor(android.R.attr.textColorSecondary))
            setPadding(0, 0, 0, dp(4))
        })

        val buttonRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
        }
        buttonRow.addView(chainButton("edit") {
            promptAddOrEditEntry(entry) { updated ->
                saveChain(ProviderChainEditing.replace(currentChain().entries, index, updated))
            }
        })
        buttonRow.addView(chainButton("\u25b2") {
            saveChain(ProviderChainEditing.moveCloudUp(currentChain().entries, index))
        }.apply { isEnabled = displayPosition > 0 })
        buttonRow.addView(chainButton("\u25bc") {
            saveChain(ProviderChainEditing.moveCloudDown(currentChain().entries, index))
        }.apply { isEnabled = displayPosition < total - 1 })
        buttonRow.addView(chainButton("\u2715") {
            confirmRemoveEntry(entry, index)
        })
        row.addView(buttonRow)

        return row
    }

    private fun chainButton(label: String, onClick: () -> Unit) =
        MaterialButton(this, null, com.google.android.material.R.attr.materialIconButtonStyle).apply {
            text = label
            textSize = 12f
            insetTop = 0
            insetBottom = 0
            setOnClickListener { onClick() }
        }

    private fun confirmRemoveEntry(entry: ProviderChainEntry, index: Int) {
        android.app.AlertDialog.Builder(this)
            .setTitle("Remove ${providerLabel(entry.kind)}?")
            .setMessage("This removes it from the provider chain. Its saved key is kept in case you add it back.")
            .setPositiveButton("Remove") { _, _ ->
                saveChain(ProviderChainEditing.remove(currentChain().entries, index))
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun credentialSummary(kind: ProviderKind): String {
        val value = ProviderCredentialStore.get(this, kind)
        return if (value.isBlank()) "not set" else ProviderCredentialStore.maskForDisplay(value)
    }

    /** Human-readable summary of [addableKinds] for the "Add provider" row subtitle, so it
     *  reflects whichever kinds are actually offered (e.g. omits OmniRoute when unconfigured)
     *  instead of a hardcoded string that could drift out of sync. */
    private fun addableKindsSummary(): String = addableKinds.joinToString(" / ", transform = ::providerLabel)

    private fun providerLabel(kind: ProviderKind): String = when (kind) {
        ProviderKind.OPENAI -> "OpenAI"
        ProviderKind.ANTHROPIC -> "Anthropic"
        ProviderKind.GEMINI -> "Gemini"
        ProviderKind.OMNIROUTE -> "OmniRoute"
        ProviderKind.LOCAL -> "Local (on-device)"
    }

    /** Shipped transcription default for [kind], for display when an entry's
     *  [ProviderChainEntry.transcriptionModel] is null (#101/#102: mirrors
     *  [CleanupDestination]'s private `defaultTranscriptionModelFor` -- duplicated rather than
     *  exposed there since this is a display-only concern local to this row/dialog, not a
     *  cross-file API). Only meaningful for [ProviderKind.supportsTranscription] kinds. */
    private fun transcriptionDefaultFor(kind: ProviderKind): String = when (kind) {
        ProviderKind.GEMINI -> GeminiTranscriberClient.DEFAULT_MODEL
        else -> TranscriberClient.DEFAULT_MODEL
    }

    // --- Add / edit provider dialog ---

    /** The catalog backing the model picker (#98): bundled/cached immediately, refreshed in the
     *  background by [ModelCatalogStore.currentCatalog] if stale. Loaded once per dialog open
     *  rather than per-kind-change so switching the provider radio in "Add provider" doesn't
     *  re-hit the cache repeatedly. */
    private fun loadCatalogForDialog(): List<ModelCatalogEntry> = ModelCatalogStore.currentCatalog(this)

    private fun promptAddOrEditEntry(existing: ProviderChainEntry?, onSave: (ProviderChainEntry) -> Unit) {
        val catalog = loadCatalogForDialog()
        val container = vertical(dp(24), dp(8))

        val radioGroup = RadioGroup(this).apply { orientation = LinearLayout.VERTICAL }
        val radioButtons = addableKinds.map { kind ->
            MaterialRadioButton(this).apply {
                text = providerLabel(kind)
                id = View.generateViewId()
                isChecked = (existing?.kind ?: addableKinds[0]) == kind
                buttonTintList = ColorStateList.valueOf(attrColor(com.google.android.material.R.attr.colorPrimary))
            }
        }
        radioButtons.forEach { radioGroup.addView(it) }
        // Editing an existing entry can't change its kind (the credential slot is per-kind) --
        // only "Add provider" offers a live picker.
        if (existing != null) radioButtons.forEach { it.isEnabled = false }
        container.addView(radioGroup)

        fun selectedKind() = addableKinds.getOrElse(radioButtons.indexOfFirst { it.isChecked }) { addableKinds[0] }

        // --- Model picker (#98): replaces the old free-text model EditText with catalog radio
        // options (tier badge + description), reusing existing curated data instead of a blind
        // typed string. Rebuilt whenever the selected provider kind changes, since the catalog
        // is keyed by ProviderKind.
        container.addView(TextView(this).apply {
            text = "Model"
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(0, dp(16), 0, dp(4))
        })
        val modelPickerContainer = vertical(0)
        container.addView(modelPickerContainer)

        // Hidden advanced escape hatch (#98): a hand-typed model id, only revealed via the
        // disclosure below. Pre-filled + auto-expanded when the existing entry's model isn't a
        // recognized catalog id for its kind (e.g. a retired id, or a value only reachable
        // through this same escape hatch previously).
        val advancedModelInput = EditText(this).apply {
            hint = "Custom model id (untested, unsupported)"
            setText(existing?.model ?: "")
        }
        val advancedSection = vertical(0).apply {
            visibility = View.GONE
            addView(TextView(this@CloudProviderActivity).apply {
                text = "Unsupported: typos or retired model ids will fail at call time with no extra validation."
                textSize = 12f
                setTextColor(attrColor(android.R.attr.textColorSecondary))
                setPadding(0, 0, 0, dp(4))
            })
            addView(advancedModelInput)
        }
        container.addView(TextView(this).apply {
            text = "Advanced: enter a custom model id"
            setTextColor(attrColor(com.google.android.material.R.attr.colorPrimary))
            setPadding(0, dp(8), 0, dp(4))
            setOnClickListener { advancedSection.visibility = View.VISIBLE }
        })
        container.addView(advancedSection)

        // Radio-selected catalog model id, or null if none picked yet (fresh "Add provider"
        // dialog before the group is first built, or the advanced field is in control instead).
        var pickedModelId: String? = null
        val modelRadioGroup = RadioGroup(this).apply { orientation = LinearLayout.VERTICAL }

        fun rebuildModelPicker(kind: ProviderKind) {
            modelPickerContainer.removeAllViews()
            modelRadioGroup.removeAllViews()
            // ModelUseCase.CLEANUP filter (#104): entriesFor(catalog, kind) alone returns EVERY
            // catalog entry for kind regardless of use case, including transcription-only entries
            // like gpt-4o-transcribe/whisper-1 -- the mirror-image of the #101/#102 bug this same
            // provider-chain rework fixed on the transcription side. Without this filter, picking
            // an ASR-only model here saves it as entry.model (the cleanup model), which then gets
            // POSTed to /v1/chat/completions and fails at call time. Matches the same
            // ModelUseCase.TRANSCRIPTION filtering already applied to the transcription picker
            // below (rebuildTranscriptionPicker).
            val entries = ModelCatalogResolver.entriesFor(catalog, kind, ModelUseCase.CLEANUP)
            val existingModel = existing?.model
            val existingIsCatalogModel = existingModel != null && entries.any { it.modelId == existingModel }
            pickedModelId = existingModel?.takeIf { existingIsCatalogModel }
                ?: entries.firstOrNull()?.modelId

            entries.forEach { entry ->
                val row = MaterialRadioButton(this).apply {
                    text = "${entry.displayName} \u00b7 ${ModelCatalogResolver.tierBadge(entry.tier)}\n${entry.description}"
                    id = View.generateViewId()
                    isChecked = entry.modelId == pickedModelId
                    buttonTintList = ColorStateList.valueOf(attrColor(com.google.android.material.R.attr.colorPrimary))
                    setOnClickListener {
                        pickedModelId = entry.modelId
                        advancedSection.visibility = View.GONE
                    }
                }
                modelRadioGroup.addView(row)
            }
            modelPickerContainer.addView(modelRadioGroup)

            // Existing entry with an off-catalog model: nothing in the picker matches, so open
            // the advanced field pre-filled instead of silently defaulting to a different model.
            if (existingModel != null && !existingIsCatalogModel) {
                advancedSection.visibility = View.VISIBLE
                pickedModelId = null
            }
        }

        rebuildModelPicker(existing?.kind ?: selectedKind())

        // --- Transcription model picker (#101/#102): a SEPARATE model choice from the cleanup
        // picker above, only shown for kinds where ProviderKind.supportsTranscription() is
        // true. Cleanup and transcription are disjoint model namespaces on the same provider --
        // sharing one picker/field for both was the actual root cause of #102 (a cleanup model
        // id like "gpt-5.4-nano" silently sent to /v1/audio/transcriptions, which needs
        // "whisper-1"/"gpt-4o-transcribe"). Container starts empty; rebuiltTranscriptionPicker
        // fills it in only when the currently-selected kind actually supports transcription.
        val transcriptionSectionHeader = TextView(this).apply {
            text = "Transcription model"
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(0, dp(16), 0, dp(4))
            visibility = View.GONE
        }
        container.addView(transcriptionSectionHeader)
        val transcriptionPickerContainer = vertical(0)
        container.addView(transcriptionPickerContainer)
        var pickedTranscriptionModelId: String? = null
        val transcriptionRadioGroup = RadioGroup(this).apply { orientation = LinearLayout.VERTICAL }

        val advancedTranscriptionModelInput = EditText(this).apply {
            hint = "Custom transcription model id (untested, unsupported)"
            setText(existing?.transcriptionModel ?: "")
        }
        val advancedTranscriptionSection = vertical(0).apply {
            visibility = View.GONE
            addView(TextView(this@CloudProviderActivity).apply {
                text = "Unsupported: typos or retired model ids will fail at call time with no extra validation."
                textSize = 12f
                setTextColor(attrColor(android.R.attr.textColorSecondary))
                setPadding(0, 0, 0, dp(4))
            })
            addView(advancedTranscriptionModelInput)
        }
        val advancedTranscriptionToggle = TextView(this).apply {
            text = "Advanced: enter a custom transcription model id"
            setTextColor(attrColor(com.google.android.material.R.attr.colorPrimary))
            setPadding(0, dp(8), 0, dp(4))
            visibility = View.GONE
            setOnClickListener { advancedTranscriptionSection.visibility = View.VISIBLE }
        }
        container.addView(advancedTranscriptionToggle)
        container.addView(advancedTranscriptionSection)

        fun rebuildTranscriptionPicker(kind: ProviderKind) {
            transcriptionPickerContainer.removeAllViews()
            transcriptionRadioGroup.removeAllViews()
            val supportsTranscription = kind.supportsTranscription() && kind != ProviderKind.LOCAL
            transcriptionSectionHeader.visibility = if (supportsTranscription) View.VISIBLE else View.GONE
            advancedTranscriptionToggle.visibility = if (supportsTranscription) View.VISIBLE else View.GONE
            if (!supportsTranscription) {
                advancedTranscriptionSection.visibility = View.GONE
                pickedTranscriptionModelId = null
                return
            }

            val entries = ModelCatalogResolver.entriesFor(catalog, kind, ModelUseCase.TRANSCRIPTION)
            val existingModel = existing?.takeIf { it.kind == kind }?.transcriptionModel
            val existingIsCatalogModel = existingModel != null && entries.any { it.modelId == existingModel }
            pickedTranscriptionModelId = existingModel?.takeIf { existingIsCatalogModel }
                ?: entries.firstOrNull()?.modelId

            entries.forEach { entry ->
                val row = MaterialRadioButton(this).apply {
                    text = "${entry.displayName} \u00b7 ${ModelCatalogResolver.tierBadge(entry.tier)}\n${entry.description}"
                    id = View.generateViewId()
                    isChecked = entry.modelId == pickedTranscriptionModelId
                    buttonTintList = ColorStateList.valueOf(attrColor(com.google.android.material.R.attr.colorPrimary))
                    setOnClickListener {
                        pickedTranscriptionModelId = entry.modelId
                        advancedTranscriptionSection.visibility = View.GONE
                    }
                }
                transcriptionRadioGroup.addView(row)
            }
            transcriptionPickerContainer.addView(transcriptionRadioGroup)

            if (existingModel != null && !existingIsCatalogModel) {
                advancedTranscriptionSection.visibility = View.VISIBLE
                pickedTranscriptionModelId = null
            }
        }

        rebuildTranscriptionPicker(existing?.kind ?: selectedKind())
        if (existing == null) {
            radioButtons.forEachIndexed { i, rb ->
                rb.setOnClickListener {
                    advancedModelInput.setText("")
                    advancedSection.visibility = View.GONE
                    rebuildModelPicker(addableKinds[i])
                    advancedTranscriptionModelInput.setText("")
                    advancedTranscriptionSection.visibility = View.GONE
                    rebuildTranscriptionPicker(addableKinds[i])
                }
            }
        }

        val baseUrlInput = EditText(this).apply {
            hint = "Base URL override (optional)"
            setText(existing?.baseUrlOverride ?: "")
            visibility = if (existing?.baseUrlOverride.isNullOrBlank()) View.GONE else View.VISIBLE
        }
        container.addView(TextView(this).apply {
            text = "Advanced: base URL override"
            setTextColor(attrColor(com.google.android.material.R.attr.colorPrimary))
            setPadding(0, dp(8), 0, dp(4))
            setOnClickListener { baseUrlInput.visibility = View.VISIBLE }
        })
        container.addView(baseUrlInput)

        container.addView(TextView(this).apply {
            text = "Credential"
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(0, dp(16), 0, dp(4))
        })
        val keyInput = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            val existingKey = existing?.let { ProviderCredentialStore.get(this@CloudProviderActivity, it.kind) } ?: ""
            hint = if (existingKey.isBlank()) "Paste key" else ProviderCredentialStore.maskForDisplay(existingKey)
        }
        container.addView(keyInput)

        val builder = android.app.AlertDialog.Builder(this)
            .setTitle(if (existing == null) "Add provider" else "Edit ${providerLabel(existing.kind)}")
            .setView(ScrollView(this).apply { addView(container) })
            .setPositiveButton("Save") { _, _ ->
                val kind = existing?.kind ?: selectedKind()
                val customModel = advancedModelInput.text.toString().trim()
                val model = if (advancedSection.visibility == View.VISIBLE && customModel.isNotBlank()) {
                    customModel
                } else {
                    pickedModelId ?: ""
                }
                if (model.isBlank()) {
                    toast("Pick a model or enter a custom model id")
                    return@setPositiveButton
                }
                // Transcription model (#101/#102): only meaningful for transcription-capable
                // kinds; null for everything else (Anthropic, OmniRoute, and any kind where the
                // section was never shown). A blank custom field with the advanced section open
                // is treated as "no explicit choice" (null), not an empty string -- consistent
                // with the null-means-unset convention documented on the field itself.
                val customTranscriptionModel = advancedTranscriptionModelInput.text.toString().trim()
                val transcriptionModel = if (!kind.supportsTranscription() || kind == ProviderKind.LOCAL) {
                    null
                } else if (advancedTranscriptionSection.visibility == View.VISIBLE && customTranscriptionModel.isNotBlank()) {
                    customTranscriptionModel
                } else {
                    pickedTranscriptionModelId
                }
                val baseUrlOverride = baseUrlInput.text.toString().trim().takeIf { it.isNotBlank() }
                val enteredKey = keyInput.text.toString().trim()
                if (enteredKey.isNotBlank()) ProviderCredentialStore.set(this, kind, enteredKey)
                onSave(ProviderChainEntry(kind, model, baseUrlOverride, transcriptionModel))
            }
            .setNegativeButton("Cancel", null)
        // Give the user a way to actually delete a stored key from the device (M10): a blank Save
        // deliberately keeps the old key, and removing a chain entry keeps the credential too, so
        // without this there was no removal path anywhere. Only offered when editing an entry whose
        // key is actually set.
        if (existing != null && ProviderCredentialStore.isConfigured(this, existing.kind)) {
            builder.setNeutralButton("Remove key") { _, _ ->
                confirmRemoveSecret("${providerLabel(existing.kind)} API key") {
                    ProviderCredentialStore.clear(this, existing.kind)
                    toast("${providerLabel(existing.kind)} key removed")
                    refresh()
                }
            }
        }
        builder.show()
    }

    // --- Use cloud for Transcription / Cleanup ---

    private fun cloudTranscriptionSubtitle(): String {
        val useLocal = prefs().getBoolean("use_local", true)
        if (useLocal) return "Off — transcription runs on-device"
        val chain = currentChain()
        val hasCandidate = ProviderChainRuntime.transcriptionCandidates(chain).any { it.kind != ProviderKind.LOCAL }
        // Capability alone isn't enough -- a provider with no key set won't actually work (L17).
        val hasConfigured = hasConfiguredCloudTranscription(chain) { ProviderCredentialStore.isConfigured(this, it) }
        return when {
            hasConfigured -> "On — uses the chain above"
            hasCandidate -> "On, but no key is set for a transcription provider yet — falls back to on-device"
            else -> "On, but no configured provider supports transcription yet — falls back to on-device"
        }
    }

    private fun cloudCleanupSubtitle(): String {
        if (!CloudFeatureToggle.cleanupEnabled(this)) return "Off — cleanup runs on-device (or is skipped if no local model is set)"
        val chain = currentChain()
        val cloudEntries = chain.capableEntriesFor(needsTranscription = false).filter { it.kind != ProviderKind.LOCAL }
        // Capability alone isn't enough -- a provider with no key set won't actually work (L17).
        val hasConfigured = cloudEntries.any { ProviderCredentialStore.isConfigured(this, it.kind) }
        return when {
            hasConfigured -> "On — uses the chain above"
            cloudEntries.isNotEmpty() -> "On, but no key is set for a cloud cleanup provider yet"
            else -> "On, but no cloud provider is configured yet"
        }
    }

    private fun onToggleCloudTranscription(newCloud: Boolean) {
        prefs().edit().putBoolean("use_local", !newCloud).apply()
        refresh()
    }

    private fun onToggleCloudCleanup(enabled: Boolean) {
        CloudFeatureToggle.setCleanupEnabled(this, enabled)
        refresh()
    }

    // --- Fallback toggles (#100) ---

    private fun localFallbackSubtitle(): String =
        if (DictationModeToggle.allowLocalFallback(this))
            "On — if every cloud provider fails, dictation falls back to on-device"
        else
            "Off — if cloud fails, you'll see an error instead of a silent on-device retry"

    private fun cloudFallbackSubtitle(): String =
        if (DictationModeToggle.allowCloudFallback(this))
            "On — if on-device isn't ready or fails, dictation retries in the cloud"
        else
            "Off — if on-device isn't ready or fails, you'll see an error instead of a silent cloud retry"

    private fun onToggleLocalFallback(enabled: Boolean) {
        DictationModeToggle.setAllowLocalFallback(this, enabled)
        refresh()
    }

    private fun onToggleCloudFallback(enabled: Boolean) {
        DictationModeToggle.setAllowCloudFallback(this, enabled)
        refresh()
    }

    companion object {
        private const val LP_WRAP = LinearLayout.LayoutParams.WRAP_CONTENT

        /** Presets shown as radio options on the Dictation mode section, in display order.
         *  [DictationMode.CUSTOM] deliberately has no radio button -- it's a derived state
         *  reachable only by hand-editing the per-feature overrides below, never picked directly. */
        private val DICTATION_MODE_OPTIONS = listOf(DictationMode.CLOUD, DictationMode.LOCAL, DictationMode.FASTEST)

        /** Category subtitle for MainActivity's Cloud row (#95 Phase 3), e.g. "1 provider
         *  configured" or "Not configured". Counts only cloud-capable entries -- the same LOCAL-
         *  is-not-a-cloud-provider filtering as [refreshChainRows] -- so a chain holding only the
         *  undeletable LOCAL floor entry (the common/default state) correctly reads as "Not
         *  configured" instead of misreporting "1 provider configured" for a provider that isn't
         *  really configurable/removable here at all. */
        fun subtitle(context: android.content.Context): String =
            subtitleFor(ProviderChainStore.load(context))

        /** Pure half of [subtitle], split out so the LOCAL-filtering logic is unit-testable
         *  without a [android.content.Context]/SharedPreferences. */
        fun subtitleFor(chain: ProviderChain): String {
            val count = chain.entries.count { it.kind != ProviderKind.LOCAL }
            return when (count) {
                0 -> "Not configured — using on-device"
                1 -> "1 provider configured"
                else -> "$count providers configured"
            }
        }
    }
}
