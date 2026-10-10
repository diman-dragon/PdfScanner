package com.example.pdfscanner.processing

import kotlin.math.abs
import kotlin.math.atan2

/**
 * Простой поиск документа в кадре без нейросетей: ищем самую большую область, заметно
 * отличающуюся по яркости от фона (светлый лист на тёмном столе или наоборот), и вписываем
 * в неё четырёхугольник. Рассчитан на документы с чёткими границами.
 */
object DocumentDetector {
    private const val MIN_FRACTION = 0.12f
    private const val MAX_FRACTION = 0.96f
    private const val MIN_EDGE = 30f

    /** pix: яркость кадра (w*h байт, строка за строкой). Возвращает углы документа или null. */
    fun detect(pix: ByteArray, w: Int, h: Int): List<Pt>? {
        if (w < 16 || h < 16 || pix.size < w * h) return null
        val n = w * h
        val g = blur3(pix, w, h)
        val threshold = otsu(g)

        for (bright in booleanArrayOf(true, false)) {
            val mask = BooleanArray(n) { if (bright) g[it] > threshold else g[it] <= threshold }
            val comp = largestComponent(mask, w, h) ?: continue
            val fraction = comp.size.toFloat() / n
            if (fraction < MIN_FRACTION || fraction > MAX_FRACTION) continue
            val fit = fitQuad(comp, w, h) ?: continue
            if (edgeStrength(g, w, h, fit.boundary) < MIN_EDGE) continue
            val ordered = order(fit.quad)
            val area = polygonArea(ordered)
            if (area < 1f) continue
            val fill = comp.size / area
            if (fill < 0.80f || fill > 1.20f) continue
            if (!isConvex(ordered)) continue
            return ordered.map { Pt((it.x + 0.5f) / w, (it.y + 0.5f) / h) }
        }
        return null
    }

    /** Средняя абсолютная разница яркости двух кадров одного размера (0..255). */
    fun meanAbsDiff(a: ByteArray, b: ByteArray): Float {
        if (a.size != b.size || a.isEmpty()) return 255f
        var sum = 0L
        for (i in a.indices) sum += abs((a[i].toInt() and 255) - (b[i].toInt() and 255))
        return sum.toFloat() / a.size
    }

    private fun blur3(pix: ByteArray, w: Int, h: Int): IntArray {
        val out = IntArray(w * h)
        for (y in 0 until h) {
            val y0 = if (y > 0) y - 1 else 0
            val y1 = if (y < h - 1) y + 1 else h - 1
            for (x in 0 until w) {
                val x0 = if (x > 0) x - 1 else 0
                val x1 = if (x < w - 1) x + 1 else w - 1
                var s = 0
                for (yy in y0..y1) for (xx in x0..x1) s += pix[yy * w + xx].toInt() and 255
                out[y * w + x] = s / ((y1 - y0 + 1) * (x1 - x0 + 1))
            }
        }
        return out
    }

    private fun otsu(g: IntArray): Int {
        val hist = IntArray(256)
        for (v in g) hist[v.coerceIn(0, 255)]++
        val total = g.size
        var sumAll = 0L
        for (i in 0..255) sumAll += i.toLong() * hist[i]
        var sumB = 0L
        var wB = 0
        var best = -1.0
        var threshold = 128
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

    private fun largestComponent(mask: BooleanArray, w: Int, h: Int): IntArray? {
        val n = w * h
        val visited = BooleanArray(n)
        val stack = IntArray(n)
        val buffer = IntArray(n)
        var best: IntArray? = null
        for (start in 0 until n) {
            if (!mask[start] || visited[start]) continue
            var sp = 0
            var count = 0
            stack[sp++] = start
            visited[start] = true
            while (sp > 0) {
                val p = stack[--sp]
                buffer[count++] = p
                val x = p % w
                val y = p / w
                if (x > 0 && mask[p - 1] && !visited[p - 1]) { visited[p - 1] = true; stack[sp++] = p - 1 }
                if (x < w - 1 && mask[p + 1] && !visited[p + 1]) { visited[p + 1] = true; stack[sp++] = p + 1 }
                if (y > 0 && mask[p - w] && !visited[p - w]) { visited[p - w] = true; stack[sp++] = p - w }
                if (y < h - 1 && mask[p + w] && !visited[p + w]) { visited[p + w] = true; stack[sp++] = p + w }
            }
            if (best == null || count > best.size) best = buffer.copyOf(count)
        }
        return best
    }

    /** Выпуклая оболочка контура области, затем отбрасываем «лишние» вершины, пока не останется четыре. */
    private class Fit(val quad: List<Pt>, val boundary: List<Pt>)

    private fun fitQuad(comp: IntArray, w: Int, h: Int): Fit? {
        val n = w * h
        val inside = BooleanArray(n)
        for (p in comp) inside[p] = true
        val boundary = ArrayList<Pt>()
        for (p in comp) {
            val x = p % w
            val y = p / w
            val edge = x == 0 || y == 0 || x == w - 1 || y == h - 1 ||
                !inside[p - 1] || !inside[p + 1] || !inside[p - w] || !inside[p + w]
            if (edge) boundary.add(Pt(x.toFloat(), y.toFloat()))
        }
        val hull = convexHull(boundary)
        if (hull.size < 4) return null
        val poly = hull.toMutableList()
        while (poly.size > 4) {
            var bestIndex = 0
            var bestArea = Float.MAX_VALUE
            for (i in poly.indices) {
                val a = poly[(i + poly.size - 1) % poly.size]
                val b = poly[i]
                val c = poly[(i + 1) % poly.size]
                val area = abs((b.x - a.x) * (c.y - a.y) - (c.x - a.x) * (b.y - a.y)) / 2f
                if (area < bestArea) {
                    bestArea = area
                    bestIndex = i
                }
            }
            poly.removeAt(bestIndex)
        }
        return Fit(poly, boundary)
    }

    /**
     * Резкость границы: медианный перепад яркости поперёк контура (кроме участков на краю кадра).
     * У листа на столе перепад большой, у плавного градиента света на стене маленький.
     */
    private fun edgeStrength(g: IntArray, w: Int, h: Int, boundary: List<Pt>): Float {
        val values = ArrayList<Int>()
        for (p in boundary) {
            val x = p.x.toInt()
            val y = p.y.toInt()
            if (x < 3 || y < 3 || x > w - 4 || y > h - 4) continue
            val gx = abs(g[y * w + x + 2] - g[y * w + x - 2])
            val gy = abs(g[(y + 2) * w + x] - g[(y - 2) * w + x])
            values.add(maxOf(gx, gy))
        }
        if (values.size < 20) return 0f
        values.sort()
        return values[values.size / 2].toFloat()
    }

    private fun convexHull(points: List<Pt>): List<Pt> {
        if (points.size < 3) return points
        val sorted = points.sortedWith(compareBy({ it.x }, { it.y }))
        fun cross(o: Pt, a: Pt, b: Pt) = (a.x - o.x) * (b.y - o.y) - (a.y - o.y) * (b.x - o.x)
        val lower = ArrayList<Pt>()
        for (p in sorted) {
            while (lower.size >= 2 && cross(lower[lower.size - 2], lower[lower.size - 1], p) <= 0f) lower.removeAt(lower.size - 1)
            lower.add(p)
        }
        val upper = ArrayList<Pt>()
        for (p in sorted.asReversed()) {
            while (upper.size >= 2 && cross(upper[upper.size - 2], upper[upper.size - 1], p) <= 0f) upper.removeAt(upper.size - 1)
            upper.add(p)
        }
        lower.removeAt(lower.size - 1)
        upper.removeAt(upper.size - 1)
        return lower + upper
    }

    /**
     * Порядок углов: левый-верхний, правый-верхний, правый-нижний, левый-нижний.
     * Углы идут по часовой стрелке, первым берётся тот, от которого верхняя сторона
     * ближе всего к горизонтали (так страница не «переворачивается» при наклоне камеры).
     */
    fun order(quad: List<Pt>): List<Pt> {
        val cx = quad.sumOf { it.x.toDouble() }.toFloat() / quad.size
        val cy = quad.sumOf { it.y.toDouble() }.toFloat() / quad.size
        val sorted = quad.sortedBy { atan2(it.y - cy, it.x - cx) }
        var start = 0
        var smallest = Float.MAX_VALUE
        for (i in sorted.indices) {
            val a = sorted[i]
            val b = sorted[(i + 1) % 4]
            val angle = abs(atan2(b.y - a.y, b.x - a.x))
            if (angle < smallest) {
                smallest = angle
                start = i
            }
        }
        return List(4) { sorted[(start + it) % 4] }
    }

    private fun polygonArea(p: List<Pt>): Float {
        var s = 0f
        for (i in p.indices) {
            val a = p[i]
            val b = p[(i + 1) % p.size]
            s += a.x * b.y - b.x * a.y
        }
        return abs(s) / 2f
    }

    private fun isConvex(p: List<Pt>): Boolean {
        var sign = 0
        for (i in p.indices) {
            val a = p[i]
            val b = p[(i + 1) % p.size]
            val c = p[(i + 2) % p.size]
            val cross = (b.x - a.x) * (c.y - b.y) - (b.y - a.y) * (c.x - b.x)
            val s = if (cross > 0f) 1 else if (cross < 0f) -1 else 0
            if (s != 0) {
                if (sign == 0) sign = s else if (s != sign) return false
            }
        }
        return true
    }
}
