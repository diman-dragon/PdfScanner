package com.example.pdfscanner.processing

/**
 * Тип сканируемого объекта. Определяет, как искать границы, в какой пропорции вырезать
 * страницу и как раскладывать её в PDF.
 *
 * sizeMm: физический размер (длинная и короткая сторона). Если задан, страница вырезается
 * строго в этой пропорции, а при perSheet = 2 две стороны (лицо и оборот) кладутся на один
 * лист A4 в натуральную величину.
 */
enum class ScanPreset(
    val title: String,
    val hint: String,
    val target: String,
    val sizeMm: Pair<Float, Float>?,
    val perSheet: Int,
    val forcedFilter: FilterMode?,
    val minFraction: Float,
    val docName: String,
) {
    DOCUMENT("Документ", "Любой лист, формат определяется по краям", "документ", null, 1, null, 0.12f, "Скан"),
    PASSPORT("Паспорт", "Страница 125×88 мм, обе на одном листе A4", "паспорт", 125f to 88f, 2, null, 0.08f, "Паспорт"),
    CARD("Карта", "ID, права, банковская карта 85,6×54 мм: лицо и оборот на одном листе", "карту", 85.6f to 54f, 2, null, 0.04f, "Карта"),
    BUSINESS_CARD("Визитка", "90×50 мм, обе стороны на одном листе", "визитку", 90f to 50f, 2, null, 0.03f, "Визитка"),
    RECEIPT("Чек", "Длинная узкая лента, чёрно-белый режим", "чек", null, 1, FilterMode.BLACK_WHITE, 0.05f, "Чек"),
    PHOTO("Фото", "Без обработки, цвета как на снимке", "фото", null, 1, FilterMode.ORIGINAL, 0.10f, "Фото"),
    ;

    /** Отношение длинной стороны к короткой (больше 1) или null, если размер свободный. */
    val aspect: Float? get() = sizeMm?.let { it.first / it.second }

    companion object {
        fun fromName(name: String?): ScanPreset =
            values().firstOrNull { it.name == name } ?: DOCUMENT
    }
}
