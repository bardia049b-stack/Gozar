package com.gozarwg

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.os.Build
import android.util.AttributeSet
import android.view.Gravity
import androidx.appcompat.widget.AppCompatTextView

class RollingValueView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : AppCompatTextView(context, attrs, defStyle) {

    private var outgoing = ""
    private var roll = 1f
    private var animator: ValueAnimator? = null

    fun setValue(value: String) {
        val next = value.trim()
        val current = text?.toString().orEmpty()
        if (next == current) return
        if (!isLaidOut || !isAttachedToWindow || (Build.VERSION.SDK_INT >= 26 && !ValueAnimator.areAnimatorsEnabled())) {
            outgoing = ""
            roll = 1f
            text = next
            return
        }
        outgoing = current
        text = next
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
        if (roll >= 1f || outgoing.isEmpty()) {
            super.onDraw(canvas)
            return
        }
        val incoming = text?.toString().orEmpty()
        val baseline = verticalBaseline()
        val shift = height.toFloat() * 0.72f
        val previousAlpha = paint.alpha
        canvas.save()
        canvas.clipRect(paddingLeft, paddingTop, width - paddingRight, height - paddingBottom)
        paint.alpha = (previousAlpha * (1f - roll)).toInt()
        canvas.drawText(outgoing, horizontalOffset(outgoing), baseline - roll * shift, paint)
        paint.alpha = (previousAlpha * roll).toInt()
        canvas.drawText(incoming, horizontalOffset(incoming), baseline + (1f - roll) * shift, paint)
        paint.alpha = previousAlpha
        canvas.restore()
    }

    private fun verticalBaseline(): Float {
        val availableTop = paddingTop.toFloat()
        val availableBottom = (height - paddingBottom).toFloat()
        val center = (availableTop + availableBottom) / 2f
        val metrics = paint.fontMetrics
        return center - (metrics.ascent + metrics.descent) / 2f
    }

    private fun horizontalOffset(value: String): Float = when (gravity and Gravity.HORIZONTAL_GRAVITY_MASK) {
        Gravity.RIGHT -> width - paddingRight - paint.measureText(value)
        Gravity.CENTER_HORIZONTAL -> (width - paddingLeft - paddingRight - paint.measureText(value)) / 2f + paddingLeft
        else -> paddingLeft.toFloat()
    }
}
