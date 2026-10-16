package com.gozarwg

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.widget.FrameLayout
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import kotlin.math.min

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
            invalidate()
        }

    private val density = resources.displayMetrics.density
    private val skin = Paint(Paint.ANTI_ALIAS_FLAG)
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val shade = Paint(Paint.ANTI_ALIAS_FLAG)
    private val disc = Path()
    private val frame = RectF()

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

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val half = min(width, height) / 2f

        val outline = ContextCompat.getColor(context, R.color.m3_outline_variant)
        val accent = ContextCompat.getColor(context, R.color.m3_primary)

        val base = when (state) {
            ON -> ContextCompat.getColor(context, R.color.m3_ok)
            BUSY -> ContextCompat.getColor(context, R.color.m3_warn)
            ERROR -> ContextCompat.getColor(context, R.color.m3_error)
            else -> ContextCompat.getColor(context, R.color.tile_container)
        }
        val onBase = when (state) {
            ON -> ContextCompat.getColor(context, R.color.m3_on_ok)
            BUSY -> ContextCompat.getColor(context, R.color.m3_on_warn)
            ERROR -> ContextCompat.getColor(context, R.color.m3_on_error)
            else -> ContextCompat.getColor(context, R.color.tile_on_container)
        }
        val top = ColorUtils.blendARGB(base, 0xFFFFFFFF.toInt(), 0.26f)
        val deep = ColorUtils.blendARGB(base, 0xFF000000.toInt(), 0.18f)
        val halo = ColorUtils.blendARGB(base, 0xFFFFFFFF.toInt(), 0.55f)

        val discRadius = half * 0.78f

        shade.color = 0xFF000000.toInt()
        shade.alpha = 46
        canvas.drawCircle(cx, cy + 9f * density, discRadius * 0.99f, shade)

        line.strokeWidth = 1.2f * density
        line.color = ColorUtils.setAlphaComponent(outline, 150)
        canvas.drawCircle(cx, cy, half * 0.985f, line)

        line.strokeWidth = 3f * density
        line.strokeCap = Paint.Cap.ROUND
        line.color = ColorUtils.setAlphaComponent(halo, 210)
        frame.set(cx - half * 0.885f, cy - half * 0.885f, cx + half * 0.885f, cy + half * 0.885f)
        canvas.drawArc(frame, -215f, 250f, false, line)

        skin.shader = LinearGradient(
            cx, cy - discRadius, cx, cy + discRadius,
            top, deep, Shader.TileMode.CLAMP
        )
        canvas.drawCircle(cx, cy, discRadius, skin)

        canvas.save()
        disc.reset()
        disc.addCircle(cx, cy, discRadius, Path.Direction.CW)
        canvas.clipPath(disc)
        skin.shader = RadialGradient(
            cx - discRadius * 0.35f, cy - discRadius * 0.45f, discRadius * 1.05f,
            ColorUtils.setAlphaComponent(0xFFFFFFFF.toInt(), 60), 0x00FFFFFF, Shader.TileMode.CLAMP
        )
        canvas.drawCircle(cx, cy, discRadius, skin)
        skin.shader = null
        skin.color = ColorUtils.setAlphaComponent(halo, 90)
        skin.alpha = 255
        canvas.drawCircle(cx, cy, discRadius * 0.995f, skin)
        canvas.restore()

        line.strokeWidth = 1.5f * density
        line.color = ColorUtils.setAlphaComponent(onBase, 70)
        canvas.drawCircle(cx, cy, discRadius * 0.80f, line)

        val glyph = ContextCompat.getDrawable(context, R.drawable.ic_power)
        if (glyph != null) {
            val side = (half * 0.62f).toInt()
            glyph.setTint(onBase)
            glyph.setBounds(
                (cx - side / 2f).toInt(),
                (cy - side / 2f).toInt(),
                (cx + side / 2f).toInt(),
                (cy + side / 2f).toInt()
            )
            glyph.draw(canvas)
        }

        line.strokeWidth = 2f * density
        line.color = ColorUtils.setAlphaComponent(accent, 40)
        canvas.drawCircle(cx, cy, half * 0.995f, line)
    }
}
