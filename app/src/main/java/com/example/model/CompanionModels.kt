package com.example.model

import android.graphics.PointF
import android.graphics.RectF
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Estados completos de la Máquina de Estados (FSM) del Compañero de Recolección.
 * Reconoce cada estado por elementos visibles en pantalla (TFLite + OCR + HUD) y actúa en consecuencia.
 */
enum class CompanionState(val titleEs: String, val descriptionEs: String) {
    IDLE("En Espera", "Bot detenido. Listo para iniciar o calibrar HUD."),
    LOADING("Cargando Partida", "Pantalla de carga detectada. Esperando ingreso al servidor."),
    MAIN_MENU("Menú Principal", "Menú inicial detectado. Pulsando botón Jugar (G7)."),
    SERVER_SELECT("Selección de Servidor", "Lista de servidores visible. Entrando al servidor guardado."),
    IN_GAME_SCANNING("Buscando Recursos", "HUD visible y seguro. Explorando árboles y rocas cercanas."),
    IN_GAME_APPROACHING("Caminando al Recurso", "Avanzando con joystick virtual hacia el recurso objetivo."),
    IN_GAME_HARVESTING("Recolectando Recurso", "Golpeando recurso con la herramienta óptima equipada."),
    TOOL_BROKEN_OR_UPGRADEABLE("Evaluando Herramienta / Crafteo", "Verificando materiales con OCR para fabricar mejor herramienta (G6)."),
    INVENTORY_FULL_RETURN_CHEST("Mochila Llena -> Ir al Cofre", "Inventario lleno detectado. Regresando al cofre para depositar (G5)."),
    DEPOSITING_IN_CHEST("Depositando en Cofre", "Transfiriendo madera y minerales recolectados al cofre."),
    STUCK_RECOVERY("Resolviendo Atasco", "Obstáculo, agua o falta de avance detectada. Ejecutando maniobra evasiva."),
    UNEXPECTED_DIALOG("Diálogo Imprevisto", "Ventana emergente detectada. Consultando reglas / respaldo LLM."),
    DEAD("Personaje Muerto", "Pantalla de muerte detectada. Deteniendo o reapareciendo según ajuste."),
    DISCONNECTED("Desconectado", "Pérdida de conexión con el servidor. Intentando reconexión segura (G7)."),
    THERMAL_PAUSED("Pausa Térmica / Batería", "Pausado preventivamente para enfriar el dispositivo (G3)."),
    EMERGENCY_STOP("PARADA DE EMERGENCIA", "Detenido al instante por seguridad (jugador, daño o cambio de app).")
}

/**
 * Modos de operación del botón flotante (Overlay).
 */
enum class OverlayMode(val labelEs: String) {
    AUTONOMOUS_GATHER("Recolectar (G5-G7)"),
    DATASET_COLLECTOR_G1_G4("Dataset / G1"),
    GESTURE_TEST_G2("Prueba Toques G2"),
    LIVE_HUD_CALIBRATION("Calibrar HUD Real")
}

/**
 * Razones auditables de parada de seguridad o abandono de punto de control.
 */
enum class StopReason(val titleEs: String, val isCriticalAlert: Boolean) {
    NONE("Operación normal", false),
    USER_MANUAL_STOP("Detenido manualmente por el usuario desde el botón flotante", false),
    PLAYER_DETECTED_NEARBY("¡ALERTA! Otro jugador detectado en pantalla. Toma el control manual.", true),
    HEALTH_DROP_DETECTED("¡ALERTA! Pérdida de vida detectada. Posible ataque o daño ambiental.", true),
    CHARACTER_DIED("¡ALERTA! Personaje muerto en partida.", true),
    HUD_LOST_UNEXPECTEDLY("HUD dejó de ser visible sin abrir inventario. Parada preventiva para no tocar a ciegas.", true),
    FOREGROUND_APP_CHANGED("Oxide dejó de estar en primer plano (cambio de app o pantalla bloqueada).", false),
    INCOMING_PHONE_CALL("Llamada telefónica detectada. Compañero pausado automáticamente.", false),
    THERMAL_LIMIT_EXCEEDED("Temperatura de batería crítica (>42.5 °C). Pausado para evitar sobrecalentamiento.", true),
    LOW_BATTERY_LIMIT("Nivel de batería bajo (<15%). Recolección detenida por seguridad.", true),
    GAME_BLOCKS_CAPTURE_G1("Abandono G1: El juego entrega frames en negro (FLAG_SECURE activo).", true),
    CHECKPOINT_ABANDONED("Criterio de abandono alcanzado tras 3 enfoques fallidos.", true)
}

/**
 * Categoría de herramienta para seleccionar automáticamente hacha vs pico.
 */
enum class ToolCategory {
    UNIVERSAL,
    WOODCUTTING,
    MINING
}

/**
 * Objeto detectado por el modelo TFLite local o el detector híbrido en el frame actual.
 * Incluye tanto coordenadas normalizadas (0..1) como coordenadas exactas en píxeles de la pantalla real.
 */
data class DetectedObject(
    val label: String,
    val confidence: Float,
    val boxNormalized: RectF,
    val boxScreenPx: RectF
) {
    val centerXScreenPx: Float get() = boxScreenPx.centerX()
    val centerYScreenPx: Float get() = boxScreenPx.centerY()
    val areaNormalized: Float get() = boxNormalized.width() * boxNormalized.height()
}

/**
 * Punto de control calibrable del HUD sobre la pantalla real del usuario.
 */
data class HudAnchorPoint(
    val id: String,
    val nameEs: String,
    val normX: Float,
    val normY: Float
) {
    fun toScreenPx(screenWidthPx: Int, screenHeightPx: Int): PointF {
        return PointF(
            (normX.coerceIn(0.02f, 0.98f) * screenWidthPx),
            (normY.coerceIn(0.02f, 0.98f) * screenHeightPx)
        )
    }
}

/**
 * Perfil de Calibración Dinámica de Pantalla Real.
 * No asume 16:9 fijo: mide el ancho y alto reales en horizontal (20:9, 19.5:9, 16:9, etc.),
 * respeta los márgenes del notch/cutout y calcula posiciones iniciales ancladas a los bordes
 * (como hace el motor Unity en Oxide) que el usuario puede arrastrar en vivo sobre su juego.
 */
data class ScreenCalibrationProfile(
    val realLandscapeWidthPx: Int,
    val realLandscapeHeightPx: Int,
    val densityDpi: Int,
    val cutoutSafeLeftPx: Int = 0,
    val cutoutSafeRightPx: Int = 0,
    val joystickCenterNorm: PointF,
    val joystickRadiusNormWidth: Float = 0.075f,
    val attackButtonNorm: PointF,
    val jumpButtonNorm: PointF,
    val crouchButtonNorm: PointF,
    val interactChestButtonNorm: PointF,
    val inventoryButtonNorm: PointF,
    val craftingMenuButtonNorm: PointF,
    val craftConfirmButtonNorm: PointF,
    val closeModalButtonNorm: PointF,
    val playMainMenuButtonNorm: PointF,
    val serverJoinButtonNorm: PointF,
    val hotbarSlot1Norm: PointF,
    val hotbarSlot2Norm: PointF,
    val hotbarSlot3Norm: PointF,
    val hotbarSlot4Norm: PointF,
    val hotbarSlot5Norm: PointF,
    val healthBarRectNorm: RectF,
    val inventoryFirstSlotNorm: PointF,
    val chestDepositTargetNorm: PointF,
    val isUserCalibrated: Boolean = false
) {
    val aspectRatioFloat: Float
        get() = if (realLandscapeHeightPx > 0) {
            realLandscapeWidthPx.toFloat() / realLandscapeHeightPx.toFloat()
        } else 1.77f

    val aspectRatioLabel: String
        get() {
            val r = aspectRatioFloat
            return when {
                abs(r - (21f / 9f)) < 0.06f -> "21:9 (${realLandscapeWidthPx}x${realLandscapeHeightPx})"
                abs(r - (20f / 9f)) < 0.06f -> "20:9 (${realLandscapeWidthPx}x${realLandscapeHeightPx})"
                abs(r - (19.5f / 9f)) < 0.05f -> "19.5:9 (${realLandscapeWidthPx}x${realLandscapeHeightPx})"
                abs(r - (19f / 9f)) < 0.05f -> "19:9 (${realLandscapeWidthPx}x${realLandscapeHeightPx})"
                abs(r - (18f / 9f)) < 0.06f -> "18:9 (${realLandscapeWidthPx}x${realLandscapeHeightPx})"
                abs(r - (16f / 9f)) < 0.06f -> "16:9 (${realLandscapeWidthPx}x${realLandscapeHeightPx})"
                else -> "${(r * 9f).roundToInt()}:9 (${realLandscapeWidthPx}x${realLandscapeHeightPx})"
            }
        }

    fun toScreenPx(norm: PointF): PointF = PointF(
        norm.x * realLandscapeWidthPx,
        norm.y * realLandscapeHeightPx
    )

    fun hotbarSlotPx(slotIndexZeroBased: Int): PointF {
        val norm = when (slotIndexZeroBased.coerceIn(0, 4)) {
            0 -> hotbarSlot1Norm
            1 -> hotbarSlot2Norm
            2 -> hotbarSlot3Norm
            3 -> hotbarSlot4Norm
            else -> hotbarSlot5Norm
        }
        return toScreenPx(norm)
    }

    fun healthBarRectPx(): RectF = RectF(
        healthBarRectNorm.left * realLandscapeWidthPx,
        healthBarRectNorm.top * realLandscapeHeightPx,
        healthBarRectNorm.right * realLandscapeWidthPx,
        healthBarRectNorm.bottom * realLandscapeHeightPx
    )

    fun allEditableAnchors(): List<HudAnchorPoint> = listOf(
        HudAnchorPoint("joystick", "Joystick (Centro)", joystickCenterNorm.x, joystickCenterNorm.y),
        HudAnchorPoint("attack", "Botón Golpear / Usar", attackButtonNorm.x, attackButtonNorm.y),
        HudAnchorPoint("interact", "Abrir Cofre / Mano", interactChestButtonNorm.x, interactChestButtonNorm.y),
        HudAnchorPoint("inventory", "Abrir Mochila", inventoryButtonNorm.x, inventoryButtonNorm.y),
        HudAnchorPoint("crafting", "Pestaña Crafteo / Mesa", craftingMenuButtonNorm.x, craftingMenuButtonNorm.y),
        HudAnchorPoint("slot1", "Hotbar Slot 1 (Hacha)", hotbarSlot1Norm.x, hotbarSlot1Norm.y),
        HudAnchorPoint("slot2", "Hotbar Slot 2 (Pico)", hotbarSlot2Norm.x, hotbarSlot2Norm.y),
        HudAnchorPoint("health", "Barra de Vida (Muestreo)", healthBarRectNorm.centerX(), healthBarRectNorm.centerY()),
        HudAnchorPoint("play_menu", "Botón Jugar (Menú)", playMainMenuButtonNorm.x, playMainMenuButtonNorm.y)
    )

    fun withUpdatedAnchor(anchorId: String, newNormX: Float, newNormY: Float): ScreenCalibrationProfile {
        val cx = newNormX.coerceIn(0.02f, 0.98f)
        val cy = newNormY.coerceIn(0.02f, 0.98f)
        return when (anchorId) {
            "joystick" -> copy(joystickCenterNorm = PointF(cx, cy), isUserCalibrated = true)
            "attack" -> copy(attackButtonNorm = PointF(cx, cy), isUserCalibrated = true)
            "interact" -> copy(interactChestButtonNorm = PointF(cx, cy), isUserCalibrated = true)
            "inventory" -> copy(inventoryButtonNorm = PointF(cx, cy), isUserCalibrated = true)
            "crafting" -> copy(craftingMenuButtonNorm = PointF(cx, cy), isUserCalibrated = true)
            "slot1" -> copy(hotbarSlot1Norm = PointF(cx, cy), isUserCalibrated = true)
            "slot2" -> copy(hotbarSlot2Norm = PointF(cx, cy), isUserCalibrated = true)
            "health" -> {
                val halfW = healthBarRectNorm.width() / 2f
                val halfH = healthBarRectNorm.height() / 2f
                copy(
                    healthBarRectNorm = RectF(cx - halfW, cy - halfH, cx + halfW, cy + halfH),
                    isUserCalibrated = true
                )
            }
            "play_menu" -> copy(playMainMenuButtonNorm = PointF(cx, cy), isUserCalibrated = true)
            else -> this
        }
    }

    companion object {
        /**
         * Calcula la geometría inicial del HUD adaptada a la pantalla real del dispositivo.
         * En motores Unity como Oxide, los controles laterales se anclan en unidades proporcionales
         * a la altura (heightPx) desde los bordes seguros izquierdo/derecho, mientras que el hotbar
         * se centra horizontalmente. Esto evita el error de asumir 16:9 en pantallas 20:9 o 19.5:9.
         */
        fun createAdaptiveDefault(
            landscapeWidthPx: Int,
            landscapeHeightPx: Int,
            densityDpi: Int,
            cutoutLeftPx: Int = 0,
            cutoutRightPx: Int = 0
        ): ScreenCalibrationProfile {
            val w = landscapeWidthPx.coerceAtLeast(1280).toFloat()
            val h = landscapeHeightPx.coerceAtLeast(720).toFloat()
            val leftInsetNorm = (cutoutLeftPx.toFloat() / w).coerceIn(0f, 0.08f)
            val rightInsetNorm = (cutoutRightPx.toFloat() / w).coerceIn(0f, 0.08f)

            // Desplazamiento lateral proporcional a la altura real (CanvasScaler de Unity)
            val sideOffsetFromHeight = { heightFraction: Float ->
                ((heightFraction * h) / w).coerceIn(0.05f, 0.35f)
            }

            val joyX = (leftInsetNorm + sideOffsetFromHeight(0.28f)).coerceIn(0.10f, 0.24f)
            val attackX = (1f - rightInsetNorm - sideOffsetFromHeight(0.25f)).coerceIn(0.76f, 0.92f)
            val jumpX = (1f - rightInsetNorm - sideOffsetFromHeight(0.12f)).coerceIn(0.84f, 0.96f)
            val interactX = (1f - rightInsetNorm - sideOffsetFromHeight(0.36f)).coerceIn(0.66f, 0.85f)
            val healthLeft = (leftInsetNorm + sideOffsetFromHeight(0.06f)).coerceIn(0.03f, 0.12f)
            val healthRight = (healthLeft + sideOffsetFromHeight(0.24f)).coerceIn(healthLeft + 0.08f, 0.30f)

            return ScreenCalibrationProfile(
                realLandscapeWidthPx = landscapeWidthPx.coerceAtLeast(1280),
                realLandscapeHeightPx = landscapeHeightPx.coerceAtLeast(720),
                densityDpi = densityDpi,
                cutoutSafeLeftPx = cutoutLeftPx,
                cutoutSafeRightPx = cutoutRightPx,
                joystickCenterNorm = PointF(joyX, 0.73f),
                joystickRadiusNormWidth = sideOffsetFromHeight(0.14f),
                attackButtonNorm = PointF(attackX, 0.72f),
                jumpButtonNorm = PointF(jumpX, 0.56f),
                crouchButtonNorm = PointF(jumpX, 0.82f),
                interactChestButtonNorm = PointF(interactX, 0.52f),
                inventoryButtonNorm = PointF(0.68f, 0.90f),
                craftingMenuButtonNorm = PointF(0.26f, 0.14f),
                craftConfirmButtonNorm = PointF(0.78f, 0.84f),
                closeModalButtonNorm = PointF(1f - rightInsetNorm - 0.05f, 0.09f),
                playMainMenuButtonNorm = PointF(0.50f, 0.78f),
                serverJoinButtonNorm = PointF(0.50f, 0.65f),
                hotbarSlot1Norm = PointF(0.36f, 0.90f),
                hotbarSlot2Norm = PointF(0.43f, 0.90f),
                hotbarSlot3Norm = PointF(0.50f, 0.90f),
                hotbarSlot4Norm = PointF(0.57f, 0.90f),
                hotbarSlot5Norm = PointF(0.64f, 0.90f),
                healthBarRectNorm = RectF(healthLeft, 0.06f, healthRight, 0.11f),
                inventoryFirstSlotNorm = PointF(0.28f, 0.36f),
                chestDepositTargetNorm = PointF(0.72f, 0.36f),
                isUserCalibrated = false
            )
        }
    }
}

/**
 * Puntos de control obligatorios (G1 a G7) con criterio de abandono al fallar 3 enfoques legítimos.
 */
enum class CheckpointId(
    val code: String,
    val titleEs: String,
    val goalEs: String,
    val approachesEs: List<String>
) {
    G1(
        code = "G1",
        titleEs = "Captura de Pantalla No Negra",
        goalEs = "Verificar que MediaProjection capture los píxeles de Oxide y el juego no bloquee con FLAG_SECURE.",
        approachesEs = listOf(
            "Enfoque 1: ImageReader RGBA_8888 con compensación exacta de rowStride y pixelStride.",
            "Enfoque 2: VirtualDisplay con VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR y superficie dedicada en HandlerThread.",
            "Enfoque 3: Muestreo estadístico de luminancia multi-cuadrante tras 1500 ms de estabilización de superficie."
        )
    ),
    G2(
        code = "G2",
        titleEs = "Toques de Accesibilidad (Mover y Golpear)",
        goalEs = "Confirmar que dispatchGesture mueva el joystick virtual del personaje y ejecute el botón de golpear.",
        approachesEs = listOf(
            "Enfoque 1: StrokeDescription continuo desde el centro calibrado del joystick hacia el borde.",
            "Enfoque 2: Trazos encadenados con willContinue=true para motores Unity que exigen eventos MOVE progresivos.",
            "Enfoque 3: Trayectoria humanizada con micro-interpolación de 16 ms y presión escalonada en botón de golpear."
        )
    ),
    G3(
        code = "G3",
        titleEs = "Estabilidad 30 Minutos sin Sobrecalentar",
        goalEs = "Mantener Oxide conectado 30 min con servicio y botón flotante activos a 5-10 FPS sin superar 42.5 °C.",
        approachesEs = listOf(
            "Enfoque 1: Throttling dinámico a 6 FPS con reutilización de ByteBuffer y descarte de frames intermedios.",
            "Enfoque 2: Inferencia TFLite con 2 hilos CPU y activación de ML Kit OCR exclusivamente con inventario abierto.",
            "Enfoque 3: Escalado automático a 4 FPS cuando BatteryManager reporta temperatura >= 39.5 °C."
        )
    ),
    G4(
        code = "G4",
        titleEs = "Detector TFLite >= 85% de Acierto",
        goalEs = "Alcanzar al menos 85% de precisión (mAP@50 / acierto en validación) en capturas no vistas durante el entrenamiento.",
        approachesEs = listOf(
            "Enfoque 1: YOLOv8n exportado a TFLite Int8/Float16 con letterboxing que preserva la proporción real (20:9/16:9).",
            "Enfoque 2: Aumento de datos día/noche de Oxide y filtrado NMS con umbral adaptativo por clase.",
            "Enfoque 3: Fusión de cajas TFLite con verificación cromática del follaje/mena y anclajes del HUD."
        )
    ),
    G5(
        code = "G5",
        titleEs = "10 Minutos de Recolección Continua + Cofre",
        goalEs = "Ciclo autónomo de 10 minutos buscando recursos, recolectando, desatascándose y depositando en el cofre.",
        approachesEs = listOf(
            "Enfoque 1: Seguimiento visual de centroide + navegación por rumbo de retorno al cofre al llenar mochila.",
            "Enfoque 2: Detección de atasco por estancamiento de área de bounding box (>3.5 s) con salto y giro lateral.",
            "Enfoque 3: Exploración en espiral sistemática cuando no hay recursos visibles en el campo de visión."
        )
    ),
    G6(
        code = "G6",
        titleEs = "Planificador de Crafteo de Herramientas",
        goalEs = "Identificar mediante OCR + JSON cuándo hay materiales y mesa suficientes para fabricar una herramienta superior.",
        approachesEs = listOf(
            "Enfoque 1: Lectura ML Kit OCR del inventario comparada contra oxide_knowledge_base.json por categoría y tier.",
            "Enfoque 2: Normalización difusa (Levenshtein/Regex bilingüe Español-Inglés) para tolerar ruido tipográfico en cantidades.",
            "Enfoque 3: Verificación cruzada del estado de ranuras del hotbar y requisitos de nivel de mesa de trabajo."
        )
    ),
    G7(
        code = "G7",
        titleEs = "Arranque desde Menú y Recuperación de Desconexión",
        goalEs = "Reconocer menú principal, selección de servidor y diálogos de desconexión para reanudar recolección de forma autónoma.",
        approachesEs = listOf(
            "Enfoque 1: Clasificación de pantalla por palabras clave OCR ('Jugar', 'Servidor', 'Reconectar') y anclajes calibrados.",
            "Enfoque 2: Temporizador de espera inteligente en pantalla de carga con reintento exponencial ante caída de red.",
            "Enfoque 3: Respaldo LLM con fallback determinista local para cerrar diálogos imprevistos sin quedar en bucle."
        )
    )
}

enum class CheckpointStatus(val labelEs: String) {
    PENDING("Pendiente"),
    IN_PROGRESS("En Prueba"),
    PASSED("SUPERADO"),
    FAILED_ABANDONED("ABANDONADO (Informe Generado)")
}

data class ApproachAttempt(
    val approachNumber: Int,
    val description: String,
    val passed: Boolean,
    val evidenceLog: String,
    val timestampMs: Long = System.currentTimeMillis()
)

data class CheckpointRecord(
    val checkpoint: CheckpointId,
    val status: CheckpointStatus = CheckpointStatus.PENDING,
    val attempts: List<ApproachAttempt> = emptyList(),
    val abandonmentReport: String? = null
)
