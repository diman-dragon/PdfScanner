package com.example.pdfscanner.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.pm.PackageInfoCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.pdfscanner.MainViewModel
import com.example.pdfscanner.R
import com.example.pdfscanner.data.ThemeMode
import com.example.pdfscanner.processing.FilterMode

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(vm: MainViewModel, onBack: () -> Unit) {
    val context = LocalContext.current
    val theme by vm.themeMode.collectAsStateWithLifecycle()
    val dynamic by vm.dynamicColor.collectAsStateWithLifecycle()
    val folder by vm.exportFolder.collectAsStateWithLifecycle()
    val filter by vm.filter.collectAsStateWithLifecycle()
    val ocrEnabled by vm.ocrEnabled.collectAsStateWithLifecycle()
    val ocrLangs by vm.ocrLangs.collectAsStateWithLifecycle()
    val batchDelay by vm.batchDelay.collectAsStateWithLifecycle()
    val privacyUrl = stringResource(R.string.privacy_policy_url)
    val version = remember { appVersion(context) }

    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) vm.setExportFolder(uri)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Настройки") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState()),
        ) {
            SectionTitle("Оформление")
            ThemeOption("Как в системе", theme == ThemeMode.SYSTEM) { vm.setThemeMode(ThemeMode.SYSTEM) }
            ThemeOption("Светлая", theme == ThemeMode.LIGHT) { vm.setThemeMode(ThemeMode.LIGHT) }
            ThemeOption("Тёмная", theme == ThemeMode.DARK) { vm.setThemeMode(ThemeMode.DARK) }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                ListItem(
                    headlineContent = { Text("Цвета из обоев") },
                    supportingContent = { Text("Приложение подстраивается под цвета системы") },
                    trailingContent = { Switch(checked = dynamic, onCheckedChange = null) },
                    modifier = Modifier.clickable { vm.setDynamicColor(!dynamic) },
                )
            }

            SectionTitle("Сканирование")
            ListItem(
                headlineContent = { Text("Следующая страница через") },
                supportingContent = {
                    Column {
                        Text(
                            if (batchDelay == 0) {
                                "Выключено: одна серия страниц в сканере Google"
                            } else {
                                "$batchDelay с после каждой серии. Сканер откроется сам, пока вы не нажмёте «Завершить»"
                            },
                        )
                        Slider(
                            value = batchDelay.toFloat(),
                            onValueChange = { vm.setBatchDelay(it.toInt()) },
                            valueRange = 0f..15f,
                            steps = 14,
                        )
                    }
                },
            )

            SectionTitle("Обработка сканов")
            FilterMode.values().forEach { m ->
                ListItem(
                    headlineContent = { Text(m.title()) },
                    supportingContent = { Text(m.subtitle()) },
                    leadingContent = { RadioButton(selected = filter == m, onClick = null) },
                    modifier = Modifier.clickable { vm.setFilter(m) },
                )
            }
            ListItem(
                headlineContent = { Text("Распознавать текст (OCR)") },
                supportingContent = { Text("Поиск по документам и копирование текста из PDF") },
                trailingContent = { Switch(checked = ocrEnabled, onCheckedChange = null) },
                modifier = Modifier.clickable { vm.setOcrEnabled(!ocrEnabled) },
            )
            if (ocrEnabled) {
                val selected = ocrLangs.split("+").filter { it in vm.availableOcrLanguages }.toSet()
                vm.availableOcrLanguages.forEach { code ->
                    ListItem(
                        headlineContent = { Text(ocrLanguageName(code)) },
                        leadingContent = { Checkbox(checked = code in selected, onCheckedChange = null) },
                        modifier = Modifier.clickable {
                            val next = if (code in selected) selected - code else selected + code
                            vm.setOcrLanguages(next)
                        },
                    )
                }
            }

            SectionTitle("Сохранение")
            ListItem(
                headlineContent = { Text("Папка для сохранения PDF") },
                supportingContent = {
                    Text(
                        folder?.let { folderLabel(context, it) }
                            ?: "Не выбрана. Предложим выбрать при первом сохранении",
                    )
                },
                modifier = Modifier.clickable { folderPicker.launch(null) },
            )

            SectionTitle("О приложении")
            ListItem(
                headlineContent = { Text("Версия") },
                supportingContent = { Text("${version.first} (сборка ${version.second})") },
            )
            ListItem(
                headlineContent = { Text("Оценить приложение") },
                modifier = Modifier.clickable { openStore(context) },
            )
            ListItem(
                headlineContent = { Text("Рассказать друзьям") },
                modifier = Modifier.clickable { shareApp(context) },
            )
            if (privacyUrl.isNotBlank()) {
                ListItem(
                    headlineContent = { Text("Политика конфиденциальности") },
                    modifier = Modifier.clickable {
                        runCatching {
                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(privacyUrl)))
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 24.dp, bottom = 4.dp),
    )
}

@Composable
private fun ThemeOption(title: String, selected: Boolean, onSelect: () -> Unit) {
    ListItem(
        headlineContent = { Text(title) },
        leadingContent = { RadioButton(selected = selected, onClick = null) },
        modifier = Modifier.clickable(onClick = onSelect),
    )
}

private fun appVersion(context: Context): Pair<String, Long> = runCatching {
    val info = context.packageManager.getPackageInfo(context.packageName, 0)
    (info.versionName ?: "?") to PackageInfoCompat.getLongVersionCode(info)
}.getOrDefault("?" to 0L)

private fun openStore(context: Context) {
    val pkg = context.packageName
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$pkg")))
    } catch (e: ActivityNotFoundException) {
        runCatching {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=$pkg")),
            )
        }
    }
}

private fun shareApp(context: Context) {
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(
            Intent.EXTRA_TEXT,
            "Сканер документов в PDF: https://play.google.com/store/apps/details?id=${context.packageName}",
        )
    }
    runCatching { context.startActivity(Intent.createChooser(intent, null)) }
}

internal fun FilterMode.title(): String = when (this) {
    FilterMode.DOCUMENT -> "Документ"
    FilterMode.BLACK_WHITE -> "Чёрно-белый"
    FilterMode.ORIGINAL -> "Оригинал"
}

internal fun FilterMode.subtitle(): String = when (this) {
    FilterMode.DOCUMENT -> "Белый лист, чёрный текст, цвета сохраняются. Убирает тени и сгибы"
    FilterMode.BLACK_WHITE -> "Максимальная чёткость текста и маленький файл"
    FilterMode.ORIGINAL -> "Без обработки, как снято"
}

internal fun ocrLanguageName(code: String): String = when (code) {
    "rus" -> "Русский"
    "eng" -> "English"
    "ukr" -> "Українська"
    "bel" -> "Беларуская"
    "srp" -> "Српски (ћирилица)"
    "srp_latn" -> "Srpski (latinica)"
    "deu" -> "Deutsch"
    "fra" -> "Français"
    "spa" -> "Español"
    else -> code
}
