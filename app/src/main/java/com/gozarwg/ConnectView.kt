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
        private const val SEGMENTS = 120
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
    private val shine = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bubble = Path()
    private val ghost = Path()
    private var born = SystemClock.elapsedRealtime()
    private var lastFrame = 0L

    init {
        setWillNotDraw(false)
        isClickable = true
        isFocusable = true
        if (isInEditMode) {
            skin.color = 0xFF4A55C7.toInt()
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val want = resources.getDimension(R.dimen.connect_button).toInt()
        val w = resolveSize(want, widthMeasureSpec)
        val h = resolveSize(want, heightMeasureSpec)
        val side = min(w, h)
        setMeasuredDimension(side, side)
    }

    private fun blob(
        target: Path,
        cx: Float,
        cy: Float,
        radius: Float,
        lobes: Int,
        amp: Float,
        spin: Float
    ) {
        target.reset()
        val base = radius - amp
        for (i in 0..SEGMENTS) {
            val a = (i.toFloat() / SEGMENTS) * 2f * PI.toFloat() + spin
            val r = base + amp * cos(lobes * a)
            val x = cx + r * cos(a)
            val y = cy + r * sin(a)
            if (i == 0) target.moveTo(x, y) else target.lineTo(x, y)
        }
        target.close()
    }

    override fun onDraw(canvas: Canvas) {
        val now = SystemClock.elapsedRealtime()
        val age = (now - born) / 1000f
        val cx = width / 2f
        val cy = height / 2f
        val half = min(width, height) / 2f

        val pop = 1f + 0.06f * exp(-age * 4f)
        val drift = 2.6f * density * sin(age * 1.15f)
        val grow = 1f + 0.035f * sin(age * 1.35f)

        val surface = ContextCompat.getColor(context, R.color.m3_surface_container_highest)
        val accent = ContextCompat.getColor(context, R.color.m3_primary)
        val accentSoft = ContextCompat.getColor(context, R.color.m3_primary_container)
        val ok = ContextCompat.getColor(context, R.color.m3_ok)
        val okSoft = ContextCompat.getColor(context, R.color.m3_ok_container)
        val err = ContextCompat.getColor(context, R.color.m3_error)
        val errSoft = ContextCompat.getColor(context, R.color.m3_error_container)

        val spin = when (state) {
            BUSY -> age * 55f
            ON -> age * 22f
            else -> age * 15f
        }
        val radius = half * 0.74f * grow * pop
        val centerY = cy + drift

        blob(ghost, cx + 1.5f * density, centerY + 4f * density, radius * 1.03f, 4, 3.2f * density, -spin * 0.7f)
        shade.color = 0xFF000000.toInt()
        shade.alpha = 40
        canvas.drawPath(ghost, shade)

        blob(bubble, cx, centerY, radius, 4, 3.2f * density, spin)
        skin.color = when (state) {
            ON -> ok
            BUSY -> accentSoft
            ERROR -> err
            else -> surface
        }
        skin.alpha = 255
        canvas.drawPath(bubble, skin)

        if (state == OFF) {
            skin.color = accent
            skin.alpha = 46
            canvas.drawPath(bubble, skin)
        }
        if (state == ERROR) {
            skin.color = errSoft
            skin.alpha = 70
            canvas.drawPath(bubble, skin)
        }
        if (state == BUSY) {
            skin.color = accent
            skin.alpha = (26 + 20 * sin(age * 6f)).toInt().coerceIn(6, 60)
            canvas.drawPath(bubble, skin)
        }

        blob(ghost, cx, centerY, radius * 0.72f, 6, 2.2f * density, -spin * 1.8f)
        shine.color = when (state) {
            ON -> okSoft
            else -> accentSoft
        }
        shine.alpha = when (state) {
            ON -> 90
            BUSY -> 70
            else -> 46
        }
        canvas.drawPath(ghost, shine)

        if (isShown && now - lastFrame >= FRAME_MS) {
            lastFrame = now
            postInvalidateOnAnimation()
        }
    }
}
