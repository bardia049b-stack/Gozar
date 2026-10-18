package com.gozarwg

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.os.Build
import android.util.AttributeSet
import android.view.Gravity
import androidx.appcompat.widget.AppCompatTextView

class RollingValueView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : AppCompatTextView(context, attrs, defStyle) {

    private data class Parts(val number: String, val unit: String)

    private var previousNumber = ""
    private var roll = 1f
    private var animator: ValueAnimator? = null

    fun setValue(value: String) {
        val next = value.trim()
        val current = text?.toString().orEmpty()
        if (next == current) return
        val from = split(current)
        val to = split(next)
        val animate = from.number.isNotEmpty() && to.number.isNotEmpty() && from.number != to.number &&
            isLaidOut && isAttachedToWindow && (Build.VERSION.SDK_INT < 26 || ValueAnimator.areAnimatorsEnabled())
        text = next
        if (!animate) {
            previousNumber = ""
            roll = 1f
            invalidate()
            return
        }
        previousNumber = from.number
        animator?.cancel()
        roll = 0f
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 190L
            interpolator = android.view.animation.DecelerateInterpolator()
            addUpdateListener {
                roll = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        animator?.cancel()
    }

    override fun onDraw(canvas: Canvas) {
        val current = split(text?.toString().orEmpty())
        if (current.number.isEmpty()) {
            super.onDraw(canvas)
            return
        }
        val baseline = verticalBaseline()
        val x = horizontalOffset(current)
        val shift = height.toFloat() * 0.72f
        // AppCompat can leave TextView's draw paint with its earlier palette after a night-mode
        // recreation. Reapply the current text colour before every custom canvas render.
        val savedColor = paint.color
        paint.color = currentTextColor
        val alpha = Color.alpha(currentTextColor)
        canvas.save()
        canvas.clipRect(paddingLeft, paddingTop, width - paddingRight, height - paddingBottom)
        if (roll < 1f && previousNumber.isNotEmpty()) {
            paint.alpha = (alpha * (1f - roll)).toInt()
            canvas.drawText(previousNumber, x, baseline - roll * shift, paint)
            paint.alpha = (alpha * roll).toInt()
            canvas.drawText(current.number, x, baseline + (1f - roll) * shift, paint)
        } else {
            canvas.drawText(current.number, x, baseline, paint)
        }
        paint.alpha = alpha
        canvas.drawText(current.unit, x + paint.measureText(current.number), baseline, paint)
        paint.color = savedColor
        canvas.restore()
    }

    private fun split(value: String): Parts {
        var end = 0
        while (end < value.length && (value[end].isDigit() || value[end] == '.' || value[end] == ',')) end++
        return Parts(value.substring(0, end), value.substring(end))
    }

    private fun verticalBaseline(): Float {
        val center = (paddingTop + height - paddingBottom) / 2f
        val metrics = paint.fontMetrics
        return center - (metrics.ascent + metrics.descent) / 2f
    }

    private fun horizontalOffset(parts: Parts): Float {
        val width = paint.measureText(parts.number + parts.unit)
        return when (gravity and Gravity.HORIZONTAL_GRAVITY_MASK) {
            Gravity.RIGHT -> this.width - paddingRight - width
            Gravity.CENTER_HORIZONTAL -> (this.width - paddingLeft - paddingRight - width) / 2f + paddingLeft
            else -> paddingLeft.toFloat()
        }
    }
}
