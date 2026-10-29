package com.gozar

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import android.view.animation.DecelerateInterpolator

class UsageMeterView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private var target = 0f
    private var shown = 0f
    private var animator: ValueAnimator? = null

    private val density = resources.displayMetrics.density
    private val trackHeight = 10f * density
    private val knobRadius = 8f * density

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = 0x14FFFFFF
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val knobPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = 0xFFFF5A6E.toInt()
    }
    private val knobRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f * density
        color = 0x33000000
    }
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = 0x33FF2D45
    }
    private val segmentPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.5f * density
        color = 0x1F000000
    }
    private val rect = RectF()
    private var shader: Shader? = null

    init {
        val base = themeColor(com.google.android.material.R.attr.colorOnSurfaceVariant)
        trackPaint.color = (base and 0x00FFFFFF) or 0x1F000000
        segmentPaint.color = (base and 0x00FFFFFF) or 0x33000000
    }

    private fun themeColor(attr: Int): Int {
        val value = android.util.TypedValue()
        return if (context.theme.resolveAttribute(attr, value, true)) value.data else 0xFF808080.toInt()
    }

    fun setProgress(percent: Int, animate: Boolean = true) {
        target = (percent.coerceIn(0, 100)) / 100f
        animator?.cancel()
        if (!animate || !isAttachedToWindow) {
            shown = target
            invalidate()
            return
        }
        animator = ValueAnimator.ofFloat(shown, target).apply {
            duration = 620
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                shown = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    override fun onDetachedFromWindow() {
        animator?.cancel()
        animator = null
        super.onDetachedFromWindow()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        shader = LinearGradient(
            0f, 0f, w.toFloat(), 0f,
            intArrayOf(0xFF8A0018.toInt(), 0xFFE4002B.toInt(), 0xFFFF5A6E.toInt()),
            floatArrayOf(0f, 0.62f, 1f),
            Shader.TileMode.CLAMP
        )
        fillPaint.shader = shader
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val wanted = (knobRadius * 2f + 4f * density).toInt()
        val height = resolveSize(wanted, heightMeasureSpec)
        setMeasuredDimension(measureWidth(widthMeasureSpec), height)
    }

    private fun measureWidth(spec: Int): Int {
        val size = MeasureSpec.getSize(spec)
        return if (size > 0) size else (200f * density).toInt()
    }

    override fun onDraw(canvas: Canvas) {
        val cy = height / 2f
        val inset = knobRadius
        val left = inset
        val right = width - inset
        val span = (right - left).coerceAtLeast(1f)
        val radius = trackHeight / 2f

        rect.set(left, cy - radius, right, cy + radius)
        canvas.drawRoundRect(rect, radius, radius, trackPaint)

        val progress = shown.coerceIn(0f, 1f)
        val edge = left + span * progress

        for (i in 1..3) {
            val x = left + span * (i / 4f)
            canvas.drawLine(x, cy - radius - 1.5f * density, x, cy + radius + 1.5f * density, segmentPaint)
        }

        if (progress > 0f) {
            val fillLeft = if (edge - radius < left) left else edge - radius
            rect.set(fillLeft, cy - radius, edge, cy + radius)
            canvas.drawRoundRect(rect, radius, radius, fillPaint)

            glowPaint.color = 0x33FF2D45
            canvas.drawCircle(edge, cy, knobRadius * 2.1f, glowPaint)
            canvas.drawCircle(edge, cy, knobRadius, knobRingPaint)
            canvas.drawCircle(edge, cy, knobRadius - 1.2f * density, knobPaint)
        }
    }
}
