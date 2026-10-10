package com.example.pdfscanner.processing

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Разворот кадра и выравнивание перспективы по четырём углам документа. */
object PerspectiveWarp {

    /** Декодирует JPEG из камеры и поворачивает его так, чтобы верх был вверху. */
    fun decodeUpright(bytes: ByteArray, rotationDegrees: Int, maxSide: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
        val options = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) ?: return null
        return rotate(bitmap, rotationDegrees)
    }

    fun decodeFile(path: String, maxSide: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
        return BitmapFactory.decodeFile(
            path,
            BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.ARGB_8888
            },
        )
    }

    private fun rotate(bitmap: Bitmap, degrees: Int): Bitmap {
        if (degrees % 360 == 0) return bitmap
        val matrix = Matrix().apply { postRotate(degrees.toFloat()) }
        val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        if (rotated !== bitmap) bitmap.recycle()
        return rotated
    }

    /** Вырезает документ по четырём углам (нормализованные координаты) и выпрямляет его в прямоугольник. */
    fun warp(src: Bitmap, quad: List<Pt>, maxSide: Int = 3000): Bitmap {
        val w = src.width.toFloat()
        val h = src.height.toFloat()
        val p = quad.map { Pt(it.x * w, it.y * h) }
        val top = hypot(p[1].x - p[0].x, p[1].y - p[0].y)
        val bottom = hypot(p[2].x - p[3].x, p[2].y - p[3].y)
        val left = hypot(p[3].x - p[0].x, p[3].y - p[0].y)
        val right = hypot(p[2].x - p[1].x, p[2].y - p[1].y)
        var outW = max(top, bottom)
        var outH = max(left, right)
        if (outW < 8f || outH < 8f) return src.copy(Bitmap.Config.ARGB_8888, false)
        val k = min(1f, maxSide / max(outW, outH))
        outW *= k
        outH *= k
        val width = outW.roundToInt().coerceAtLeast(1)
        val height = outH.roundToInt().coerceAtLeast(1)

        val from = floatArrayOf(p[0].x, p[0].y, p[1].x, p[1].y, p[2].x, p[2].y, p[3].x, p[3].y)
        val to = floatArrayOf(0f, 0f, width.toFloat(), 0f, width.toFloat(), height.toFloat(), 0f, height.toFloat())
        val matrix = Matrix()
        matrix.setPolyToPoly(from, 0, to, 0, 4)

        val out = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        Canvas(out).drawBitmap(src, matrix, Paint(Paint.FILTER_BITMAP_FLAG))
        return out
    }
}
