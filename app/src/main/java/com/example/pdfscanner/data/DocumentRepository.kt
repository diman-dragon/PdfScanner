package com.example.pdfscanner.data

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.example.pdfscanner.processing.FilterMode
import com.example.pdfscanner.processing.ImageEnhancer
import com.example.pdfscanner.processing.OcrEngine
import com.example.pdfscanner.processing.PdfComposer
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.multipdf.PDFMergerUtility
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import kotlin.math.roundToInt

/** Состояние длительной операции для индикатора на экране. fraction = null: неопределённый прогресс. */
data class Progress(val text: String, val fraction: Float?)

class DocumentRepository(
    context: Context,
    private val dao: DocumentDao,
) {
    private val context: Context = context.applicationContext

    val documents = dao.observeAll()

    fun document(id: String) = dao.observe(id)

    private class Built(
        val thumb: File?,
        val pageCount: Int,
        val text: String,
        val hasText: Boolean,
    )

    /**
     * Основной путь: страницы от сканера (уже выровненные по перспективе) → улучшение
     * (тени, сгибы, баланс белого, контраст) → OCR → PDF с текстовым слоем.
     * Исходные страницы сохраняются, чтобы потом можно было сменить фильтр.
     */
    suspend fun importScan(
        pages: List<Uri>,
        filter: FilterMode,
        languages: String?,
        onProgress: (Progress) -> Unit,
    ): DocumentEntity {
        val id = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        val origDir = File(dir("orig"), id).apply { mkdirs() }
        val pdf = File(dir("pdfs"), "$id.pdf")
        try {
            withContext(Dispatchers.IO) {
                pages.forEachIndexed { i, uri -> copy(uri, File(origDir, "page_%03d.jpg".format(i))) }
            }
            val built = build(origDir, id, filter, languages, pdf, onProgress)
            val doc = DocumentEntity(
                id = id,
                name = "Скан " + SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault()).format(Date(now)),
                createdAt = now,
                pageCount = built.pageCount,
                sizeBytes = pdf.length(),
                pdfPath = pdf.absolutePath,
                thumbPath = built.thumb?.absolutePath,
                origDir = origDir.absolutePath,
                hasText = built.hasText,
                filterMode = filter.name,
                ocrText = built.text.ifBlank { null },
            )
            dao.insert(doc)
            return doc
        } catch (e: Throwable) {
            withContext(NonCancellable + Dispatchers.IO) {
                pdf.delete()
                origDir.deleteRecursively()
                File(dir("thumbs"), "$id.jpg").delete()
            }
            throw e
        }
    }

    /**
     * Повторная обработка существующего документа другим фильтром и/или OCR.
     * pagesDir: подготовленная папка с изменённым набором страниц (после редактирования);
     * при успехе она становится папкой исходных страниц документа.
     */
    suspend fun reprocess(
        doc: DocumentEntity,
        filter: FilterMode,
        languages: String?,
        onProgress: (Progress) -> Unit,
        pagesDir: File? = null,
    ): DocumentEntity {
        val origDir = doc.origDir?.let { File(it) }
        val sourceDir = pagesDir
            ?: origDir?.takeIf { it.isDirectory }
            ?: throw IllegalStateException("Исходные страницы этого документа недоступны")
        val tmp = File(dir("pdfs"), "${doc.id}.tmp")
        try {
            val built = build(sourceDir, doc.id, filter, languages, tmp, onProgress)
            val target = File(doc.pdfPath)
            withContext(Dispatchers.IO) {
                if (!tmp.renameTo(target)) {
                    tmp.copyTo(target, overwrite = true)
                    tmp.delete()
                }
                if (pagesDir != null && origDir != null) {
                    origDir.deleteRecursively()
                    if (!pagesDir.renameTo(origDir)) {
                        pagesDir.copyRecursively(origDir, overwrite = true)
                        pagesDir.deleteRecursively()
                    }
                }
            }
            val updated = doc.copy(
                pageCount = built.pageCount,
                sizeBytes = target.length(),
                thumbPath = built.thumb?.absolutePath ?: doc.thumbPath,
                hasText = built.hasText,
                filterMode = filter.name,
                ocrText = built.text.ifBlank { null },
            )
            dao.update(updated)
            return updated
        } catch (e: Throwable) {
            withContext(NonCancellable + Dispatchers.IO) {
                tmp.delete()
                pagesDir?.deleteRecursively()
            }
            throw e
        }
    }

    // ---- Черновик пакетного сканирования: страницы копятся, пока пользователь не нажмёт «Завершить» ----

    fun newDraftDir(): File = File(File(context.filesDir, "draft").apply { mkdirs() }, UUID.randomUUID().toString())
        .apply { mkdirs() }

    fun clearDrafts() {
        File(context.filesDir, "draft").deleteRecursively()
    }

    /** Копирует страницы сканера в черновик, возвращает общее число страниц в нём. */
    suspend fun addToDraft(draft: File, uris: List<Uri>): Int = withContext(Dispatchers.IO) {
        var n = draft.listFiles()?.size ?: 0
        for (uri in uris) {
            copy(uri, File(draft, "page_%03d.jpg".format(n)))
            n++
        }
        n
    }

    private suspend fun build(
        origDir: File,
        id: String,
        filter: FilterMode,
        languages: String?,
        pdfTarget: File,
        onProgress: (Progress) -> Unit,
    ): Built = withContext(Dispatchers.Default) {
        val originals = origDir.listFiles { f -> f.extension.equals("jpg", ignoreCase = true) }
            ?.sortedBy { it.name }
            .orEmpty()
        if (originals.isEmpty()) throw IllegalStateException("Нет страниц для обработки")

        val work = File(context.cacheDir, "work_$id").apply {
            deleteRecursively()
            mkdirs()
        }
        val ocr = languages?.let { lang -> OcrEngine(context).takeIf { it.open(lang) } }
        val thumbFile = File(dir("thumbs"), "$id.jpg")
        try {
            val pages = ArrayList<PdfComposer.Page>()
            val texts = ArrayList<String>()
            val n = originals.size
            originals.forEachIndexed { i, file ->
                ensureActive()
                onProgress(Progress("Страница ${i + 1} из $n: улучшение изображения", i.toFloat() / n))
                val src = ImageEnhancer.decode(file.path, MAX_SIDE)
                    ?: throw IOException("Не удалось прочитать страницу ${i + 1}")
                val bmp = ImageEnhancer.enhance(src, filter)
                if (bmp !== src) src.recycle()
                if (i == 0) saveThumb(bmp, thumbFile)

                var text: OcrEngine.PageText? = null
                if (ocr != null) {
                    onProgress(Progress("Страница ${i + 1} из $n: распознавание текста", (i + 0.5f) / n))
                    text = runCatching { ocr.recognize(bmp) }.getOrNull()
                }
                val jpg = File(work, "p$i.jpg")
                jpg.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 88, it) }
                pages.add(PdfComposer.Page(jpg, bmp.width, bmp.height, text))
                val pageText = text?.text
                if (!pageText.isNullOrBlank()) texts.add(pageText)
                bmp.recycle()
            }
            onProgress(Progress("Сборка PDF…", null))
            val withText = pages.any { it.text?.words?.isNotEmpty() == true }
            PdfComposer(context).compose(pages, pdfTarget, withText)
            Built(
                thumb = thumbFile.takeIf { it.exists() },
                pageCount = pages.size,
                text = texts.joinToString("\n\n"),
                hasText = withText,
            )
        } finally {
            ocr?.close()
            work.deleteRecursively()
        }
    }

    private fun saveThumb(bitmap: Bitmap, file: File) {
        val w = 360
        val h = (bitmap.height * w.toFloat() / bitmap.width).roundToInt().coerceAtLeast(1)
        val thumb = Bitmap.createScaledBitmap(bitmap, w, h, true)
        file.outputStream().use { thumb.compress(Bitmap.CompressFormat.JPEG, 85, it) }
        if (thumb !== bitmap) thumb.recycle()
    }

    /** Запасной путь: если сканер вернул только готовый PDF без страниц. */
    suspend fun import(pdfUri: Uri, firstPageUri: Uri?, pageCount: Int): DocumentEntity =
        withContext(Dispatchers.IO) {
            val id = UUID.randomUUID().toString()
            val now = System.currentTimeMillis()
            val pdfFile = File(dir("pdfs"), "$id.pdf")
            val thumbFile = firstPageUri?.let { uri ->
                File(dir("thumbs"), "$id.jpg").takeIf { f -> runCatching { copy(uri, f) }.isSuccess }
            }
            try {
                copy(pdfUri, pdfFile)
                val doc = DocumentEntity(
                    id = id,
                    name = "Скан " + SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault()).format(Date(now)),
                    createdAt = now,
                    pageCount = pageCount,
                    sizeBytes = pdfFile.length(),
                    pdfPath = pdfFile.absolutePath,
                    thumbPath = thumbFile?.absolutePath,
                )
                dao.insert(doc)
                doc
            } catch (e: Exception) {
                pdfFile.delete()
                thumbFile?.delete()
                throw e
            }
        }

    /** Склеивает документы в указанном порядке. Страницы и текстовые слои копируются без перекодирования. */
    suspend fun merge(sources: List<DocumentEntity>, name: String): DocumentEntity =
        withContext(Dispatchers.IO) {
            PDFBoxResourceLoader.init(context)
            val id = UUID.randomUUID().toString()
            val now = System.currentTimeMillis()
            val out = File(dir("pdfs"), "$id.pdf")
            var thumb: File? = null
            var mergedOrig: File? = null
            try {
                val merger = PDFMergerUtility()
                merger.destinationFileName = out.absolutePath
                sources.forEach { merger.addSource(File(it.pdfPath)) }
                merger.mergeDocuments(MemoryUsageSetting.setupTempFileOnly())

                sources.first().thumbPath?.let { File(it) }?.takeIf { it.exists() }?.let { src ->
                    thumb = src.copyTo(File(dir("thumbs"), "$id.jpg"), overwrite = true)
                }
                // Если у всех исходных документов есть оригиналы страниц, объединённый тоже можно редактировать.
                val sourceDirs = sources.map { s -> s.origDir?.let { File(it) } }
                if (sourceDirs.all { it != null && it.isDirectory }) {
                    val od = File(dir("orig"), id).apply { mkdirs() }
                    mergedOrig = od
                    var n = 0
                    for (d in sourceDirs) {
                        val files = d?.listFiles { f -> f.extension.equals("jpg", ignoreCase = true) }
                            ?.sortedBy { it.name }.orEmpty()
                        for (f in files) {
                            f.copyTo(File(od, "page_%03d.jpg".format(n)), overwrite = true)
                            n++
                        }
                    }
                }
                val mergedText = sources.mapNotNull { it.ocrText }.joinToString("\n\n")
                val doc = DocumentEntity(
                    id = id,
                    name = name.trim().ifEmpty { "Объединённый документ" },
                    createdAt = now,
                    pageCount = sources.sumOf { it.pageCount },
                    sizeBytes = out.length(),
                    pdfPath = out.absolutePath,
                    thumbPath = thumb?.absolutePath,
                    origDir = mergedOrig?.absolutePath,
                    hasText = sources.any { it.hasText },
                    filterMode = sources.first().filterMode,
                    ocrText = mergedText.ifBlank { null },
                )
                dao.insert(doc)
                doc
            } catch (e: Exception) {
                out.delete()
                thumb?.delete()
                mergedOrig?.deleteRecursively()
                throw e
            }
        }

    /** Сохраняет копию PDF в папку, выбранную пользователем (Storage Access Framework). */
    suspend fun exportToFolder(doc: DocumentEntity, tree: Uri, fileName: String): Result<String> =
        withContext(Dispatchers.IO + NonCancellable) {
            runCatching {
                val base = safeName(fileName).removeSuffix(".pdf").removeSuffix(".PDF").trim().ifEmpty { "scan" }
                val folder = DocumentFile.fromTreeUri(context, tree)?.takeIf { it.canWrite() }
                    ?: throw IllegalStateException("Нет доступа к папке. Выберите её заново в настройках.")
                // Если файл с таким именем есть, система сама добавит « (1)», ничего не перезапишется.
                val target = folder.createFile("application/pdf", base)
                    ?: throw IllegalStateException("Не удалось создать файл в выбранной папке.")
                try {
                    val output = context.contentResolver.openOutputStream(target.uri)
                        ?: throw IllegalStateException("Не удалось открыть файл для записи.")
                    output.use { dst -> File(doc.pdfPath).inputStream().use { it.copyTo(dst) } }
                } catch (e: Exception) {
                    target.delete()
                    throw e
                }
                target.name ?: "$base.pdf"
            }
        }

    suspend fun rename(id: String, name: String) = dao.rename(id, name.trim())

    /** Удаляет запись (файлы остаются, пока не истечёт время отмены). */
    suspend fun deleteRow(doc: DocumentEntity) = dao.delete(doc)

    suspend fun restore(doc: DocumentEntity) = dao.insert(doc)

    suspend fun deleteFiles(doc: DocumentEntity) = withContext(Dispatchers.IO) {
        File(doc.pdfPath).delete()
        doc.thumbPath?.let { File(it).delete() }
        doc.origDir?.let { File(it).deleteRecursively() }
        Unit
    }

    /** Копия PDF с читаемым именем для отправки через «Поделиться». */
    suspend fun prepareShareFile(doc: DocumentEntity): File = withContext(Dispatchers.IO) {
        val shareDir = File(context.cacheDir, "share").apply { mkdirs() }
        shareDir.listFiles()?.forEach { it.delete() }
        File(shareDir, safeName(doc.name).ifEmpty { "scan" } + ".pdf")
            .also { File(doc.pdfPath).copyTo(it, overwrite = true) }
    }

    private fun safeName(name: String) = name.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim()

    private fun dir(name: String) = File(context.filesDir, name).apply { mkdirs() }

    private fun copy(from: Uri, to: File) {
        val input = context.contentResolver.openInputStream(from)
            ?: throw IllegalStateException("Не удалось открыть файл сканера")
        input.use { src -> to.outputStream().use { dst -> src.copyTo(dst) } }
    }

    private companion object {
        /** Длинная сторона страницы при обработке: около 200 dpi для A4, хватает для чтения и OCR. */
        const val MAX_SIDE = 2400
    }
}
