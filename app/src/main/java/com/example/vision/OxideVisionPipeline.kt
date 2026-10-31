package com.example.vision

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.Log
import com.example.model.DetectedObject
import com.example.model.ScreenCalibrationProfile
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.tasks.await
import org.tensorflow.lite.Interpreter
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import kotlin.math.max
import kotlin.math.min

/**
 * Resultado unificado del análisis visual de un fotograma de Oxide: Survival Island.
 */
data class VisionFrameAnalysis(
    val detectedObjects: List<DetectedObject>,
    val isHudVisible: Boolean,
    val estimatedHealthPercent: Float,
    val isBackpackFullIndicatorVisible: Boolean,
    val isCustomTfliteModelLoaded: Boolean,
    val inferenceLatencyMs: Long,
    val ocrTextRaw: String = "",
    val parsedInventoryCounts: Map<String, Int> = emptyMap()
)

/**
 * Pipeline de Visión Computacional:
 * 1. Detector TFLite local (`oxide_detector.tflite` en `assets/` o en `getExternalFilesDir("models")`)
 *    con preprocesamiento Letterbox para no deformar pantallas 20:9, 19.5:9 ni 16:9.
 * 2. Verificador de barra de vida y presencia de HUD en las coordenadas reales calibradas por el usuario.
 * 3. Reconocimiento de texto en pantalla con Google ML Kit OCR (bajo demanda al abrir inventario, mesa o diálogos)
 *    para extraer cantidades exactas de madera, piedra, fragmentos de metal y azufre.
 */
class OxideVisionPipeline(private val context: Context) {

    private var tfliteInterpreter: Interpreter? = null
    private var isCustomModelActive: Boolean = false
    private val labels: List<String> by lazy { loadLabels() }
    private val textRecognizer by lazy {
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    }

    private val inputTensorSize = 320
    private val inputBuffer: ByteBuffer = ByteBuffer.allocateDirect(1 * inputTensorSize * inputTensorSize * 3 * 4).apply {
        order(ByteOrder.nativeOrder())
    }

    init {
        reloadTfliteInterpreter()
    }

    /**
     * Intenta cargar primero un modelo entrenado por el usuario en `files/models/oxide_detector.tflite`
     * o bien el modelo incluido en `assets/oxide_detector.tflite`.
     */
    fun reloadTfliteInterpreter(): Boolean {
        return try {
            tfliteInterpreter?.close()
            val externalModel = File(context.getExternalFilesDir("models"), "oxide_detector.tflite")
            val buffer: MappedByteBuffer? = when {
                externalModel.exists() && externalModel.length() > 1024 -> {
                    FileInputStream(externalModel).channel.map(
                        FileChannel.MapMode.READ_ONLY,
                        0,
                        externalModel.length()
                    )
                }
                assetExists("oxide_detector.tflite") -> {
                    context.assets.openFd("oxide_detector.tflite").use { fd ->
                        FileInputStream(fd.fileDescriptor).channel.map(
                            FileChannel.MapMode.READ_ONLY,
                            fd.startOffset,
                            fd.declaredLength
                        )
                    }
                }
                else -> null
            }

            if (buffer != null) {
                val options = Interpreter.Options().apply {
                    setNumThreads(2)
                }
                tfliteInterpreter = Interpreter(buffer, options)
                isCustomModelActive = true
                Log.i(TAG, "Modelo TFLite oxide_detector.tflite cargado correctamente")
                true
            } else {
                isCustomModelActive = false
                false
            }
        } catch (e: Exception) {
            Log.w(TAG, "Modelo TFLite personalizado aún no disponible; usando detector cromático/estructural base", e)
            isCustomModelActive = false
            false
        }
    }

    private fun assetExists(name: String): Boolean {
        return try {
            context.assets.list("")?.contains(name) == true
        } catch (e: Exception) {
            false
        }
    }

    private fun loadLabels(): List<String> {
        return try {
            context.assets.open("oxide_labels.txt").bufferedReader().readLines()
                .map { it.trim() }
                .filter { it.isNotEmpty() }
        } catch (e: Exception) {
            listOf("tree", "rock_stone", "rock_metal", "rock_sulfur", "chest", "player", "hud_anchor")
        }
    }

    /**
     * Analiza un frame capturado en tiempo real (5-10 FPS):
     * - Mide la salud en la barra calibrada (`calibration.healthBarRectPx()`).
     * - Verifica si el HUD está presente.
     * - Ejecuta detección de objetos con Letterboxing para preservar la geometría real (20:9, 19.5:9, 16:9).
     */
    fun analyzeFrame(
        frame: Bitmap,
        calibration: ScreenCalibrationProfile
    ): VisionFrameAnalysis {
        val startMs = System.currentTimeMillis()
        val healthPct = sampleCalibratedHealthBar(frame, calibration)
        val hudVisible = checkHudPresence(frame, calibration, healthPct)

        val detections = if (isCustomModelActive && tfliteInterpreter != null) {
            runTfliteWithLetterbox(frame)
        } else {
            runStructuralColorFallbackDetector(frame, calibration)
        }

        val elapsed = (System.currentTimeMillis() - startMs).coerceAtLeast(1L)
        return VisionFrameAnalysis(
            detectedObjects = detections,
            isHudVisible = hudVisible,
            estimatedHealthPercent = healthPct,
            isBackpackFullIndicatorVisible = false,
            isCustomTfliteModelLoaded = isCustomModelActive,
            inferenceLatencyMs = elapsed
        )
    }

    /**
     * Preprocesa el frame aplicando Letterbox (relleno gris simétrico arriba/abajo) para que
     * una pantalla 20:9 o 19.5:9 no se deforme al entrar al tensor cuadrado de TFLite, y luego
     * proyecta las cajas detectadas de vuelta a los píxeles exactos de la pantalla real.
     */
    private fun runTfliteWithLetterbox(frame: Bitmap): List<DetectedObject> {
        val interpreter = tfliteInterpreter ?: return emptyList()
        val srcW = frame.width.toFloat()
        val srcH = frame.height.toFloat()
        val scale = min(inputTensorSize / srcW, inputTensorSize / srcH)
        val scaledW = (srcW * scale).toInt().coerceAtLeast(1)
        val scaledH = (srcH * scale).toInt().coerceAtLeast(1)
        val padX = (inputTensorSize - scaledW) / 2f
        val padY = (inputTensorSize - scaledH) / 2f

        val letterboxed = Bitmap.createBitmap(inputTensorSize, inputTensorSize, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(letterboxed)
        canvas.drawColor(Color.rgb(114, 114, 114))
        val destRect = RectF(padX, padY, padX + scaledW, padY + scaledH)
        canvas.drawBitmap(frame, null, destRect, Paint(Paint.FILTER_BITMAP_FLAG))

        inputBuffer.rewind()
        val pixels = IntArray(inputTensorSize * inputTensorSize)
        letterboxed.getPixels(pixels, 0, inputTensorSize, 0, 0, inputTensorSize, inputTensorSize)
        for (px in pixels) {
            inputBuffer.putFloat(((px shr 16) and 0xFF) / 255.0f)
            inputBuffer.putFloat(((px shr 8) and 0xFF) / 255.0f)
            inputBuffer.putFloat((px and 0xFF) / 255.0f)
        }
        letterboxed.recycle()

        // Salida estándar SSD/YOLO simplificada [1, maxDetections, 6] -> [x1, y1, x2, y2, score, classIdx]
        val maxDet = 20
        val outputArray = Array(1) { Array(maxDet) { FloatArray(6) } }
        return try {
            interpreter.run(inputBuffer, outputArray)
            val results = mutableListOf<DetectedObject>()
            for (det in outputArray[0]) {
                val score = det[4]
                if (score >= 0.45f) {
                    val clsIdx = det[5].toInt().coerceIn(0, labels.lastIndex)
                    val x1 = ((det[0] * inputTensorSize - padX) / scale).coerceIn(0f, srcW)
                    val y1 = ((det[1] * inputTensorSize - padY) / scale).coerceIn(0f, srcH)
                    val x2 = ((det[2] * inputTensorSize - padX) / scale).coerceIn(0f, srcW)
                    val y2 = ((det[3] * inputTensorSize - padY) / scale).coerceIn(0f, srcH)
                    if (x2 > x1 + 8f && y2 > y1 + 8f) {
                        results.add(
                            DetectedObject(
                                label = labels[clsIdx],
                                confidence = score,
                                boxNormalized = RectF(x1 / srcW, y1 / srcH, x2 / srcW, y2 / srcH),
                                boxScreenPx = RectF(x1, y1, x2, y2)
                            )
                        )
                    }
                }
            }
            applyNonMaxSuppression(results)
        } catch (e: Exception) {
            Log.w(TAG, "Formato de tensor distinto, usando respaldo estructural", e)
            emptyList()
        }
    }

    /**
     * Detector estructural cromático en tiempo real sobre el horizonte de juego cuando el usuario
     * aún está recolectando capturas para entrenar su modelo personalizado en G4.
     */
    private fun runStructuralColorFallbackDetector(
        frame: Bitmap,
        calibration: ScreenCalibrationProfile
    ): List<DetectedObject> {
        val w = frame.width
        val h = frame.height
        val results = mutableListOf<DetectedObject>()
        // Escaneamos la franja central del horizonte (evitando el cielo superior y los controles inferiores del HUD)
        val gridCols = 8
        val gridRows = 4
        val startY = (h * 0.22f).toInt()
        val endY = (h * 0.72f).toInt()
        val startX = (w * 0.12f).toInt()
        val endX = (w * 0.88f).toInt()
        val cellW = ((endX - startX) / gridCols).coerceAtLeast(16)
        val cellH = ((endY - startY) / gridRows).coerceAtLeast(16)

        for (r in 0 until gridRows) {
            for (c in 0 until gridCols) {
                val cx = startX + c * cellW + cellW / 2
                val cy = startY + r * cellH + cellH / 2
                val px = frame.getPixel(cx.coerceIn(0, w - 1), cy.coerceIn(0, h - 1))
                val red = (px shr 16) and 0xFF
                val green = (px shr 8) and 0xFF
                val blue = px and 0xFF

                // Follaje / tronco de árbol en Oxide
                if (green > red + 22 && green > blue + 28 && green in 55..195) {
                    val rectPx = RectF(
                        (cx - cellW / 2f).coerceAtLeast(0f),
                        (cy - cellH / 2f).coerceAtLeast(0f),
                        (cx + cellW / 2f).coerceAtMost(w.toFloat()),
                        (cy + cellH / 2f).coerceAtMost(h.toFloat())
                    )
                    results.add(
                        DetectedObject(
                            label = "tree",
                            confidence = 0.68f,
                            boxNormalized = RectF(rectPx.left / w, rectPx.top / h, rectPx.right / w, rectPx.bottom / h),
                            boxScreenPx = rectPx
                        )
                    )
                } else if (kotlin.math.abs(red - green) < 12 && kotlin.math.abs(green - blue) < 12 && red in 95..175) {
                    // Nodo de roca / piedra grisácea en el terreno
                    val rectPx = RectF(
                        (cx - cellW / 2f).coerceAtLeast(0f),
                        (cy - cellH / 2f).coerceAtLeast(0f),
                        (cx + cellW / 2f).coerceAtMost(w.toFloat()),
                        (cy + cellH / 2f).coerceAtMost(h.toFloat())
                    )
                    results.add(
                        DetectedObject(
                            label = "rock_stone",
                            confidence = 0.62f,
                            boxNormalized = RectF(rectPx.left / w, rectPx.top / h, rectPx.right / w, rectPx.bottom / h),
                            boxScreenPx = rectPx
                        )
                    )
                }
            }
        }
        return applyNonMaxSuppression(results).take(5)
    }

    /**
     * Muestrea la barra de vida en el rectángulo calibrado por el usuario sobre su pantalla real.
     */
    fun sampleCalibratedHealthBar(frame: Bitmap, calibration: ScreenCalibrationProfile): Float {
        val rect = calibration.healthBarRectPx()
        val left = rect.left.toInt().coerceIn(0, frame.width - 2)
        val right = rect.right.toInt().coerceIn(left + 1, frame.width - 1)
        val centerY = rect.centerY().toInt().coerceIn(0, frame.height - 1)

        var activeColumns = 0
        val totalColumns = (right - left).coerceAtLeast(1)
        for (x in left until right) {
            val px = frame.getPixel(x, centerY)
            val r = (px shr 16) and 0xFF
            val g = (px shr 8) and 0xFF
            val b = px and 0xFF
            // La barra de salud en Oxide usa tonos verdes/rojos brillantes sobre un fondo oscuro semitransparente
            val isFilled = (g > 110 && g > b + 30) || (r > 135 && r > b + 40)
            if (isFilled) activeColumns++
        }
        val ratio = (activeColumns.toFloat() / totalColumns.toFloat()) * 100f
        return if (ratio < 5f) 100f else ratio.coerceIn(0f, 100f)
    }

    private fun checkHudPresence(
        frame: Bitmap,
        calibration: ScreenCalibrationProfile,
        healthPct: Float
    ): Boolean {
        val slot1 = calibration.hotbarSlotPx(0)
        val sx = slot1.x.toInt().coerceIn(0, frame.width - 1)
        val sy = slot1.y.toInt().coerceIn(0, frame.height - 1)
        val px = frame.getPixel(sx, sy)
        val luma = 0.299 * ((px shr 16) and 0xFF) + 0.587 * ((px shr 8) and 0xFF) + 0.114 * (px and 0xFF)
        return luma > 12.0 && healthPct > 0f
    }

    /**
     * Ejecuta ML Kit Text Recognition (OCR) sobre el fotograma actual para leer:
     * - Cantidades del inventario ("Madera x250", "Piedra 150", "Fragmentos de metal 80")
     * - Estado de mochila llena
     * - Textos de menú ("Jugar", "Servidores", "Reconectar", "Reaparecer")
     */
    suspend fun runOcrOnFrame(frame: Bitmap): Pair<String, Map<String, Int>> {
        return try {
            val image = InputImage.fromBitmap(frame, 0)
            val visionText = textRecognizer.process(image).await()
            val fullText = visionText.text
            val counts = parseResourceQuantitiesFromOcr(fullText)
            fullText to counts
        } catch (e: Exception) {
            Log.e(TAG, "Error ejecutando ML Kit OCR", e)
            "" to emptyMap()
        }
    }

    /**
     * Extrae cantidades de materiales en Español e Inglés desde el texto reconocido por ML Kit OCR.
     * Soporta formatos como "Madera x250", "250 Madera", "Piedra: 120", "Wood x180", "Metal Fragments 90".
     */
    fun parseResourceQuantitiesFromOcr(ocrText: String): Map<String, Int> {
        val result = mutableMapOf<String, Int>()
        val patterns = mapOf(
            "wood" to listOf(
                Regex("""(?i)(?:madera|tronco|wood)\s*[:x×*]?\s*(\d{1,4})"""),
                Regex("""(?i)(\d{1,4})\s*[x×]?\s*(?:madera|wood)""")
            ),
            "stone" to listOf(
                Regex("""(?i)(?:piedra|roca|stone|stones)\s*[:x×*]?\s*(\d{1,4})"""),
                Regex("""(?i)(\d{1,4})\s*[x×]?\s*(?:piedra|stone)""")
            ),
            "metal_fragments" to listOf(
                Regex("""(?i)(?:fragmentos\s+de\s+metal|metal\s+fragments|metal)\s*[:x×*]?\s*(\d{1,4})"""),
                Regex("""(?i)(\d{1,4})\s*[x×]?\s*(?:fragmentos\s+de\s+metal|metal\s+fragments)""")
            ),
            "sulfur_ore" to listOf(
                Regex("""(?i)(?:azufre|mineral\s+de\s+azufre|sulfur)\s*[:x×*]?\s*(\d{1,4})"""),
                Regex("""(?i)(\d{1,4})\s*[x×]?\s*(?:azufre|sulfur)""")
            ),
            "scrap" to listOf(
                Regex("""(?i)(?:chatarra|scrap)\s*[:x×*]?\s*(\d{1,4})""")
            )
        )

        for ((materialKey, regexList) in patterns) {
            var totalForMaterial = 0
            for (regex in regexList) {
                regex.findAll(ocrText).forEach { match ->
                    val qty = match.groupValues.getOrNull(1)?.toIntOrNull() ?: 0
                    totalForMaterial = max(totalForMaterial, qty)
                }
            }
            if (totalForMaterial > 0) {
                result[materialKey] = totalForMaterial
            }
        }
        return result
    }

    private fun applyNonMaxSuppression(
        boxes: List<DetectedObject>,
        iouThreshold: Float = 0.45f
    ): List<DetectedObject> {
        val sorted = boxes.sortedByDescending { it.confidence }.toMutableList()
        val selected = mutableListOf<DetectedObject>()
        while (sorted.isNotEmpty()) {
            val current = sorted.removeAt(0)
            selected.add(current)
            sorted.removeAll { other ->
                other.label == current.label && computeIoU(current.boxNormalized, other.boxNormalized) > iouThreshold
            }
        }
        return selected
    }

    private fun computeIoU(a: RectF, b: RectF): Float {
        val interLeft = max(a.left, b.left)
        val interTop = max(a.top, b.top)
        val interRight = min(a.right, b.right)
        val interBottom = min(a.bottom, b.bottom)
        val interW = (interRight - interLeft).coerceAtLeast(0f)
        val interH = (interBottom - interTop).coerceAtLeast(0f)
        val interArea = interW * interH
        val unionArea = a.width() * a.height() + b.width() * b.height() - interArea
        return if (unionArea <= 0f) 0f else interArea / unionArea
    }

    companion object {
        private const val TAG = "OxideVisionPipeline"
    }
}
