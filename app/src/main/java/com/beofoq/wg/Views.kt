package com.beofoq.wg

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.View
import kotlin.math.cos
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
        track.color = if (checked) resources.getColor(R.color.green, context.theme) else Color.parseColor("#3D767E8C")
        canvas.drawRoundRect(0f, 0f, w, h, r, r, track)
        val thumbR = h / 2 - 2 * d
        val cx = r + pos * (w - 2 * r)
        thumb.color = Color.WHITE
        thumb.setShadowLayer(4 * d, 0f, 1 * d, Color.parseColor("#40000000"))
        canvas.drawCircle(cx, h / 2, thumbR, thumb)
    }
}

class RingView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
    var state = 0
        private set
    private var angle = 0f
    private var phase = 0f
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val handler = Handler(Looper.getMainLooper())
    private var running = false

    private val loop = object : Runnable {
        override fun run() {
            if (state == 1) angle = (angle + 8f) % 360f
            if (state == 2) phase += 0.05f
            invalidate()
            if (state == 1 || state == 2) handler.postDelayed(this, 40)
            else running = false
        }
    }

    fun setState(v: Int) {
        state = v
        invalidate()
        if ((v == 1 || v == 2) && !running) {
            running = true
            handler.post(loop)
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        handler.removeCallbacks(loop)
        running = false
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

        paint.style = Paint.Style.STROKE
        paint.strokeCap = Paint.Cap.ROUND

        if (state == 2) {
            paint.style = Paint.Style.FILL
            paint.color = green
            paint.alpha = (18 + 10 * sin(phase)).toInt().coerceIn(0, 40)
            canvas.drawCircle(cx, cy, r - 6 * d, paint)
            paint.style = Paint.Style.STROKE
        }

        paint.strokeWidth = 4 * d
        paint.color = track
        paint.alpha = 255
        canvas.drawCircle(cx, cy, r, paint)

        val rect = android.graphics.RectF(cx - r, cy - r, cx + r, cy + r)
        when (state) {
            1 -> {
                paint.color = accent
                canvas.drawArc(rect, angle, 260f, false, paint)
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
    }
}
