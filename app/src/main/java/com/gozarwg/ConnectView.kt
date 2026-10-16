package com.gozarwg

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.os.Build
import android.util.AttributeSet
import android.view.View
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import kotlin.math.PI
import kotlin.math.min
import kotlin.math.sin

class ConnectView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : View(context, attrs, defStyle) {

    companion object {
        const val OFF = 0
        const val BUSY = 1
        const val ON = 2
        const val ERROR = 3

        private const val DISC = 0.73f
        private const val RING = 0.83f
        private const val FILL = 0.84f
        private const val SPIN = 300f
        private const val SPAN = 1250f
        private const val BREATH = 1650f
        private const val WAVE = 1850f
        private const val LULL = 3400f
        private const val TAU = 2f * PI.toFloat()
    }

    var state = OFF
        set(value) {
            if (field == value) return
            field = value
            tone()
            sync()
            invalidate()
        }

    private val density = resources.displayMetrics.density
    private val skin = Paint(Paint.ANTI_ALIAS_FLAG)
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val glyph = AppCompatResources.getDrawable(context, R.drawable.ic_power)
    private val arcFrame = RectF()
    private var base = 0
    private var onBase = 0
    private var spin = 0f
    private var span = 0f
    private var breath = 0f
    private var wave = 0f
    private var stamp = 0L
    private var ticking = false

    private val ticker = object : Runnable {
        override fun run() {
            if (!isAttachedToWindow) {
                ticking = false
                return
            }
            step()
            invalidate()
            if (ticking) postOnAnimation(this)
        }
    }

    init {
        tone()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val want = resources.getDimension(R.dimen.connect_button).toInt()
        val side = min(resolveSize(want, widthMeasureSpec), resolveSize(want, heightMeasureSpec))
        setMeasuredDimension(side, side)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        stamp = 0L
        sync()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        ticking = false
        removeCallbacks(ticker)
    }

    private fun tone() {
        when (state) {
            ON -> {
                base = color(R.color.m3_ok)
                onBase = color(R.color.m3_on_ok)
            }

            BUSY -> {
                base = color(R.color.m3_primary)
                onBase = color(R.color.m3_on_primary)
            }

            ERROR -> {
                base = color(R.color.m3_error)
                onBase = color(R.color.m3_on_error)
            }

            else -> {
                base = color(R.color.m3_primary_container)
                onBase = color(R.color.m3_on_primary_container)
            }
        }
    }

    private fun color(id: Int): Int = ContextCompat.getColor(context, id)

    private fun moving(): Boolean =
        Build.VERSION.SDK_INT < 26 || ValueAnimator.areAnimatorsEnabled()

    private fun sync() {
        val want = (state == BUSY || state == ON) && moving()
        if (want && !ticking) {
            ticking = true
            stamp = 0L
            removeCallbacks(ticker)
            postOnAnimation(ticker)
            return
        }
        if (!want) {
            ticking = false
            removeCallbacks(ticker)
            breath = 0f
            wave = 0f
            if (state != BUSY) {
                spin = 0f
                span = 0f
            }
        }
    }

    private fun step() {
        val now = System.nanoTime()
        val dt = if (stamp == 0L) 16f else ((now - stamp) / 1_000_000f).coerceIn(1f, 40f)
        stamp = now
        if (state == BUSY) {
            spin = (spin + SPIN * dt / 1000f) % 360f
            span = (span + TAU * dt / SPAN) % TAU
            breath = (breath + TAU * dt / BREATH) % TAU
            wave = (wave + dt / WAVE) % 1f
        } else {
            breath = (breath + TAU * dt / LULL) % TAU
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val half = min(width, height) / 2f
        if (half <= 0f) return
        val cx = width / 2f
        val cy = height / 2f
        val discR = half * DISC
        val ringR = half * RING
        val swell = if (state == BUSY) 1f + 0.065f * sin(breath) else 1f
        val r = discR * swell
        val pressed = if (isPressed) 1f else 0f

        skin.shader = null
        skin.alpha = 255
        skin.color = 0x38000000
        canvas.drawCircle(cx, cy + 9f * density, r * 0.99f, skin)

        if (state == BUSY) {
            val wr = r + (ringR * 1.16f - r) * wave
            line.strokeWidth = 2f * density
            line.color = ColorUtils.setAlphaComponent(ColorUtils.blendARGB(base, 0xFFFFFFFF.toInt(), 0.42f), ((1f - wave) * 104f).toInt())
            canvas.drawCircle(cx, cy, wr, line)
        }

        val sweep = 46f + 176f * (0.5f + 0.5f * sin(span))
        if (state == BUSY) {
            arcFrame.set(cx - ringR, cy - ringR, cx + ringR, cy + ringR)
            line.color = ColorUtils.setAlphaComponent(color(R.color.m3_surface_container_highest), 130)
            line.strokeWidth = 8.6f * density
            canvas.drawArc(arcFrame, spin, sweep, false, line)
            line.strokeWidth = 3.4f * density
            line.color = ColorUtils.blendARGB(base, 0xFFFFFFFF.toInt(), 0.32f)
            canvas.drawArc(arcFrame, spin, sweep, false, line)
        } else {
            if (state != OFF) {
                arcFrame.set(cx - ringR, cy - ringR, cx + ringR, cy + ringR)
                line.color = ColorUtils.setAlphaComponent(color(R.color.m3_surface_container_highest), 104)
                line.strokeWidth = 5.2f * density
                canvas.drawArc(arcFrame, 0f, 360f, false, line)
            }
            arcFrame.set(cx - ringR, cy - ringR, cx + ringR, cy + ringR)
            line.strokeWidth = 3f * density
            line.color = ColorUtils.setAlphaComponent(
                base,
                when (state) {
                    ON -> (134f + 54f * (0.5f + 0.5f * sin(breath))).toInt()
                    ERROR -> 214
                    else -> 60
                }
            )
            canvas.drawArc(arcFrame, 0f, 360f, false, line)
        }

        skin.shader = LinearGradient(
            cx, cy - r, cx, cy + r,
            ColorUtils.setAlphaComponent(ColorUtils.blendARGB(base, 0xFFFFFFFF.toInt(), 0.24f), (FILL * 255f).toInt()),
            ColorUtils.setAlphaComponent(ColorUtils.blendARGB(base, 0xFF000000.toInt(), 0.20f), (FILL * 255f).toInt()),
            Shader.TileMode.CLAMP
        )
        canvas.drawCircle(cx, cy, r, skin)

        skin.shader = RadialGradient(
            cx - r * 0.38f, cy - r * 0.46f, r * 1.08f,
            ColorUtils.setAlphaComponent(0xFFFFFFFF.toInt(), 58), 0x00FFFFFF, Shader.TileMode.CLAMP
        )
        canvas.drawCircle(cx, cy, r, skin)
        skin.shader = null

        if (pressed > 0f) {
            skin.color = ColorUtils.setAlphaComponent(onBase, 44)
            canvas.drawCircle(cx, cy, r, skin)
        }

        line.strokeWidth = 1f * density
        line.color = ColorUtils.setAlphaComponent(onBase, 64)
        canvas.drawCircle(cx, cy, r * 0.80f, line)

        if (glyph != null) {
            val side = (r * 0.88f).toInt()
            glyph.setTint(onBase)
            glyph.setBounds(
                (cx - side / 2f).toInt(),
                (cy - side / 2f).toInt(),
                (cx + side / 2f).toInt(),
                (cy + side / 2f).toInt()
            )
            glyph.draw(canvas)
        }
    }
}
