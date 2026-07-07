package com.trevornk.ramblr

import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.Spannable
import android.text.SpannableString
import android.text.TextWatcher
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import com.google.android.material.button.MaterialButton
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.radiobutton.MaterialRadioButton
import kotlin.concurrent.thread

/**
 * "Advanced" category screen (#93 restructure, updated #95 Phase 3): redo setup walkthrough,
 * debug/visibility toggle, overlay appearance customization, personal vocabulary, dictation
 * history toggle + viewer. Everything here was previously tucked behind a collapsed-by-default
 * "Advanced" section on the single MainActivity ScrollView (#38); now it's its own screen and
 * therefore no longer needs the in-memory expand/collapse chevron -- it's always fully shown once
 * navigated to.
 *
 * The old cleanup waterfall editor (3 hardcoded credential rows + ordered step list with
 * add/edit/move/remove/test) that used to live here has been fully superseded by the unified
 * CloudProviderActivity (#95 Phase 3) -- see MainActivity's Cloud category row. [groupLabel] is
 * kept only because dictation history's "paid fallback" badge still needs to render a
 * [CleanupStepGroup] name for old history entries.
 *
 * "Redo setup walkthrough" is the one row here that can't act locally: the onboarding wizard
 * dialogs stay owned by MainActivity (the simplest option -- see MainActivity's own kdoc note),
 * so this just navigates back to MainActivity with an intent extra telling it to start the
 * walkthrough once resumed, rather than duplicating any wizard-dialog logic here.
 */
class AdvancedActivity : BaseSettingsActivity() {

    private lateinit var debugVisibilitySwitch: MaterialSwitch
    private lateinit var perAppPersonaSwitch: MaterialSwitch
    private lateinit var hideIconSwitch: MaterialSwitch
    private lateinit var autoPeekSwitch: MaterialSwitch
    private lateinit var autoPeekDelayRow: LinearLayout
    private lateinit var peekSizeRow: LinearLayout
    private lateinit var rawTextRetrySwitch: MaterialSwitch
    private lateinit var vocabularyRowSub: TextView
    private lateinit var historyEnabledSwitch: MaterialSwitch

    // Overlay appearance (#43/#53) -- rows read fresh from OverlayAppearancePrefs/OverlayIconStore
    // on every refreshOverlayAppearanceRows() call rather than caching values on these fields.
    private lateinit var overlaySizeRow: LinearLayout
    private lateinit var overlayBorderColorRow: LinearLayout
    private lateinit var overlayFillColorRow: LinearLayout
    private lateinit var overlayGlyphColorRow: LinearLayout
    private lateinit var overlayCustomIconRow: LinearLayout
    private lateinit var overlayRemoveCustomIconRow: LinearLayout

    // Modern Photo Picker (#43): registered unconditionally during field init, per the Activity
    // Result API.
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = vertical(0, 0)

        val header = TextView(this).apply {
            text = "Advanced"
            textSize = 32f
            setPadding(dp(24), dp(64), dp(24), dp(24))
        }
        root.addView(header)

        root.addView(settingsRow(
            "Redo setup walkthrough",
            "Re-run the guide for permissions, transcription, cleanup, and streaming preview"
        ) { startWalkthroughInMainActivity() })

        debugVisibilitySwitch = MaterialSwitch(this).apply {
            isChecked = DebugVisibilityToggle.isEnabled(this@AdvancedActivity)
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

        perAppPersonaSwitch = MaterialSwitch(this).apply {
            isChecked = PerAppPersonaToggle.isEnabled(this@AdvancedActivity)
            isClickable = false
        }
        val perAppPersonaRow = settingsRow(
            "Remember cleanup style per app",
            "Auto-selects the last style you picked for each app instead of always using your global default",
            perAppPersonaSwitch
        ) {
            val newVal = !perAppPersonaSwitch.isChecked
            PerAppPersonaToggle.setEnabled(this, newVal)
            perAppPersonaSwitch.isChecked = newVal
        }
        root.addView(perAppPersonaRow)

        hideIconSwitch = MaterialSwitch(this).apply {
            isChecked = HideIconToggle.isEnabled(this@AdvancedActivity)
            isClickable = false
        }
        val hideIconRow = settingsRow(
            "Allow hiding the floating icon",
            "Adds a 'Hide icon' option to the long-press menu, so you can fully hide the icon and bring it back from a notification",
            hideIconSwitch
        ) {
            val newVal = !hideIconSwitch.isChecked
            HideIconToggle.setEnabled(this, newVal)
            hideIconSwitch.isChecked = newVal
        }
        root.addView(hideIconRow)

        autoPeekSwitch = MaterialSwitch(this).apply {
            isChecked = AutoPeekToggle.isEnabled(this@AdvancedActivity)
            isClickable = false
        }
        val autoPeekRow = settingsRow(
            "Auto-hide icon when idle",
            "Slides the icon toward the screen edge after a few seconds of inactivity. Turn off to keep it fully visible at all times",
            autoPeekSwitch
        ) {
            val newVal = !autoPeekSwitch.isChecked
            AutoPeekToggle.setEnabled(this, newVal)
            autoPeekSwitch.isChecked = newVal
            if (!newVal) WhisperAccessibilityService.instance?.restoreFromPeekIfPeeked()
        }
        root.addView(autoPeekRow)

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

        rawTextRetrySwitch = MaterialSwitch(this).apply {
            isChecked = RawTextRetryToggle.isEnabled(this@AdvancedActivity)
            isClickable = false
        }
        val rawTextRetryRow = settingsRow(
            "Offer raw text after cleanup",
            "Shows a \"Tap to use raw text\" bubble for a few seconds after cleanup changes your wording, so you can undo it with one tap",
            rawTextRetrySwitch
        ) {
            val newVal = !rawTextRetrySwitch.isChecked
            RawTextRetryToggle.setEnabled(this, newVal)
            rawTextRetrySwitch.isChecked = newVal
        }
        root.addView(rawTextRetryRow)

        // Fallback restore path (#Feature B): if the icon is currently hidden -- including for
        // someone who turns the toggle above off while already hidden -- give them a way back
        // that doesn't depend on the notification still being around.
        if (IconHiddenState.isHidden(this)) {
            root.addView(
                settingsRow("Icon is currently hidden", "Tap to show it again") {
                    IconHiddenState.setHidden(this, false)
                    WhisperAccessibilityService.instance?.applyOverlayVisibility()
                    IconVisibilityNotifications.cancel(this)
                    recreate()
                }
            )
        }

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

        // --- More (personal vocabulary, dictation history) ---
        root.addView(sectionHeader("More"))

        val vocabularyRow = settingsRow("Personal vocabulary", vocabularySummary()) { promptVocabulary() }
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

        val historyGroup = nestedGroup()
        historyGroup.content.addView(
            settingsRow("Dictation history", "Recover past transcripts, tap to copy", indent = 0) { showHistory() }
        )
        root.addView(historyGroup.outer)

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
        debugVisibilitySwitch.isChecked = DebugVisibilityToggle.isEnabled(this)
        vocabularyRowSub.text = vocabularySummary()
        historyEnabledSwitch.isChecked = prefs().getBoolean(KEY_HISTORY_ENABLED, true)
        autoPeekDelayRow.findViewWithTag<TextView>("subtitle").text = autoPeekDelaySummary()
        peekSizeRow.findViewWithTag<TextView>("subtitle").text = peekSizeSummary()
        // Included here (not just onCreate) so background work finishing after a fold/rotation
        // -- e.g. pickOverlayIcon's thread -- doesn't leave a stale overlay-appearance row (#89).
        refreshOverlayAppearanceRows()
    }

    /** Navigates back to MainActivity and asks it to (re-)start the onboarding wizard once
     *  resumed (#93): onboarding dialogs stay owned by MainActivity rather than being duplicated
     *  or moved here, so this is a plain intent hand-off, not a re-implementation. */
    private fun startWalkthroughInMainActivity() {
        startActivity(Intent(this, MainActivity::class.java).apply {
            putExtra(MainActivity.EXTRA_START_WALKTHROUGH, true)
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        })
        finish()
    }

    // --- Auto-hide delay (Feature A follow-up) ---

    private fun autoPeekDelaySummary(): String {
        val seconds = AutoPeekDelay.secondsOrDefault(this)
        return "$seconds second${if (seconds == 1) "" else "s"} of inactivity before it slides to the edge"
    }

    /** Numeric picker for the auto-peek idle delay, entered in whole seconds and clamped to
     *  [AutoPeekDelay.MIN_SECONDS]..[AutoPeekDelay.MAX_SECONDS]. Takes effect on the very next
     *  idle-timer arm in [WhisperAccessibilityService] -- no service restart needed, matching the
     *  existing [AutoPeekToggle] pattern. */
    private fun promptAutoPeekDelay() {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(AutoPeekDelay.secondsOrDefault(this@AdvancedActivity).toString())
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

    /** Numeric picker for how much of the ring stays visible/tappable once peeked, entered in
     *  whole dp and clamped to [PeekVisibleSize.MIN_DP]..[PeekVisibleSize.MAX_DP]. A bigger sliver
     *  is a bigger, easier-to-hit restore target -- the same tradeoff that drove the shipped
     *  default up from 14dp to [RingPeek.PEEK_VISIBLE_DP] after the SchildiChat peek-restore bug
     *  (see [RingPeek]'s doc). Takes effect on the next auto-peek, no service restart needed. */
    private fun promptPeekSize() {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(PeekVisibleSize.dpOrDefault(this@AdvancedActivity).toString())
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

    /** Lets the user edit their personal vocabulary — project names and jargon cleanup should
     *  preserve verbatim — one term per line, seeded from [VocabularyTerms.DEFAULTS] on first
     *  run so existing behavior doesn't regress (#26). */
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

    // --- Dictation History (#25) ---

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
     *  debug/visibility toggle is on and this entry was actually served by a paid group. */
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

    private fun pickOverlayColor(title: String, current: Int?, onPick: (Int?) -> Unit) {
        val swatchItems = overlayColorSwatches.map { (label, color) ->
            SpannableString("●  $label").apply {
                setSpan(
                    ForegroundColorSpan(color ?: attrColor(android.R.attr.textColorSecondary)),
                    0, 1, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            }
        }
        val customItem = SpannableString("●  Custom…").apply {
            setSpan(
                ForegroundColorSpan(current ?: attrColor(android.R.attr.textColorSecondary)),
                0, 1, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }
        val items = swatchItems + customItem
        android.app.AlertDialog.Builder(this)
            .setTitle(title)
            .setItems(items.toTypedArray()) { _, which ->
                if (which < overlayColorSwatches.size) onPick(overlayColorSwatches[which].second)
                else openCustomColorPicker(title, current, onPick)
            }
            .show()
    }

    private fun openCustomColorPicker(title: String, current: Int?, onPick: (Int?) -> Unit) {
        val hsv = FloatArray(3)
        Color.colorToHSV((current ?: Color.WHITE) or 0xFF000000.toInt(), hsv)

        var isSyncing = false
        fun currentColor(): Int = Color.HSVToColor(hsv) or 0xFF000000.toInt()

        val preview = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(LP_MATCH, dp(56))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(8).toFloat()
                setColor(currentColor())
                setStroke(dp(1), attrColor(android.R.attr.textColorSecondary))
            }
        }

        val hexInput = EditText(this).apply {
            hint = "#RRGGBB"
            setText(String.format("#%06X", 0xFFFFFF and currentColor()))
        }

        fun updatePreview() {
            (preview.background as GradientDrawable).setColor(currentColor())
        }

        fun syncHexFromSliders() {
            isSyncing = true
            hexInput.setText(String.format("#%06X", 0xFFFFFF and currentColor()))
            isSyncing = false
        }

        fun sliderRow(labelText: String, maxValue: Int, initialProgress: Int, onChange: (Int) -> Unit): LinearLayout {
            val valueLabel = TextView(this).apply { text = "$labelText: $initialProgress" }
            val seekBar = SeekBar(this).apply {
                max = maxValue
                progress = initialProgress
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                        if (!fromUser || isSyncing) return
                        valueLabel.text = "$labelText: $progress"
                        onChange(progress)
                        updatePreview()
                        syncHexFromSliders()
                    }
                    override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                    override fun onStopTrackingTouch(seekBar: SeekBar?) {}
                })
            }
            return LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                addView(valueLabel)
                addView(seekBar)
                tag = Pair(valueLabel, seekBar)
            }
        }

        val hueRow = sliderRow("Hue", 360, hsv[0].toInt()) { hsv[0] = it.toFloat() }
        val satRow = sliderRow("Saturation", 100, (hsv[1] * 100).toInt()) { hsv[1] = it / 100f }
        val valRow = sliderRow("Brightness", 100, (hsv[2] * 100).toInt()) { hsv[2] = it / 100f }

        @Suppress("UNCHECKED_CAST")
        fun sliderRowViews(row: LinearLayout) = row.tag as Pair<TextView, SeekBar>

        hexInput.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                if (isSyncing) return
                val parsed = parseHexColor(s.toString()) ?: return
                Color.colorToHSV(parsed, hsv)
                isSyncing = true
                listOf(hueRow to 0, satRow to 1, valRow to 2).forEach { (row, hsvIndex) ->
                    val (label, seekBar) = sliderRowViews(row)
                    val progress = if (hsvIndex == 0) hsv[0].toInt() else (hsv[hsvIndex] * 100).toInt()
                    seekBar.progress = progress
                    val name = when (hsvIndex) { 0 -> "Hue"; 1 -> "Saturation"; else -> "Brightness" }
                    label.text = "$name: $progress"
                }
                isSyncing = false
                updatePreview()
            }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(8), dp(24), dp(8))
            addView(preview, LinearLayout.LayoutParams(LP_MATCH, dp(56)).apply { bottomMargin = dp(16) })
            addView(hueRow)
            addView(satRow)
            addView(valRow)
            addView(TextView(this@AdvancedActivity).apply {
                text = "Hex"
                setPadding(0, dp(8), 0, dp(4))
            })
            addView(hexInput)
        }

        android.app.AlertDialog.Builder(this)
            .setTitle(title)
            .setView(ScrollView(this).apply { addView(container) })
            .setPositiveButton("Use this color") { _, _ -> onPick(currentColor()) }
            .setNeutralButton("Reset to default") { _, _ -> onPick(null) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun parseHexColor(raw: String): Int? = HexColorParser.parse(raw)

    // --- History display helper (#95 Phase 3: waterfall editor removed, groupLabel kept only
    // for rendering old dictation-history "paid fallback" badges) ---

    private fun groupLabel(group: CleanupStepGroup) = when (group) {
        CleanupStepGroup.LEGACY -> "Legacy"
        CleanupStepGroup.OMNIROUTE -> "OmniRoute"
        CleanupStepGroup.OPENAI_DIRECT -> "Direct OpenAI"
        CleanupStepGroup.ANTHROPIC_DIRECT -> "Direct Anthropic"
        CleanupStepGroup.GEMINI_DIRECT -> "Direct Gemini"
        CleanupStepGroup.LOCAL_LLM -> "Local (on-device)"
    }

    companion object {
        private const val LP_MATCH = LinearLayout.LayoutParams.MATCH_PARENT
        private const val LP_WRAP = LinearLayout.LayoutParams.WRAP_CONTENT
        private const val KEY_HISTORY_ENABLED = "dictation_history_enabled"

        /** Category subtitle for MainActivity's Advanced row (#93) -- unlike the other four
         *  categories there's no single on/off/mode signal worth summarizing here (this screen
         *  is redo-setup + debug toggle + overlay appearance + vocabulary + history), so this
         *  just names what's inside rather than picking one value. */
        fun subtitle(context: android.content.Context): String =
            "Redo setup, overlay appearance, vocabulary, history"
    }
}
