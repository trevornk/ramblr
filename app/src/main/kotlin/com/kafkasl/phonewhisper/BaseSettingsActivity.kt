package com.kafkasl.phonewhisper

import android.graphics.Typeface
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

/**
 * Shared base for every per-category Settings screen (#93): holds the small UI-building helpers
 * every screen needs (settingsRow/sectionHeader/subsectionHeader/nestedGroup/vertical/dp/
 * attrColor) that used to live only on MainActivity back when Settings was one continuous
 * ScrollView. Extracted here instead of duplicated five times, per Trevor's restructure request.
 *
 * Also owns the #35 "floating overlay must not cover Settings" foreground flag for every
 * subclass, not just the launcher screen -- SetupActivity/TranscriptionActivity/etc. are just as
 * much "Settings is open" from the overlay's point of view as MainActivity was.
 */
abstract class BaseSettingsActivity : AppCompatActivity() {

    override fun onResume() {
        super.onResume()
        WhisperAccessibilityService.setMainActivityForeground(true)
    }

    override fun onPause() {
        super.onPause()
        WhisperAccessibilityService.setMainActivityForeground(false)
    }

    // --- UI Helpers (moved verbatim from the old single-Activity MainActivity, #93) ---

    protected fun settingsRow(title: String, subtitle: String, widget: View? = null, indent: Int = 0, onClick: (() -> Unit)? = null): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(24) + dp(INDENT_STEP_DP) * indent, dp(16), dp(24), dp(16))
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
            tag = "title"
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

    protected fun sectionHeader(title: String) = TextView(this).apply {
        text = title
        textSize = 14f
        setTypeface(typeface, Typeface.BOLD)
        setTextColor(attrColor(com.google.android.material.R.attr.colorPrimary)) // Neutral Android-like blue
        setPadding(dp(24), dp(24), dp(24), dp(8))
    }

    /** A smaller, indented label for a subsection nested inside a top-level feature (#49) -- e.g.
     *  "Local models" under Transcription -- visually distinct from [sectionHeader] so it never
     *  reads as another same-level top-level area. */
    protected fun subsectionHeader(title: String, indent: Int = 1) = TextView(this).apply {
        text = title
        textSize = 13f
        setTypeface(typeface, Typeface.BOLD)
        setTextColor(attrColor(android.R.attr.textColorSecondary))
        setPadding(dp(24) + dp(INDENT_STEP_DP) * indent, dp(16), dp(24), dp(4))
    }

    /** The single consistent "this is a sub-item of the section above it" mechanism used
     *  everywhere in Settings (#49): a thin tinted accent bar to the left of an indented content
     *  column. Returns the outer view to add/remove from the parent for show/hide, and the inner
     *  content column that callers add their nested rows to. */
    protected data class NestedGroup(val outer: View, val content: LinearLayout)

    protected fun nestedGroup(): NestedGroup {
        val content = vertical(0)
        // The accent bar sits inside the same left gutter every settingsRow/subsectionHeader
        // already leaves before its dp(24)-inset text, so nested rows land a modest, consistent
        // step further right than their parent's own text -- not stacked on top of it.
        val accent = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(3), LP_MATCH).apply { marginStart = dp(12) }
            setBackgroundColor(withAlpha(attrColor(com.google.android.material.R.attr.colorPrimary), 0x33))
        }
        val outer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(accent)
            addView(content.apply {
                layoutParams = LinearLayout.LayoutParams(0, LP_WRAP, 1f)
            })
        }
        return NestedGroup(outer, content)
    }

    protected fun withAlpha(color: Int, alpha: Int): Int = (color and 0x00FFFFFF) or (alpha shl 24)

    protected fun vertical(padH: Int, padV: Int = padH) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(padH, padV, padH, padV)
    }

    protected fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()

    protected fun attrColor(attr: Int): Int {
        val ta = obtainStyledAttributes(intArrayOf(attr))
        val color = ta.getColor(0, 0)
        ta.recycle()
        return color
    }

    protected fun prefs() = getSharedPreferences("phonewhisper", MODE_PRIVATE)

    protected fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    /** Confirms clearing a stored secret when the user saves a blank value over it (#89). Shared
     *  by every screen with a credential field: TranscriptionActivity/CleanupActivity's OpenAI
     *  key row (#93) and AdvancedActivity's three waterfall credential rows. */
    protected fun confirmRemoveSecret(label: String, onConfirm: () -> Unit) {
        android.app.AlertDialog.Builder(this)
            .setTitle("Remove $label?")
            .setMessage("The saved $label will be deleted from this device.")
            .setPositiveButton("Remove") { _, _ -> onConfirm() }
            .setNegativeButton("Keep", null)
            .show()
    }

    /** Subtitle text for the shared OpenAI API key row (#49/#93) -- identical wherever the row is
     *  shown, since it's the same [ApiKeyStore]-backed value regardless of which screen shows it. */
    protected fun apiKeyRowSubtitleText(): String {
        val apiKey = ApiKeyStore.getApiKey(this)
        return if (apiKey.isBlank()) "Tap to set" else ApiKeyStore.maskForDisplay(apiKey)
    }

    /**
     * The OpenAI API key dialog (#49), factored out so it can be shown identically from both
     * TranscriptionActivity's cloud sub-section and CleanupActivity's cloud sub-section (#93) --
     * one underlying [ApiKeyStore] value, shown contextually wherever it's actually relevant,
     * instead of living in one physical place a Cloud-Cleanup user had to go hunting for.
     */
    protected fun promptApiKey(onSaved: () -> Unit) {
        val existingKey = ApiKeyStore.getApiKey(this)
        val input = android.widget.EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            hint = if (existingKey.isBlank()) "sk-..." else ApiKeyStore.maskForDisplay(existingKey)
        }
        android.app.AlertDialog.Builder(this)
            .setTitle("OpenAI API Key")
            .setView(input.apply { setPadding(dp(24), dp(8), dp(24), dp(8)) })
            .setPositiveButton("Save") { _, _ ->
                val entered = input.text.toString().trim()
                when {
                    entered.isNotBlank() -> {
                        ApiKeyStore.setApiKey(this, entered)
                        onSaved()
                    }
                    // Saving blank over a stored key is the only way to remove it -- once
                    // stored, a secret used to stay in encrypted prefs forever (#89).
                    existingKey.isNotBlank() -> confirmRemoveSecret("OpenAI API key") {
                        ApiKeyStore.setApiKey(this, "")
                        onSaved()
                    }
                    else -> onSaved()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    companion object {
        const val LP_MATCH = LinearLayout.LayoutParams.MATCH_PARENT
        const val LP_WRAP = LinearLayout.LayoutParams.WRAP_CONTENT
        // Consistent nesting indent step (#49) -- see nestedGroup()/subsectionHeader()/settingsRow's indent param.
        const val INDENT_STEP_DP = 16
    }
}
