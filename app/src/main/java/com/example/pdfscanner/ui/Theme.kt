package com.example.pdfscanner.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/** Светло-коричневая тема «под бумагу»: тёплая крафтовая бумага, чернильно-коричневый текст и акценты. */
private val LightBrownScheme = lightColorScheme(
    primary = Color(0xFF5E3B1E),
    onPrimary = Color(0xFFFFF6E6),
    primaryContainer = Color(0xFFE2CCA6),
    onPrimaryContainer = Color(0xFF2A1805),
    secondary = Color(0xFF7D5F3F),
    onSecondary = Color(0xFFFFF6E6),
    secondaryContainer = Color(0xFFE9D8BA),
    onSecondaryContainer = Color(0xFF2B1F10),
    tertiary = Color(0xFF9A6A12),
    background = Color(0xFFEFE3CC),
    onBackground = Color(0xFF2A1E12),
    surface = Color(0xFFF3E8D2),
    onSurface = Color(0xFF2A1E12),
    surfaceVariant = Color(0xFFE3D3B6),
    onSurfaceVariant = Color(0xFF584730),
    outline = Color(0xFF87704F),
    outlineVariant = Color(0xFFD2BE9B),
    surfaceContainerLowest = Color(0xFFFAF2E1),
    surfaceContainerLow = Color(0xFFF6EBD6),
    surfaceContainer = Color(0xFFEDE0C6),
    surfaceContainerHigh = Color(0xFFE8DABD),
    surfaceContainerHighest = Color(0xFFE2D2B3),
)

/** AMOLED: чистый чёрный фон (пиксели выключены), акцент фирменный жёлтый DataLaw. */
private val AmoledScheme = darkColorScheme(
    primary = Color(0xFFF2B33D),
    onPrimary = Color(0xFF1A1200),
    primaryContainer = Color(0xFF3A2A08),
    onPrimaryContainer = Color(0xFFFFE2A6),
    secondary = Color(0xFFD9C7A3),
    onSecondary = Color(0xFF20190C),
    secondaryContainer = Color(0xFF2A2418),
    onSecondaryContainer = Color(0xFFF1E7D2),
    tertiary = Color(0xFFF1E7D2),
    background = Color(0xFF000000),
    onBackground = Color(0xFFECE6DC),
    surface = Color(0xFF000000),
    onSurface = Color(0xFFECE6DC),
    surfaceVariant = Color(0xFF1B1B1B),
    onSurfaceVariant = Color(0xFFBFB5A3),
    outline = Color(0xFF6F675A),
    outlineVariant = Color(0xFF2E2A23),
    surfaceContainerLowest = Color(0xFF000000),
    surfaceContainerLow = Color(0xFF0B0B0B),
    surfaceContainer = Color(0xFF111111),
    surfaceContainerHigh = Color(0xFF171717),
    surfaceContainerHighest = Color(0xFF202020),
)

@Composable
fun PdfScannerTheme(
    amoled: Boolean,
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (amoled) AmoledScheme else LightBrownScheme,
        content = content,
    )
}
