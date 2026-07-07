package com.trevornk.ramblr

import android.view.Gravity
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.google.android.material.button.MaterialButton
import com.google.android.material.checkbox.MaterialCheckBox

/**
 * Style manager (#103): full CRUD over cleanup personas, replacing the flat built-in-only radio
 * list that used to live directly on [CleanupActivity]. Every persona from [PersonaRegistry.all]
 * is listed (built-ins first, then custom/seeded-legacy personas in creation order); each row has
 * a checkbox controlling whether it appears in the floating icon's long-press quick menu (capped
 * at [QuickMenuPersonaStore.MAX_ENTRIES], see [refreshQuickMenuCheckbox]), an edit action, and --
 * for custom personas only -- a delete action. Built-ins can't be deleted; tapping edit on one
 * forks a new custom persona seeded with its current title/subtitle/prompt instead of mutating the
 * shipped constant (mirrors Superwhisper's "Customizing Built-In Modes" pattern).
 *
 * This screen doesn't touch which persona is *currently selected* -- that's still
 * [CleanupActivity]'s "Style" row (now just a summary + link here, see its refactor) plus the
 * long-press quick menu itself for day-to-day switching. This screen is purely persona
 * management: what exists, what's editable, what shows up in the quick menu.
 */
class StyleManagerActivity : BaseSettingsActivity() {

    private lateinit var listContainer: LinearLayout
    private lateinit var quickMenuCountLabel: TextView
    private val rows = mutableMapOf<String, PersonaRowViews>()

    private data class PersonaRowViews(val checkbox: MaterialCheckBox, val editBtn: MaterialButton, val deleteBtn: MaterialButton?)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = vertical(0, 0)

        val header = TextView(this).apply {
            text = "Manage styles"
            textSize = 32f
            setPadding(dp(24), dp(64), dp(24), dp(24))
        }
        root.addView(header)

        quickMenuCountLabel = TextView(this).apply {
            textSize = 13f
            setTextColor(attrColor(android.R.attr.textColorSecondary))
            setPadding(dp(24), 0, dp(24), dp(16))
        }
        root.addView(quickMenuCountLabel)

        val newStyleRow = settingsRow("+ New style", "Write your own cleanup prompt") { promptNewPersona() }
        root.addView(newStyleRow)

        listContainer = vertical(0)
        root.addView(listContainer)

        setContentView(ScrollView(this).apply {
            setBackgroundColor(attrColor(android.R.attr.colorBackground))
            addView(root)
        })

        rebuildList()
    }

    override fun onResume() {
        super.onResume()
        rebuildList()
    }

    private fun rebuildList() {
        listContainer.removeAllViews()
        rows.clear()
        for (persona in PersonaRegistry.all(this)) listContainer.addView(buildPersonaRow(persona))
        refreshQuickMenuCount()
    }

    private fun buildPersonaRow(persona: CleanupPersona): View {
        val checkbox = MaterialCheckBox(this).apply {
            isChecked = persona.key in QuickMenuPersonaStore.load(this@StyleManagerActivity)
            setOnClickListener { onQuickMenuCheckboxTapped(persona, isChecked) }
        }
        val editBtn = MaterialButton(this, null, com.google.android.material.R.attr.materialIconButtonStyle).apply {
            text = "✎"
            textSize = 16f
            setTextColor(attrColor(com.google.android.material.R.attr.colorPrimary))
            setOnClickListener { promptEditPersona(persona) }
        }
        val deleteBtn = if (!persona.isBuiltIn) {
            MaterialButton(this, null, com.google.android.material.R.attr.materialIconButtonStyle).apply {
                text = "🗑"
                textSize = 16f
                setOnClickListener { confirmDeletePersona(persona) }
            }
        } else null

        val rightContainer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(editBtn)
            deleteBtn?.let { addView(it) }
            addView(checkbox)
        }

        val subtitle = if (persona.isBuiltIn) persona.subtitle else "${persona.subtitle} · Custom"
        val row = settingsRow(persona.title, subtitle, rightContainer, indent = 0)
        rows[persona.key] = PersonaRowViews(checkbox, editBtn, deleteBtn)
        return row
    }

    /** Enforces the [QuickMenuPersonaStore.MAX_ENTRIES] cap at the moment a checkbox is checked --
     *  rather than only on save -- so the user gets immediate feedback instead of a silent
     *  truncation the next time the quick menu opens. */
    private fun onQuickMenuCheckboxTapped(persona: CleanupPersona, nowChecked: Boolean) {
        val current = QuickMenuPersonaStore.load(this).toMutableList()
        if (nowChecked) {
            if (current.size >= QuickMenuPersonaStore.MAX_ENTRIES) {
                rows[persona.key]?.checkbox?.isChecked = false
                toast("Quick menu is full (${QuickMenuPersonaStore.MAX_ENTRIES} max) — remove one first")
                return
            }
            current.add(persona.key)
        } else {
            current.remove(persona.key)
        }
        QuickMenuPersonaStore.setSelection(this, current)
        refreshQuickMenuCount()
    }

    private fun refreshQuickMenuCount() {
        val count = QuickMenuPersonaStore.load(this).size
        quickMenuCountLabel.text = "Long-press quick menu: $count/${QuickMenuPersonaStore.MAX_ENTRIES} selected" +
            if (count < QuickMenuPersonaStore.MIN_ENTRIES) " (aim for at least ${QuickMenuPersonaStore.MIN_ENTRIES})" else ""
    }

    /** Opens the create dialog for a brand-new custom persona. */
    private fun promptNewPersona() = showPersonaEditor(title = "New style", initialTitle = "", initialPrompt = "") { name, prompt ->
        val created = CustomPersonaStore.add(this, title = name, subtitle = "Custom style", prompt = prompt)
        // A brand-new custom persona is useless if it's not reachable anywhere -- auto-add it to
        // the quick menu when there's room, same as the model-download "ready to use" convenience
        // pattern elsewhere in this codebase (e.g. CleanupActivity.onCleanupModelWorkInfos).
        val current = QuickMenuPersonaStore.load(this)
        if (current.size < QuickMenuPersonaStore.MAX_ENTRIES) {
            QuickMenuPersonaStore.setSelection(this, current + created.key)
        }
        rebuildList()
    }

    /** Edit action: a built-in forks into a new custom persona seeded with its current
     *  title/subtitle/prompt (never mutates the shipped constant); a custom persona is edited
     *  in place. */
    private fun promptEditPersona(persona: CleanupPersona) {
        if (persona.isBuiltIn) {
            showPersonaEditor(title = "Customize \"${persona.title}\"", initialTitle = "${persona.title} (custom)", initialPrompt = persona.prompt) { name, prompt ->
                CustomPersonaStore.add(this, title = name, subtitle = "Custom style", prompt = prompt)
                rebuildList()
                toast("Saved as a new custom style")
            }
        } else {
            showPersonaEditor(title = "Edit style", initialTitle = persona.title, initialPrompt = persona.prompt) { name, prompt ->
                CustomPersonaStore.update(this, persona.key, title = name, subtitle = persona.subtitle, prompt = prompt)
                rebuildList()
            }
        }
    }

    private fun showPersonaEditor(title: String, initialTitle: String, initialPrompt: String, onSave: (String, String) -> Unit) {
        val container = vertical(dp(24), dp(8))
        val nameInput = EditText(this).apply {
            hint = "Style name"
            setText(initialTitle)
        }
        val promptInput = EditText(this).apply {
            hint = "Prompt sent to the cleanup model"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 4
            gravity = Gravity.TOP or Gravity.START
            setText(initialPrompt)
        }
        container.addView(nameInput)
        container.addView(promptInput)

        android.app.AlertDialog.Builder(this)
            .setTitle(title)
            .setView(container)
            .setPositiveButton("Save") { _, _ ->
                val name = nameInput.text.toString().trim()
                val prompt = promptInput.text.toString().trim()
                if (name.isBlank() || prompt.isBlank()) {
                    toast("Name and prompt can't be empty")
                    return@setPositiveButton
                }
                onSave(name, prompt)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun confirmDeletePersona(persona: CleanupPersona) {
        android.app.AlertDialog.Builder(this)
            .setTitle("Delete \"${persona.title}\"?")
            .setMessage("This can't be undone. Any app that was set to use this style falls back to your global style.")
            .setPositiveButton("Delete") { _, _ ->
                CustomPersonaStore.delete(this, persona.key)
                QuickMenuPersonaStore.remove(this, persona.key)
                rebuildList()
                toast("${persona.title} deleted")
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
