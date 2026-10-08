package com.example.pdfscanner.processing

import android.content.Context
import android.graphics.Bitmap
import com.googlecode.tesseract.android.TessBaseAPI
import java.io.File

/** Распознавание текста на устройстве (Tesseract). Модели лежат в assets/tessdata. */
class OcrEngine(private val context: Context) {

    class Word(val text: String, val left: Int, val top: Int, val right: Int, val bottom: Int)
    class PageText(val text: String, val words: List<Word>)

    private var api: TessBaseAPI? = null

    /** languages: коды через «+», например "rus+eng". false, если модели недоступны. */
    fun open(languages: String): Boolean {
        return try {
            val root = File(context.filesDir, "tesseract")
            val dir = File(root, "tessdata").apply { mkdirs() }
            for (lang in languages.split("+").filter { it.isNotBlank() }) {
                val target = File(dir, "$lang.traineddata")
                if (!target.exists() || target.length() == 0L) {
                    val tmp = File(dir, "$lang.tmp")
                    context.assets.open("tessdata/$lang.traineddata").use { input ->
                        tmp.outputStream().use { input.copyTo(it) }
                    }
                    tmp.renameTo(target)
                }
            }
            val engine = TessBaseAPI()
            if (!engine.init(root.absolutePath, languages)) {
                engine.recycle()
                return false
            }
            engine.setPageSegMode(TessBaseAPI.PageSegMode.PSM_AUTO)
            api = engine
            true
        } catch (e: Exception) {
            false
        }
    }

    fun recognize(bitmap: Bitmap): PageText {
        val engine = api ?: return PageText("", emptyList())
        engine.setImage(bitmap)
        val text = engine.getUTF8Text().orEmpty()
        val words = ArrayList<Word>()
        val iter = engine.getResultIterator()
        if (iter != null) {
            try {
                val level = TessBaseAPI.PageIteratorLevel.RIL_WORD
                iter.begin()
                do {
                    val word = iter.getUTF8Text(level)
                    val rect = iter.getBoundingRect(level)
                    if (!word.isNullOrBlank() && iter.confidence(level) >= 30f && rect != null) {
                        words.add(Word(word.trim(), rect.left, rect.top, rect.right, rect.bottom))
                    }
                } while (iter.next(level))
            } finally {
                iter.delete()
            }
        }
        return PageText(text.trim(), words)
    }

    fun close() {
        api?.recycle()
        api = null
    }
}
