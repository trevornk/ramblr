package com.trevornk.ramblr

import android.content.Context
import android.widget.ScrollView

/**
 * A [ScrollView] that never measures wider than [maxWidthPx] or taller than [maxHeightPx] (plain
 * Views have no maxWidth/maxHeight). Used for the floating icon's long-press menu so long rows
 * wrap and a long menu scrolls inside a bounded panel instead of growing past the screen.
 */
class BoundedScrollView(
    context: Context,
    private val maxWidthPx: Int,
    private val maxHeightPx: Int,
) : ScrollView(context) {
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(capped(widthMeasureSpec, maxWidthPx), capped(heightMeasureSpec, maxHeightPx))
    }

    private fun capped(spec: Int, maxPx: Int): Int {
        val mode = MeasureSpec.getMode(spec)
        val size = MeasureSpec.getSize(spec)
        return when (mode) {
            MeasureSpec.EXACTLY -> MeasureSpec.makeMeasureSpec(minOf(size, maxPx), MeasureSpec.EXACTLY)
            MeasureSpec.AT_MOST -> MeasureSpec.makeMeasureSpec(minOf(size, maxPx), MeasureSpec.AT_MOST)
            else -> MeasureSpec.makeMeasureSpec(maxPx, MeasureSpec.AT_MOST)
        }
    }
}
