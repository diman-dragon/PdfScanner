package com.example.pdfscanner.ui

import android.graphics.BitmapFactory
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.pdfscanner.MainViewModel
import com.example.pdfscanner.data.DocumentEntity
import com.example.pdfscanner.data.PageEditSession
import com.example.pdfscanner.data.PageItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Редактор страниц: порядок, поворот, удаление, добавление новых страниц.
 * Все правки идут в рабочей копии и применяются кнопкой «Применить» (PDF пересобирается).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PageEditorScreen(id: String, vm: MainViewModel, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val progress by vm.progress.collectAsStateWithLifecycle()
    val session = remember(id) { PageEditSession(context, id) }
    val pages = remember { mutableStateListOf<PageItem>() }
    var doc by remember { mutableStateOf<DocumentEntity?>(null) }
    var loading by remember { mutableStateOf(true) }
    var dirty by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var moveTarget by remember { mutableStateOf<PageItem?>(null) }
    var confirmExit by remember { mutableStateOf(false) }

    DisposableEffect(session) { onDispose { session.close() } }

    LaunchedEffect(id) {
        val loaded = vm.document(id).first()
        val dir = loaded?.origDir?.let { File(it) }?.takeIf { it.isDirectory }
        if (loaded == null || dir == null) {
            snackbar.showSnackbar("Исходные страницы этого документа недоступны")
            onBack()
            return@LaunchedEffect
        }
        try {
            doc = loaded
            pages.addAll(session.open(dir))
        } catch (e: Exception) {
            snackbar.showSnackbar("Не удалось открыть страницы")
        }
        loading = false
    }

    val startScan = rememberScanStarter(
        pageLimit = 30,
        onResult = { result ->
            val uris = result.pages?.map { it.imageUri }.orEmpty()
            if (uris.isNotEmpty()) {
                scope.launch {
                    busy = true
                    try {
                        pages.addAll(session.addPages(uris))
                        dirty = true
                    } catch (e: Exception) {
                        snackbar.showSnackbar("Не удалось добавить страницы")
                    }
                    busy = false
                }
            }
        },
        onCancelled = { },
        onError = { message -> scope.launch { snackbar.showSnackbar(message) } },
    )

    fun move(from: Int, to: Int) {
        if (from == to || from !in pages.indices) return
        val target = to.coerceIn(0, pages.lastIndex)
        val item = pages.removeAt(from)
        pages.add(target, item)
        dirty = true
    }

    fun rotate(index: Int) {
        val item = pages.getOrNull(index) ?: return
        scope.launch {
            busy = true
            try {
                val updated = session.rotate(item)
                val now = pages.indexOfFirst { it.key == item.key }
                if (now >= 0) pages[now] = updated
                dirty = true
            } catch (e: Exception) {
                snackbar.showSnackbar("Не удалось повернуть страницу")
            }
            busy = false
        }
    }

    fun apply() {
        val current = doc ?: return
        if (pages.isEmpty()) return
        scope.launch {
            busy = true
            val staged = try {
                session.stage(pages.toList(), File(context.filesDir, "orig/${current.id}_new"))
            } catch (e: Exception) {
                null
            }
            if (staged == null) {
                snackbar.showSnackbar("Не удалось сохранить изменения")
                busy = false
                return@launch
            }
            vm.applyPages(current, staged)
            onBack()
        }
    }

    BackHandler(enabled = dirty) { confirmExit = true }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Страницы (${pages.size})") },
                navigationIcon = {
                    IconButton(onClick = { if (dirty) confirmExit = true else onBack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад")
                    }
                },
                actions = {
                    IconButton(onClick = startScan, enabled = !busy && !loading) {
                        Icon(Icons.Default.Add, contentDescription = "Добавить страницы")
                    }
                    TextButton(
                        onClick = { apply() },
                        enabled = dirty && pages.isNotEmpty() && !busy && progress == null,
                    ) { Text("Применить") }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            if (loading) {
                CircularProgressIndicator(Modifier.align(Alignment.Center))
            } else if (pages.isEmpty()) {
                Text(
                    "В документе не осталось страниц. Добавьте страницу кнопкой «+» или выйдите без сохранения.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.align(Alignment.Center).padding(32.dp),
                )
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(2),
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(pages, key = { it.key }) { item ->
                        val index = pages.indexOfFirst { it.key == item.key }
                        PageCell(
                            item = item,
                            number = index + 1,
                            canMoveBack = index > 0,
                            canMoveForward = index < pages.lastIndex,
                            enabled = !busy,
                            onNumberClick = { moveTarget = item },
                            onBack = { move(index, index - 1) },
                            onForward = { move(index, index + 1) },
                            onRotate = { rotate(index) },
                            onDelete = {
                                pages.removeAll { it.key == item.key }
                                dirty = true
                            },
                        )
                    }
                }
            }
            if (busy || progress != null) {
                val running = progress
                if (running != null) {
                    ProgressBanner(running, Modifier.align(Alignment.TopCenter))
                } else {
                    androidx.compose.material3.LinearProgressIndicator(
                        Modifier.fillMaxWidth().align(Alignment.TopCenter),
                    )
                }
            }
        }
    }

    moveTarget?.let { target ->
        val from = pages.indexOfFirst { it.key == target.key }
        var text by remember(target.key) { mutableStateOf((from + 1).toString()) }
        val position = text.toIntOrNull()
        AlertDialog(
            onDismissRequest = { moveTarget = null },
            title = { Text("Переместить страницу ${from + 1}") },
            text = {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it.filter { c -> c.isDigit() }.take(3) },
                    label = { Text("Новая позиция (1–${pages.size})") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
            },
            confirmButton = {
                TextButton(
                    enabled = position != null && position in 1..pages.size,
                    onClick = {
                        move(from, (position ?: 1) - 1)
                        moveTarget = null
                    },
                ) { Text("Переместить") }
            },
            dismissButton = { TextButton(onClick = { moveTarget = null }) { Text("Отмена") } },
        )
    }

    if (confirmExit) {
        AlertDialog(
            onDismissRequest = { confirmExit = false },
            title = { Text("Выйти без сохранения?") },
            text = { Text("Изменения страниц не будут применены.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmExit = false
                    onBack()
                }) { Text("Выйти") }
            },
            dismissButton = { TextButton(onClick = { confirmExit = false }) { Text("Остаться") } },
        )
    }
}

@Composable
private fun PageCell(
    item: PageItem,
    number: Int,
    canMoveBack: Boolean,
    canMoveForward: Boolean,
    enabled: Boolean,
    onNumberClick: () -> Unit,
    onBack: () -> Unit,
    onForward: () -> Unit,
    onRotate: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column {
            Box(
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(0.75f)
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest),
            ) {
                PageThumb(item, Modifier.fillMaxSize().padding(6.dp))
                Box(
                    Modifier
                        .align(Alignment.BottomStart)
                        .padding(8.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.primary)
                        .clickable(onClick = onNumberClick)
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                ) {
                    Text(
                        "$number",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                }
            }
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                IconButton(onClick = onBack, enabled = enabled && canMoveBack, modifier = Modifier.size(40.dp)) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Переместить раньше")
                }
                IconButton(onClick = onForward, enabled = enabled && canMoveForward, modifier = Modifier.size(40.dp)) {
                    Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = "Переместить позже")
                }
                IconButton(onClick = onRotate, enabled = enabled, modifier = Modifier.size(40.dp)) {
                    Icon(Icons.Default.Refresh, contentDescription = "Повернуть на 90°")
                }
                IconButton(onClick = onDelete, enabled = enabled, modifier = Modifier.size(40.dp)) {
                    Icon(Icons.Default.Delete, contentDescription = "Удалить страницу")
                }
            }
        }
    }
}

@Composable
private fun PageThumb(item: PageItem, modifier: Modifier) {
    val bitmap by produceState<ImageBitmap?>(null, item.file.path, item.rev) {
        value = withContext(Dispatchers.IO) { decodeThumb(item.file.path, 480) }
    }
    val image = bitmap
    if (image != null) {
        Image(
            bitmap = image,
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = modifier,
        )
    } else {
        Box(modifier.background(Color.Transparent), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(Modifier.size(24.dp))
        }
    }
}

private fun decodeThumb(path: String, targetWidth: Int): ImageBitmap? = try {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(path, bounds)
    var sample = 1
    while (bounds.outWidth / (sample * 2) >= targetWidth) sample *= 2
    BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })?.asImageBitmap()
} catch (e: Exception) {
    null
}
