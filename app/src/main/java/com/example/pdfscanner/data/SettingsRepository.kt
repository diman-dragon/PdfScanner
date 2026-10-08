package com.example.pdfscanner.data

import android.content.Context
import com.example.pdfscanner.processing.FilterMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class ThemeMode { SYSTEM, LIGHT, DARK }

class SettingsRepository(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("settings", Context.MODE_PRIVATE)

    private val _themeMode = MutableStateFlow(
        runCatching { ThemeMode.valueOf(prefs.getString(KEY_THEME, null) ?: "SYSTEM") }
            .getOrDefault(ThemeMode.SYSTEM),
    )
    val themeMode: StateFlow<ThemeMode> = _themeMode.asStateFlow()

    private val _dynamicColor = MutableStateFlow(prefs.getBoolean(KEY_DYNAMIC, true))
    val dynamicColor: StateFlow<Boolean> = _dynamicColor.asStateFlow()

    private val _exportFolder = MutableStateFlow(prefs.getString(KEY_FOLDER, null))
    val exportFolder: StateFlow<String?> = _exportFolder.asStateFlow()

    private val _filter = MutableStateFlow(
        runCatching { FilterMode.valueOf(prefs.getString(KEY_FILTER, null) ?: "DOCUMENT") }
            .getOrDefault(FilterMode.DOCUMENT),
    )
    val filter: StateFlow<FilterMode> = _filter.asStateFlow()

    private val _ocrEnabled = MutableStateFlow(prefs.getBoolean(KEY_OCR, true))
    val ocrEnabled: StateFlow<Boolean> = _ocrEnabled.asStateFlow()

    /** Коды языков через «+», например "rus+eng". */
    private val _ocrLangs = MutableStateFlow(prefs.getString(KEY_LANGS, null) ?: "rus+eng")
    val ocrLangs: StateFlow<String> = _ocrLangs.asStateFlow()

    /** Пауза до следующей страницы в пакетном режиме, секунды (по умолчанию 5). */
    private val _batchDelay = MutableStateFlow(prefs.getInt(KEY_BATCH, 5).coerceIn(1, 30))
    val batchDelay: StateFlow<Int> = _batchDelay.asStateFlow()

    fun setBatchDelay(seconds: Int) {
        val value = seconds.coerceIn(1, 30)
        prefs.edit().putInt(KEY_BATCH, value).apply()
        _batchDelay.value = value
    }

    fun setThemeMode(mode: ThemeMode) {
        prefs.edit().putString(KEY_THEME, mode.name).apply()
        _themeMode.value = mode
    }

    fun setDynamicColor(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_DYNAMIC, enabled).apply()
        _dynamicColor.value = enabled
    }

    fun setExportFolder(uri: String?) {
        prefs.edit().putString(KEY_FOLDER, uri).apply()
        _exportFolder.value = uri
    }

    fun setFilter(mode: FilterMode) {
        prefs.edit().putString(KEY_FILTER, mode.name).apply()
        _filter.value = mode
    }

    fun setOcrEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_OCR, enabled).apply()
        _ocrEnabled.value = enabled
    }

    fun setOcrLangs(langs: String) {
        prefs.edit().putString(KEY_LANGS, langs).apply()
        _ocrLangs.value = langs
    }

    private companion object {
        const val KEY_THEME = "theme"
        const val KEY_DYNAMIC = "dynamic_color"
        const val KEY_FOLDER = "export_folder"
        const val KEY_FILTER = "filter"
        const val KEY_OCR = "ocr_enabled"
        const val KEY_LANGS = "ocr_langs"
        const val KEY_BATCH = "batch_delay_s"
    }
}
