package com.example.pdfscanner

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.pdfscanner.data.AppDatabase
import com.example.pdfscanner.data.BorderMode
import com.example.pdfscanner.data.DocumentEntity
import com.example.pdfscanner.data.DocumentRepository
import com.example.pdfscanner.data.Progress
import com.example.pdfscanner.data.SettingsRepository
import com.example.pdfscanner.data.ThemeMode
import com.example.pdfscanner.processing.FilterMode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File

sealed interface UiEvent {
    data class Message(val text: String) : UiEvent
    data class Opened(val id: String) : UiEvent
}

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
    val borderMode: StateFlow<BorderMode> = settings.borderMode
    val autoCapture: StateFlow<Boolean> = settings.autoCapture

    /** Языки, для которых в приложение вложены модели (файлы .traineddata в assets/tessdata). */
    val availableOcrLanguages: List<String> = runCatching {
        app.assets.list("tessdata").orEmpty()
            .filter { it.endsWith(".traineddata") }
            .map { it.removeSuffix(".traineddata") }
            .sorted()
    }.getOrDefault(emptyList())

    private val _progress = MutableStateFlow<Progress?>(null)
    val progress: StateFlow<Progress?> = _progress.asStateFlow()

    /** События главного экрана (открыть документ, сообщение). */
    private val _events = Channel<UiEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    /** Уведомления экрана документа и редактора страниц. */
    private val _notices = Channel<String>(Channel.BUFFERED)
    val notices = _notices.receiveAsFlow()

    init {
        // Черновики съёмки не переживают перезапуск процесса, чистим остатки.
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

    // ---------------- Съёмка и импорт ----------------

    fun newDraftDir(): File = repo.newDraftDir()

    fun discardDraft(dir: File) {
        viewModelScope.launch(Dispatchers.IO) { dir.deleteRecursively() }
    }

    /**
     * Страницы из камеры (папка с page_*.jpg): создаём новый документ или, если задан appendId,
     * дописываем страницы в существующий.
     */
    fun importCaptured(dir: File, appendId: String?) {
        val files = dir.listFiles { f ->
            f.isFile && f.name.startsWith("page_") && f.extension.equals("jpg", ignoreCase = true)
        }?.sortedBy { it.name }.orEmpty()
        if (files.isEmpty()) {
            dir.deleteRecursively()
            return
        }
        viewModelScope.launch {
            _progress.value = Progress("Подготовка…", null)
            try {
                if (appendId == null) {
                    val doc = repo.importScan(
                        files.map { Uri.fromFile(it) },
                        settings.filter.value,
                        ocrLanguagesOrNull(),
                    ) { _progress.value = it }
                    _events.send(UiEvent.Opened(doc.id))
                } else {
                    val current = repo.document(appendId).first()
                        ?: throw IllegalStateException("Документ не найден")
                    val filter = runCatching { FilterMode.valueOf(current.filterMode.orEmpty()) }
                        .getOrDefault(settings.filter.value)
                    repo.appendPages(
                        current,
                        files,
                        filter,
                        ocrLanguagesOrNull(current.hasText || settings.ocrEnabled.value),
                    ) { _progress.value = it }
                    _notices.send("Страницы добавлены")
                }
            } catch (e: Throwable) {
                if (e is CancellationException) throw e
                val text = "Не удалось обработать страницы. Проверьте свободное место и попробуйте ещё раз."
                if (appendId == null) _events.send(UiEvent.Message(text)) else _notices.send(text)
            } finally {
                _progress.value = null
                dir.deleteRecursively()
            }
        }
    }

    /** Импорт готовых снимков из галереи: все выбранные картинки становятся страницами одного документа. */
    fun importUris(uris: List<Uri>) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            _progress.value = Progress("Подготовка…", null)
            try {
                val doc = repo.importScan(uris, settings.filter.value, ocrLanguagesOrNull()) { _progress.value = it }
                _events.send(UiEvent.Opened(doc.id))
            } catch (e: Throwable) {
                if (e is CancellationException) throw e
                _events.send(UiEvent.Message("Не удалось импортировать изображения."))
            } finally {
                _progress.value = null
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
    fun setBorderMode(mode: BorderMode) = settings.setBorderMode(mode)
    fun setAutoCapture(enabled: Boolean) = settings.setAutoCapture(enabled)
    fun setOcrLanguages(codes: Set<String>) {
        if (codes.isNotEmpty()) settings.setOcrLangs(codes.sorted().joinToString("+"))
    }
}
