package com.example.pdfscanner.camera

import androidx.camera.core.ImageProxy

/** Уменьшенная копия кадра в градациях серого, повёрнутая «верхом вверх». */
class Gray(val pix: ByteArray, val w: Int, val h: Int)

object Luma {
    /** Берёт яркостную плоскость кадра камеры (она уже серая), поворачивает и уменьшает до targetW по ширине. */
    fun upright(image: ImageProxy, targetW: Int): Gray? {
        val plane = image.planes.firstOrNull() ?: return null
        val buffer = plane.buffer
        val rowStride = plane.rowStride
        val pixelStride = plane.pixelStride
        val sw = image.width
        val sh = image.height
        val rotation = image.imageInfo.rotationDegrees
        val sideways = rotation == 90 || rotation == 270
        val uw = if (sideways) sh else sw
        val uh = if (sideways) sw else sh
        val tw = targetW
        val th = (tw.toLong() * uh / uw).toInt().coerceAtLeast(1)
        val out = ByteArray(tw * th)
        for (ty in 0 until th) {
            val uy = (((ty + 0.5f) * uh) / th).toInt().coerceIn(0, uh - 1)
            for (tx in 0 until tw) {
                val ux = (((tx + 0.5f) * uw) / tw).toInt().coerceIn(0, uw - 1)
                val x: Int
                val y: Int
                when (rotation) {
                    90 -> { x = uy; y = sh - 1 - ux }
                    180 -> { x = sw - 1 - ux; y = sh - 1 - uy }
                    270 -> { x = sw - 1 - uy; y = ux }
                    else -> { x = ux; y = uy }
                }
                val index = y * rowStride + x * pixelStride
                out[ty * tw + tx] = if (index in 0 until buffer.limit()) buffer.get(index) else 0
            }
        }
        return Gray(out, tw, th)
    }
}
