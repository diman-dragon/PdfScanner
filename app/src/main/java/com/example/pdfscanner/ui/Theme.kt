package com.example.pdfscanner.ui

import android.os.Build
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val LightScheme = lightColorScheme(
    primary = Color(0xFF3B5BDB),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDDE3FF),
    onPrimaryContainer = Color(0xFF001A5C),
    secondaryContainer = Color(0xFFE3E5F7),
    background = Color(0xFFFBF8FF),
    surface = Color(0xFFFBF8FF),
    surfaceContainerLow = Color(0xFFF3F2FA),
    surfaceContainerHighest = Color(0xFFE3E2EA),
)

private val DarkScheme = darkColorScheme(
    primary = Color(0xFFB6C4FF),
    onPrimary = Color(0xFF0B2A8F),
    primaryContainer = Color(0xFF2441B5),
    onPrimaryContainer = Color(0xFFDDE3FF),
    secondaryContainer = Color(0xFF3F4256),
    background = Color(0xFF121318),
    surface = Color(0xFF121318),
    surfaceContainerLow = Color(0xFF1A1B21),
    surfaceContainerHighest = Color(0xFF34343B),
)

@Composable
fun PdfScannerTheme(
    dark: Boolean,
    dynamicColor: Boolean,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val scheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> DarkScheme
        else -> LightScheme
    }
    MaterialTheme(colorScheme = scheme, content = content)
}
