package com.example.vision

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.sqrt

/**
 * Diagnóstico de un fotograma capturado para validar el Punto de Control G1 (Captura no negra).
 */
data class FrameDiagnostic(
    val isBlackOrBlocked: Boolean,
    val meanLuminance: Double,
    val luminanceStdDev: Double,
    val approachIndexUsed: Int,
    val diagnosticMessageEs: String
)

/**
 * Gestor de Captura de Pantalla mediante MediaProjection a 5-10 FPS.
 * - Respeta la resolución y relación de aspecto real del teléfono (20:9, 19.5:9, 16:9, etc.).
 * - Implementa los 3 enfoques legítimos del Punto de Control G1 para verificar si Oxide bloquea la captura.
 * - Incluye recolector de dataset en segundo plano para etiquetar y entrenar el modelo TFLite (G4).
 */
class ScreenCaptureManager(
    private val context: Context,
    private val onFrameCaptured: (Bitmap, FrameDiagnostic) -> Unit,
    private val onG1Abandoned: (String) -> Unit
) {
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var captureThread: HandlerThread? = null
    private var captureHandler: Handler? = null

    private val isCapturing = AtomicBoolean(false)
    private val lastFrameTimestampMs = AtomicLong(0L)

    // Frecuencia objetivo entre 5 y 10 FPS (por defecto 7 FPS -> ~142 ms por frame)
    @Volatile
    var targetFps: Int = 7
        set(value) {
            field = value.coerceIn(4, 10)
        }

    // Seguimiento de los 3 enfoques de G1 en caso de frames negros consecutivos
    private var currentG1Approach: Int = 1
    private var consecutiveBlackFramesInApproach: Int = 0
    private val g1EvidenceLogs = mutableListOf<String>()

    private var captureWidthPx: Int = 1920
    private var captureHeightPx: Int = 1080
    private var captureDensityDpi: Int = 320

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            Log.i(TAG, "MediaProjection detenido por el sistema")
            stopCapture()
        }
    }

    /**
     * Inicia la sesión de captura sobre la pantalla real del dispositivo.
     */
    fun startCapture(
        projection: MediaProjection,
        realWidthPx: Int,
        realHeightPx: Int,
        densityDpi: Int,
        approachNumber: Int = 1
    ) {
        stopCapture()
        this.mediaProjection = projection
        this.captureWidthPx = realWidthPx.coerceAtLeast(1280)
        this.captureHeightPx = realHeightPx.coerceAtLeast(720)
        this.captureDensityDpi = densityDpi
        this.currentG1Approach = approachNumber.coerceIn(1, 3)
        this.consecutiveBlackFramesInApproach = 0

        val thread = HandlerThread("OxideMediaProjectionThread").apply { start() }
        val handler = Handler(thread.looper)
        this.captureThread = thread
        this.captureHandler = handler

        projection.registerCallback(projectionCallback, handler)
        setupVirtualDisplayForApproach(this.currentG1Approach, handler)
        isCapturing.set(true)
    }

    private fun setupVirtualDisplayForApproach(approach: Int, handler: Handler) {
        virtualDisplay?.release()
        imageReader?.close()

        val reader = ImageReader.newInstance(
            captureWidthPx,
            captureHeightPx,
            PixelFormat.RGBA_8888,
            2
        )
        this.imageReader = reader

        // Enfoque 1 vs Enfoque 2: banderas legítimas de VirtualDisplay
        val displayFlags = when (approach) {
            1 -> DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR
            2 -> DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC or DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR
            else -> DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR or DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION
        }

        reader.setOnImageAvailableListener({ ir ->
            if (!isCapturing.get()) return@setOnImageAvailableListener
            val image = try {
                ir.acquireLatestImage()
            } catch (e: Exception) {
                null
            } ?: return@setOnImageAvailableListener

            val now = System.currentTimeMillis()
            val minIntervalMs = (1000L / targetFps.coerceIn(4, 10))
            if (now - lastFrameTimestampMs.get() < minIntervalMs) {
                image.close()
                return@setOnImageAvailableListener
            }
            lastFrameTimestampMs.set(now)

            try {
                val bitmap = imageToBitmapWithStrideCompensation(image)
                image.close()
                if (bitmap != null) {
                    val diagnostic = evaluateFrameG1(bitmap, currentG1Approach)
                    handleG1Progression(bitmap, diagnostic, handler)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error procesando frame de ImageReader", e)
                image.close()
            }
        }, handler)

        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "OxideCompanionCapture_A$approach",
            captureWidthPx,
            captureHeightPx,
            captureDensityDpi,
            displayFlags,
            reader.surface,
            null,
            handler
        )
    }

    /**
     * Extrae el Bitmap compensando el rowPadding que introducen GPUs Adreno/Mali en resoluciones 20:9 y 19.5:9.
     */
    private fun imageToBitmapWithStrideCompensation(image: Image): Bitmap? {
        val plane = image.planes.firstOrNull() ?: return null
        val buffer = plane.buffer
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        val rowPadding = rowStride - pixelStride * image.width

        val paddedWidth = image.width + (rowPadding / pixelStride).coerceAtLeast(0)
        val rawBitmap = Bitmap.createBitmap(paddedWidth, image.height, Bitmap.Config.ARGB_8888)
        buffer.rewind()
        rawBitmap.copyPixelsFromBuffer(buffer)

        return if (rowPadding > 0) {
            val cropped = Bitmap.createBitmap(rawBitmap, 0, 0, image.width, image.height)
            rawBitmap.recycle()
            cropped
        } else {
            rawBitmap
        }
    }

    /**
     * Evalúa estadísticamente la luminancia y varianza de los 4 cuadrantes para detectar si el frame es negro (G1).
     */
    fun evaluateFrameG1(bitmap: Bitmap, approach: Int): FrameDiagnostic {
        val w = bitmap.width
        val h = bitmap.height
        val stepX = (w / 24).coerceAtLeast(4)
        val stepY = (h / 16).coerceAtLeast(4)

        var sumLuma = 0.0
        var sumSqLuma = 0.0
        var count = 0

        for (y in stepY until (h - stepY) step stepY) {
            for (x in stepX until (w - stepX) step stepX) {
                val pixel = bitmap.getPixel(x, y)
                val r = (pixel shr 16) and 0xFF
                val g = (pixel shr 8) and 0xFF
                val b = pixel and 0xFF
                val luma = 0.299 * r + 0.587 * g + 0.114 * b
                sumLuma += luma
                sumSqLuma += luma * luma
                count++
            }
        }

        if (count == 0) {
            return FrameDiagnostic(true, 0.0, 0.0, approach, "Frame vacío")
        }

        val mean = sumLuma / count
        val variance = (sumSqLuma / count) - (mean * mean)
        val stdDev = sqrt(variance.coerceAtLeast(0.0))

        val isBlack = mean < 2.2 && stdDev < 1.5
        val msg = if (isBlack) {
            "G1 Enfoque $approach: Frame en negro detectado (luma=${"%.2f".format(mean)}, std=${"%.2f".format(stdDev)})"
        } else {
            "G1 Enfoque $approach OK: Píxeles reales recibidos (${w}x${h}, luma=${"%.1f".format(mean)}, std=${"%.1f".format(stdDev)})"
        }

        return FrameDiagnostic(
            isBlackOrBlocked = isBlack,
            meanLuminance = mean,
            luminanceStdDev = stdDev,
            approachIndexUsed = approach,
            diagnosticMessageEs = msg
        )
    }

    /**
     * Aplica la regla de los 3 enfoques de G1: si un enfoque recibe 12 frames negros seguidos (~2s),
     * pasa al siguiente enfoque legítimo. Si fallan los 3 enfoques, se detiene y genera el informe de abandono.
     */
    private fun handleG1Progression(bitmap: Bitmap, diagnostic: FrameDiagnostic, handler: Handler) {
        if (!diagnostic.isBlackOrBlocked) {
            consecutiveBlackFramesInApproach = 0
            onFrameCaptured(bitmap, diagnostic)
            return
        }

        consecutiveBlackFramesInApproach++
        if (consecutiveBlackFramesInApproach >= 12) {
            g1EvidenceLogs.add(
                "Enfoque $currentG1Approach falló tras $consecutiveBlackFramesInApproach frames negros consecutivos: ${diagnostic.diagnosticMessageEs}"
            )
            if (currentG1Approach < 3) {
                currentG1Approach++
                consecutiveBlackFramesInApproach = 0
                Log.w(TAG, "Cambiando a Enfoque G1 #$currentG1Approach")
                setupVirtualDisplayForApproach(currentG1Approach, handler)
            } else {
                // Criterio de abandono G1 alcanzado tras 3 enfoques legítimos: NO evadir FLAG_SECURE, detener e informar.
                val report = buildString {
                    appendLine("=== INFORME DE HALLAZGO Y ABANDONO (PUNTO DE CONTROL G1) ===")
                    appendLine("Estado: FALLÓ TRAS 3 ENFOQUES LEGÍTIMOS")
                    appendLine("Resolución probada: ${captureWidthPx}x${captureHeightPx} (${captureDensityDpi} dpi)")
                    appendLine("Evidencia registrada:")
                    g1EvidenceLogs.forEach { appendLine(" - $it") }
                    appendLine("Causa raíz técnica: La ventana del juego tiene activa la bandera WindowManager.LayoutParams.FLAG_SECURE o bloqueo DRM de superficie, haciendo que el compositor SurfaceFlinger entregue buffers con luminancia 0.0.")
                    appendLine("Acción ética aplicada: Cumpliendo las reglas del proyecto, NO se intenta evadir el bloqueo mediante root ni inyección de memoria. Ejecución detenida.")
                }
                stopCapture()
                onG1Abandoned(report)
                return
            }
        }
        onFrameCaptured(bitmap, diagnostic)
    }

    /**
     * Guarda una captura real en el almacenamiento de la app para etiquetar y entrenar el detector TFLite (G1-G4).
     */
    fun saveDatasetCapture(bitmap: Bitmap, tagPrefix: String = "oxide"): File? {
        return try {
            val dir = File(context.getExternalFilesDir(null), "dataset_captures").apply {
                if (!exists()) mkdirs()
            }
            val stamp = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())
            val file = File(dir, "${tagPrefix}_${bitmap.width}x${bitmap.height}_$stamp.png")
            FileOutputStream(file).use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
            file
        } catch (e: Exception) {
            Log.e(TAG, "No se pudo guardar captura del dataset", e)
            null
        }
    }

    fun getDatasetCapturesCount(): Int {
        val dir = File(context.getExternalFilesDir(null), "dataset_captures")
        return dir.listFiles()?.count { it.name.endsWith(".png") || it.name.endsWith(".jpg") } ?: 0
    }

    fun getDatasetDirectoryPath(): String {
        val dir = File(context.getExternalFilesDir(null), "dataset_captures").apply {
            if (!exists()) mkdirs()
        }
        return dir.absolutePath
    }

    fun stopCapture() {
        isCapturing.set(false)
        try {
            imageReader?.setOnImageAvailableListener(null, null)
            virtualDisplay?.release()
            imageReader?.close()
            mediaProjection?.unregisterCallback(projectionCallback)
        } catch (e: Exception) {
            Log.w(TAG, "Error liberando recursos de captura", e)
        } finally {
            virtualDisplay = null
            imageReader = null
            captureThread?.quitSafely()
            captureThread = null
            captureHandler = null
        }
    }

    companion object {
        private const val TAG = "ScreenCaptureManager"
    }
}
