package com.trevornk.ramblr

import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.Editable
import android.text.Spannable
import android.text.SpannableString
import android.text.TextWatcher
import android.text.style.ForegroundColorSpan
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import kotlin.concurrent.thread

/**
 * "Overlay Appearance" settings screen (#104 restructure): icon size, border/fill/glyph color,
 * custom icon image, remove custom icon -- the 5 rows that previously lived directly on
 * AdvancedActivity's single long list. Moved here verbatim (same OverlayAppearancePrefs/
 * OverlayIconStore reads, same WhisperAccessibilityService.applyOverlayAppearance() refresh
 * call, same color-picker dialog) -- this is a pure UI reorganization, not a behavior change.
 */
class OverlayAppearanceActivity : BaseSettingsActivity() {

    private lateinit var overlaySizeRow: LinearLayout
    private lateinit var overlayBorderColorRow: LinearLayout
    private lateinit var overlayFillColorRow: LinearLayout
    private lateinit var overlayGlyphColorRow: LinearLayout
    private lateinit var overlayCustomIconRow: LinearLayout
    private lateinit var overlayRemoveCustomIconRow: LinearLayout

    private val pickOverlayIcon = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri == null) return@registerForActivityResult
        val appContext = applicationContext
        thread {
            val saved = OverlayIconStore.save(appContext, uri)
            runOnUiThread {
                if (isDestroyed || isFinishing) return@runOnUiThread
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

        root.addView(TextView(this).apply {
            text = "Overlay Appearance"
            textSize = 32f
            setPadding(dp(24), dp(64), dp(24), dp(24))
        })

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

        setContentView(ScrollView(this).apply {
            setBackgroundColor(attrColor(android.R.attr.colorBackground))
            addView(root)
        })

        refreshOverlayAppearanceRows()
    }

    override fun onResume() {
        super.onResume()
        refreshOverlayAppearanceRows()
    }

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
            addView(TextView(this@OverlayAppearanceActivity).apply {
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

    companion object {
        private const val LP_MATCH = LinearLayout.LayoutParams.MATCH_PARENT

        /** Category subtitle for AdvancedActivity's Overlay Appearance row (#104). */
        fun subtitle(context: android.content.Context): String {
            val appearance = OverlayAppearancePrefs.load(context)
            val hasCustomIcon = appearance.hasCustomIcon && OverlayIconStore.exists(context)
            return if (hasCustomIcon) "Custom icon, ${appearance.ringSizeDp}dp"
                else "Default icon, ${appearance.ringSizeDp}dp"
        }
    }
}
