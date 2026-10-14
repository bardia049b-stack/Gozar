package com.beofoq.wg

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import kotlin.math.min
import kotlin.math.sin

class GlassSwitch @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
    var checked = false
        private set
    var onChange: ((Boolean) -> Unit)? = null
    private var pos = 0f
    private val track = Paint(Paint.ANTI_ALIAS_FLAG)
    private val thumb = Paint(Paint.ANTI_ALIAS_FLAG)

    init {
        setOnClickListener { setChecked(!checked, true) }
    }

    fun setChecked(v: Boolean, notify: Boolean) {
        if (checked == v && !notify) {
            pos = if (v) 1f else 0f
            invalidate()
            return
        }
        checked = v
        val anim = ValueAnimator.ofFloat(pos, if (v) 1f else 0f)
        anim.duration = 180
        anim.addUpdateListener {
            pos = it.animatedValue as Float
            invalidate()
        }
        anim.start()
        if (notify) onChange?.invoke(v)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = resolveSize((52 * resources.displayMetrics.density).toInt(), widthMeasureSpec)
        val h = resolveSize((32 * resources.displayMetrics.density).toInt(), heightMeasureSpec)
        setMeasuredDimension(w, h)
    }

    override fun onDraw(canvas: Canvas) {
        val d = resources.displayMetrics.density
        val w = width.toFloat()
        val h = height.toFloat()
        val r = h / 2
        track.color = if (checked) resources.getColor(R.color.accent, context.theme) else resources.getColor(R.color.glass_inner, context.theme)
        canvas.drawRoundRect(0f, 0f, w, h, r, r, track)
        val thumbR = if (checked) h / 2 - 2 * d else h / 2 - 6 * d
        val cx = r + pos * (w - 2 * r)
        thumb.color = if (checked) Color.WHITE else resources.getColor(R.color.text2, context.theme)
        thumb.setShadowLayer(4 * d, 0f, 1 * d, Color.parseColor("#40000000"))
        canvas.drawCircle(cx, h / 2, thumbR, thumb)
    }
}

class RingView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
    var state = 0
        private set
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    fun setState(v: Int) {
        if (state == v) return
        state = v
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val d = resources.displayMetrics.density
        val cx = width / 2f
        val cy = height / 2f
        val r = min(width, height) / 2f - 8 * d
        val accent = resources.getColor(R.color.accent, context.theme)
        val green = resources.getColor(R.color.green, context.theme)
        val red = resources.getColor(R.color.red, context.theme)
        val track = resources.getColor(R.color.separator, context.theme)
        val t = android.os.SystemClock.elapsedRealtime()

        paint.style = Paint.Style.STROKE
        paint.strokeCap = Paint.Cap.ROUND
        paint.strokeWidth = 4 * d

        if (state == 2) {
            val glow = 0.05f + 0.03f * sin(t / 500.0)
            paint.style = Paint.Style.FILL
            paint.color = green
            paint.alpha = (glow * 255).toInt().coerceIn(0, 40)
            canvas.drawCircle(cx, cy, r - 6 * d, paint)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 4 * d
        }

        paint.color = track
        paint.alpha = 255
        canvas.drawCircle(cx, cy, r, paint)

        val rect = android.graphics.RectF(cx - r, cy - r, cx + r, cy + r)
        when (state) {
            1 -> {
                val angle = (t % 1100L) / 1100f * 360f
                paint.color = accent
                canvas.drawArc(rect, angle, 270f, false, paint)
            }
            2 -> {
                paint.color = green
                canvas.drawArc(rect, 0f, 360f, false, paint)
            }
            3 -> {
                paint.color = red
                canvas.drawArc(rect, 0f, 360f, false, paint)
            }
        }
        paint.alpha = 255
        if (state == 1 || state == 2) postInvalidateOnAnimation()
    }
}
