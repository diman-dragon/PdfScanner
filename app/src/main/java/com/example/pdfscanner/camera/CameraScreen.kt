package com.example.pdfscanner.camera

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.AspectRatio
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.pdfscanner.MainViewModel
import com.example.pdfscanner.data.BorderMode
import com.example.pdfscanner.processing.ScanPreset
import kotlinx.coroutines.delay
import java.util.concurrent.Executors

/**
 * Собственная камера. Одиночный режим: навели, кадр сам снялся, проверили, «Готово» или «Удалить».
 * Пакетный: после каждой страницы пауза (по умолчанию 5 с), за которую переворачиваете лист,
 * затем камера снимает следующий кадр сама. appendId: добавить снятые страницы в существующий документ.
 */
@Composable
fun CameraScreen(vm: MainViewModel, batch: Boolean, appendId: String?, onClose: () -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var granted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED,
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    LaunchedEffect(Unit) {
        if (!granted) permissionLauncher.launch(Manifest.permission.CAMERA)
    }

    val session = remember {
        CaptureSession(
            context = context,
            dir = vm.newDraftDir(),
            batch = batch,
            borderMode = vm.borderMode.value,
            delaySeconds = vm.batchDelay.value,
            autoCapture = vm.autoCapture.value,
            preset = vm.preset.value,
        )
    }
    DisposableEffect(session) { onDispose { session.dispose() } }

    var showExit by remember { mutableStateOf(false) }
    var torch by remember { mutableStateOf(false) }
    var camera by remember { mutableStateOf<Camera?>(null) }

    fun finish() {
        vm.importCaptured(session.dir, appendId, session.preset)
        onClose()
    }

    fun discard() {
        vm.discardDraft(session.dir)
        onClose()
    }

    fun requestExit() {
        when {
            session.phase == CaptureSession.Phase.EDIT -> session.cancelEdit()
            session.pages.isNotEmpty() -> showExit = true
            else -> discard()
        }
    }
    BackHandler { requestExit() }

    // Пауза между страницами
    LaunchedEffect(session.phase, session.pages.size) {
        if (session.phase == CaptureSession.Phase.COUNTDOWN) {
            for (left in session.delaySeconds downTo 1) {
                session.countdown = left
                delay(1_000)
            }
            session.endCountdown()
        } else {
            session.countdown = null
        }
    }

    // Короткие сообщения
    LaunchedEffect(session.message) {
        if (session.message != null) {
            delay(2_500)
            session.message = null
        }
    }

    LaunchedEffect(torch, camera) {
        camera?.cameraControl?.enableTorch(torch)
    }

    val previewView = remember {
        PreviewView(context).apply {
            scaleType = PreviewView.ScaleType.FIT_CENTER
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        }
    }
    val analysisExecutor = remember { Executors.newSingleThreadExecutor() }
    DisposableEffect(Unit) { onDispose { analysisExecutor.shutdown() } }

    DisposableEffect(granted) {
        if (!granted) return@DisposableEffect onDispose { }
        val future = ProcessCameraProvider.getInstance(context)
        var provider: ProcessCameraProvider? = null
        future.addListener(
            {
                try {
                    val p = future.get()
                    provider = p
                    val preview = Preview.Builder()
                        .setTargetAspectRatio(AspectRatio.RATIO_4_3)
                        .build()
                        .also { it.setSurfaceProvider(previewView.surfaceProvider) }
                    val capture = ImageCapture.Builder()
                        .setTargetAspectRatio(AspectRatio.RATIO_4_3)
                        .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                        .build()
                    val analysis = ImageAnalysis.Builder()
                        .setTargetAspectRatio(AspectRatio.RATIO_4_3)
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build()
                    analysis.setAnalyzer(analysisExecutor) { image -> session.analyze(image) }
                    session.imageCapture = capture
                    p.unbindAll()
                    camera = p.bindToLifecycle(
                        lifecycleOwner,
                        CameraSelector.DEFAULT_BACK_CAMERA,
                        preview,
                        capture,
                        analysis,
                    )
                } catch (e: Exception) {
                    session.message = "Не удалось запустить камеру"
                }
            },
            ContextCompat.getMainExecutor(context),
        )
        onDispose {
            provider?.unbindAll()
            session.imageCapture = null
            camera = null
        }
    }

    Box(Modifier.fillMaxSize().background(Color.Black).systemBarsPadding()) {
        if (!granted) {
            PermissionCard(
                onAllow = { permissionLauncher.launch(Manifest.permission.CAMERA) },
                onClose = { discard() },
                modifier = Modifier.align(Alignment.Center),
            )
        } else {
            Box(Modifier.align(Alignment.Center).fillMaxWidth().aspectRatio(3f / 4f)) {
                AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
                Canvas(Modifier.fillMaxSize()) {
                    val quad = session.liveQuad
                    if (quad != null && session.phase == CaptureSession.Phase.AIMING) {
                        val color = lerp(Color.White, Color(0xFF4ADE80), session.progress)
                        val path = Path().apply {
                            moveTo(quad[0].x * size.width, quad[0].y * size.height)
                            for (i in 1 until 4) lineTo(quad[i].x * size.width, quad[i].y * size.height)
                            close()
                        }
                        drawPath(path, color.copy(alpha = 0.18f))
                        drawPath(path, color, style = Stroke(width = 3.dp.toPx()))
                    }
                }
            }

            // Верхняя панель
            Column(Modifier.align(Alignment.TopCenter).fillMaxWidth()) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = { requestExit() }) {
                        Icon(Icons.Default.Close, contentDescription = "Закрыть", tint = Color.White)
                    }
                    Spacer(Modifier.weight(1f))
                    FilterChip(
                        selected = session.borderMode == BorderMode.AUTO,
                        onClick = {
                            session.changeBorderMode(BorderMode.AUTO)
                            vm.setBorderMode(BorderMode.AUTO)
                        },
                        label = { Text("Границы: авто") },
                    )
                    Spacer(Modifier.width(8.dp))
                    FilterChip(
                        selected = session.borderMode == BorderMode.MANUAL,
                        onClick = {
                            session.changeBorderMode(BorderMode.MANUAL)
                            vm.setBorderMode(BorderMode.MANUAL)
                        },
                        label = { Text("Свои") },
                    )
                    if (batch) {
                        Spacer(Modifier.width(8.dp))
                        FilterChip(
                            selected = session.autoCapture,
                            onClick = {
                                val enabled = !session.autoCapture
                                session.changeAutoCapture(enabled)
                                vm.setAutoCapture(enabled)
                            },
                            label = { Text("Автоснимок") },
                        )
                    }
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = { torch = !torch }) {
                        Text(if (torch) "Свет: вкл" else "Свет", color = Color.White)
                    }
                }
                Row(
                    Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    ScanPreset.values().forEach { p ->
                        FilterChip(
                            selected = session.preset == p,
                            enabled = session.pages.isEmpty() && appendId == null,
                            onClick = {
                                session.changePreset(p)
                                vm.setPreset(p)
                            },
                            label = { Text(p.title) },
                        )
                    }
                }
                val status = statusText(session)
                if (status != null && session.phase != CaptureSession.Phase.EDIT) {
                    Surface(
                        color = Color(0x99000000),
                        shape = RoundedCornerShape(20.dp),
                        modifier = Modifier.align(Alignment.CenterHorizontally).padding(top = 4.dp),
                    ) {
                        Text(
                            status,
                            color = Color.White,
                            style = MaterialTheme.typography.bodyMedium,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        )
                    }
                    if (session.phase == CaptureSession.Phase.AIMING && session.autoCapture && session.progress > 0f) {
                        LinearProgressIndicator(
                            progress = { session.progress },
                            modifier = Modifier
                                .align(Alignment.CenterHorizontally)
                                .padding(top = 8.dp)
                                .width(160.dp),
                        )
                    }
                }
            }

            // Нижняя часть: кнопка съёмки или карточка проверки
            Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth()) {
                when (session.phase) {
                    CaptureSession.Phase.AIMING -> AimingControls(
                        session = session,
                        onFinish = { finish() },
                    )
                    CaptureSession.Phase.COUNTDOWN, CaptureSession.Phase.REVIEW -> ReviewCard(
                        session = session,
                        onFinish = { finish() },
                    )
                    CaptureSession.Phase.EDIT -> Unit
                }
            }

            session.message?.let { text ->
                Surface(
                    color = Color(0xCC000000),
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.align(Alignment.Center).padding(32.dp),
                ) {
                    Text(text, color = Color.White, modifier = Modifier.padding(16.dp), textAlign = TextAlign.Center)
                }
            }
            if (session.busy) {
                CircularProgressIndicator(Modifier.align(Alignment.Center))
            }

            session.edit?.let { request ->
                BorderEditor(
                    request = request,
                    isManualMode = session.borderMode == BorderMode.MANUAL,
                    onConfirm = { session.confirmEdit(it) },
                    onCancel = { session.cancelEdit() },
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }

    if (showExit) {
        AlertDialog(
            onDismissRequest = { showExit = false },
            title = { Text("Сохранить снятые страницы?") },
            text = { Text("Снято страниц: ${session.pages.size}") },
            confirmButton = {
                Row {
                    TextButton(onClick = {
                        showExit = false
                        discard()
                    }) { Text("Не сохранять") }
                    TextButton(onClick = {
                        showExit = false
                        finish()
                    }) { Text("Сохранить") }
                }
            },
            dismissButton = { TextButton(onClick = { showExit = false }) { Text("Продолжить") } },
        )
    }
}

private fun statusText(session: CaptureSession): String? = when (session.phase) {
    CaptureSession.Phase.AIMING -> when {
        session.borderMode == BorderMode.MANUAL && session.manualQuad != null ->
            if (session.autoCapture) "Совместите края с рамкой" else "Совместите края с рамкой и нажмите кнопку"
        session.borderMode == BorderMode.MANUAL ->
            if (session.autoCapture) "Держите камеру неподвижно, затем выставите границы" else "Нажмите кнопку, затем выставите границы"
        !session.autoCapture -> if (session.liveQuad == null) "Наведите на ${session.preset.target} и нажмите кнопку" else "Найдено. Нажмите кнопку"
        session.liveQuad == null -> "Наведите камеру на ${session.preset.target}"
        session.progress >= 1f -> "Снимаю…"
        else -> "Держите ровно…"
    }
    CaptureSession.Phase.COUNTDOWN -> session.countdown?.let { "Переверните страницу. Съёмка через $it с" }
    CaptureSession.Phase.REVIEW -> "Проверьте снимок"
    CaptureSession.Phase.EDIT -> null
}

@Composable
private fun AimingControls(session: CaptureSession, onFinish: () -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        val last = session.pages.lastOrNull()
        if (last != null) {
            Surface(
                color = Color(0xCC000000),
                shape = RoundedCornerShape(20.dp),
                modifier = Modifier.align(Alignment.CenterHorizontally),
            ) {
                Row(
                    Modifier.padding(start = 10.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Image(
                        bitmap = last.thumb,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.width(34.dp).height(46.dp).clip(RoundedCornerShape(6.dp)),
                    )
                    Text(
                        "Стр. ${session.pages.size}",
                        color = Color.White,
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.padding(horizontal = 10.dp),
                    )
                    TextButton(onClick = { session.editLast() }, enabled = !session.busy) {
                        Text("Границы", color = Color.White)
                    }
                    TextButton(onClick = { session.deleteLast() }, enabled = !session.busy) {
                        Text("Удалить", color = Color.White)
                    }
                }
            }
        }
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(64.dp)) {
                if (last != null) {
                    Box(
                        Modifier
                            .align(Alignment.Center)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary)
                            .padding(horizontal = 14.dp, vertical = 8.dp),
                    ) {
                        Text(
                            "${session.pages.size}",
                            color = MaterialTheme.colorScheme.onPrimary,
                            style = MaterialTheme.typography.titleMedium,
                        )
                    }
                }
            }
            Box(
                Modifier
                    .size(76.dp)
                    .border(4.dp, Color.White, CircleShape)
                    .padding(7.dp)
                    .clip(CircleShape)
                    .background(if (session.busy) Color.Gray else Color.White)
                    .clickable(enabled = !session.busy) { session.shoot() },
            )
            if (session.pages.isNotEmpty()) {
                FilledTonalButton(onClick = onFinish, enabled = !session.busy) { Text("Готово") }
            } else {
                Spacer(Modifier.width(64.dp))
            }
        }
    }
}

@Composable
private fun ReviewCard(session: CaptureSession, onFinish: () -> Unit) {
    val last = session.pages.lastOrNull() ?: return
    val counting = session.phase == CaptureSession.Phase.COUNTDOWN
    Surface(
        modifier = Modifier.fillMaxWidth().padding(12.dp),
        shape = RoundedCornerShape(28.dp),
        tonalElevation = 6.dp,
        shadowElevation = 12.dp,
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Image(
                    bitmap = last.thumb,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.width(64.dp).height(88.dp).clip(RoundedCornerShape(10.dp)),
                )
                Column(Modifier.weight(1f).padding(horizontal = 16.dp)) {
                    Text("Страниц: ${session.pages.size}", style = MaterialTheme.typography.titleMedium)
                    Text(
                        if (counting) "Переверните страницу, следующая снимется сама" else "Проверьте снимок",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                val left = session.countdown
                if (counting && left != null) {
                    Box(Modifier.size(56.dp), contentAlignment = Alignment.Center) {
                        val target = (left - 1f).coerceAtLeast(0f) / session.delaySeconds.coerceAtLeast(1)
                        val ring by animateFloatAsState(
                            targetValue = target,
                            animationSpec = tween(durationMillis = 1000, easing = LinearEasing),
                            label = "ring",
                        )
                        CircularProgressIndicator(
                            progress = { ring },
                            modifier = Modifier.fillMaxSize(),
                            strokeWidth = 4.dp,
                            trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                        )
                        Text("$left", style = MaterialTheme.typography.titleMedium)
                    }
                }
            }
            Row(
                Modifier.fillMaxWidth().padding(top = 14.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(onClick = { session.editLast() }, enabled = !session.busy, modifier = Modifier.weight(1f)) {
                    Text("Границы")
                }
                OutlinedButton(onClick = { session.deleteLast() }, enabled = !session.busy, modifier = Modifier.weight(1f)) {
                    Text("Удалить")
                }
            }
            Row(
                Modifier.fillMaxWidth().padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (counting) {
                    OutlinedButton(onClick = onFinish, enabled = !session.busy, modifier = Modifier.weight(1f)) {
                        Text("Завершить")
                    }
                    Button(onClick = { session.endCountdown() }, modifier = Modifier.weight(1f)) { Text("Сейчас") }
                } else {
                    val preset = session.preset
                    if (preset.perSheet > 1 && session.pages.size % preset.perSheet != 0) {
                        OutlinedButton(
                            onClick = { session.shootNextSide() },
                            enabled = !session.busy,
                            modifier = Modifier.weight(1f),
                        ) { Text("Снять оборот") }
                    }
                    Button(onClick = onFinish, enabled = !session.busy, modifier = Modifier.weight(1f)) { Text("Готово") }
                }
            }
        }
    }
}

@Composable
private fun PermissionCard(onAllow: () -> Unit, onClose: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier.padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            "Для съёмки нужен доступ к камере. Снимки остаются только на вашем устройстве.",
            color = Color.White,
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.bodyLarge,
        )
        Button(onClick = onAllow) { Text("Разрешить") }
        TextButton(onClick = onClose) { Text("Закрыть", color = Color.White) }
    }
}
