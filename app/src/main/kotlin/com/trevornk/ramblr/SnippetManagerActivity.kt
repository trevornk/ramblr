package com.trevornk.ramblr

import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.google.android.material.button.MaterialButton
import com.google.android.material.materialswitch.MaterialSwitch

/**
 * Snippets manager (#248): full CRUD over voice-triggered canned-text expansions, following
 * [StyleManagerActivity]'s exact list/edit/delete pattern -- a master on/off switch (opt-in, see
 * [SnippetsToggle]) plus a "+ New snippet" row and one row per configured [SnippetEntry] with
 * edit and delete actions.
 *
 * See [SnippetExpander]'s kdoc for the full matching contract this UI's help text summarizes,
 * and [SnippetRuntimeSupport] for the single call site both dictation hosts consult.
 */
class SnippetManagerActivity : BaseSettingsActivity() {

    private lateinit var enabledSwitch: MaterialSwitch
    private lateinit var enabledRowSub: TextView
    private lateinit var newSnippetRow: LinearLayout
    private lateinit var emptyStateRow: TextView
    private lateinit var listContainer: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = vertical(0, 0)

        root.addView(TextView(this).apply {
            text = "Snippets"
            textSize = 32f
            setPadding(dp(24), dp(64), dp(24), dp(24))
        })

        enabledSwitch = MaterialSwitch(this).apply {
            isChecked = SnippetsToggle.isEnabled(this@SnippetManagerActivity)
            isClickable = false
        }
        val enabledRow = settingsRow(
            "Enable snippets",
            enabledSubtitle(),
            enabledSwitch,
        ) {
            val newVal = !enabledSwitch.isChecked
            SnippetsToggle.setEnabled(this, newVal)
            enabledSwitch.isChecked = newVal
            refresh()
        }
        enabledRowSub = enabledRow.findViewWithTag("subtitle")
        root.addView(enabledRow)

        root.addView(TextView(this).apply {
            text = HELP_TEXT
            textSize = 13f
            setTextColor(attrColor(android.R.attr.textColorSecondary))
            setPadding(dp(24), 0, dp(24), dp(16))
        })

        newSnippetRow = settingsRow("+ New snippet", "Add a trigger phrase and its expansion") { promptNewSnippet() }
        root.addView(newSnippetRow)

        emptyStateRow = TextView(this).apply {
            text = "No snippets configured yet."
            textSize = 14f
            setTextColor(attrColor(android.R.attr.textColorSecondary))
            setPadding(dp(24), dp(8), dp(24), dp(16))
        }
        root.addView(emptyStateRow)

        listContainer = vertical(0)
        root.addView(listContainer)

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
        val enabled = SnippetsToggle.isEnabled(this)
        enabledSwitch.isChecked = enabled
        enabledRowSub.text = enabledSubtitle()
        rebuildList()
    }

    private fun enabledSubtitle(): String {
        val count = SnippetsStore.load(this).size
        return if (SnippetsToggle.isEnabled(this)) {
            "On -- $count snippet${if (count == 1) "" else "s"} configured"
        } else {
            "Off -- configured snippets are kept but never expanded"
        }
    }

    private fun rebuildList() {
        listContainer.removeAllViews()
        val entries = SnippetsStore.load(this)
        emptyStateRow.visibility = if (entries.isEmpty()) View.VISIBLE else View.GONE
        for (entry in entries) listContainer.addView(buildSnippetRow(entry))
    }

    private fun buildSnippetRow(entry: SnippetEntry): View {
        val editBtn = MaterialButton(this, null, com.google.android.material.R.attr.materialIconButtonStyle).apply {
            text = "\u270E"
            textSize = 16f
            setTextColor(attrColor(com.google.android.material.R.attr.colorPrimary))
            setOnClickListener { promptEditSnippet(entry) }
        }
        val deleteBtn = MaterialButton(this, null, com.google.android.material.R.attr.materialIconButtonStyle).apply {
            text = "\uD83D\uDDD1"
            textSize = 16f
            setOnClickListener { confirmDeleteSnippet(entry) }
        }
        val rightContainer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(editBtn)
            addView(deleteBtn)
        }
        return settingsRow(
            "\u201C${entry.trigger}\u201D",
            expansionPreview(entry.expansion),
            rightContainer,
            indent = 0,
        ) { promptEditSnippet(entry) }
    }

    private fun expansionPreview(expansion: String): String {
        val oneLine = expansion.replace('\n', ' ').trim()
        return if (oneLine.length > 80) oneLine.take(80) + "\u2026" else oneLine
    }

    private fun promptNewSnippet() = showSnippetEditor(
        title = "New snippet",
        initialTrigger = "",
        initialExpansion = "",
        excludingKey = null,
    ) { trigger, expansion ->
        SnippetsStore.add(this, trigger, expansion)
        rebuildList()
        enabledRowSub.text = enabledSubtitle()
    }

    private fun promptEditSnippet(entry: SnippetEntry) = showSnippetEditor(
        title = "Edit snippet",
        initialTrigger = entry.trigger,
        initialExpansion = entry.expansion,
        excludingKey = entry.key,
    ) { trigger, expansion ->
        SnippetsStore.update(this, entry.key, trigger, expansion)
        rebuildList()
    }

    private fun showSnippetEditor(
        title: String,
        initialTrigger: String,
        initialExpansion: String,
        excludingKey: String?,
        onSave: (String, String) -> Unit,
    ) {
        val container = vertical(dp(24), dp(8))
        val triggerInput = EditText(this).apply {
            hint = "Trigger phrase, e.g. my home address"
            setText(initialTrigger)
        }
        val expansionInput = EditText(this).apply {
            hint = "Text to insert instead"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 3
            gravity = Gravity.TOP or Gravity.START
            setText(initialExpansion)
        }
        val errorLabel = TextView(this).apply {
            setTextColor(0xFFCC3333.toInt())
            textSize = 13f
            visibility = View.GONE
        }
        container.addView(triggerInput)
        container.addView(expansionInput)
        container.addView(errorLabel)

        val dialog = android.app.AlertDialog.Builder(this)
            .setTitle(title)
            .setView(container)
            .setPositiveButton("Save", null) // real handler wired below, mirrors StyleManagerActivity (#125)
            .setNegativeButton("Cancel", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val trigger = triggerInput.text.toString()
                val expansion = expansionInput.text.toString()
                val error = SnippetEntryValidation.validate(
                    trigger, expansion,
                    SnippetsStore.existingTriggerWordSets(this, excludingKey),
                )
                if (error != null) {
                    errorLabel.text = SnippetEntryValidation.message(error)
                    errorLabel.visibility = View.VISIBLE
                } else {
                    onSave(trigger.trim(), expansion.trim())
                    dialog.dismiss()
                }
            }
        }
        dialog.show()
    }

    private fun confirmDeleteSnippet(entry: SnippetEntry) {
        android.app.AlertDialog.Builder(this)
            .setTitle("Delete \u201C${entry.trigger}\u201D?")
            .setMessage("This can't be undone.")
            .setPositiveButton("Delete") { _, _ ->
                SnippetsStore.delete(this, entry.key)
                rebuildList()
                enabledRowSub.text = enabledSubtitle()
                toast("Snippet deleted")
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    companion object {
        /** Row subtitle for [BehaviorActivity]'s "Snippets" link row. */
        fun subtitle(context: android.content.Context): String {
            val count = SnippetsStore.load(context).size
            if (count == 0) return "No snippets configured"
            val state = if (SnippetsToggle.isEnabled(context)) "on" else "off (kept, not applied)"
            return "$count snippet${if (count == 1) "" else "s"} \u2014 $state"
        }

        /**
         * Explicit, plain-language statement of the cleanup-interaction limitation
         * [SnippetExpander]'s kdoc documents and [SnippetExpanderCleanupInteractionTest] tests
         * directly: expansion runs on cleanup's OUTPUT, so a cleanup pass that rewrites the
         * trigger phrase's wording (not just case/punctuation) can stop it from matching. Kept
         * as a `const` (not inline string literal at the call site) so a JVM test can assert on
         * the exact copy shown to the user without a Robolectric UI test.
         */
        const val HELP_TEXT =
            "Say a trigger phrase while dictating and it's replaced with the canned text " +
                "you configure below -- useful for addresses, signatures, or anything you'd " +
                "rather not fully dictate every time. The expansion is inserted exactly as " +
                "written, after cleanup and vocabulary correction run -- so a cleanup rewrite " +
                "that changes the wording of your trigger phrase (not just its case or " +
                "punctuation) can prevent it from matching."
    }
}
