package com.trevornk.ramblr

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.graphics.drawable.RippleDrawable
import android.content.res.ColorStateList
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** Colours the keyboard's own widgets paint with, resolved once from the panel's DayNight theme. */
internal data class ImePanelColors(
    val onSurface: Int,
    val onSurfaceVariant: Int,
    val keyFill: Int,
    val accent: Int,
)

/** A LinearLayout that never grows past [maxWidthPx]: keeps the key rows a sensible width on the
 *  Fold's wide inner display instead of stretching them edge to edge. */
internal class MaxWidthLinearLayout(context: Context, private val maxWidthPx: Int) : LinearLayout(context) {
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val mode = MeasureSpec.getMode(widthMeasureSpec)
        val size = MeasureSpec.getSize(widthMeasureSpec)
        val clamped = if (mode != MeasureSpec.UNSPECIFIED && size > maxWidthPx) {
            MeasureSpec.makeMeasureSpec(maxWidthPx, MeasureSpec.EXACTLY)
        } else widthMeasureSpec
        super.onMeasure(clamped, heightMeasureSpec)
    }
}

/**
 * The keyboard's cleanup-style picker (#312). It is a panel *inside* the keyboard window that
 * temporarily replaces the main controls, not a PopupWindow: an InputMethodService has no Activity
 * window to anchor popups to, and a popup that has to extend above the IME frame is clipped or
 * mispositioned on some devices. Rendering in-window works everywhere and keeps the keyboard's
 * height stable (the host sizes its container to the main panel's height before showing it).
 *
 * Content is the [CleanupStyleMenu.Model] the floating ring's menu also renders: a cleanup on/off
 * chip in the header, then the user's quick-menu styles as a grid of title chips with the current
 * one marked. A grid (2 columns, 3 on wide screens such as the Fold's inner display) rather than a
 * list because the keyboard is short: two-line rows left room for barely two styles.
 */
internal class ImeStylePickerView(
    context: Context,
    private val palette: ImePanelColors,
    private val onClose: () -> Unit,
    private val onToggleCleanup: () -> Unit,
    private val onPick: (CleanupPersona) -> Unit,
) : LinearLayout(context) {
    private val density = context.resources.displayMetrics.density
    private fun dp(v: Int) = (v * density).toInt()
    private val toggleChip = TextView(context)
    private val grid = LinearLayout(context).apply { orientation = VERTICAL }
    private val scroll = ScrollView(context).apply {
        isFillViewport = false
        addView(grid, ViewGroup.LayoutParams(-1, -2))
    }

    init {
        orientation = VERTICAL
        val header = LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(TextView(context).apply {
                text = context.getString(R.string.ime_style_picker_title)
                textSize = 16f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(palette.onSurface)
                setPadding(dp(8), 0, 0, 0)
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
                isAccessibilityHeading = true
            }, LayoutParams(0, -2, 1f))
            toggleChip.apply {
                textSize = 14f
                gravity = Gravity.CENTER
                minHeight = dp(48)
                setPadding(dp(14), 0, dp(14), 0)
                setTextColor(palette.onSurface)
                isClickable = true
                isFocusable = true
                background = ImeKeyStyle.keyBackground(context, palette)
                setOnClickListener { onToggleCleanup() }
            }
            addView(toggleChip, LayoutParams(-2, dp(48)))
            addView(ImageButton(context).apply {
                setImageResource(R.drawable.ic_close)
                contentDescription = context.getString(R.string.ime_style_picker_close)
                setColorFilter(palette.onSurface)
                background = null
                setOnClickListener { onClose() }
            }, LayoutParams(dp(48), dp(48)))
        }
        addView(header, LayoutParams(-1, dp(48)))
        addView(scroll, LayoutParams(-1, 0, 1f))
    }

    fun bind(model: CleanupStyleMenu.Model) {
        toggleChip.text = context.getString(if (model.cleanupEnabled) R.string.ime_style_cleanup_on else R.string.ime_style_cleanup_off)
        toggleChip.contentDescription = toggleChip.text.toString() + ". " +
            context.getString(if (model.cleanupEnabled) R.string.ime_style_cleanup_on_hint else R.string.ime_style_cleanup_off_hint)
        grid.removeAllViews()
        val columns = if (context.resources.configuration.screenWidthDp >= 560) 3 else 2
        for (chunk in model.personas.chunked(columns)) {
            val row = LinearLayout(context).apply { orientation = HORIZONTAL }
            for (persona in chunk) row.addView(styleChip(persona, persona == model.current), LayoutParams(0, dp(48), 1f))
            // Keep the last row's chips the same width as the rest instead of stretching one wide.
            repeat(columns - chunk.size) { row.addView(View(context), LayoutParams(0, dp(48), 1f)) }
            grid.addView(row, LayoutParams(-1, -2))
        }
    }

    private fun styleChip(persona: CleanupPersona, selected: Boolean): View {
        val chip = LinearLayout(context).apply {
            gravity = Gravity.CENTER
            minimumHeight = dp(48)
            isClickable = true
            isFocusable = true
            setPadding(dp(8), 0, dp(8), 0)
            contentDescription = if (selected) context.getString(R.string.ime_style_selected_desc, persona.title) else persona.title
            isSelected = selected
            val face = GradientDrawable().apply {
                cornerRadius = dp(12).toFloat()
                if (selected) {
                    setColor((palette.accent and 0x00FFFFFF) or 0x33000000)
                    setStroke(dp(2), palette.accent)
                } else {
                    setColor(palette.keyFill)
                }
            }
            val mask = GradientDrawable().apply { cornerRadius = dp(12).toFloat(); setColor(-1) }
            background = InsetDrawable(
                RippleDrawable(ColorStateList.valueOf((palette.onSurface and 0x00FFFFFF) or 0x33000000), face, mask),
                dp(4), dp(4), dp(4), dp(4),
            )
            setOnClickListener { onPick(persona) }
        }
        chip.addView(TextView(context).apply {
            text = persona.title
            textSize = 15f
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            setTextColor(palette.onSurface)
            if (selected) setTypeface(typeface, Typeface.BOLD)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LayoutParams(-2, -2))
        if (selected) {
            chip.addView(ImageView(context).apply {
                setImageResource(R.drawable.ic_check)
                setColorFilter(palette.accent)
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, LayoutParams(dp(18), dp(18)).apply { marginStart = dp(4) })
        }
        return chip
    }
}

internal object ImeKeyStyle {
    /** Rounded key face: a 48dp tall touch target whose painted face is inset so rows read as keys. */
    fun keyBackground(context: Context, colors: ImePanelColors, verticalInsetDp: Int = 4): android.graphics.drawable.Drawable {
        val d = context.resources.displayMetrics.density
        val face = GradientDrawable().apply {
            cornerRadius = 12 * d
            setColor(colors.keyFill)
        }
        val mask = GradientDrawable().apply { cornerRadius = 12 * d; setColor(-1) }
        val ripple = RippleDrawable(ColorStateList.valueOf((colors.onSurface and 0x00FFFFFF) or 0x33000000), face, mask)
        val inset = (verticalInsetDp * d).toInt()
        return InsetDrawable(ripple, 0, inset, 0, inset)
    }

    /**
     * Press-and-hold key: acts on touch-down (so a tap is instant and a hold repeats via
     * [repeater]) and stops on up/cancel. Returns true from the touch listener, which means a
     * TalkBack double-tap still arrives through the click listener -- both paths act exactly once.
     */
    @SuppressLint("ClickableViewAccessibility")
    fun makeHoldable(view: View, repeater: KeyRepeater, onAccessibilityClick: () -> Unit) {
        view.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    if (!v.isEnabled) return@setOnTouchListener true
                    v.isPressed = true
                    repeater.press()
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.isPressed = false
                    repeater.release()
                }
            }
            true
        }
        view.setOnClickListener { onAccessibilityClick() }
    }
}
