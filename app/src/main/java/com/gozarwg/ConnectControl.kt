package com.gozarwg

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.os.Build
import android.util.AttributeSet
import android.view.View
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

class ConnectControl @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : View(context, attrs, defStyle) {

    companion object {
        const val OFF = 0
        const val BUSY = 1
        const val ON = 2
        const val ERROR = 3
        private const val TAU = 2f * PI.toFloat()
    }

    var state = OFF
        set(value) {
            if (field == value) return
            field = value
            updateColors()
            sync()
            invalidate()
        }

    private val density = resources.displayMetrics.density
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bounds = RectF()
    private val wave = Path()
    private val power: Drawable? = AppCompatResources.getDrawable(context, R.drawable.ic_power)
    private var stroke = 0
    private var onStroke = 0
    private var disc = 0
    private var phase = 0f
    private var stamp = 0L
    private var running = false

    private val ticker = object : Runnable {
        override fun run() {
            if (!isAttachedToWindow || !running) return
            val now = System.nanoTime()
            val elapsed = if (stamp == 0L) 16f else ((now - stamp) / 1_000_000f).coerceIn(1f, 40f)
            stamp = now
            phase = (phase + elapsed / 1120f) % 1f
            invalidate()
            postOnAnimation(this)
        }
    }

    init {
        updateColors()
        isClickable = true
        isFocusable = true
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val wanted = (192f * density).toInt()
        val side = min(resolveSize(wanted, widthMeasureSpec), resolveSize(wanted, heightMeasureSpec))
        setMeasuredDimension(side, side)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        sync()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        running = false
        removeCallbacks(ticker)
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    private fun updateColors() {
        when (state) {
            ON -> {
                stroke = color(R.color.m3_ok)
                onStroke = color(R.color.m3_on_ok)
                disc = color(R.color.m3_ok_container)
            }
            ERROR -> {
                stroke = color(R.color.m3_error)
                onStroke = color(R.color.m3_on_error)
                disc = color(R.color.m3_error_container)
            }
            else -> {
                stroke = color(R.color.m3_primary)
                onStroke = color(R.color.m3_on_primary)
                disc = color(R.color.m3_primary_container)
            }
        }
    }

    private fun sync() {
        val animate = state == BUSY && (Build.VERSION.SDK_INT < 26 || ValueAnimator.areAnimatorsEnabled())
        if (animate && !running) {
            running = true
            stamp = 0L
            postOnAnimation(ticker)
        } else if (!animate && running) {
            running = false
            removeCallbacks(ticker)
            phase = 0f
        }
    }

    private fun color(id: Int): Int = ContextCompat.getColor(context, id)

    override fun onDraw(canvas: Canvas) {
        val side = min(width, height).toFloat()
        if (side <= 0f) return
        val cx = width / 2f
        val cy = height / 2f
        val radius = side * 0.39f
        val buttonRadius = side * 0.235f
        val width = 3.5f * density
        bounds.set(cx - radius, cy - radius, cx + radius, cy + radius)

        ring.strokeWidth = width
        ring.color = ColorUtils.setAlphaComponent(stroke, if (state == ON) 255 else 68)
        canvas.drawArc(bounds, -150f, 300f, false, ring)

        when (state) {
            BUSY -> drawWave(canvas, cx, cy, radius)
            ON -> {
                ring.color = stroke
                canvas.drawArc(bounds, -90f, 360f, false, ring)
            }
            ERROR -> {
                ring.color = stroke
                canvas.drawArc(bounds, -90f, 360f, false, ring)
            }
        }

        fill.color = disc
        canvas.drawCircle(cx, cy, buttonRadius, fill)
        if (isPressed) {
            fill.color = ColorUtils.setAlphaComponent(onStroke, 40)
            canvas.drawCircle(cx, cy, buttonRadius, fill)
        }
        val icon = power
        if (icon != null) {
            val iconSize = (buttonRadius * 1.02f).toInt()
            icon.setTint(onStroke)
            icon.setBounds(
                (cx - iconSize / 2f).toInt(),
                (cy - iconSize / 2f).toInt(),
                (cx + iconSize / 2f).toInt(),
                (cy + iconSize / 2f).toInt()
            )
            icon.draw(canvas)
        }
    }

    private fun drawWave(canvas: Canvas, cx: Float, cy: Float, radius: Float) {
        val start = -150f + phase * 360f
        val span = 208f
        val steps = 92
        wave.reset()
        for (i in 0..steps) {
            val t = i / steps.toFloat()
            val angle = Math.toRadians((start + span * t).toDouble()).toFloat()
            val pulse = sin((t * 4f * TAU) + phase * TAU) * (2.4f * density)
            val r = radius + pulse
            val x = cx + cos(angle) * r
            val y = cy + sin(angle) * r
            if (i == 0) wave.moveTo(x, y) else wave.lineTo(x, y)
        }
        ring.strokeWidth = 5f * density
        ring.color = stroke
        canvas.drawPath(wave, ring)
    }
}
