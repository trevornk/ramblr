package com.trevornk.ramblr

import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.LayerDrawable
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat

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

    override fun onCreate(savedInstanceState: Bundle?) {
        // targetSdk 36 lays every window out behind the system bars with no opt-out. Declaring it
        // explicitly means the behaviour is identical on API 30-34, so there is one layout to
        // reason about and to test on old devices, rather than a path that only appears on 35+.
        WindowCompat.setDecorFitsSystemWindows(window, false)
        super.onCreate(savedInstanceState)
    }

    /**
     * Applies edge-to-edge insets to whatever every subclass passes in, so none of the eleven
     * Settings screens has to remember to do it and a twelfth added later gets it for free. Every
     * one of them calls this with a root ScrollView.
     */
    override fun setContentView(view: View) {
        super.setContentView(view)
        applyContentInsets(view)
        // Rows appear, disappear and move between sections at runtime (refresh() toggles
        // visibility), so "is this the last row of its list" is re-evaluated on every layout.
        view.viewTreeObserver.addOnGlobalLayoutListener { updateRowDividers(view) }
    }

    /** Shows a row's divider only when the next visible sibling is another row: the last row of a
     *  list (followed by a section header, an explainer, or nothing) gets no trailing line. */
    private fun updateRowDividers(view: View) {
        if (view !is ViewGroup) return
        var nextIsRow = false
        for (i in view.childCount - 1 downTo 0) {
            val child = view.getChildAt(i)
            if (child.visibility == View.GONE) continue
            val divider = child.getTag(R.id.settings_row_divider) as? android.graphics.drawable.Drawable
            if (divider != null) {
                divider.alpha = if (nextIsRow) 255 else 0
                nextIsRow = true
            } else {
                updateRowDividers(child)
                nextIsRow = false
            }
        }
    }

    private fun applyContentInsets(content: View) {
        // Padding, not margin: the bars should show the scrolling content behind them (that is the
        // point of edge-to-edge), while the content's own resting position stays clear of them.
        // clipToPadding=false keeps that true for a ScrollView, whose default would otherwise clip
        // the scrolled content at the padding box and undo the effect.
        (content as? ViewGroup)?.clipToPadding = false

        ViewCompat.setOnApplyWindowInsetsListener(content) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val cutout = insets.getInsets(WindowInsetsCompat.Type.displayCutout())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())

            val p = WindowInsetsMath.resolve(
                barsLeft = bars.left, barsTop = bars.top, barsRight = bars.right, barsBottom = bars.bottom,
                cutoutLeft = cutout.left, cutoutRight = cutout.right,
                imeBottom = ime.bottom,
            )
            // Extra room under the last item so it never sits flush against the navigation bar.
            v.setPadding(p.left, p.top, p.right, p.bottom + dp(BOTTOM_SPACE_DP))

            // Consume nothing: return the insets unchanged so any child that wants to react to the
            // keyboard still can. Returning CONSUMED here would silently break those.
            insets
        }
        // The listener above only fires on the next inset dispatch. A view added after the window
        // already has its insets (which is every one of these, since setContentView runs well
        // after attach on a warm activity) would otherwise sit unpadded until something moved.
        ViewCompat.requestApplyInsets(content)
    }

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

        // Medium-weight title over a regular subtitle, so a long list reads as "name, then what
        // it does" at a glance instead of two lines of equal weight.
        textContainer.addView(TextView(this).apply {
            tag = "title"
            text = title
            textSize = 17f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(attrColor(android.R.attr.textColorPrimary))
        })

        textContainer.addView(TextView(this).apply {
            tag = "subtitle"
            text = subtitle
            textSize = 14f
            setLineSpacing(dp(2).toFloat(), 1f)
            setTextColor(attrColor(android.R.attr.textColorSecondary))
            setPadding(0, dp(3), 0, 0)
        })

        row.addView(textContainer)
        if (widget != null) {
            // Keep a gap so wrapped text never runs into the switch.
            (textContainer.layoutParams as LinearLayout.LayoutParams).marginEnd = dp(16)
            row.addView(widget)
        }

        // Hairline under every row so long lists don't read as one undivided block. Drawn as the
        // foreground so it never replaces the ripple background above; inset on both sides to
        // line up with the row's text and right padding, so it never runs to the screen edges.
        // One physical pixel at a stronger alpha reads as a crisp line instead of a grey band.
        // updateRowDividers() hides it on the last row of each list.
        val divider = LayerDrawable(arrayOf(ColorDrawable(withAlpha(attrColor(android.R.attr.textColorPrimary), 0x40)))).apply {
            setLayerGravity(0, Gravity.BOTTOM or Gravity.FILL_HORIZONTAL)
            setLayerHeight(0, 1)
            setLayerInsetStart(0, dp(24) + dp(INDENT_STEP_DP) * indent)
            setLayerInsetEnd(0, dp(24))
        }
        row.foreground = divider
        row.setTag(R.id.settings_row_divider, divider)

        return row
    }

    protected fun sectionHeader(title: String) = TextView(this).apply {
        text = title
        textSize = 14f
        letterSpacing = 0.02f
        setTypeface(typeface, Typeface.BOLD)
        setTextColor(attrColor(com.google.android.material.R.attr.colorPrimary)) // Neutral Android-like blue
        setPadding(dp(24), dp(28), dp(24), dp(8))
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

    protected fun prefs() = getSharedPreferences("ramblr", MODE_PRIVATE)

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

    companion object {
        const val LP_MATCH = LinearLayout.LayoutParams.MATCH_PARENT
        const val LP_WRAP = LinearLayout.LayoutParams.WRAP_CONTENT
        // Consistent nesting indent step (#49) -- see nestedGroup()/subsectionHeader()/settingsRow's indent param.
        const val INDENT_STEP_DP = 16
        // Breathing room below the last item of every screen, on top of the navigation bar inset.
        const val BOTTOM_SPACE_DP = 32
    }
}
