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
    private val barHeight = 10f * density
    private val pad = 6f * density

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val capPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val sheenPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = 0x26FFFFFF
    }
    private val rect = RectF()

    init {
        val base = themeColor(android.R.attr.colorBackground)
        trackPaint.color = blend(base, 0x33FFFFFF)
    }

    private fun themeColor(attr: Int): Int {
        val value = android.util.TypedValue()
        return if (context.theme.resolveAttribute(attr, value, true)) value.data else 0xFF121212.toInt()
    }

    private fun blend(background: Int, overlay: Int): Int {
        val a = (overlay ushr 24) / 255f
        val r = ((overlay shr 16 and 0xFF) * a + (background shr 16 and 0xFF) * (1 - a)).toInt()
        val g = ((overlay shr 8 and 0xFF) * a + (background shr 8 and 0xFF) * (1 - a)).toInt()
        val b = ((overlay and 0xFF) * a + (background and 0xFF) * (1 - a)).toInt()
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }

    fun setProgress(percent: Int, animate: Boolean = true) {
        target = percent.coerceIn(0, 100) / 100f
        animator?.cancel()
        if (!animate || !isAttachedToWindow) {
            shown = target
            invalidate()
            return
        }
        animator = ValueAnimator.ofFloat(shown, target).apply {
            duration = 640
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
        fillPaint.shader = LinearGradient(
            pad, 0f, (w - pad).toFloat(), 0f,
            intArrayOf(0xFF9E0018.toInt(), 0xFFE4002B.toInt(), 0xFFFF5A6E.toInt()),
            floatArrayOf(0f, 0.55f, 1f),
            Shader.TileMode.CLAMP
        )
        capPaint.shader = fillPaint.shader
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val wanted = (barHeight + pad * 2).toInt()
        val height = if (MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.EXACTLY) {
            MeasureSpec.getSize(heightMeasureSpec)
        } else {
            wanted
        }
        val width = MeasureSpec.getSize(widthMeasureSpec)
        setMeasuredDimension(if (width > 0) width else (240f * density).toInt(), height)
    }

    override fun onDraw(canvas: Canvas) {
        val top = (height - barHeight) / 2f
        val bottom = top + barHeight
        val left = pad
        val right = width - pad
        if (right <= left) return
        val radius = barHeight / 2f

        rect.set(left, top, right, bottom)
        canvas.drawRoundRect(rect, radius, radius, trackPaint)

        val progress = shown.coerceIn(0f, 1f)
        if (progress <= 0.001f) return

        val span = (right - left) * progress
        val edge = left + span
        val fillLeft = if (span < barHeight) edge - barHeight else left
        rect.set(fillLeft.coerceAtLeast(left), top, edge, bottom)
        canvas.drawRoundRect(rect, radius, radius, fillPaint)

        if (span > barHeight * 2f) {
            rect.set(left + radius, top + barHeight * 0.18f, edge - radius, top + barHeight * 0.42f)
            canvas.drawRoundRect(rect, barHeight * 0.12f, barHeight * 0.12f, sheenPaint)
        }

        canvas.drawCircle(edge - radius, (top + bottom) / 2f, radius, capPaint)
    }
}
