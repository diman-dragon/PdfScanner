package com.example.pdfscanner

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.pdfscanner.data.AppDatabase
import com.example.pdfscanner.data.DocumentEntity
import com.example.pdfscanner.data.DocumentRepository
import com.example.pdfscanner.data.Progress
import com.example.pdfscanner.data.SettingsRepository
import com.example.pdfscanner.data.ThemeMode
import com.example.pdfscanner.processing.FilterMode
import com.google.mlkit.vision.documentscanner.GmsDocumentScanningResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File

sealed interface UiEvent {
    data class Message(val text: String) : UiEvent
    data class Opened(val id: String) : UiEvent
    /** Пакетный режим: пора открыть сканер для следующей страницы. */
    data object LaunchScanner : UiEvent
}

/** Пакетное сканирование: pages — сколько страниц уже набрано, countdown — секунд до следующей (null: идёт сканирование). */
data class BatchState(val pages: Int, val countdown: Int?)

class MainViewModel(private val app: Application) : AndroidViewModel(app) {
    private val repo = DocumentRepository(app, AppDatabase.get(app).documentDao())
    private val settings = SettingsRepository(app)

    /** null = ещё загружается (чтобы не мигал экран «нет документов»). */
    val documents: StateFlow<List<DocumentEntity>?> =
        repo.documents.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val themeMode: StateFlow<ThemeMode> = settings.themeMode
    val dynamicColor: StateFlow<Boolean> = settings.dynamicColor
    val exportFolder: StateFlow<String?> = settings.exportFolder
    val filter: StateFlow<FilterMode> = settings.filter
    val ocrEnabled: StateFlow<Boolean> = settings.ocrEnabled
    val ocrLangs: StateFlow<String> = settings.ocrLangs
    val batchDelay: StateFlow<Int> = settings.batchDelay

    /** Языки, для которых в приложение вложены модели (файлы .traineddata в assets/tessdata). */
    val availableOcrLanguages: List<String> = runCatching {
        app.assets.list("tessdata").orEmpty()
            .filter { it.endsWith(".traineddata") }
            .map { it.removeSuffix(".traineddata") }
            .sorted()
    }.getOrDefault(emptyList())

    private val _progress = MutableStateFlow<Progress?>(null)
    val progress: StateFlow<Progress?> = _progress.asStateFlow()

    private val _batch = MutableStateFlow<BatchState?>(null)
    val batch: StateFlow<BatchState?> = _batch.asStateFlow()
    private var batchDir: File? = null
    private var countdownJob: Job? = null

    /** События главного экрана (открыть документ, запустить сканер). */
    private val _events = Channel<UiEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    /** Уведомления экрана документа и редактора страниц. */
    private val _notices = Channel<String>(Channel.BUFFERED)
    val notices = _notices.receiveAsFlow()

    init {
        // Черновики пакетного режима не переживают перезапуск процесса, чистим остатки.
        viewModelScope.launch(Dispatchers.IO) { repo.clearDrafts() }
    }

    fun document(id: String) = repo.document(id)

    /** Языки для OCR или null, если распознавание выключено или нет моделей. */
    private fun ocrLanguagesOrNull(enabled: Boolean = settings.ocrEnabled.value): String? {
        if (!enabled || availableOcrLanguages.isEmpty()) return null
        return settings.ocrLangs.value.split("+")
            .filter { it in availableOcrLanguages }
            .ifEmpty { listOf(availableOcrLanguages.first()) }
            .joinToString("+")
    }

    // ---------------- Сканирование ----------------

    fun onScanResult(result: GmsDocumentScanningResult?) {
        val pages = result?.pages?.map { it.imageUri }.orEmpty()
        val pdf = result?.pdf
        if (pages.isEmpty() && pdf == null) {
            _events.trySend(UiEvent.Message("Сканер не вернул страницы. Попробуйте ещё раз."))
            return
        }
        val delaySeconds = settings.batchDelay.value
        if (pages.isNotEmpty() && (delaySeconds > 0 || _batch.value != null)) {
            addToBatch(pages, delaySeconds.coerceAtLeast(1))
            return
        }
        viewModelScope.launch {
            _progress.value = Progress("Подготовка…", null)
            try {
                val doc = if (pages.isNotEmpty()) {
                    repo.importScan(pages, settings.filter.value, ocrLanguagesOrNull()) { _progress.value = it }
                } else {
                    repo.import(pdf!!.uri, null, pdf.pageCount)
                }
                _events.send(UiEvent.Opened(doc.id))
            } catch (e: Throwable) {
                if (e is CancellationException) throw e
                _events.send(UiEvent.Message("Не удалось обработать документ. Проверьте свободное место и попробуйте ещё раз."))
            } finally {
                _progress.value = null
            }
        }
    }

    /** Пользователь закрыл сканер без результата: в пакетном режиме это конец серии. */
    fun onScanCancelled() {
        if (_batch.value != null) finishBatch()
    }

    private fun addToBatch(pages: List<Uri>, delaySeconds: Int) {
        countdownJob?.cancel()
        viewModelScope.launch {
            try {
                val dir = batchDir ?: repo.newDraftDir().also { batchDir = it }
                val total = repo.addToDraft(dir, pages)
                _batch.value = BatchState(total, null)
                startCountdown(delaySeconds)
            } catch (e: Exception) {
                _events.send(UiEvent.Message("Не удалось сохранить страницы. Проверьте свободное место."))
                finishBatch()
            }
        }
    }

    private fun startCountdown(seconds: Int) {
        countdownJob?.cancel()
        countdownJob = viewModelScope.launch {
            for (left in seconds downTo 1) {
                _batch.value = _batch.value?.copy(countdown = left)
                delay(1_000)
            }
            _batch.value = _batch.value?.copy(countdown = null)
            _events.send(UiEvent.LaunchScanner)
        }
    }

    /** «Сканировать сейчас»: не ждать конца паузы. */
    fun nextNow() {
        if (_batch.value == null) return
        countdownJob?.cancel()
        _batch.value = _batch.value?.copy(countdown = null)
        _events.trySend(UiEvent.LaunchScanner)
    }

    /** Завершить серию: обработать накопленные страницы и сохранить один документ. */
    fun finishBatch() {
        countdownJob?.cancel()
        countdownJob = null
        val dir = batchDir
        batchDir = null
        _batch.value = null
        if (dir == null) return
        val files = dir.listFiles { f -> f.extension.equals("jpg", ignoreCase = true) }
            ?.sortedBy { it.name }.orEmpty()
        if (files.isEmpty()) {
            dir.deleteRecursively()
            return
        }
        viewModelScope.launch {
            _progress.value = Progress("Подготовка…", null)
            try {
                val doc = repo.importScan(
                    files.map { Uri.fromFile(it) },
                    settings.filter.value,
                    ocrLanguagesOrNull(),
                ) { _progress.value = it }
                _events.send(UiEvent.Opened(doc.id))
            } catch (e: Throwable) {
                if (e is CancellationException) throw e
                _events.send(UiEvent.Message("Не удалось обработать документ. Проверьте свободное место и попробуйте ещё раз."))
            } finally {
                _progress.value = null
                dir.deleteRecursively()
            }
        }
    }

    // ---------------- Документы ----------------

    fun reprocess(doc: DocumentEntity, filter: FilterMode, ocr: Boolean) {
        viewModelScope.launch {
            _progress.value = Progress("Подготовка…", null)
            try {
                repo.reprocess(doc, filter, ocrLanguagesOrNull(ocr), { _progress.value = it })
                _notices.send("Документ обновлён")
            } catch (e: Throwable) {
                if (e is CancellationException) throw e
                _notices.send("Не удалось обработать документ заново.")
            } finally {
                _progress.value = null
            }
        }
    }

    /** Применить правки страниц: stagedDir содержит страницы в новом порядке. */
    fun applyPages(doc: DocumentEntity, stagedDir: File) {
        viewModelScope.launch {
            _progress.value = Progress("Применяю изменения…", null)
            try {
                val filter = runCatching { FilterMode.valueOf(doc.filterMode.orEmpty()) }
                    .getOrDefault(settings.filter.value)
                repo.reprocess(
                    doc,
                    filter,
                    ocrLanguagesOrNull(doc.hasText || settings.ocrEnabled.value),
                    { _progress.value = it },
                    stagedDir,
                )
                _notices.send("Страницы обновлены")
            } catch (e: Throwable) {
                if (e is CancellationException) throw e
                _notices.send("Не удалось применить изменения.")
            } finally {
                _progress.value = null
            }
        }
    }

    fun merge(sources: List<DocumentEntity>, name: String, deleteSources: Boolean) {
        if (sources.size < 2) return
        viewModelScope.launch {
            _progress.value = Progress("Объединение документов…", null)
            try {
                val doc = repo.merge(sources, name)
                if (deleteSources) {
                    sources.forEach {
                        repo.deleteRow(it)
                        repo.deleteFiles(it)
                    }
                }
                _events.send(UiEvent.Opened(doc.id))
            } catch (e: Exception) {
                _events.send(UiEvent.Message("Не удалось объединить документы. Возможно, один из файлов повреждён."))
            } finally {
                _progress.value = null
            }
        }
    }

    fun rename(id: String, name: String) {
        if (name.isBlank()) return
        viewModelScope.launch { repo.rename(id, name) }
    }

    fun delete(doc: DocumentEntity) = viewModelScope.launch { repo.deleteRow(doc) }
    fun restore(doc: DocumentEntity) = viewModelScope.launch { repo.restore(doc) }
    fun purge(doc: DocumentEntity) = viewModelScope.launch { repo.deleteFiles(doc) }

    suspend fun prepareShareFile(doc: DocumentEntity): File = repo.prepareShareFile(doc)

    /** Возвращает текст для уведомления пользователя. */
    suspend fun export(doc: DocumentEntity, fileName: String): String {
        val folder = settings.exportFolder.value ?: return "Папка не выбрана."
        return repo.exportToFolder(doc, Uri.parse(folder), fileName).fold(
            onSuccess = { "Сохранено: $it" },
            onFailure = { it.message ?: "Не удалось сохранить файл." },
        )
    }

    fun setExportFolder(uri: Uri) {
        runCatching {
            app.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        }
        settings.setExportFolder(uri.toString())
    }

    fun setThemeMode(mode: ThemeMode) = settings.setThemeMode(mode)
    fun setDynamicColor(enabled: Boolean) = settings.setDynamicColor(enabled)
    fun setFilter(mode: FilterMode) = settings.setFilter(mode)
    fun setOcrEnabled(enabled: Boolean) = settings.setOcrEnabled(enabled)
    fun setBatchDelay(seconds: Int) = settings.setBatchDelay(seconds)
    fun setOcrLanguages(codes: Set<String>) {
        if (codes.isNotEmpty()) settings.setOcrLangs(codes.sorted().joinToString("+"))
    }
}
