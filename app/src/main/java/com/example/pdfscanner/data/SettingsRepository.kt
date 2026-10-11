package com.example.pdfscanner.data

import android.content.Context
import com.example.pdfscanner.processing.FilterMode
import com.example.pdfscanner.processing.ScanPreset
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** LIGHT: светло-коричневая, AMOLED: чёрная. SYSTEM: днём светло-коричневая, ночью AMOLED. */
enum class ThemeMode { SYSTEM, LIGHT, AMOLED }

/** AUTO: границы документа ищутся на каждом кадре. MANUAL: задаются один раз на первом снимке и дальше применяются сами. */
enum class BorderMode { AUTO, MANUAL }

class SettingsRepository(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("settings", Context.MODE_PRIVATE)

    private val _themeMode = MutableStateFlow(
        when (prefs.getString(KEY_THEME, null)) {
            "LIGHT" -> ThemeMode.LIGHT
            "AMOLED", "DARK" -> ThemeMode.AMOLED
            else -> ThemeMode.SYSTEM
        },
    )
    val themeMode: StateFlow<ThemeMode> = _themeMode.asStateFlow()

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

    private val _borderMode = MutableStateFlow(
        runCatching { BorderMode.valueOf(prefs.getString(KEY_BORDER, null) ?: "AUTO") }
            .getOrDefault(BorderMode.AUTO),
    )
    val borderMode: StateFlow<BorderMode> = _borderMode.asStateFlow()

    /** Автоснимок в пакетном режиме (по умолчанию выключен: снимаем по кнопке). */
    private val _autoCapture = MutableStateFlow(prefs.getBoolean(KEY_AUTO_CAPTURE, false))
    val autoCapture: StateFlow<Boolean> = _autoCapture.asStateFlow()

    /** Последний выбранный тип документа. */
    private val _preset = MutableStateFlow(ScanPreset.fromName(prefs.getString(KEY_PRESET, null)))
    val preset: StateFlow<ScanPreset> = _preset.asStateFlow()

    fun setPreset(value: ScanPreset) {
        prefs.edit().putString(KEY_PRESET, value.name).apply()
        _preset.value = value
    }

    fun setBorderMode(mode: BorderMode) {
        prefs.edit().putString(KEY_BORDER, mode.name).apply()
        _borderMode.value = mode
    }

    fun setAutoCapture(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_AUTO_CAPTURE, enabled).apply()
        _autoCapture.value = enabled
    }

    fun setThemeMode(mode: ThemeMode) {
        prefs.edit().putString(KEY_THEME, mode.name).apply()
        _themeMode.value = mode
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
        const val KEY_FOLDER = "export_folder"
        const val KEY_FILTER = "filter"
        const val KEY_OCR = "ocr_enabled"
        const val KEY_LANGS = "ocr_langs"
        const val KEY_BATCH = "batch_delay_s"
        const val KEY_BORDER = "border_mode"
        const val KEY_PRESET = "scan_preset"
        const val KEY_AUTO_CAPTURE = "auto_capture_batch"
    }
}
