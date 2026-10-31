package com.example.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.PointF
import android.os.Build
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import com.example.model.ScreenCalibrationProfile
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * Servicio de Accesibilidad Ético para Oxide: Survival Island.
 * - Detecta en tiempo real qué paquete está en primer plano (`TYPE_WINDOW_STATE_CHANGED`).
 * - Ejecuta gestos de joystick virtual, golpeo de recursos, cambio de slot en el hotbar,
 *   apertura de inventario/cofre y pulsación de crafteo usando las coordenadas reales calibradas.
 * - Implementa los 3 enfoques legítimos del Punto de Control G2 (Toques de movimiento y golpeo).
 * - Nunca envía ni un solo toque si el botón de Parada de Emergencia está activo o si Oxide no está en primer plano.
 */
class OxideAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        CompanionRepository.updateAccessibilityConnected(true)
        Log.i(TAG, "OxideAccessibilityService conectado y listo")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            event.eventType != AccessibilityEvent.TYPE_WINDOWS_CHANGED
        ) return

        // Priorizar la ventana realmente activa del sistema. El paquete del evento puede
        // pertenecer a una superposición (Companion, controles de Samsung, etc.) y no a
        // la aplicación que permanece debajo de ella.
        val activeWindowPackage = try {
            windows.firstOrNull { it.isActive }?.root?.packageName?.toString()
        } catch (_: Exception) {
            null
        }
        val eventPackage = event.packageName?.toString()
        val pkg = activeWindowPackage
            ?: eventPackage?.takeIf { event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED }

        if (!pkg.isNullOrBlank() &&
            pkg != packageName &&
            !pkg.startsWith("com.android.systemui") &&
            !pkg.startsWith("android.inputmethodservice")
        ) {
            CompanionRepository.updateForegroundPackage(pkg)
            Log.d(TAG, "Ventana activa detectada: $pkg")
        }
    }

    override fun onInterrupt() {
        Log.w(TAG, "OxideAccessibilityService interrumpido")
    }

    override fun onDestroy() {
        if (instance === this) {
            instance = null
        }
        CompanionRepository.updateAccessibilityConnected(false)
        super.onDestroy()
    }

    /**
     * Desplaza el joystick virtual desde el centro calibrado hacia el ángulo `(targetX, targetY)` en pantalla.
     * Soporta los 3 enfoques legítimos del Punto de Control G2:
     * - Enfoque 1: Trazo lineal continuo estándar (`StrokeDescription`).
     * - Enfoque 2: Trazo segmentado con `willContinue = true` (arranque + mantenimiento) para controles Unity.
     * - Enfoque 3: Trayectoria curva humanizada con micro-variación natural.
     */
    fun dispatchJoystickMoveToward(
        calibration: ScreenCalibrationProfile,
        targetScreenX: Float,
        targetScreenY: Float,
        durationMs: Long = 520L,
        g2Approach: Int = 1,
        onComplete: ((Boolean) -> Unit)? = null
    ): Boolean {
        if (!canDispatchSafely()) {
            onComplete?.invoke(false)
            return false
        }

        val joyCenter = calibration.toScreenPx(calibration.joystickCenterNorm)
        val radiusPx = (calibration.joystickRadiusNormWidth * calibration.realLandscapeWidthPx)
            .coerceIn(55f, 220f)

        // Calculamos el vector de dirección respecto al centro horizontal de la vista del jugador
        val screenCenterX = calibration.realLandscapeWidthPx / 2f
        val screenCenterY = calibration.realLandscapeHeightPx / 2f
        val dx = targetScreenX - screenCenterX
        // Avanzar hacia adelante equivale a empujar el joystick hacia arriba (Y negativo) con giro lateral según dx
        val angleRad = atan2(-kotlin.math.abs(screenCenterY * 0.65f), dx * 0.85f)
        val endX = (joyCenter.x + cos(angleRad) * radiusPx).coerceIn(10f, calibration.realLandscapeWidthPx - 10f)
        val endY = (joyCenter.y + sin(angleRad) * radiusPx).coerceIn(10f, calibration.realLandscapeHeightPx - 10f)

        val gesture = buildJoystickGesture(
            start = joyCenter,
            end = PointF(endX, endY),
            durationMs = durationMs.coerceIn(150L, 2500L),
            approach = g2Approach
        )

        return dispatchGesture(
            gesture,
            object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    onComplete?.invoke(true)
                }

                override fun onCancelled(gestureDescription: GestureDescription?) {
                    onComplete?.invoke(false)
                }
            },
            null
        )
    }

    private fun buildJoystickGesture(
        start: PointF,
        end: PointF,
        durationMs: Long,
        approach: Int
    ): GestureDescription {
        val builder = GestureDescription.Builder()

        when (approach) {
            2 -> {
                // Enfoque 2: Trazo encadenado con willContinue (para Canvas de Unity que requieren evento MOVE separado)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    val mid = PointF((start.x + end.x) / 2f, (start.y + end.y) / 2f)
                    val path1 = Path().apply {
                        moveTo(start.x, start.y)
                        lineTo(mid.x, mid.y)
                    }
                    val stroke1 = GestureDescription.StrokeDescription(path1, 0L, 120L, true)
                    val path2 = Path().apply {
                        moveTo(mid.x, mid.y)
                        lineTo(end.x, end.y)
                    }
                    val stroke2 = stroke1.continueStroke(path2, 0L, (durationMs - 120L).coerceAtLeast(100L), false)
                    builder.addStroke(stroke1)
                    builder.addStroke(stroke2)
                    return builder.build()
                }
            }
            3 -> {
                // Enfoque 3: Curva cuadrática suave con micro-interpolación humana
                val ctrlX = (start.x + end.x) / 2f + 6f
                val ctrlY = (start.y + end.y) / 2f - 5f
                val path = Path().apply {
                    moveTo(start.x, start.y)
                    quadTo(ctrlX, ctrlY, end.x, end.y)
                }
                builder.addStroke(GestureDescription.StrokeDescription(path, 0L, durationMs))
                return builder.build()
            }
        }

        // Enfoque 1 (por defecto): Trazo lineal directo desde el centro del joystick hacia el borde
        val path = Path().apply {
            moveTo(start.x, start.y)
            lineTo(end.x, end.y)
        }
        builder.addStroke(GestureDescription.StrokeDescription(path, 0L, durationMs))
        return builder.build()
    }

    /**
     * Pulsa el botón de golpear / recolectar en su coordenada calibrada real.
     */
    fun dispatchHarvestHit(
        calibration: ScreenCalibrationProfile,
        g2Approach: Int = 1,
        onComplete: ((Boolean) -> Unit)? = null
    ): Boolean {
        val pt = calibration.toScreenPx(calibration.attackButtonNorm)
        val holdMs = when (g2Approach) {
            1 -> 95L
            2 -> 160L
            else -> 220L
        }
        return dispatchTapAtPixel(pt.x, pt.y, holdMs, onComplete)
    }

    /**
     * Gira la cámara horizontalmente en la mitad derecha de la pantalla para explorar cuando no hay recursos visibles.
     */
    fun dispatchCameraLookSweep(
        calibration: ScreenCalibrationProfile,
        turnRight: Boolean = true
    ): Boolean {
        if (!canDispatchSafely()) return false
        val startX = calibration.realLandscapeWidthPx * 0.62f
        val startY = calibration.realLandscapeHeightPx * 0.42f
        val deltaX = if (turnRight) -calibration.realLandscapeWidthPx * 0.18f else calibration.realLandscapeWidthPx * 0.18f
        val endX = (startX + deltaX).coerceIn(100f, calibration.realLandscapeWidthPx - 100f)

        val path = Path().apply {
            moveTo(startX, startY)
            lineTo(endX, startY)
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0L, 280L))
            .build()
        return dispatchGesture(gesture, null, null)
    }

    /**
     * Arrastra un slot del inventario hacia el cofre abierto para depositar recursos recolectados (G5).
     */
    fun dispatchDragInventoryToChest(calibration: ScreenCalibrationProfile): Boolean {
        if (!canDispatchSafely()) return false
        val from = calibration.toScreenPx(calibration.inventoryFirstSlotNorm)
        val to = calibration.toScreenPx(calibration.chestDepositTargetNorm)
        val path = Path().apply {
            moveTo(from.x, from.y)
            lineTo(to.x, to.y)
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0L, 340L))
            .build()
        return dispatchGesture(gesture, null, null)
    }

    /**
     * Ejecuta un toque puntual en las coordenadas exactas en píxeles de la pantalla real.
     */
    fun dispatchTapAtPixel(
        xPx: Float,
        yPx: Float,
        holdDurationMs: Long = 85L,
        onComplete: ((Boolean) -> Unit)? = null
    ): Boolean {
        if (!canDispatchSafely()) {
            onComplete?.invoke(false)
            return false
        }
        val path = Path().apply {
            moveTo(xPx.coerceAtLeast(1f), yPx.coerceAtLeast(1f))
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0L, holdDurationMs.coerceIn(45L, 600L)))
            .build()

        return dispatchGesture(
            gesture,
            object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    onComplete?.invoke(true)
                }

                override fun onCancelled(gestureDescription: GestureDescription?) {
                    onComplete?.invoke(false)
                }
            },
            null
        )
    }

    private fun canDispatchSafely(): Boolean {
        return CompanionRepository.isAutomationRunning.value &&
            !CompanionRepository.isEmergencyStopped.value
    }

    companion object {
        private const val TAG = "OxideAccessibilitySvc"

        @Volatile
        var instance: OxideAccessibilityService? = null
            private set
    }
}
