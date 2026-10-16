package com.gozarwg

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
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

        private const val FRAME_MS = 33L
        private const val SEGMENTS = 96
    }

    var state = OFF
        set(value) {
            if (field == value) return
            field = value
            born = SystemClock.elapsedRealtime()
            invalidate()
        }

    private val density = resources.displayMetrics.density
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val disc = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bubble = Paint(Paint.ANTI_ALIAS_FLAG)
    private val track = Paint(Paint.ANTI_ALIAS_FLAG)
    private val shell = Path()
    private val ghost = Path()
    private var born = SystemClock.elapsedRealtime()
    private var lastFrame = 0L
    private val box = RectF()
    private var sweep = 0f

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
        target: Path,
        cx: Float,
        cy: Float,
        radius: Float,
        lobes: Int,
        amp: Float,
        spin: Float,
        wobble: Float
    ) {
        target.reset()
        val base = radius - amp
        for (i in 0..SEGMENTS) {
            val a = (i.toFloat() / SEGMENTS) * 2f * PI.toFloat() + spin
            val r = base + amp * cos(lobes * a) + wobble * sin(2f * a + spin * 2.6f)
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
        val pop = 1f + 0.05f * exp(-age * 5f)
        val breath = 1f + 0.02f * sin(age * 1.5f)

        val surface = ContextCompat.getColor(context, R.color.m3_surface_container_highest)
        val outline = ContextCompat.getColor(context, R.color.m3_outline_variant)
        val accent = ContextCompat.getColor(context, R.color.m3_primary)
        val accentSoft = ContextCompat.getColor(context, R.color.m3_primary_container)
        val ok = ContextCompat.getColor(context, R.color.m3_ok)
        val err = ContextCompat.getColor(context, R.color.m3_error)
        val errSoft = ContextCompat.getColor(context, R.color.m3_error_container)

        val spin = when (state) {
            BUSY -> age * 22f
            ON -> age * 7f
            else -> age * 3f
        }
        val wobble = min(half * 0.012f, 2.2f * density)

        blob(shell, cx, cy, half * 0.86f * breath * pop, 4, 3f * density * breath, spin, wobble)
        bubble.color = if (state == ON) ok else surface
        bubble.alpha = if (state == ON) 140 else 235
        canvas.drawPath(shell, bubble)

        blob(ghost, cx, cy, half * 0.78f * breath, 8, 2.4f * density, -spin * 1.4f, wobble * 0.7f)
        bubble.color = if (state == ON) accent else accentSoft
        bubble.alpha = if (state == ON) 46 else 40
        canvas.drawPath(ghost, bubble)

        val outer = half * 0.70f
        val trackR = half * 0.585f
        val stroke = 6 * density

        ring.style = Paint.Style.STROKE
        ring.strokeWidth = stroke
        ring.alpha = 255
        ring.color = when (state) {
            ON -> ok
            BUSY -> outline
            ERROR -> err
            else -> outline
        }
        canvas.drawCircle(cx, cy, outer, ring)

        box.set(cx - trackR, cy - trackR, cx + trackR, cy + trackR)
        when (state) {
            BUSY -> {
                sweep = (age * 300f) % 360f
                ring.color = accent
                canvas.drawArc(box, sweep, 260f, false, ring)
            }
            ON -> {
                track.style = Paint.Style.STROKE
                track.strokeWidth = stroke * 0.7f
                track.strokeCap = Paint.Cap.ROUND
                track.color = ok
                track.alpha = 90
                canvas.drawCircle(cx, cy, trackR, track)
            }
            ERROR -> canvas.drawArc(box, -90f, 360f, false, ring)
        }

        if (state == ON) {
            ring.style = Paint.Style.STROKE
            ring.strokeWidth = 12 * density
            ring.color = accent
            ring.alpha = (14 + 12 * sin(age * 2.2)).toInt().coerceIn(4, 32)
            canvas.drawCircle(cx, cy, outer + 6 * density, ring)
        }

        val face = half * 0.44f * pop
        disc.color = when (state) {
            ON -> accent
            BUSY -> accentSoft
            ERROR -> errSoft
            else -> surface
        }
        canvas.drawCircle(cx, cy, face, disc)

        if (state == OFF || state == BUSY) {
            disc.color = accentSoft
            disc.alpha = if (state == BUSY) 60 else 36
            canvas.drawCircle(cx, cy, face, disc)
            disc.alpha = 255
        }

        if (state == ERROR) {
            disc.color = err
            disc.alpha = 30
            canvas.drawCircle(cx, cy, face, disc)
            disc.alpha = 255
        }

        if (isShown && now - lastFrame >= FRAME_MS) {
            lastFrame = now
            postInvalidateOnAnimation()
        }
    }
}
