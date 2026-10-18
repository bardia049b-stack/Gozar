package com.gozarwg

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import androidx.annotation.ColorInt
import androidx.core.content.ContextCompat
import kotlin.math.min

/** A circular, image-backed country badge. It intentionally never relies on emoji glyph rendering. */
class CountryFlagView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val label = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = android.graphics.Typeface.create("sans", android.graphics.Typeface.BOLD)
    }

    var countryCode: String? = null
        set(value) {
            field = value?.trim()?.uppercase()?.takeIf { it.length == 2 }
            contentDescription = field
            invalidate()
        }

    @ColorInt
    var ringColor: Int = ContextCompat.getColor(context, R.color.map_exit)
        set(value) {
            field = value
            invalidate()
        }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val size = min(width, height).toFloat()
        if (size <= 0f) return
        val radius = size / 2f
        val cx = width / 2f
        val cy = height / 2f
        val inset = dp(2f)

        fill.color = alpha(ringColor, 0.28f)
        canvas.drawCircle(cx, cy, radius - inset / 2f, fill)
        stroke.color = ringColor
        stroke.strokeWidth = inset
        canvas.drawCircle(cx, cy, radius - inset, stroke)

        val inner = (radius - inset * 2.1f).coerceAtLeast(0f)
        val clip = Path().apply { addCircle(cx, cy, inner, Path.Direction.CW) }
        canvas.save()
        canvas.clipPath(clip)
        val code = countryCode
        val flag = FlagAssets.bitmap(context, code)
        if (flag != null) {
            FlagAssets.drawCropped(canvas, flag, RectF(cx - inner, cy - inner, cx + inner, cy + inner), bitmapPaint)
        } else {
            fill.color = ContextCompat.getColor(context, R.color.m3_surface_container_highest)
            canvas.drawCircle(cx, cy, inner, fill)
            label.color = ContextCompat.getColor(context, R.color.m3_on_surface)
            label.textSize = inner * 0.78f
            val text = code ?: "··"
            val baseline = cy - (label.ascent() + label.descent()) / 2f
            canvas.drawText(text, cx, baseline, label)
        }
        canvas.restore()
    }

    private fun dp(value: Float): Float = value * resources.displayMetrics.density
    private fun alpha(@ColorInt color: Int, amount: Float): Int =
        (color and 0x00FFFFFF) or ((amount.coerceIn(0f, 1f) * 255f).toInt() shl 24)
}
