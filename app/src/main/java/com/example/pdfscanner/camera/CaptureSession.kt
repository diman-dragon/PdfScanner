package com.example.pdfscanner.camera

import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.example.pdfscanner.data.BorderMode
import com.example.pdfscanner.processing.DocumentDetector
import com.example.pdfscanner.processing.PerspectiveWarp
import com.example.pdfscanner.processing.Pt
import com.example.pdfscanner.processing.fullQuad
import com.example.pdfscanner.processing.insetQuad
import com.example.pdfscanner.processing.maxCornerDistance
import java.io.File
import java.io.IOException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.min
import kotlin.math.roundToInt

/** Снятая страница: raw — полный кадр, file — вырезанная и выровненная страница. */
class CapturedPage(
    val key: Long,
    val raw: File,
    val file: File,
    val quad: List<Pt>,
    val thumb: ImageBitmap,
)

/** Запрос на правку границ: isNew = снимок ещё не стал страницей (первый кадр в режиме «свои границы»). */
class EditRequest(val raw: File, val initial: List<Pt>, val key: Long, val isNew: Boolean)

/**
 * Логика съёмки: следит за кадром, ищет границы, решает когда снимать, считает паузу между
 * страницами и хранит снятые страницы. Интерфейс только читает состояние.
 *
 * Правило автоснимка: кадр должен быть стабильным (границы документа не дрожат либо камера
 * неподвижна) примерно 0,7 с. Для второй и следующих страниц ещё нужно, чтобы после предыдущего
 * снимка в кадре что-то поменялось (вы перевернули страницу), иначе одна и та же страница
 * не снимется дважды.
 */
class CaptureSession(
    context: Context,
    val dir: File,
    val batch: Boolean,
    borderMode: BorderMode,
    val delaySeconds: Int,
    val autoCapture: Boolean,
) {
    enum class Phase { AIMING, COUNTDOWN, REVIEW, EDIT }

    private val appContext = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val worker: ExecutorService = Executors.newSingleThreadExecutor()
    private val rawDir = File(dir, "raw").apply { mkdirs() }

    var phase by mutableStateOf(Phase.AIMING)
        private set
    var borderMode by mutableStateOf(borderMode)
        private set
    var liveQuad by mutableStateOf<List<Pt>?>(null)
        private set
    var progress by mutableStateOf(0f)
        private set
    var countdown by mutableStateOf<Int?>(null)
    var message by mutableStateOf<String?>(null)
    var edit by mutableStateOf<EditRequest?>(null)
        private set
    var manualQuad by mutableStateOf<List<Pt>?>(null)
        private set
    var busy by mutableStateOf(false)
        private set
    val pages = mutableStateListOf<CapturedPage>()

    @Volatile
    var imageCapture: ImageCapture? = null

    private var shooting = false
    private var nextKey = 1L
    private var phaseBeforeEdit = Phase.AIMING

    // Состояние анализа кадров (рабочий поток анализатора)
    private var lastAnalysis = 0L
    private var prevGray: ByteArray? = null
    private var refGray: ByteArray? = null
    private var needChange = false
    private var sceneChanged = false
    private var lastQuad: List<Pt>? = null
    private var stableCount = 0

    fun changeBorderMode(mode: BorderMode) {
        borderMode = mode
        resetStability()
        liveQuad = if (mode == BorderMode.MANUAL) manualQuad else null
    }

    private fun resetStability() {
        stableCount = 0
        lastQuad = null
        progress = 0f
    }

    // ---------------------------------------------------------------- анализ кадров

    fun analyze(image: ImageProxy) {
        try {
            val now = SystemClock.elapsedRealtime()
            if (now - lastAnalysis < ANALYSIS_INTERVAL_MS) return
            lastAnalysis = now
            val current = phase
            if ((current != Phase.AIMING && current != Phase.COUNTDOWN) || shooting || busy) return

            val gray = Luma.upright(image, ANALYSIS_WIDTH) ?: return
            val previous = prevGray
            val diff = if (previous != null && previous.size == gray.pix.size) {
                DocumentDetector.meanAbsDiff(previous, gray.pix)
            } else {
                255f
            }
            prevGray = gray.pix

            val reference = refGray
            if (needChange && reference != null && DocumentDetector.meanAbsDiff(reference, gray.pix) > CHANGE_THRESHOLD) {
                sceneChanged = true
            }
            if (current == Phase.COUNTDOWN) return

            val quad = if (borderMode == BorderMode.AUTO) {
                DocumentDetector.detect(gray.pix, gray.w, gray.h)
            } else {
                manualQuad
            }
            liveQuad = quad

            if (borderMode == BorderMode.AUTO) {
                val last = lastQuad
                stableCount = if (quad != null && last != null && maxCornerDistance(quad, last) < QUAD_STABLE_DISTANCE) {
                    stableCount + 1
                } else {
                    0
                }
                lastQuad = quad
            } else {
                stableCount = if (diff < STILL_THRESHOLD) stableCount + 1 else 0
            }
            progress = min(1f, stableCount.toFloat() / STABLE_FRAMES)

            val allowed = !needChange || sceneChanged
            if (autoCapture && allowed && stableCount >= STABLE_FRAMES) {
                stableCount = 0
                main.post { shoot() }
            }
        } finally {
            image.close()
        }
    }

    // ---------------------------------------------------------------- съёмка

    fun shoot() {
        if (shooting || busy) return
        if (phase != Phase.AIMING && phase != Phase.COUNTDOWN) return
        val capture = imageCapture ?: return
        shooting = true
        busy = true
        val quadAtShot = if (borderMode == BorderMode.AUTO) liveQuad else manualQuad
        capture.takePicture(
            worker,
            object : ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureSuccess(image: ImageProxy) {
                    var bytes = ByteArray(0)
                    var rotation = 0
                    try {
                        val buffer = image.planes[0].buffer
                        bytes = ByteArray(buffer.remaining())
                        buffer.get(bytes)
                        rotation = image.imageInfo.rotationDegrees
                    } finally {
                        image.close()
                    }
                    handleShot(bytes, rotation, quadAtShot)
                }

                override fun onError(exception: ImageCaptureException) {
                    main.post {
                        shooting = false
                        busy = false
                        message = "Не удалось снять кадр. Попробуйте ещё раз."
                    }
                }
            },
        )
    }

    private fun handleShot(bytes: ByteArray, rotation: Int, quadAtShot: List<Pt>?) {
        try {
            val raw = PerspectiveWarp.decodeUpright(bytes, rotation, RAW_MAX_SIDE)
                ?: throw IOException("decode")
            val key = nextKey++
            val rawFile = File(rawDir, "raw_$key.jpg")
            rawFile.outputStream().use { raw.compress(Bitmap.CompressFormat.JPEG, 92, it) }

            val quad: List<Pt>? = when {
                borderMode == BorderMode.MANUAL -> manualQuad
                else -> quadAtShot ?: detectOn(raw) ?: fullQuad()
            }
            if (quad == null) {
                // Режим «свои границы», первый кадр: просим пользователя выставить углы.
                val initial = detectOn(raw) ?: insetQuad()
                raw.recycle()
                main.post {
                    edit = EditRequest(rawFile, initial, key, true)
                    phaseBeforeEdit = Phase.AIMING
                    phase = Phase.EDIT
                    shooting = false
                    busy = false
                }
                return
            }
            val page = buildPage(key, rawFile, raw, quad)
            raw.recycle()
            main.post { onPageReady(page) }
        } catch (e: Throwable) {
            main.post {
                shooting = false
                busy = false
                message = "Не удалось обработать кадр. Проверьте свободное место."
            }
        }
    }

    private fun buildPage(key: Long, rawFile: File, raw: Bitmap, quad: List<Pt>): CapturedPage {
        val warped = PerspectiveWarp.warp(raw, quad)
        val pageFile = File(dir, "page_%05d.jpg".format(key))
        pageFile.outputStream().use { warped.compress(Bitmap.CompressFormat.JPEG, 92, it) }
        val tw = 360
        val th = (warped.height * tw.toFloat() / warped.width).roundToInt().coerceAtLeast(1)
        val thumb = Bitmap.createScaledBitmap(warped, tw, th, true)
        if (thumb !== warped) warped.recycle()
        return CapturedPage(key, rawFile, pageFile, quad, thumb.asImageBitmap())
    }

    private fun detectOn(bitmap: Bitmap): List<Pt>? {
        val w = ANALYSIS_WIDTH
        val h = (bitmap.height * w.toFloat() / bitmap.width).roundToInt().coerceAtLeast(1)
        val small = Bitmap.createScaledBitmap(bitmap, w, h, true)
        val pixels = IntArray(w * h)
        small.getPixels(pixels, 0, w, 0, 0, w, h)
        if (small !== bitmap) small.recycle()
        val gray = ByteArray(w * h) {
            val p = pixels[it]
            ((((p shr 16) and 255) * 77 + ((p shr 8) and 255) * 150 + (p and 255) * 29) shr 8).toByte()
        }
        return DocumentDetector.detect(gray, w, h)
    }

    private fun onPageReady(page: CapturedPage) {
        pages.add(page)
        afterNewPage()
    }

    private fun afterNewPage() {
        shooting = false
        busy = false
        refGray = prevGray
        needChange = true
        sceneChanged = false
        resetStability()
        phase = if (batch) Phase.COUNTDOWN else Phase.REVIEW
    }

    // ---------------------------------------------------------------- пауза между страницами

    fun endCountdown() {
        if (phase == Phase.COUNTDOWN) {
            countdown = null
            resetStability()
            phase = Phase.AIMING
        }
    }

    // ---------------------------------------------------------------- проверка снимка

    /** «Удалить»: убирает последнюю страницу и возвращает к съёмке. */
    fun deleteLast() {
        val last = pages.lastOrNull() ?: return
        pages.removeAt(pages.lastIndex)
        last.file.delete()
        last.raw.delete()
        needChange = false
        refGray = null
        countdown = null
        resetStability()
        phase = Phase.AIMING
    }

    fun editLast() {
        val last = pages.lastOrNull() ?: return
        phaseBeforeEdit = phase
        edit = EditRequest(last.raw, last.quad, last.key, false)
        phase = Phase.EDIT
    }

    fun cancelEdit() {
        val request = edit ?: return
        edit = null
        if (request.isNew) {
            request.raw.delete()
            resetStability()
            phase = Phase.AIMING
        } else {
            phase = phaseBeforeEdit
        }
    }

    fun confirmEdit(quad: List<Pt>) {
        val request = edit ?: return
        busy = true
        worker.execute {
            try {
                val raw = PerspectiveWarp.decodeFile(request.raw.path, RAW_MAX_SIDE)
                    ?: throw IOException("decode")
                val page = buildPage(request.key, request.raw, raw, quad)
                raw.recycle()
                main.post {
                    // «Свои границы»: выставили один раз, дальше они применяются ко всем кадрам.
                    if (borderMode == BorderMode.MANUAL) {
                        manualQuad = quad
                        liveQuad = quad
                    }
                    val index = pages.indexOfFirst { it.key == request.key }
                    if (index >= 0) pages[index] = page else pages.add(page)
                    edit = null
                    if (request.isNew) {
                        afterNewPage()
                    } else {
                        busy = false
                        phase = phaseBeforeEdit
                    }
                }
            } catch (e: Throwable) {
                main.post {
                    busy = false
                    message = "Не удалось применить границы."
                }
            }
        }
    }

    fun dispose() {
        imageCapture = null
        worker.shutdown()
    }

    private companion object {
        const val ANALYSIS_WIDTH = 160
        const val ANALYSIS_INTERVAL_MS = 120L
        const val STABLE_FRAMES = 6
        const val STILL_THRESHOLD = 2.5f
        const val CHANGE_THRESHOLD = 10f
        const val QUAD_STABLE_DISTANCE = 0.035f
        const val RAW_MAX_SIDE = 3600
    }
}
