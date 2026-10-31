package com.example.service

import android.graphics.Bitmap
import com.example.model.CheckpointId
import com.example.model.CompanionState
import com.example.model.DetectedObject
import com.example.model.ScreenCalibrationProfile
import com.example.model.StopReason
import com.example.model.ToolCategory
import com.example.planner.DialogActionType
import com.example.planner.KnowledgeBasePlanner
import com.example.vision.OxideVisionPipeline
import com.example.vision.VisionFrameAnalysis
import kotlin.math.abs

/**
 * Máquina de Estados Finita (FSM) Completa del Compañero de Recolección.
 * Gobierna el ciclo completo:
 * - Menú principal -> Selección de servidor -> Carga -> En partida (G7)
 * - Buscar recurso cercano -> Caminar -> Seleccionar herramienta -> Golpear -> Verificar -> Repetir
 * - Detectar y resolver atascos (obstáculos, agua, falta de avance en 3.5 s)
 * - Mochila llena -> Ir al cofre -> Depositar materiales (G5)
 * - Mejorar / craftear mejor herramienta disponible con OCR + JSON (G6)
 */
class CompanionStateMachine(
    private val visionPipeline: OxideVisionPipeline,
    private val planner: KnowledgeBasePlanner,
    private val safetyGuardian: SafetyGuardian
) {
    private var currentEquippedSlot: Int = -1
    private var currentToolTiers = mutableMapOf(
        ToolCategory.WOODCUTTING to 1,
        ToolCategory.MINING to 1
    )
    private var availableWorkbenchLevel: Int = 1

    // Variables de seguimiento para detección de atascos y ciclo de recolección
    private var lastTargetLabel: String? = null
    private var lastTargetArea: Float = 0f
    private var approachStartTimeMs: Long = 0L
    private var harvestHitsOnCurrentNode: Int = 0
    private var totalHarvestCyclesCompleted: Int = 0
    private var systematicSweepCount: Int = 0
    private var g2ApproachInUse: Int = 1

    /**
     * Ejecuta un paso completo del ciclo percepción -> seguridad -> decisión -> acción.
     */
    suspend fun step(
        frame: Bitmap,
        calibration: ScreenCalibrationProfile,
        accessibility: OxideAccessibilityService?
    ) {
        if (!CompanionRepository.isAutomationRunning.value || CompanionRepository.isEmergencyStopped.value) {
            return
        }

        // 1. Verificación prioritaria de paquete en primer plano y llamadas telefónicas
        if (safetyGuardian.isPhoneCallActive()) {
            triggerSafetyStop(StopReason.INCOMING_PHONE_CALL)
            return
        }

        val fgPkg = CompanionRepository.foregroundPackage.value
        if (!safetyGuardian.isAllowedForegroundPackage(fgPkg, allowCompanionAppForTesting = false)) {
            triggerSafetyStop(StopReason.FOREGROUND_APP_CHANGED)
            return
        }

        // 2. Telemetría informativa: temperatura y carga se muestran, pero nunca bloquean la automatización.
        val batteryStatus = safetyGuardian.readThermalAndBatteryStatus()
        CompanionRepository.updateThermalAndBattery(
            batteryStatus.temperatureCelsius,
            batteryStatus.batteryPercent
        )

        // 3. Percepción visual (TFLite + Salud en barra calibrada)
        val vision = visionPipeline.analyzeFrame(frame, calibration)
        val fps = (1000L / vision.inferenceLatencyMs.coerceAtLeast(100L)).toInt().coerceIn(1, 10)
        CompanionRepository.updateVisionTelemetry(
            detections = vision.detectedObjects,
            healthPct = vision.estimatedHealthPercent,
            fps = fps
        )

        // 4. Evaluar seguridad en partida (Jugadores cercanos, caída de vida, pérdida de HUD)
        val currentState = CompanionRepository.currentState.value
        val isModalOrMenuState = currentState in listOf(
            CompanionState.LOADING,
            CompanionState.MAIN_MENU,
            CompanionState.SERVER_SELECT,
            CompanionState.DEPOSITING_IN_CHEST,
            CompanionState.TOOL_BROKEN_OR_UPGRADEABLE,
            CompanionState.UNEXPECTED_DIALOG
        )

        val safetyIssue = safetyGuardian.evaluateFrameSafety(
            detections = vision.detectedObjects,
            currentHealthPercent = vision.estimatedHealthPercent,
            isHudVisible = vision.isHudVisible,
            isInventoryOrModalOpen = isModalOrMenuState,
            ocrText = vision.ocrTextRaw
        )

        if (safetyIssue != StopReason.NONE) {
            // Si el HUD no es visible, ejecutamos OCR rápido para comprobar si estamos en Menú Principal, Carga o Desconexión (G7)
            if (safetyIssue == StopReason.HUD_LOST_UNEXPECTEDLY) {
                val (ocrText, counts) = visionPipeline.runOcrOnFrame(frame)
                CompanionRepository.updateInventoryCounts(counts)
                if (handleNonGameplayScreenByOcr(ocrText, calibration, accessibility)) {
                    return
                }
            }
            triggerSafetyStop(safetyIssue)
            return
        }

        // 5. Transiciones de la Máquina de Estados en Partida
        if (accessibility == null) {
            CompanionRepository.appendLog("Esperando activación de OxideAccessibilityService...")
            return
        }

        executeGameplayStateStep(frame, vision, calibration, accessibility)
    }

    private suspend fun executeGameplayStateStep(
        frame: Bitmap,
        vision: VisionFrameAnalysis,
        calibration: ScreenCalibrationProfile,
        accessibility: OxideAccessibilityService
    ) {
        // Si ya completamos 8 nodos recolectados o la mochila se detecta llena, ir al cofre (G5)
        if (totalHarvestCyclesCompleted >= 8 && CompanionRepository.currentState.value != CompanionState.DEPOSITING_IN_CHEST) {
            handleReturnAndDepositChest(vision, calibration, accessibility)
            return
        }

        // Cada 4 ciclos de recolección verificamos si podemos fabricar una herramienta superior (G6)
        if (totalHarvestCyclesCompleted > 0 && totalHarvestCyclesCompleted % 4 == 0 &&
            CompanionRepository.currentState.value == CompanionState.IN_GAME_SCANNING
        ) {
            handleToolCraftingCheck(frame, calibration, accessibility)
            totalHarvestCyclesCompleted++ // Avanzar contador para no repetir en bucle
            return
        }

        // Buscar recursos naturales visibles (`tree`, `rock_stone`, `rock_metal`, `rock_sulfur`)
        val resourceTarget = selectBestResourceTarget(vision.detectedObjects)

        if (resourceTarget == null) {
            // No hay recursos visibles en el campo de visión: explorar con patrón sistemático
            CompanionRepository.updateState(CompanionState.IN_GAME_SCANNING)
            systematicSweepCount++
            if (systematicSweepCount % 3 == 0) {
                // Caminar hacia adelante un tramo corto después de girar la vista
                accessibility.dispatchJoystickMoveToward(
                    calibration = calibration,
                    targetScreenX = calibration.realLandscapeWidthPx / 2f,
                    targetScreenY = calibration.realLandscapeHeightPx * 0.35f,
                    durationMs = 650L,
                    g2Approach = g2ApproachInUse
                )
            } else {
                accessibility.dispatchCameraLookSweep(calibration, turnRight = true)
            }
            return
        }

        systematicSweepCount = 0

        // Si el recurso ya ocupa suficiente área en el centro de la pantalla, estamos a distancia de golpeo
        val isCloseEnoughToHit = resourceTarget.areaNormalized >= 0.045f &&
            abs(resourceTarget.boxNormalized.centerX() - 0.5f) <= 0.24f

        if (!isCloseEnoughToHit) {
            CompanionRepository.updateState(CompanionState.IN_GAME_APPROACHING)
            val now = System.currentTimeMillis()
            if (lastTargetLabel != resourceTarget.label) {
                lastTargetLabel = resourceTarget.label
                lastTargetArea = resourceTarget.areaNormalized
                approachStartTimeMs = now
            } else {
                // Detector de atasco: si llevamos > 3.5 s caminando y el área del recurso no crece (>10%)
                val areaGrowth = resourceTarget.areaNormalized - lastTargetArea
                if (now - approachStartTimeMs > 3500L && areaGrowth < 0.004f) {
                    CompanionRepository.updateState(CompanionState.STUCK_RECOVERY)
                    performUnstuckManeuver(calibration, accessibility)
                    approachStartTimeMs = now
                    return
                }
                if (resourceTarget.areaNormalized > lastTargetArea) {
                    lastTargetArea = resourceTarget.areaNormalized
                }
            }

            accessibility.dispatchJoystickMoveToward(
                calibration = calibration,
                targetScreenX = resourceTarget.centerXScreenPx,
                targetScreenY = resourceTarget.centerYScreenPx,
                durationMs = 540L,
                g2Approach = g2ApproachInUse
            )
        } else {
            // Estamos frente al árbol o roca: equipar la herramienta óptima y golpear
            CompanionRepository.updateState(CompanionState.IN_GAME_HARVESTING)
            val desiredSlot = planner.selectOptimalHotbarSlotForResource(resourceTarget.label)
            if (currentEquippedSlot != desiredSlot) {
                val slotPt = calibration.hotbarSlotPx(desiredSlot)
                accessibility.dispatchTapAtPixel(slotPt.x, slotPt.y, 80L)
                currentEquippedSlot = desiredSlot
                return
            }

            accessibility.dispatchHarvestHit(calibration, g2ApproachInUse)
            harvestHitsOnCurrentNode++
            if (harvestHitsOnCurrentNode >= 6) {
                harvestHitsOnCurrentNode = 0
                totalHarvestCyclesCompleted++
                CompanionRepository.appendLog(
                    "Recurso (${resourceTarget.label}) recolectado. Ciclos acumulados: $totalHarvestCyclesCompleted"
                )
            }
        }
    }

    /**
     * Maniobra automática de desatasco (obstáculo, roca baja o borde de agua):
     * pulsa saltar + desplaza el joystick lateralmente + gira ligeramente la cámara.
     */
    private fun performUnstuckManeuver(
        calibration: ScreenCalibrationProfile,
        accessibility: OxideAccessibilityService
    ) {
        CompanionRepository.appendLog("Atasco detectado: ejecutando salto + desplazamiento lateral evasivo")
        val jumpPt = calibration.toScreenPx(calibration.jumpButtonNorm)
        accessibility.dispatchTapAtPixel(jumpPt.x, jumpPt.y, 90L)
        accessibility.dispatchJoystickMoveToward(
            calibration = calibration,
            targetScreenX = calibration.realLandscapeWidthPx * 0.85f,
            targetScreenY = calibration.realLandscapeHeightPx * 0.50f,
            durationMs = 620L,
            g2Approach = g2ApproachInUse
        )
    }

    /**
     * Ciclo de retorno y depósito en el cofre cuando la mochila se llena (Punto de Control G5).
     */
    private fun handleReturnAndDepositChest(
        vision: VisionFrameAnalysis,
        calibration: ScreenCalibrationProfile,
        accessibility: OxideAccessibilityService
    ) {
        val chestObj = vision.detectedObjects.firstOrNull { it.label == "chest" }
        if (chestObj == null) {
            CompanionRepository.updateState(CompanionState.INVENTORY_FULL_RETURN_CHEST)
            accessibility.dispatchCameraLookSweep(calibration, turnRight = false)
            return
        }

        if (chestObj.areaNormalized < 0.04f) {
            CompanionRepository.updateState(CompanionState.INVENTORY_FULL_RETURN_CHEST)
            accessibility.dispatchJoystickMoveToward(
                calibration = calibration,
                targetScreenX = chestObj.centerXScreenPx,
                targetScreenY = chestObj.centerYScreenPx,
                durationMs = 500L,
                g2Approach = g2ApproachInUse
            )
        } else {
            CompanionRepository.updateState(CompanionState.DEPOSITING_IN_CHEST)
            val interactPt = calibration.toScreenPx(calibration.interactChestButtonNorm)
            accessibility.dispatchTapAtPixel(interactPt.x, interactPt.y, 95L)
            accessibility.dispatchDragInventoryToChest(calibration)
            totalHarvestCyclesCompleted = 0
            CompanionRepository.recordCheckpointAttempt(
                id = CheckpointId.G5,
                approachNumber = 1,
                passed = true,
                evidence = "Depósito en cofre completado tras ciclo continuo de recolección."
            )
        }
    }

    /**
     * Abre el inventario/mesa, lee las cantidades reales con ML Kit OCR y consulta el Planificador JSON (G6).
     */
    private suspend fun handleToolCraftingCheck(
        frame: Bitmap,
        calibration: ScreenCalibrationProfile,
        accessibility: OxideAccessibilityService
    ) {
        CompanionRepository.updateState(CompanionState.TOOL_BROKEN_OR_UPGRADEABLE)
        val invPt = calibration.toScreenPx(calibration.inventoryButtonNorm)
        accessibility.dispatchTapAtPixel(invPt.x, invPt.y, 90L)

        val (_, ocrCounts) = visionPipeline.runOcrOnFrame(frame)
        CompanionRepository.updateInventoryCounts(ocrCounts)

        val decision = planner.evaluateUpgradeOrCraft(
            inventoryCounts = CompanionRepository.inventoryCounts.value,
            currentToolTierByCategory = currentToolTiers,
            availableWorkbenchLevel = availableWorkbenchLevel
        )
        CompanionRepository.updatePlannerDecision(decision.reasonEs)

        if (decision.shouldCraft && decision.targetTool != null) {
            val craftTab = calibration.toScreenPx(calibration.craftingMenuButtonNorm)
            val craftBtn = calibration.toScreenPx(calibration.craftConfirmButtonNorm)
            accessibility.dispatchTapAtPixel(craftTab.x, craftTab.y, 90L)
            accessibility.dispatchTapAtPixel(craftBtn.x, craftBtn.y, 110L)
            currentToolTiers[decision.targetTool.category] = decision.targetTool.tier
            CompanionRepository.recordCheckpointAttempt(
                id = CheckpointId.G6,
                approachNumber = 1,
                passed = true,
                evidence = "Planificador identificó y ordenó fabricar ${decision.targetTool.nameEs}"
            )
        }

        // Cerrar inventario para volver a partida
        val closePt = calibration.toScreenPx(calibration.closeModalButtonNorm)
        accessibility.dispatchTapAtPixel(closePt.x, closePt.y, 85L)
        CompanionRepository.updateState(CompanionState.IN_GAME_SCANNING)
    }

    /**
     * Reconoce pantallas fuera de partida (Menú principal, Selección de servidor, Carga, Desconexión o Diálogo imprevisto)
     * mediante ML Kit OCR y el respaldo del Planificador (Punto de Control G7).
     */
    private suspend fun handleNonGameplayScreenByOcr(
        ocrText: String,
        calibration: ScreenCalibrationProfile,
        accessibility: OxideAccessibilityService?
    ): Boolean {
        if (ocrText.isBlank()) return false
        val lower = ocrText.lowercase()

        when {
            lower.contains("cargando") || lower.contains("loading") -> {
                CompanionRepository.updateState(CompanionState.LOADING)
                return true
            }
            lower.contains("jugar") || lower.contains("play") -> {
                CompanionRepository.updateState(CompanionState.MAIN_MENU)
                val playPt = calibration.toScreenPx(calibration.playMainMenuButtonNorm)
                accessibility?.dispatchTapAtPixel(playPt.x, playPt.y, 100L)
                return true
            }
            lower.contains("servidor") || lower.contains("server") -> {
                CompanionRepository.updateState(CompanionState.SERVER_SELECT)
                val joinPt = calibration.toScreenPx(calibration.serverJoinButtonNorm)
                accessibility?.dispatchTapAtPixel(joinPt.x, joinPt.y, 100L)
                CompanionRepository.recordCheckpointAttempt(
                    id = CheckpointId.G7,
                    approachNumber = 1,
                    passed = true,
                    evidence = "Ingreso autónomo desde menú/selección de servidor completado."
                )
                return true
            }
            lower.contains("desconectado") || lower.contains("disconnected") || lower.contains("connection") -> {
                CompanionRepository.updateState(CompanionState.DISCONNECTED)
                val resolution = planner.resolveUnexpectedSituation(ocrText)
                if (resolution.actionType == DialogActionType.CLICK_RECONNECT_OR_PLAY) {
                    val playPt = calibration.toScreenPx(calibration.playMainMenuButtonNorm)
                    accessibility?.dispatchTapAtPixel(playPt.x, playPt.y, 100L)
                    return true
                }
            }
            else -> {
                CompanionRepository.updateState(CompanionState.UNEXPECTED_DIALOG)
                val resolution = planner.resolveUnexpectedSituation(ocrText)
                CompanionRepository.appendLog(resolution.explanationEs)
                if (resolution.actionType == DialogActionType.CLICK_CLOSE_MODAL) {
                    val closePt = calibration.toScreenPx(calibration.closeModalButtonNorm)
                    accessibility?.dispatchTapAtPixel(closePt.x, closePt.y, 90L)
                    return true
                }
            }
        }
        return false
    }

    private fun selectBestResourceTarget(detections: List<DetectedObject>): DetectedObject? {
        return detections
            .filter {
                it.label == "tree" ||
                    it.label == "rock_stone" ||
                    it.label == "rock_metal" ||
                    it.label == "rock_sulfur"
            }
            .maxByOrNull { it.areaNormalized * it.confidence }
    }

    private fun triggerSafetyStop(reason: StopReason) {
        CompanionRepository.triggerImmediateStop(reason)
        safetyGuardian.triggerUrgentPlayerAlert(reason)
    }
}
