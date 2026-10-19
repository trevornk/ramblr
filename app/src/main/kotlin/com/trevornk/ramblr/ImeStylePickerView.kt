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
 * Rows are the [CleanupStyleMenu.Model] the floating ring's menu also renders: cleanup on/off,
 * then the user's quick-menu styles with the current one marked.
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
    private val list = LinearLayout(context).apply { orientation = VERTICAL }
    private val scroll = ScrollView(context).apply {
        isFillViewport = false
        addView(list, ViewGroup.LayoutParams(-1, -2))
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
        list.removeAllViews()
        list.addView(row(
            title = context.getString(if (model.cleanupEnabled) R.string.ime_style_cleanup_on else R.string.ime_style_cleanup_off),
            subtitle = context.getString(if (model.cleanupEnabled) R.string.ime_style_cleanup_on_hint else R.string.ime_style_cleanup_off_hint),
            selected = false,
            description = null,
        ) { onToggleCleanup() })
        list.addView(View(context).apply {
            setBackgroundColor((palette.onSurfaceVariant and 0x00FFFFFF) or 0x33000000)
        }, LayoutParams(-1, dp(1)).apply { topMargin = dp(4); bottomMargin = dp(4) })
        for (persona in model.personas) {
            val selected = persona == model.current
            list.addView(row(
                title = persona.title,
                subtitle = persona.subtitle,
                selected = selected,
                description = if (selected) context.getString(R.string.ime_style_selected_desc, persona.title) else persona.title,
            ) { onPick(persona) })
        }
    }

    private fun row(title: String, subtitle: String, selected: Boolean, description: String?, onClick: () -> Unit): View {
        val row = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(48)
            isClickable = true
            isFocusable = true
            setPadding(dp(12), dp(6), dp(12), dp(6))
            background = RippleDrawable(
                ColorStateList.valueOf((palette.onSurface and 0x00FFFFFF) or 0x33000000),
                if (selected) GradientDrawable().apply {
                    cornerRadius = dp(12).toFloat()
                    setColor((palette.accent and 0x00FFFFFF) or 0x26000000)
                } else null,
                GradientDrawable().apply { cornerRadius = dp(12).toFloat(); setColor(-1) },
            )
            if (description != null) contentDescription = description
            setOnClickListener { onClick() }
        }
        val texts = LinearLayout(context).apply {
            orientation = VERTICAL
            addView(TextView(context).apply {
                text = title
                textSize = 15f
                setTextColor(palette.onSurface)
                if (selected) setTypeface(typeface, Typeface.BOLD)
            })
            addView(TextView(context).apply {
                text = subtitle
                textSize = 12f
                maxLines = 2
                ellipsize = android.text.TextUtils.TruncateAt.END
                setTextColor(palette.onSurfaceVariant)
                // The row's own contentDescription already carries the title; don't double-read.
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            })
        }
        row.addView(texts, LayoutParams(0, -2, 1f))
        if (selected) {
            row.addView(ImageView(context).apply {
                setImageResource(R.drawable.ic_check)
                setColorFilter(palette.accent)
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, LayoutParams(dp(24), dp(24)).apply { marginStart = dp(8) })
        }
        return row
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
