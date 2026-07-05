package com.kafkasl.phonewhisper

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
 *
 * GEMINI is deliberately excluded from "Add provider": [ProviderChainRuntime] models its
 * capabilities but has no live HTTP transport for either feature yet
 * (cleanupKindsNotImplemented/transcriptionKindsNotImplemented both contain it), so offering it
 * here would let a user configure a dead-end entry that silently never fires. It can be added to
 * the picker the moment a future phase wires up real Gemini calls.
 */
class CloudProviderActivity : BaseSettingsActivity() {

    private lateinit var chainContainer: LinearLayout
    private lateinit var emptyStateView: View
    private lateinit var cloudTranscriptionSwitch: MaterialSwitch
    private lateinit var cloudTranscriptionRowSub: TextView
    private lateinit var cloudCleanupSwitch: MaterialSwitch
    private lateinit var cloudCleanupRowSub: TextView

    /** Provider kinds a user can manually add here. LOCAL is the implicit floor (never a
     *  manually-added row); GEMINI is excluded until a real transport exists -- see class kdoc. */
    private val addableKinds = listOf(ProviderKind.OPENAI, ProviderKind.ANTHROPIC, ProviderKind.OMNIROUTE)

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

        root.addView(settingsRow("Add provider", "OpenAI / Anthropic / OmniRoute", indent = 0) {
            promptAddOrEditEntry(null) { newEntry -> saveChain(currentChain().entries + newEntry) }
        })

        root.addView(sectionHeader("Use cloud for"))

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
            "Always on-device -- a cloud round trip would defeat the point of showing text live while you speak"
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
    }

    private fun refreshChainRows(chain: ProviderChain) {
        chainContainer.removeAllViews()
        if (chain.entries.isEmpty()) {
            emptyStateView.visibility = View.VISIBLE
            return
        }
        emptyStateView.visibility = View.GONE
        chain.entries.forEachIndexed { index, entry ->
            chainContainer.addView(buildChainEntryRow(entry, index, chain.entries.size))
        }
    }

    private fun buildChainEntryRow(entry: ProviderChainEntry, index: Int, total: Int): View {
        val row = vertical(0).apply { setPadding(dp(24), dp(10), dp(24), dp(10)) }

        val topLine = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        topLine.addView(TextView(this).apply {
            text = "${index + 1}. ${providerLabel(entry.kind)} \u00b7 ${entry.model}"
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
            saveChain(ProviderChainEditing.moveUp(currentChain().entries, index))
        }.apply { isEnabled = index > 0 })
        buttonRow.addView(chainButton("\u25bc") {
            saveChain(ProviderChainEditing.moveDown(currentChain().entries, index))
        }.apply { isEnabled = index < total - 1 })
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

    private fun providerLabel(kind: ProviderKind): String = when (kind) {
        ProviderKind.OPENAI -> "OpenAI"
        ProviderKind.ANTHROPIC -> "Anthropic"
        ProviderKind.GEMINI -> "Gemini"
        ProviderKind.OMNIROUTE -> "OmniRoute"
        ProviderKind.LOCAL -> "Local (on-device)"
    }

    // --- Add / edit provider dialog ---

    private fun promptAddOrEditEntry(existing: ProviderChainEntry?, onSave: (ProviderChainEntry) -> Unit) {
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

        val modelInput = EditText(this).apply {
            hint = "Model, e.g. gpt-4o-mini"
            setText(existing?.model ?: "")
        }
        container.addView(modelInput)

        val baseUrlInput = EditText(this).apply {
            hint = "Base URL override (optional)"
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

        fun selectedKind() = addableKinds.getOrElse(radioButtons.indexOfFirst { it.isChecked }) { addableKinds[0] }

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

        android.app.AlertDialog.Builder(this)
            .setTitle(if (existing == null) "Add provider" else "Edit ${providerLabel(existing.kind)}")
            .setView(ScrollView(this).apply { addView(container) })
            .setPositiveButton("Save") { _, _ ->
                val kind = existing?.kind ?: selectedKind()
                val model = modelInput.text.toString().trim()
                if (model.isBlank()) {
                    toast("Model can't be blank")
                    return@setPositiveButton
                }
                val baseUrlOverride = baseUrlInput.text.toString().trim().takeIf { it.isNotBlank() }
                val enteredKey = keyInput.text.toString().trim()
                if (enteredKey.isNotBlank()) ProviderCredentialStore.set(this, kind, enteredKey)
                onSave(ProviderChainEntry(kind, model, baseUrlOverride))
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // --- Use cloud for Transcription / Cleanup ---

    private fun cloudTranscriptionSubtitle(): String {
        val useLocal = prefs().getBoolean("use_local", true)
        if (useLocal) return "Off -- transcription runs on-device"
        val chain = currentChain()
        val hasCandidate = ProviderChainRuntime.transcriptionCandidates(chain).any { it.kind != ProviderKind.LOCAL }
        return if (hasCandidate) "On -- uses the chain above" else "On, but no configured provider supports transcription yet -- falls back to on-device"
    }

    private fun cloudCleanupSubtitle(): String {
        if (!CloudFeatureToggle.cleanupEnabled(this)) return "Off -- cleanup runs on-device (or is skipped if no local model is set)"
        val chain = currentChain()
        val hasCandidate = chain.capableEntriesFor(needsTranscription = false).any { it.kind != ProviderKind.LOCAL }
        return if (hasCandidate) "On -- uses the chain above" else "On, but no cloud provider is configured yet"
    }

    private fun onToggleCloudTranscription(newCloud: Boolean) {
        prefs().edit().putBoolean("use_local", !newCloud).apply()
        refresh()
    }

    private fun onToggleCloudCleanup(enabled: Boolean) {
        CloudFeatureToggle.setCleanupEnabled(this, enabled)
        refresh()
    }

    companion object {
        private const val LP_WRAP = LinearLayout.LayoutParams.WRAP_CONTENT

        /** Category subtitle for MainActivity's Cloud row (#95 Phase 3), e.g. "1 provider
         *  configured" or "Not configured". */
        fun subtitle(context: android.content.Context): String {
            val count = ProviderChainStore.load(context).entries.size
            return when (count) {
                0 -> "Not configured -- using on-device"
                1 -> "1 provider configured"
                else -> "$count providers configured"
            }
        }
    }
}
