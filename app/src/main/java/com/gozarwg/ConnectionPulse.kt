package com.gozarwg

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.os.Build
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import kotlin.math.PI
import kotlin.math.sin

class ConnectionPulse @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : View(context, attrs, defStyle) {

    var state = ConnectControl.OFF
        set(value) {
            if (field == value) return
            field = value
            sync()
            invalidate()
        }

    private val density = resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val path = Path()
    private var phase = 0f
    private var stamp = 0L
    private var running = false

    private val ticker = object : Runnable {
        override fun run() {
            if (!isAttachedToWindow || !running) return
            val now = System.nanoTime()
            val elapsed = if (stamp == 0L) 16f else ((now - stamp) / 1_000_000f).coerceIn(1f, 40f)
            stamp = now
            phase = (phase + elapsed / 860f) % 1f
            invalidate()
            postOnAnimation(this)
        }
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

    private fun sync() {
        val animate = state == ConnectControl.BUSY && (Build.VERSION.SDK_INT < 26 || ValueAnimator.areAnimatorsEnabled())
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

    override fun onDraw(canvas: Canvas) {
        if (width <= 0 || height <= 0) return
        val primary = ContextCompat.getColor(context, R.color.m3_primary)
        val green = ContextCompat.getColor(context, R.color.m3_ok)
        val red = ContextCompat.getColor(context, R.color.m3_error)
        paint.strokeWidth = if (state == ConnectControl.BUSY) 3f * density else 2f * density
        paint.color = when (state) {
            ConnectControl.ON -> green
            ConnectControl.ERROR -> red
            else -> primary
        }
        val middle = height / 2f
        if (state != ConnectControl.BUSY) {
            canvas.drawLine(0f, middle, width.toFloat(), middle, paint)
            return
        }
        path.reset()
        val amplitude = 2.4f * density
        val steps = 56
        for (i in 0..steps) {
            val x = width * i / steps.toFloat()
            val y = middle + sin((i / steps.toFloat() * 2f * PI.toFloat() * 2.2f) + phase * 2f * PI.toFloat()) * amplitude
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        canvas.drawPath(path, paint)
    }
}
