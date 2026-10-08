package com.example.pdfscanner.processing

import android.content.Context
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.font.PDFont
import com.tom_roush.pdfbox.pdmodel.font.PDType0Font
import com.tom_roush.pdfbox.pdmodel.graphics.image.JPEGFactory
import com.tom_roush.pdfbox.pdmodel.graphics.state.RenderingMode
import com.tom_roush.pdfbox.util.Matrix
import java.io.File

/**
 * Собирает PDF из обработанных страниц. Если есть результат OCR, поверх картинки
 * кладётся невидимый текстовый слой: PDF можно искать и копировать из него текст.
 */
class PdfComposer(private val context: Context) {

    class Page(val jpeg: File, val width: Int, val height: Int, val text: OcrEngine.PageText?)

    fun compose(pages: List<Page>, out: File, withText: Boolean) {
        // Несколько запасных вариантов: сначала со шрифтом-подмножеством, затем с полным шрифтом,
        // в крайнем случае без текстового слоя, чтобы документ в любом случае сохранился.
        val attempts = if (withText) {
            listOf(true to true, true to false, false to true)
        } else {
            listOf(false to true)
        }
        var last: Exception? = null
        for ((text, subset) in attempts) {
            try {
                write(pages, out, text, subset)
                return
            } catch (e: Exception) {
                last = e
                out.delete()
            }
        }
        throw last ?: IllegalStateException("Не удалось собрать PDF")
    }

    private fun write(pages: List<Page>, out: File, withText: Boolean, subset: Boolean) {
        PDFBoxResourceLoader.init(context)
        val doc = PDDocument(MemoryUsageSetting.setupTempFileOnly())
        try {
            val font: PDFont? = if (withText) {
                context.assets.open("fonts/PTSans-Regular.ttf").use { PDType0Font.load(doc, it, subset) }
            } else {
                null
            }
            val glyphs = HashMap<Int, Boolean>()
            for (page in pages) {
                val pageWidth = 595f
                val pageHeight = pageWidth * page.height / page.width
                val pdPage = PDPage(PDRectangle(pageWidth, pageHeight))
                doc.addPage(pdPage)
                val image = page.jpeg.inputStream().use { JPEGFactory.createFromStream(doc, it) }
                PDPageContentStream(doc, pdPage).use { cs ->
                    cs.drawImage(image, 0f, 0f, pageWidth, pageHeight)
                    val text = page.text
                    if (font != null && text != null && text.words.isNotEmpty()) {
                        drawInvisibleText(cs, font, glyphs, text, page.width, pageWidth, pageHeight)
                    }
                }
            }
            doc.save(out)
        } finally {
            doc.close()
        }
    }

    private fun drawInvisibleText(
        cs: PDPageContentStream,
        font: PDFont,
        glyphs: MutableMap<Int, Boolean>,
        text: OcrEngine.PageText,
        imageWidth: Int,
        pageWidth: Float,
        pageHeight: Float,
    ) {
        val scale = pageWidth / imageWidth
        cs.beginText()
        cs.setRenderingMode(RenderingMode.NEITHER)
        for (word in text.words) {
            val clean = sanitize(word.text, font, glyphs)
            if (clean.isEmpty()) continue
            val heightPt = ((word.bottom - word.top) * scale).coerceIn(4f, 60f)
            val widthPt = (word.right - word.left) * scale
            val natural = font.getStringWidth(clean) / 1000f * heightPt
            if (natural <= 0f || widthPt <= 0f) continue
            val horizontal = (widthPt / natural).coerceIn(0.2f, 5f)
            val x = word.left * scale
            val y = pageHeight - word.bottom * scale + heightPt * 0.2f
            cs.setFont(font, heightPt)
            cs.setTextMatrix(Matrix(horizontal, 0f, 0f, 1f, x, y))
            cs.showText(clean)
        }
        cs.endText()
    }

    /** Оставляет только символы, которые есть в шрифте (иначе PDFBox выбросит исключение). */
    private fun sanitize(word: String, font: PDFont, cache: MutableMap<Int, Boolean>): String {
        val sb = StringBuilder()
        var i = 0
        while (i < word.length) {
            val cp = word.codePointAt(i)
            i += Character.charCount(cp)
            val ok = cache.getOrPut(cp) {
                runCatching { font.encode(String(Character.toChars(cp))) }.isSuccess
            }
            if (ok) sb.appendCodePoint(cp)
        }
        return sb.toString()
    }
}
