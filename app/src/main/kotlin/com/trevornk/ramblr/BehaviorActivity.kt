package com.trevornk.ramblr

import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
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
    private lateinit var autoPeekSwitch: MaterialSwitch
    private lateinit var autoPeekDelayRow: LinearLayout
    private lateinit var peekSizeRow: LinearLayout
    private lateinit var singleTapRestoreSwitch: MaterialSwitch
    private lateinit var rawTextRetrySwitch: MaterialSwitch
    private lateinit var vocabularyRowSub: TextView
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
        val vocabularyRow = settingsRow("Personal vocabulary", vocabularySummary()) { promptVocabulary() }
        vocabularyRowSub = vocabularyRow.findViewWithTag("subtitle")
        root.addView(vocabularyRow)

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
        iconHiddenRow.visibility = if (IconHiddenState.isHidden(this)) View.VISIBLE else View.GONE
        debugVisibilitySwitch.isChecked = DebugVisibilityToggle.isEnabled(this)
        perAppPersonaSwitch.isChecked = PerAppPersonaToggle.isEnabled(this)
        hideIconSwitch.isChecked = HideIconToggle.isEnabled(this)
        autoPeekSwitch.isChecked = AutoPeekToggle.isEnabled(this)
        singleTapRestoreSwitch.isChecked = SingleTapRestoreToggle.isEnabled(this)
        rawTextRetrySwitch.isChecked = RawTextRetryToggle.isEnabled(this)
        vocabularyRowSub.text = vocabularySummary()
        autoPeekDelayRow.findViewWithTag<TextView>("subtitle").text = autoPeekDelaySummary()
        peekSizeRow.findViewWithTag<TextView>("subtitle").text = peekSizeSummary()
    }

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

    // --- Personal vocabulary (#26) ---

    private fun vocabularyTerms() = VocabularyTerms.parse(
        prefs().getString("custom_vocabulary_terms", VocabularyTerms.DEFAULT_SERIALIZED)
    )

    private fun vocabularySummary(): String {
        val terms = vocabularyTerms()
        return if (terms.isEmpty()) "No custom terms" else terms.joinToString(", ")
    }

    private fun promptVocabulary() {
        val input = EditText(this).apply {
            hint = "One term per line, e.g. FastHTML"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
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

    companion object {
        /** Category subtitle for AdvancedActivity's Behavior row (#104). */
        fun subtitle(context: android.content.Context): String =
            "Auto-hide, per-app style, vocabulary, and more"
    }
}
