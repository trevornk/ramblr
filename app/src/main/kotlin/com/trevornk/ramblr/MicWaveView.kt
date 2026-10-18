package com.trevornk.ramblr

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.os.SystemClock
import android.view.View
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/**
 * Sound waves drawn inside the keyboard's mic button while recording, in place of the mic glyph.
 * [push] takes the runtime's 0..1 loudness about 20 times a second.
 *
 * [Style.SINE] (the default): a few overlapping sine waves whose height follows the voice.
 * [Style.BARS]: thin rounded bars; the newest level sits in the middle and older ones flow
 * outwards, so speech reads as ripples spreading from the centre. In silence both settle flat: the
 * sine into a line, the bars into a row of dots ([push] gets 0 for room noise, see
 * [AudioLevelMeter]).
 */
internal class MicWaveView(context: Context) : View(context) {
    enum class Style { BARS, SINE }

    /** A wave rather than bars, so the panel doesn't read as another vendor's voice keyboard. */
    var style: Style = Style.SINE
        set(value) { field = value; invalidate() }

    /** Wave colour; the button behind it supplies the contrast. */
    var color: Int = Color.WHITE
        set(value) { field = value; invalidate() }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private val clip = Path()
    /** Recent levels, newest first. */
    private val history = FloatArray(HISTORY)
    /** What each bar currently shows, eased towards [history] so bars glide instead of jumping
     *  at the runtime's ~20 Hz level rate. */
    private val shownBars = FloatArray(HISTORY)
    private var active = false
    private var target = 0f
    private var shown = 0f
    private var lastFrameMs = 0L

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        isClickable = false
    }

    /** Shows the waves while recording; turning them off forgets the history. */
    fun setActive(on: Boolean) {
        if (active == on) return
        active = on
        history.fill(0f)
        shownBars.fill(0f)
        target = 0f
        shown = 0f
        lastFrameMs = 0L
        if (on) postInvalidateOnAnimation() else invalidate()
    }

    fun push(level: Float) {
        val value = level.coerceIn(0f, 1f)
        System.arraycopy(history, 0, history, 1, HISTORY - 1)
        history[0] = value
        target = value
        postInvalidateOnAnimation()
    }

    internal fun historyForTest(): FloatArray = history.copyOf()

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (!active) return
        when (style) {
            Style.BARS -> drawBars(canvas)
            Style.SINE -> drawSine(canvas)
        }
    }

    /** Milliseconds since the previous frame, so easing runs at the same speed at any frame rate. */
    private fun frameDelta(now: Long): Float {
        val dt = if (lastFrameMs == 0L) 16L else (now - lastFrameMs).coerceIn(0L, 100L)
        lastFrameMs = now
        return dt.toFloat()
    }

    private fun ease(from: Float, to: Float, dt: Float): Float =
        from + (to - from) * (1f - exp(-dt / (if (to > from) RISE_MS else FALL_MS)))

    private fun drawBars(canvas: Canvas) {
        val dt = frameDelta(SystemClock.uptimeMillis())
        for (i in 0 until HISTORY) shownBars[i] = ease(shownBars[i], history[i], dt)
        val density = resources.displayMetrics.density
        val barWidth = BAR_WIDTH_DP * density
        val gap = BAR_GAP_DP * density
        val maxHeight = min(width, height) * BAR_MAX_HEIGHT
        val total = BARS * barWidth + (BARS - 1) * gap
        val cy = height / 2f
        paint.color = color
        paint.style = Paint.Style.FILL
        for (i in 0 until BARS) {
            val distance = kotlin.math.abs(i - BARS / 2)
            val bar = barWidth + (maxHeight - barWidth) * lift(shownBars[distance]) * ENVELOPE[distance]
            val left = (width - total) / 2f + i * (barWidth + gap)
            canvas.drawRoundRect(left, cy - bar / 2f, left + barWidth, cy + bar / 2f, barWidth / 2f, barWidth / 2f, paint)
        }
        postInvalidateOnAnimation()
    }

    private fun drawSine(canvas: Canvas) {
        val now = SystemClock.uptimeMillis()
        shown = ease(shown, target, frameDelta(now))

        val size = min(width, height).toFloat()
        val cx = width / 2f
        val cy = height / 2f
        val half = size * 0.3f
        clip.reset()
        clip.addCircle(cx, cy, size / 2f, Path.Direction.CW)
        canvas.save()
        canvas.clipPath(clip)
        paint.style = Paint.Style.STROKE
        paint.strokeCap = Paint.Cap.ROUND
        val amplitude = size * SINE_MAX_AMPLITUDE * lift(shown)
        val seconds = now / 1000f
        for ((index, wave) in WAVES.withIndex()) {
            path.reset()
            val steps = 48
            for (s in 0..steps) {
                val t = s / steps.toFloat()
                // Pinned to the centre line at both ends so the waves read as one bundle.
                val taper = sin(PI * t).toFloat()
                val y = cy + amplitude * wave.scale * taper *
                    sin(2 * PI * (wave.cycles * t + wave.speed * seconds)).toFloat()
                val x = cx - half + 2 * half * t
                if (s == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            paint.color = color
            paint.alpha = wave.alpha
            paint.strokeWidth = (if (index == 0) 2.25f else 1.5f) * resources.displayMetrics.density
            canvas.drawPath(path, paint)
        }
        canvas.restore()
        postInvalidateOnAnimation()
    }

    /** Lifts quiet-to-normal speech so the waves use most of their height without clipping
     *  loud speech. */
    private fun lift(level: Float): Float = level.coerceIn(0f, 1f).pow(LIFT)

    private class Wave(val cycles: Float, val speed: Float, val scale: Float, val alpha: Int)

    private companion object {
        const val BARS = 9
        const val HISTORY = BARS / 2 + 1
        const val BAR_WIDTH_DP = 2f
        const val BAR_GAP_DP = 2.5f
        /** Tallest bar as a share of the button, leaving a margin inside the circle. */
        const val BAR_MAX_HEIGHT = 0.62f
        /** Outer bars are shorter so the group stays inside the round button. */
        val ENVELOPE = floatArrayOf(1f, 0.86f, 0.7f, 0.54f, 0.38f)
        /** Peak sine height either side of the centre line, as a share of the button. */
        const val SINE_MAX_AMPLITUDE = 0.24f
        const val LIFT = 0.7f
        const val RISE_MS = 110f
        const val FALL_MS = 280f
        val WAVES = listOf(
            Wave(cycles = 1.5f, speed = 0.6f, scale = 1f, alpha = 0xFF),
            Wave(cycles = 2.2f, speed = -0.85f, scale = 0.7f, alpha = 0x99),
            Wave(cycles = 1.1f, speed = 0.4f, scale = 0.5f, alpha = 0x66),
        )
    }
}
