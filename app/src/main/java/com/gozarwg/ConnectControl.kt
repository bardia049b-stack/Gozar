package com.gozarwg

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.SystemClock
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.graphics.shapes.CornerRounding
import androidx.graphics.shapes.Morph
import androidx.graphics.shapes.RoundedPolygon
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

class ConnectControl @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : View(context, attrs, defStyle) {

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
            updateTarget()
            sync()
            invalidate()
        }

    var elapsedSeconds = 0
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }

    private val density = resources.displayMetrics.density
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val overlay = Paint(Paint.ANTI_ALIAS_FLAG)
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
    }
    private val bounds = RectF()
    private val work = Path()
    private val matrix = Matrix()
    private val power: Drawable? = AppCompatResources.getDrawable(context, R.drawable.ic_power)
    private val idle = regular(12, 0.19f)
    private val live = flower(12, 0.78f, 0.12f)
    private val fault = regular(6, 0.23f)
    private val searching = listOf(
        flower(12, 0.78f, 0.12f),
        regular(9, 0.22f),
        regular(5, 0.22f),
        roundedSquare(),
        flower(8, 0.83f, 0.15f)
    )
    private var from = idle
    private var to = idle
    private var progress = 1f
    private var morph = Morph(idle, idle)
    private var shapeStep = -1
    private var phase = 0f
    private var stamp = 0L
    private var running = false
    private var primary = 0
    private var onPrimary = 0
    private var accent = 0
    private var onAccent = 0
    private var error = 0
    private var onError = 0
    private var morphAnimator: ValueAnimator? = null

    private val ticker = object : Runnable {
        override fun run() {
            if (!isAttachedToWindow || !running) return
            val now = System.nanoTime()
            val elapsed = if (stamp == 0L) 16f else ((now - stamp) / 1_000_000f).coerceIn(1f, 42f)
            stamp = now
            phase = (phase + elapsed / if (state == BUSY) 3100f else 18000f) % 1f
            if (state == BUSY) {
                val next = searchIndex()
                if (next != shapeStep) {
                    shapeStep = next
                    changeShape(searching[next])
                }
            }
            invalidate()
            postOnAnimation(this)
        }
    }

    init {
        isClickable = true
        isFocusable = true
        updateColors()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        updateColors()
        sync()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        running = false
        removeCallbacks(ticker)
        morphAnimator?.cancel()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val wanted = (208f * density).toInt()
        val side = min(resolveSize(wanted, widthMeasureSpec), resolveSize(wanted, heightMeasureSpec))
        setMeasuredDimension(side, side)
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                isPressed = true
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP -> {
                val clicked = isPressed
                isPressed = false
                invalidate()
                if (clicked && event.x in 0f..width.toFloat() && event.y in 0f..height.toFloat()) performClick()
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                isPressed = false
                invalidate()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun updateColors() {
        primary = color(R.color.m3_primary)
        onPrimary = color(R.color.m3_on_primary)
        accent = color(R.color.m3_ok)
        onAccent = color(R.color.m3_on_ok)
        error = color(R.color.m3_error)
        onError = color(R.color.m3_on_error)
    }

    private fun sync() {
        val animate = (state == BUSY || state == ON) && (Build.VERSION.SDK_INT < 26 || ValueAnimator.areAnimatorsEnabled())
        if (animate && !running) {
            running = true
            stamp = 0L
            postOnAnimation(ticker)
        } else if (!animate && running) {
            running = false
            removeCallbacks(ticker)
        }
    }

    private fun searchIndex(): Int = ((SystemClock.elapsedRealtime() / 720L) % searching.size).toInt()

    private fun updateTarget() {
        val target = when (state) {
            ON -> live
            BUSY -> searching[searchIndex()]
            ERROR -> fault
            else -> idle
        }
        if (state != BUSY) shapeStep = -1
        changeShape(target)
    }

    private fun changeShape(target: RoundedPolygon) {
        if (target === to) return
        from = if (progress >= 0.5f) to else from
        to = target
        morph = Morph(from, to)
        progress = 0f
        morphAnimator?.cancel()
        if (Build.VERSION.SDK_INT >= 26 && !ValueAnimator.areAnimatorsEnabled()) {
            progress = 1f
            invalidate()
            return
        }
        morphAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 460L
            interpolator = android.view.animation.OvershootInterpolator(0.72f)
            addUpdateListener {
                progress = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    private fun color(id: Int): Int = ContextCompat.getColor(context, id)

    override fun onDraw(canvas: Canvas) {
        val side = min(width, height).toFloat()
        if (side <= 0f) return
        val cx = width / 2f
        val cy = height / 2f
        val scale = side * 0.425f
        work.rewind()
        var first = true
        morph.forEachCubic(progress.coerceIn(0f, 1f)) { cubic ->
            if (first) {
                work.moveTo(cubic.anchor0X, cubic.anchor0Y)
                first = false
            }
            work.cubicTo(
                cubic.control0X,
                cubic.control0Y,
                cubic.control1X,
                cubic.control1Y,
                cubic.anchor1X,
                cubic.anchor1Y
            )
        }
        work.close()
        matrix.reset()
        matrix.setScale(scale, scale)
        work.transform(matrix)
        work.computeBounds(bounds, true)
        work.offset(cx - bounds.centerX(), cy - bounds.centerY())

        val start = when (state) {
            ON -> accent
            ERROR -> error
            else -> primary
        }
        val end = when (state) {
            BUSY -> accent
            ON -> ColorUtils.blendARGB(accent, primary, 0.2f)
            ERROR -> error
            else -> ColorUtils.blendARGB(primary, accent, 0.38f)
        }
        fill.shader = android.graphics.LinearGradient(
            cx - scale, cy - scale, cx + scale, cy + scale,
            start, end, android.graphics.Shader.TileMode.CLAMP
        )
        canvas.save()
        val rotation = when (state) {
            BUSY -> phase * 165f
            ON -> phase * 9f
            else -> 0f
        }
        canvas.rotate(rotation, cx, cy)
        canvas.drawPath(work, fill)
        canvas.restore()
        fill.shader = null

        if (isPressed) {
            overlay.color = ColorUtils.setAlphaComponent(Color.WHITE, 34)
            canvas.drawPath(work, overlay)
        }

        when (state) {
            ON -> drawElapsed(canvas, cx, cy, side)
            ERROR -> drawFault(canvas, cx, cy, side)
            else -> drawPower(canvas, cx, cy, side, onPrimary)
        }
    }

    private fun drawPower(canvas: Canvas, cx: Float, cy: Float, side: Float, tint: Int) {
        val icon = power ?: return
        val iconSize = (side * 0.31f).toInt()
        icon.setTint(tint)
        icon.setBounds(
            (cx - iconSize / 2f).toInt(),
            (cy - iconSize / 2f).toInt(),
            (cx + iconSize / 2f).toInt(),
            (cy + iconSize / 2f).toInt()
        )
        icon.draw(canvas)
    }

    private fun drawElapsed(canvas: Canvas, cx: Float, cy: Float, side: Float) {
        label.color = onAccent
        label.textSize = max(14f * density, side * 0.142f)
        val text = String.format("%02d:%02d", elapsedSeconds.coerceAtLeast(0) / 60, elapsedSeconds.coerceAtLeast(0) % 60)
        val metrics = label.fontMetrics
        canvas.drawText(text, cx, cy - (metrics.ascent + metrics.descent) / 2f, label)
    }

    private fun drawFault(canvas: Canvas, cx: Float, cy: Float, side: Float) {
        label.color = onError
        label.textSize = side * 0.28f
        val metrics = label.fontMetrics
        canvas.drawText("!", cx, cy - (metrics.ascent + metrics.descent) / 2f, label)
    }

    private fun regular(sides: Int, radius: Float): RoundedPolygon {
        return RoundedPolygon(
            numVertices = sides,
            radius = 1f,
            centerX = 0f,
            centerY = 0f,
            rounding = CornerRounding(radius, 0.82f)
        )
    }

    private fun roundedSquare(): RoundedPolygon {
        return RoundedPolygon(
            numVertices = 4,
            radius = 1f,
            centerX = 0f,
            centerY = 0f,
            rounding = CornerRounding(0.42f, 0.92f)
        )
    }

    private fun flower(points: Int, inner: Float, rounding: Float): RoundedPolygon {
        val vertices = FloatArray(points * 4)
        for (i in 0 until points * 2) {
            val radius = if (i % 2 == 0) 1f else inner
            val angle = -PI.toFloat() / 2f + i * PI.toFloat() / points
            vertices[i * 2] = cos(angle) * radius
            vertices[i * 2 + 1] = sin(angle) * radius
        }
        return RoundedPolygon(
            vertices = vertices,
            rounding = CornerRounding(rounding, 0.65f),
            centerX = 0f,
            centerY = 0f
        )
    }
}
