package com.gozar

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.util.LruCache

/**
 * Bundled country artwork adapted from ZedSecure's flag asset set. The original SVGs are from
 * flag-icons (MIT); compact PNG derivatives are stored in assets/flags for View/Canvas rendering.
 */
object FlagAssets {
    private val cache = object : LruCache<String, Bitmap>(256) {}

    fun bitmap(context: Context, countryCode: String?): Bitmap? {
        val code = countryCode?.trim()?.lowercase()?.takeIf { it.length == 2 } ?: return null
        cache.get(code)?.let { return it }
        val decoded = try {
            context.assets.open("flags/$code.png").use { BitmapFactory.decodeStream(it) }
        } catch (_: Exception) {
            null
        } ?: return null
        cache.put(code, decoded)
        return decoded
    }

    fun prefetch(context: Context, codes: Collection<String>) {
        for (code in codes) {
            if (code.length != 2) continue
            if (cache.get(code.lowercase()) != null) continue
            bitmap(context, code)
        }
    }

    /** Center-crops a 4:3 flag into the supplied round-marker square without stretching it. */
    fun drawCropped(canvas: Canvas, bitmap: Bitmap, destination: RectF, paint: Paint) {
        val destinationRatio = destination.width() / destination.height()
        val sourceRatio = bitmap.width.toFloat() / bitmap.height.toFloat()
        val source = if (sourceRatio > destinationRatio) {
            val wanted = (bitmap.height * destinationRatio).toInt().coerceAtLeast(1)
            val left = ((bitmap.width - wanted) / 2).coerceAtLeast(0)
            Rect(left, 0, (left + wanted).coerceAtMost(bitmap.width), bitmap.height)
        } else {
            val wanted = (bitmap.width / destinationRatio).toInt().coerceAtLeast(1)
            val top = ((bitmap.height - wanted) / 2).coerceAtLeast(0)
            Rect(0, top, bitmap.width, (top + wanted).coerceAtMost(bitmap.height))
        }
        canvas.drawBitmap(bitmap, source, destination, paint)
    }
}
