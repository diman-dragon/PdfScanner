package com.example.pdfscanner.camera

import android.graphics.BitmapFactory
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.example.pdfscanner.processing.Pt
import com.example.pdfscanner.processing.fullQuad
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/** Правка границ: четыре угла перетаскиваются пальцем поверх снятого кадра. */
@Composable
fun BorderEditor(
    request: EditRequest,
    isManualMode: Boolean,
    onConfirm: (List<Pt>) -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val bitmap by produceState<ImageBitmap?>(null, request.raw.path) {
        value = withContext(Dispatchers.IO) { decodeForEditor(request.raw.path, 1600) }
    }
    var points by remember(request.key) { mutableStateOf(request.initial) }
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }
    var active by remember { mutableIntStateOf(-1) }

    Column(modifier.background(Color.Black)) {
        Text(
            if (isManualMode && request.isNew) {
                "Выставьте границы один раз: дальше они применятся ко всем страницам"
            } else {
                "Перетащите углы так, чтобы рамка совпала с краями страницы"
            },
            style = MaterialTheme.typography.bodyMedium,
            color = Color.White,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
        )
        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .onSizeChanged { canvasSize = it }
                .pointerInput(bitmap) {
                    val image = bitmap
                    detectDragGestures(
                        onDragStart = { start ->
                            if (image != null) {
                                val rect = imageRect(canvasSize, image)
                                var best = -1
                                var bestDistance = 90f * density
                                points.forEachIndexed { i, p ->
                                    val d = hypot(
                                        rect[0] + p.x * rect[2] - start.x,
                                        rect[1] + p.y * rect[3] - start.y,
                                    )
                                    if (d < bestDistance) {
                                        bestDistance = d
                                        best = i
                                    }
                                }
                                active = best
                            }
                        },
                        onDragEnd = { active = -1 },
                        onDragCancel = { active = -1 },
                        onDrag = { change, drag ->
                            change.consume()
                            val index = active
                            if (index >= 0 && image != null) {
                                val rect = imageRect(canvasSize, image)
                                val p = points[index]
                                val moved = Pt(
                                    (p.x + drag.x / rect[2]).coerceIn(0f, 1f),
                                    (p.y + drag.y / rect[3]).coerceIn(0f, 1f),
                                )
                                points = points.mapIndexed { i, q -> if (i == index) moved else q }
                            }
                        },
                    )
                },
        ) {
            val image = bitmap
            Canvas(Modifier.fillMaxSize()) {
                if (image != null) {
                    val rect = imageRect(canvasSize, image)
                    drawImage(
                        image,
                        srcOffset = IntOffset.Zero,
                        srcSize = IntSize(image.width, image.height),
                        dstOffset = IntOffset(rect[0].toInt(), rect[1].toInt()),
                        dstSize = IntSize(rect[2].toInt(), rect[3].toInt()),
                    )
                    val screen = points.map { Offset(rect[0] + it.x * rect[2], rect[1] + it.y * rect[3]) }
                    val path = Path().apply {
                        moveTo(screen[0].x, screen[0].y)
                        for (i in 1 until 4) lineTo(screen[i].x, screen[i].y)
                        close()
                    }
                    drawPath(path, Color(0x3322D3EE))
                    drawPath(path, Color(0xFF22D3EE), style = Stroke(width = 3.dp.toPx()))
                    screen.forEachIndexed { i, o ->
                        drawCircle(Color.White, radius = 13.dp.toPx(), center = o)
                        drawCircle(
                            if (i == active) Color(0xFFFFC107) else Color(0xFF22D3EE),
                            radius = 9.dp.toPx(),
                            center = o,
                        )
                    }
                }
            }
        }
        Row(
            Modifier.fillMaxWidth().padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedButton(onClick = onCancel, modifier = Modifier.weight(1f)) { Text("Отмена") }
            TextButton(onClick = { points = fullQuad() }) { Text("Весь кадр", color = Color.White) }
            Button(onClick = { onConfirm(points) }, modifier = Modifier.weight(1f)) { Text("Готово") }
        }
    }
}

/** Прямоугольник картинки внутри холста: left, top, width, height. */
private fun imageRect(canvas: IntSize, image: ImageBitmap): FloatArray {
    val scale = min(canvas.width.toFloat() / image.width, canvas.height.toFloat() / image.height)
    val w = image.width * scale
    val h = image.height * scale
    return floatArrayOf((canvas.width - w) / 2f, (canvas.height - h) / 2f, max(w, 1f), max(h, 1f))
}

private fun decodeForEditor(path: String, targetSide: Int): ImageBitmap? = try {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(path, bounds)
    var sample = 1
    while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= targetSide) sample *= 2
    BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })?.asImageBitmap()
} catch (e: Exception) {
    null
}
