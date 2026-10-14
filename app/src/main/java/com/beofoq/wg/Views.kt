package com.beofoq.wg

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View

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
