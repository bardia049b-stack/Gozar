package com.gozarwg

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.os.SystemClock
import android.util.AttributeSet
import android.widget.FrameLayout
import androidx.core.content.ContextCompat
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.sin

class ConnectView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) :
    FrameLayout(context, attrs) {

    companion object {
        const val OFF = 0
        const val BUSY = 1
        const val ON = 2
        const val ERROR = 3

        private const val FRAME_MS = 16L
        private const val SEGMENTS = 140
    }

    var state = OFF
        set(value) {
            if (field == value) return
            field = value
            born = SystemClock.elapsedRealtime()
            lastFrame = 0L
            invalidate()
        }

    private val density = resources.displayMetrics.density
    private val skin = Paint(Paint.ANTI_ALIAS_FLAG)
    private val shade = Paint(Paint.ANTI_ALIAS_FLAG)
    private val gloss = Paint(Paint.ANTI_ALIAS_FLAG)
    private val wave = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val bubble = Path()
    private val ghost = Path()
    private var born = SystemClock.elapsedRealtime()
    private var lastFrame = 0L

    init {
        setWillNotDraw(false)
        isClickable = true
        isFocusable = true
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val want = resources.getDimension(R.dimen.connect_button).toInt()
        val w = resolveSize(want, widthMeasureSpec)
        val h = resolveSize(want, heightMeasureSpec)
        val side = min(w, h)
        setMeasuredDimension(side, side)
    }

    private fun blob(
        path: Path,
        cx: Float,
        cy: Float,
        radius: Float,
        lobes: Int,
        amp: Float,
        spin: Float
    ) {
        path.reset()
        val base = radius - amp
        for (i in 0..SEGMENTS) {
            val a = (i.toFloat() / SEGMENTS) * 2f * PI.toFloat() + spin
            val r = base + amp * cos(lobes * a)
            val x = cx + r * cos(a)
            val y = cy + r * sin(a)
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        path.close()
    }

    override fun onDraw(canvas: Canvas) {
        val now = SystemClock.elapsedRealtime()
        val age = (now - born) / 1000f
        val cx = width / 2f
        val cy = height / 2f
        val half = min(width, height) / 2f
        val unit = density

        val pop = 1f + 0.08f * exp(-age * 4.5f)
        val drift = 4f * unit * sin(age * 1.1f)
        val breathe = when (state) {
            BUSY -> 1f + 0.11f * sin(age * 5.4f)
            ON -> 1f + 0.045f * sin(age * 1.5f)
            else -> 1f + 0.028f * sin(age * 1.25f)
        }
        val spin = when (state) {
            BUSY -> age * 105f
            ON -> age * 40f
            ERROR -> age * 26f
            else -> age * 22f
        }
        val amp = when (state) {
            BUSY -> 12f * unit
            ON -> 7f * unit
            else -> 8.5f * unit
        }
        val baseRadius = when (state) {
            BUSY -> 0.62f
            ON -> 0.60f
            else -> 0.58f
        }

        val surface = ContextCompat.getColor(context, R.color.m3_surface_container_highest)
        val accent = ContextCompat.getColor(context, R.color.m3_primary)
        val accentSoft = ContextCompat.getColor(context, R.color.m3_primary_container)
        val ok = ContextCompat.getColor(context, R.color.m3_ok)
        val okSoft = ContextCompat.getColor(context, R.color.m3_ok_container)
        val err = ContextCompat.getColor(context, R.color.m3_error)
        val errSoft = ContextCompat.getColor(context, R.color.m3_error_container)

        val radius = half * baseRadius * breathe * pop
        val centerY = cy + drift

        blob(ghost, cx + 1.5f * unit, centerY + 5f * unit, radius * 1.02f, 4, amp * 0.9f, -spin * 0.6f)
        shade.color = 0xFF000000.toInt()
        shade.alpha = 46
        canvas.drawPath(ghost, shade)

        blob(bubble, cx, centerY, radius, 4, amp, spin)
        skin.color = when (state) {
            ON -> ok
            BUSY -> accentSoft
            ERROR -> err
            else -> accent
        }
        skin.alpha = when (state) {
            ON -> 174
            BUSY -> 158
            ERROR -> 150
            else -> 200
        }
        canvas.drawPath(bubble, skin)

        if (state == BUSY || state == ERROR) {
            skin.color = if (state == BUSY) accent else errSoft
            skin.alpha = if (state == BUSY) (40 + 34 * sin(age * 6f)).toInt().coerceIn(14, 88) else 90
            canvas.drawPath(bubble, skin)
        }

        blob(ghost, cx, centerY, radius * 0.70f, 6, amp * 0.55f, -spin * 1.6f)
        gloss.color = when (state) {
            ON -> okSoft
            else -> accentSoft
        }
        gloss.alpha = when (state) {
            ON -> 120
            BUSY -> 96
            else -> 74
        }
        canvas.drawPath(ghost, gloss)

        if (state == BUSY) {
            for (k in 0 until 2) {
                val phase = ((age * 0.85f) + k * 0.5f) % 1f
                val ringRadius = radius + (radius * 0.55f) * phase
                wave.strokeWidth = (5f - 3f * phase) * unit
                wave.color = accent
                wave.alpha = ((1f - phase) * 130).toInt()
                blob(ghost, cx, centerY, ringRadius, 4, amp * (1f + phase * 0.5f), spin * 0.4f)
                canvas.drawPath(ghost, wave)
            }
        }

        if (isShown && now - lastFrame >= FRAME_MS) {
            lastFrame = now
            postInvalidateOnAnimation()
        }
    }
}
