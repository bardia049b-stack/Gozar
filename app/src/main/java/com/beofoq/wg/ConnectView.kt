package com.beofoq.wg

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.SystemClock
import android.util.AttributeSet
import android.widget.FrameLayout
import androidx.core.content.ContextCompat
import kotlin.math.min
import kotlin.math.sin

class ConnectView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) :
    FrameLayout(context, attrs) {

    companion object {
        const val OFF = 0
        const val BUSY = 1
        const val ON = 2
        const val ERROR = 3
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
    private var born = SystemClock.elapsedRealtime()
    private val box = RectF()
    private var sweep = 0f

    init {
        setWillNotDraw(false)
        isClickable = true
        isFocusable = true
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val want = (resources.getDimension(R.dimen.connect_button)).toInt()
        val w = resolveSize(want, widthMeasureSpec)
        val h = resolveSize(want, heightMeasureSpec)
        val side = min(w, h)
        setMeasuredDimension(side, side)
    }

    override fun onDraw(canvas: Canvas) {
        val now = SystemClock.elapsedRealtime()
        val age = (now - born) / 1000f
        val cx = width / 2f
        val cy = height / 2f
        val outer = min(width, height) / 2f - 10 * density
        val stroke = 6 * density

        box.set(cx - outer, cy - outer, cx + outer, cy + outer)

        val surface = ContextCompat.getColor(context, R.color.m3_surface_container_highest)
        val outline = ContextCompat.getColor(context, R.color.m3_outline_variant)
        val accent = ContextCompat.getColor(context, R.color.m3_primary)
        val accentSoft = ContextCompat.getColor(context, R.color.m3_primary_container)
        val onSoft = ContextCompat.getColor(context, R.color.m3_on_primary_container)
        val ok = ContextCompat.getColor(context, R.color.m3_ok)
        val err = ContextCompat.getColor(context, R.color.m3_error)
        val errSoft = ContextCompat.getColor(context, R.color.m3_error_container)

        if (state == ON) {
            ring.style = Paint.Style.STROKE
            ring.strokeWidth = 14 * density
            ring.color = accent
            ring.alpha = (18 + 14 * sin(age * 2.2)).toInt().coerceIn(6, 40)
            canvas.drawCircle(cx, cy, outer + 4 * density, ring)
        }

        ring.style = Paint.Style.STROKE
        ring.strokeWidth = stroke
        ring.alpha = 255
        ring.color = outline
        canvas.drawCircle(cx, cy, outer, ring)

        val track = outer - 22 * density
        box.set(cx - track, cy - track, cx + track, cy + track)

        when (state) {
            BUSY -> {
                sweep = (age * 300f) % 360f
                ring.color = accent
                ring.strokeWidth = stroke
                canvas.drawArc(box, sweep, 260f, false, ring)
            }
            ON -> {
                ring.color = ok
                ring.strokeWidth = stroke
                canvas.drawArc(box, -90f, 360f, false, ring)
            }
            ERROR -> {
                ring.color = err
                ring.strokeWidth = stroke
                canvas.drawArc(box, -90f, 360f, false, ring)
            }
        }

        val inner = outer - 40 * density
        disc.color = when (state) {
            ON -> accent
            BUSY -> accentSoft
            ERROR -> errSoft
            else -> surface
        }
        canvas.drawCircle(cx, cy, inner, disc)

        if (state == OFF || state == BUSY) {
            disc.color = accentSoft
            disc.alpha = if (state == BUSY) 60 else 34
            canvas.drawCircle(cx, cy, inner, disc)
            disc.alpha = 255
        }

        if (state == ON || state == BUSY) postInvalidateOnAnimation()
    }
}
