package com.example.pdfscanner.ui

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.RadioButton
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import com.example.pdfscanner.processing.FilterMode
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.pdfscanner.MainViewModel
import com.example.pdfscanner.data.DocumentEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Человекочитаемое имя папки, выбранной через системный выбор. */
internal fun folderLabel(context: Context, uri: String): String =
    runCatching { DocumentFile.fromTreeUri(context, Uri.parse(uri))?.name }.getOrNull()
        ?: "Выбранная папка"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DocumentScreen(id: String, vm: MainViewModel, onBack: () -> Unit, onEditPages: (String) -> Unit) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val doc by remember(id) { vm.document(id) }.collectAsStateWithLifecycle(initialValue = null)
    val folder by vm.exportFolder.collectAsStateWithLifecycle()
    val progress by vm.progress.collectAsStateWithLifecycle()
    val ocrDefault by vm.ocrEnabled.collectAsStateWithLifecycle()
    var showRename by remember { mutableStateOf(false) }
    var showDelete by remember { mutableStateOf(false) }
    var showExport by remember { mutableStateOf(false) }
    var showText by remember { mutableStateOf(false) }
    var showReprocess by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    val current = doc
    val running = progress

    LaunchedEffect(Unit) {
        vm.notices.collect { message -> snackbar.showSnackbar(message) }
    }

    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            vm.setExportFolder(uri)
            showExport = true
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(current?.name.orEmpty(), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад")
                    }
                },
                actions = {
                    if (current != null) {
                        IconButton(onClick = { showRename = true }) {
                            Icon(Icons.Default.Edit, contentDescription = "Переименовать")
                        }
                        IconButton(onClick = {
                            scope.launch {
                                runCatching {
                                    val file = vm.prepareShareFile(current)
                                    val uri = FileProvider.getUriForFile(
                                        context, "${context.packageName}.fileprovider", file,
                                    )
                                    val intent = Intent(Intent.ACTION_SEND).apply {
                                        type = "application/pdf"
                                        putExtra(Intent.EXTRA_STREAM, uri)
                                        putExtra(Intent.EXTRA_SUBJECT, current.name)
                                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                    }
                                    context.startActivity(Intent.createChooser(intent, null))
                                }
                            }
                        }) {
                            Icon(Icons.Default.Share, contentDescription = "Поделиться")
                        }
                        Box {
                            IconButton(onClick = { menu = true }) {
                                Icon(Icons.Default.MoreVert, contentDescription = "Ещё")
                            }
                            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                if (!current.ocrText.isNullOrBlank()) {
                                    DropdownMenuItem(
                                        text = { Text("Распознанный текст") },
                                        onClick = {
                                            menu = false
                                            showText = true
                                        },
                                    )
                                }
                                if (current.origDir != null) {
                                    DropdownMenuItem(
                                        text = { Text("Страницы: порядок, повороты, добавление") },
                                        onClick = {
                                            menu = false
                                            onEditPages(current.id)
                                        },
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Обработка и распознавание") },
                                        onClick = {
                                            menu = false
                                            showReprocess = true
                                        },
                                    )
                                }
                                DropdownMenuItem(
                                    text = { Text("Удалить") },
                                    onClick = {
                                        menu = false
                                        showDelete = true
                                    },
                                )
                            }
                        }
                    }
                },
            )
        },
        floatingActionButton = {
            if (current != null) {
                ExtendedFloatingActionButton(
                    onClick = {
                        if (folder == null) folderPicker.launch(null) else showExport = true
                    },
                ) { Text("Сохранить в папку") }
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
            if (current == null) {
                CircularProgressIndicator()
            } else {
                PdfViewer(current.pdfPath, current.sizeBytes)
            }
            if (running != null) {
                ProgressBanner(running, Modifier.align(Alignment.TopCenter))
            }
        }
    }

    if (showRename && current != null) {
        var text by remember { mutableStateOf(current.name) }
        AlertDialog(
            onDismissRequest = { showRename = false },
            title = { Text("Название документа") },
            text = {
                OutlinedTextField(value = text, onValueChange = { text = it }, singleLine = true)
            },
            confirmButton = {
                TextButton(
                    enabled = text.isNotBlank(),
                    onClick = {
                        vm.rename(current.id, text)
                        showRename = false
                    },
                ) { Text("Сохранить") }
            },
            dismissButton = { TextButton(onClick = { showRename = false }) { Text("Отмена") } },
        )
    }

    if (showExport && current != null) {
        var fileName by remember { mutableStateOf(current.name) }
        val label = remember(folder) { folder?.let { folderLabel(context, it) } ?: "не выбрана" }
        AlertDialog(
            onDismissRequest = { showExport = false },
            title = { Text("Сохранить в папку") },
            text = {
                Column {
                    OutlinedTextField(
                        value = fileName,
                        onValueChange = { fileName = it },
                        label = { Text("Имя файла") },
                        suffix = { Text(".pdf") },
                        singleLine = true,
                    )
                    Text(
                        "Папка: $label",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 12.dp),
                    )
                    TextButton(onClick = { folderPicker.launch(null) }) { Text("Выбрать другую папку") }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = fileName.isNotBlank() && folder != null,
                    onClick = {
                        showExport = false
                        scope.launch { snackbar.showSnackbar(vm.export(current, fileName)) }
                    },
                ) { Text("Сохранить") }
            },
            dismissButton = { TextButton(onClick = { showExport = false }) { Text("Отмена") } },
        )
    }

    if (showText && current != null) {
        val fullText = current.ocrText.orEmpty()
        AlertDialog(
            onDismissRequest = { showText = false },
            title = { Text("Распознанный текст") },
            text = {
                SelectionContainer {
                    Text(
                        fullText,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()),
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    clipboard.setText(AnnotatedString(fullText))
                    showText = false
                    scope.launch { snackbar.showSnackbar("Текст скопирован") }
                }) { Text("Копировать всё") }
            },
            dismissButton = { TextButton(onClick = { showText = false }) { Text("Закрыть") } },
        )
    }

    if (showReprocess && current != null) {
        var mode by remember {
            mutableStateOf(
                runCatching { FilterMode.valueOf(current.filterMode ?: "DOCUMENT") }
                    .getOrDefault(FilterMode.DOCUMENT),
            )
        }
        var ocr by remember { mutableStateOf(current.hasText || ocrDefault) }
        AlertDialog(
            onDismissRequest = { showReprocess = false },
            title = { Text("Обработка и распознавание") },
            text = {
                Column {
                    FilterMode.values().forEach { m ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { mode = m }
                                .padding(vertical = 6.dp),
                        ) {
                            RadioButton(selected = mode == m, onClick = null)
                            Column(Modifier.padding(start = 12.dp)) {
                                Text(m.title(), style = MaterialTheme.typography.bodyLarge)
                                Text(
                                    m.subtitle(),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { ocr = !ocr }
                            .padding(top = 8.dp),
                    ) {
                        Checkbox(checked = ocr, onCheckedChange = null)
                        Text("Распознать текст", modifier = Modifier.padding(start = 12.dp))
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    showReprocess = false
                    vm.reprocess(current, mode, ocr)
                }) { Text("Применить") }
            },
            dismissButton = { TextButton(onClick = { showReprocess = false }) { Text("Отмена") } },
        )
    }

    if (showDelete && current != null) {
        AlertDialog(
            onDismissRequest = { showDelete = false },
            title = { Text("Удалить документ?") },
            text = { Text("«${current.name}» будет удалён без возможности восстановления.") },
            confirmButton = {
                TextButton(onClick = {
                    showDelete = false
                    deleteAndClose(vm, current, onBack)
                }) { Text("Удалить") }
            },
            dismissButton = { TextButton(onClick = { showDelete = false }) { Text("Отмена") } },
        )
    }
}

private fun deleteAndClose(vm: MainViewModel, doc: DocumentEntity, onBack: () -> Unit) {
    onBack()
    vm.delete(doc)
    vm.purge(doc)
}

/** Обёртка над PdfRenderer: он не потокобезопасен и открывает одну страницу за раз. */
private class PdfDoc(path: String) : AutoCloseable {
    private val pfd = ParcelFileDescriptor.open(File(path), ParcelFileDescriptor.MODE_READ_ONLY)
    private val renderer = PdfRenderer(pfd)
    private var closed = false
    val pageCount: Int = renderer.pageCount

    @Synchronized
    fun render(index: Int, widthPx: Int): Bitmap? {
        if (closed) return null
        return try {
            renderer.openPage(index).use { page ->
                val height = (widthPx.toFloat() * page.height / page.width).toInt().coerceAtLeast(1)
                val bitmap = Bitmap.createBitmap(widthPx, height, Bitmap.Config.ARGB_8888)
                bitmap.eraseColor(AndroidColor.WHITE)
                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                bitmap
            }
        } catch (e: Exception) {
            null
        }
    }

    @Synchronized
    override fun close() {
        if (!closed) {
            closed = true
            renderer.close()
            pfd.close()
        }
    }
}

@Composable
private fun PdfViewer(path: String, version: Long) {
    val pdf = remember(path, version) { runCatching { PdfDoc(path) }.getOrNull() }
    DisposableEffect(pdf) { onDispose { pdf?.close() } }

    if (pdf == null) {
        Text(
            "Не удалось открыть файл. Возможно, он повреждён.",
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(32.dp),
        )
        return
    }

    val density = LocalDensity.current
    val widthPx = with(density) { (LocalConfiguration.current.screenWidthDp.dp - 32.dp).roundToPx() }

    LazyColumn(
        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceContainerLow),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 96.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items((0 until pdf.pageCount).toList()) { index ->
            PdfPage(pdf, index, widthPx)
        }
    }
}

@Composable
private fun PdfPage(pdf: PdfDoc, index: Int, widthPx: Int) {
    val bitmap by produceState<ImageBitmap?>(null, pdf, index, widthPx) {
        value = withContext(Dispatchers.IO) { pdf.render(index, widthPx)?.asImageBitmap() }
    }
    Card(
        shape = RoundedCornerShape(6.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(defaultElevation = 3.dp),
    ) {
        val image = bitmap
        if (image != null) {
            Image(
                bitmap = image,
                contentDescription = "Страница ${index + 1}",
                contentScale = ContentScale.FillWidth,
                modifier = Modifier.fillMaxWidth(),
            )
        } else {
            Box(Modifier.fillMaxWidth().aspectRatio(0.707f), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        }
    }
}
