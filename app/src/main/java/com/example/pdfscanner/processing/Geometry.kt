package com.example.pdfscanner.processing

import kotlin.math.hypot
import kotlin.math.max

/** Точка в нормализованных координатах кадра: x и y от 0 до 1. */
data class Pt(val x: Float, val y: Float)

/** Четырёхугольник документа: углы в порядке левый-верхний, правый-верхний, правый-нижний, левый-нижний. */
fun fullQuad(): List<Pt> = listOf(Pt(0f, 0f), Pt(1f, 0f), Pt(1f, 1f), Pt(0f, 1f))

fun insetQuad(margin: Float = 0.06f): List<Pt> = listOf(
    Pt(margin, margin),
    Pt(1f - margin, margin),
    Pt(1f - margin, 1f - margin),
    Pt(margin, 1f - margin),
)

/** Наибольшее смещение угла между двумя четырёхугольниками (в долях кадра). */
fun maxCornerDistance(a: List<Pt>, b: List<Pt>): Float {
    var m = 0f
    for (i in 0 until 4) m = max(m, hypot(a[i].x - b[i].x, a[i].y - b[i].y))
    return m
}
