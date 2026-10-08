package com.example.pdfscanner.processing

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

enum class FilterMode { DOCUMENT, BLACK_WHITE, ORIGINAL }

/**
 * Превращает «фото листа» в «скан»:
 *  1. оценивает освещённость и цвет бумаги в каждой точке страницы (карта фона);
 *  2. делит снимок на эту карту: уходят тени, перепады света, затемнения у сгибов,
 *     жёлтый оттенок бумаги, а сама бумага становится белой;
 *  3. подтягивает контраст: текст чёрный, фон чисто белый (или полностью чёрно-белая картинка).
 * Геометрию (перспективу, обрезку по краям листа) выравнивает сканер Google до этого шага.
 */
object ImageEnhancer {

    fun decode(path: String, maxSide: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
        val options = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val bitmap = BitmapFactory.decodeFile(path, options) ?: return null
        val longest = max(bitmap.width, bitmap.height)
        if (longest <= maxSide) return bitmap
        val k = maxSide.toFloat() / longest
        val scaled = Bitmap.createScaledBitmap(
            bitmap,
            (bitmap.width * k).roundToInt().coerceAtLeast(1),
            (bitmap.height * k).roundToInt().coerceAtLeast(1),
            true,
        )
        if (scaled !== bitmap) bitmap.recycle()
        return scaled
    }

    fun enhance(src: Bitmap, mode: FilterMode): Bitmap {
        if (mode == FilterMode.ORIGINAL) return src

        val w = src.width
        val h = src.height
        val pixels = IntArray(w * h)
        src.getPixels(pixels, 0, w, 0, 0, w, h)

        // --- 1. Карта фона: в каждом блоке берём самый светлый пиксель (бумага, не текст) ---
        val block = max(4, max(w, h) / 300)
        val sw = (w + block - 1) / block
        val sh = (h + block - 1) / block
        var bgR = FloatArray(sw * sh)
        var bgG = FloatArray(sw * sh)
        var bgB = FloatArray(sw * sh)
        for (by in 0 until sh) {
            val y0 = by * block
            val y1 = min(h, y0 + block)
            for (bx in 0 until sw) {
                val x0 = bx * block
                val x1 = min(w, x0 + block)
                var best = 0
                var bestLuma = -1
                for (y in y0 until y1) {
                    val row = y * w
                    for (x in x0 until x1) {
                        val p = pixels[row + x]
                        val l = ((p shr 16) and 255) * 77 + ((p shr 8) and 255) * 150 + (p and 255) * 29
                        if (l > bestLuma) {
                            bestLuma = l
                            best = p
                        }
                    }
                }
                val i = by * sw + bx
                bgR[i] = ((best shr 16) and 255).toFloat()
                bgG[i] = ((best shr 8) and 255).toFloat()
                bgB[i] = (best and 255).toFloat()
            }
        }
        // Закрываем «дыры» от крупных букв и сглаживаем.
        bgR = blur(blur(dilate(bgR, sw, sh, 2), sw, sh, 3), sw, sh, 3)
        bgG = blur(blur(dilate(bgG, sw, sh, 2), sw, sh, 3), sw, sh, 3)
        bgB = blur(blur(dilate(bgB, sw, sh, 2), sw, sh, 3), sw, sh, 3)

        // Нижняя граница фона: большие тёмные области (фото, плашки) не «выбеливаются» целиком.
        val floorR = max(1f, 0.55f * percentile(bgR, 0.9f))
        val floorG = max(1f, 0.55f * percentile(bgG, 0.9f))
        val floorB = max(1f, 0.55f * percentile(bgB, 0.9f))

        val xi0 = IntArray(w)
        val xi1 = IntArray(w)
        val xw = FloatArray(w)
        for (x in 0 until w) {
            val fx = (x + 0.5f) / block - 0.5f
            val i0 = floor(fx).toInt()
            xw[x] = fx - i0
            xi0[x] = i0.coerceIn(0, sw - 1)
            xi1[x] = (i0 + 1).coerceIn(0, sw - 1)
        }

        // --- 2. Деление на фон (выравнивание света и баланс белого) + гистограмма ---
        val hist = IntArray(256)
        for (y in 0 until h) {
            val fy = (y + 0.5f) / block - 0.5f
            val y0 = floor(fy).toInt()
            val wy = fy - y0
            val r0 = y0.coerceIn(0, sh - 1) * sw
            val r1 = (y0 + 1).coerceIn(0, sh - 1) * sw
            val row = y * w
            for (x in 0 until w) {
                val a = r0 + xi0[x]
                val b = r0 + xi1[x]
                val c = r1 + xi0[x]
                val d = r1 + xi1[x]
                val wx = xw[x]
                val p = pixels[row + x]
                val r = min(255, (((p shr 16) and 255) * 255f / max(bilerp(bgR, a, b, c, d, wx, wy), floorR)).toInt())
                val g = min(255, (((p shr 8) and 255) * 255f / max(bilerp(bgG, a, b, c, d, wx, wy), floorG)).toInt())
                val bl = min(255, ((p and 255) * 255f / max(bilerp(bgB, a, b, c, d, wx, wy), floorB)).toInt())
                pixels[row + x] = (r shl 16) or (g shl 8) or bl
                hist[(r * 77 + g * 150 + bl * 29) shr 8]++
            }
        }

        // --- 3. Тоновая кривая ---
        val total = w * h
        if (mode == FilterMode.DOCUMENT) {
            val target = (total * 0.003f).toInt()
            var cum = 0
            var lo = 0
            for (i in 0..255) {
                cum += hist[i]
                if (cum >= target) {
                    lo = i
                    break
                }
            }
            lo = lo.coerceIn(0, 110)
            val hi = 232
            val lut = IntArray(256) { v ->
                val t = ((v - lo).toFloat() / (hi - lo)).coerceIn(0f, 1f)
                val smooth = t * t * (3f - 2f * t)
                ((0.5f * t + 0.5f * smooth) * 255f + 0.5f).toInt()
            }
            for (i in 0 until total) {
                val p = pixels[i]
                pixels[i] = (0xFF shl 24) or
                    (lut[(p shr 16) and 255] shl 16) or
                    (lut[(p shr 8) and 255] shl 8) or
                    lut[p and 255]
            }
        } else {
            val t0 = otsu(hist, total).coerceIn(120, 200)
            val lut = IntArray(256) { v ->
                val t = ((v - (t0 - 25)).toFloat() / 50f).coerceIn(0f, 1f)
                ((t * t * (3f - 2f * t)) * 255f + 0.5f).toInt()
            }
            for (i in 0 until total) {
                val p = pixels[i]
                val l = lut[(((p shr 16) and 255) * 77 + ((p shr 8) and 255) * 150 + (p and 255) * 29) shr 8]
                pixels[i] = (0xFF shl 24) or (l shl 16) or (l shl 8) or l
            }
        }
        return Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888)
    }

    private fun bilerp(m: FloatArray, a: Int, b: Int, c: Int, d: Int, wx: Float, wy: Float): Float {
        val top = m[a] + (m[b] - m[a]) * wx
        val bottom = m[c] + (m[d] - m[c]) * wx
        return top + (bottom - top) * wy
    }

    private fun percentile(a: FloatArray, p: Float): Float {
        val s = a.copyOf()
        s.sort()
        return s[((s.size - 1) * p).toInt()]
    }

    private fun dilate(src: FloatArray, w: Int, h: Int, r: Int): FloatArray {
        val tmp = FloatArray(src.size)
        val out = FloatArray(src.size)
        for (y in 0 until h) {
            val row = y * w
            for (x in 0 until w) {
                var m = 0f
                for (k in max(0, x - r)..min(w - 1, x + r)) if (src[row + k] > m) m = src[row + k]
                tmp[row + x] = m
            }
        }
        for (y in 0 until h) {
            for (x in 0 until w) {
                var m = 0f
                for (k in max(0, y - r)..min(h - 1, y + r)) if (tmp[k * w + x] > m) m = tmp[k * w + x]
                out[y * w + x] = m
            }
        }
        return out
    }

    private fun blur(src: FloatArray, w: Int, h: Int, r: Int): FloatArray {
        val tmp = FloatArray(src.size)
        val out = FloatArray(src.size)
        for (y in 0 until h) {
            val row = y * w
            for (x in 0 until w) {
                var sum = 0f
                val from = max(0, x - r)
                val to = min(w - 1, x + r)
                for (k in from..to) sum += src[row + k]
                tmp[row + x] = sum / (to - from + 1)
            }
        }
        for (y in 0 until h) {
            val from = max(0, y - r)
            val to = min(h - 1, y + r)
            for (x in 0 until w) {
                var sum = 0f
                for (k in from..to) sum += tmp[k * w + x]
                out[y * w + x] = sum / (to - from + 1)
            }
        }
        return out
    }

    private fun otsu(hist: IntArray, total: Int): Int {
        var sumAll = 0L
        for (i in 0..255) sumAll += i.toLong() * hist[i]
        var sumB = 0L
        var wB = 0
        var best = 0.0
        var threshold = 160
        for (t in 0..255) {
            wB += hist[t]
            if (wB == 0) continue
            val wF = total - wB
            if (wF == 0) break
            sumB += t.toLong() * hist[t]
            val mB = sumB.toDouble() / wB
            val mF = (sumAll - sumB).toDouble() / wF
            val between = wB.toDouble() * wF * (mB - mF) * (mB - mF)
            if (between > best) {
                best = between
                threshold = t
            }
        }
        return threshold
    }
}
