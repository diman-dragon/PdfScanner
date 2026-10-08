package com.example.pdfscanner.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.net.Uri
import com.example.pdfscanner.processing.ImageEnhancer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/** Страница в редакторе. key постоянный, rev растёт при изменении картинки (поворот), чтобы обновить превью. */
class PageItem(val key: Long, val file: File, val rev: Int)

/**
 * Рабочая копия страниц документа для редактора: все изменения идут в копии,
 * оригиналы документа не трогаются, пока пользователь не нажмёт «Применить».
 */
class PageEditSession(context: Context, docId: String) {
    private val appContext = context.applicationContext
    private val dir = File(appContext.cacheDir, "edit_$docId")
    private var nextKey = 1L

    suspend fun open(origDir: File): List<PageItem> = withContext(Dispatchers.IO) {
        dir.deleteRecursively()
        dir.mkdirs()
        val files = origDir.listFiles { f -> f.extension.equals("jpg", ignoreCase = true) }
            ?.sortedBy { it.name }.orEmpty()
        files.map { f ->
            val key = nextKey++
            PageItem(key, f.copyTo(File(dir, "p$key.jpg"), overwrite = true), 0)
        }
    }

    suspend fun addPages(uris: List<Uri>): List<PageItem> = withContext(Dispatchers.IO) {
        uris.map { uri ->
            val key = nextKey++
            val target = File(dir, "p$key.jpg")
            val input = appContext.contentResolver.openInputStream(uri)
                ?: throw IOException("Не удалось открыть страницу")
            input.use { src -> target.outputStream().use { dst -> src.copyTo(dst) } }
            PageItem(key, target, 0)
        }
    }

    /** Поворот на 90° по часовой стрелке. */
    suspend fun rotate(item: PageItem): PageItem = withContext(Dispatchers.IO) {
        val bitmap = ImageEnhancer.decode(item.file.path, 3000) ?: throw IOException("Не удалось прочитать страницу")
        val matrix = Matrix().apply { postRotate(90f) }
        val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        if (rotated !== bitmap) bitmap.recycle()
        item.file.outputStream().use { rotated.compress(Bitmap.CompressFormat.JPEG, 92, it) }
        rotated.recycle()
        PageItem(item.key, item.file, item.rev + 1)
    }

    /** Складывает страницы в указанном порядке в новую папку (потом она заменит оригиналы документа). */
    suspend fun stage(items: List<PageItem>, target: File): File = withContext(Dispatchers.IO) {
        target.deleteRecursively()
        target.mkdirs()
        items.forEachIndexed { i, item ->
            item.file.copyTo(File(target, "page_%03d.jpg".format(i)), overwrite = true)
        }
        target
    }

    fun close() {
        dir.deleteRecursively()
    }
}
