package com.example.pdfscanner.ui

import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions
import com.google.mlkit.vision.documentscanner.GmsDocumentScanning
import com.google.mlkit.vision.documentscanner.GmsDocumentScanningResult

/**
 * Возвращает функцию «открыть сканер Google». Результат приходит в onResult,
 * закрытие сканера без результата в onCancelled, недоступность сервисов в onError.
 */
@Composable
fun rememberScanStarter(
    onResult: (GmsDocumentScanningResult) -> Unit,
    onCancelled: () -> Unit,
    onError: (String) -> Unit,
): () -> Unit {
    val context = LocalContext.current
    val currentResult by rememberUpdatedState(onResult)
    val currentCancelled by rememberUpdatedState(onCancelled)
    val currentError by rememberUpdatedState(onError)

    val scanner = remember {
        GmsDocumentScanning.getClient(
            GmsDocumentScannerOptions.Builder()
                .setGalleryImportAllowed(true)
                .setPageLimit(30)
                .setResultFormats(
                    GmsDocumentScannerOptions.RESULT_FORMAT_PDF,
                    GmsDocumentScannerOptions.RESULT_FORMAT_JPEG,
                )
                .setScannerMode(GmsDocumentScannerOptions.SCANNER_MODE_FULL)
                .build(),
        )
    }

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { res ->
        val parsed = if (res.resultCode == Activity.RESULT_OK) {
            GmsDocumentScanningResult.fromActivityResultIntent(res.data)
        } else {
            null
        }
        if (parsed != null) currentResult(parsed) else currentCancelled()
    }

    return remember<() -> Unit>(scanner, launcher) {
        {
            scanner.getStartScanIntent(context.findActivity())
                .addOnSuccessListener { sender ->
                    launcher.launch(IntentSenderRequest.Builder(sender).build())
                }
                .addOnFailureListener {
                    currentError(
                        "Сканер недоступен. Обновите Google Play Services и проверьте интернет при первом запуске.",
                    )
                }
            Unit
        }
    }
}
