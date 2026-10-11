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

    fun compose(pages: List<Page>, out: File, withText: Boolean, preset: ScanPreset = ScanPreset.DOCUMENT) {
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
                write(pages, out, text, subset, preset)
                return
            } catch (e: Exception) {
                last = e
                out.delete()
            }
        }
        throw last ?: IllegalStateException("Не удалось собрать PDF")
    }

    private fun write(pages: List<Page>, out: File, withText: Boolean, subset: Boolean, preset: ScanPreset) {
        PDFBoxResourceLoader.init(context)
        val doc = PDDocument(MemoryUsageSetting.setupTempFileOnly())
        try {
            val font: PDFont? = if (withText) {
                context.assets.open("fonts/PTSans-Regular.ttf").use { PDType0Font.load(doc, it, subset) }
            } else {
                null
            }
            val glyphs = HashMap<Int, Boolean>()
            if (preset.sizeMm != null && preset.perSheet > 1) {
                // Карта, паспорт, визитка: обе стороны на одном листе A4 в натуральную величину.
                for (group in pages.chunked(preset.perSheet)) addSheet(doc, group, preset, font, glyphs)
            } else {
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
                            drawInvisibleText(cs, font, glyphs, text, 0f, 0f, pageWidth / page.width, pageHeight)
                        }
                    }
                }
            }
            doc.save(out)
        } finally {
            doc.close()
        }
    }

    /** Лист A4 с одной или двумя картинками в натуральную величину, по центру. */
    private fun addSheet(
        doc: PDDocument,
        group: List<Page>,
        preset: ScanPreset,
        font: PDFont?,
        glyphs: MutableMap<Int, Boolean>,
    ) {
        val sheetWidth = 595.28f
        val sheetHeight = 841.89f
        val (longMm, shortMm) = preset.sizeMm ?: return
        val toPt = 72f / 25.4f
        val sizes = group.map { p ->
            val landscape = p.width >= p.height
            val w = (if (landscape) longMm else shortMm) * toPt
            val h = (if (landscape) shortMm else longMm) * toPt
            w to h
        }
        val gap = 28f
        val total = sizes.sumOf { it.second.toDouble() }.toFloat() + gap * (group.size - 1)
        var top = if (group.size > 1) (sheetHeight - total) / 2f else 70f
        val pdPage = PDPage(PDRectangle(sheetWidth, sheetHeight))
        doc.addPage(pdPage)
        PDPageContentStream(doc, pdPage).use { cs ->
            group.forEachIndexed { i, p ->
                val (w, h) = sizes[i]
                val x = (sheetWidth - w) / 2f
                val image = p.jpeg.inputStream().use { JPEGFactory.createFromStream(doc, it) }
                cs.drawImage(image, x, sheetHeight - top - h, w, h)
                val text = p.text
                if (font != null && text != null && text.words.isNotEmpty()) {
                    drawInvisibleText(cs, font, glyphs, text, x, top, w / p.width, sheetHeight)
                }
                top += h + gap
            }
        }
    }

    /** originX и originTop: положение левого верхнего угла картинки на странице (в пунктах, от верха). */
    private fun drawInvisibleText(
        cs: PDPageContentStream,
        font: PDFont,
        glyphs: MutableMap<Int, Boolean>,
        text: OcrEngine.PageText,
        originX: Float,
        originTop: Float,
        scale: Float,
        pageHeight: Float,
    ) {
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
            val x = originX + word.left * scale
            val y = pageHeight - (originTop + word.bottom * scale) + heightPt * 0.2f
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
