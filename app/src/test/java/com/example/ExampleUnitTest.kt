package com.example

import com.example.model.CheckpointId
import com.example.model.CheckpointStatus
import com.example.model.ScreenCalibrationProfile
import com.example.model.ToolCategory
import com.example.planner.KnowledgeBasePlanner
import com.example.service.CompanionRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Pruebas unitarias con Robolectric para verificar:
 * 1. Calibración dinámica en pantallas reales (20:9 vs 16:9) sin asumir 16:9 fijo.
 * 2. Planificador JSON (Punto de Control G6) identificando cuándo fabricar una herramienta superior.
 * 3. Regla de orden estricto y criterio de abandono tras 3 enfoques fallidos en G1..G7.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ExampleUnitTest {

    @Test
    fun realScreenCalibration_adaptsTo20By9And16By9WithoutFixedAssumption() {
        val profile20by9 = ScreenCalibrationProfile.createAdaptiveDefault(
            landscapeWidthPx = 2400,
            landscapeHeightPx = 1080,
            densityDpi = 420,
            cutoutLeftPx = 80,
            cutoutRightPx = 0
        )
        val profile16by9 = ScreenCalibrationProfile.createAdaptiveDefault(
            landscapeWidthPx = 1920,
            landscapeHeightPx = 1080,
            densityDpi = 320,
            cutoutLeftPx = 0,
            cutoutRightPx = 0
        )

        assertTrue(profile20by9.aspectRatioLabel.startsWith("20:9"))
        assertTrue(profile16by9.aspectRatioLabel.startsWith("16:9"))
        // En 20:9 con cutout izquierdo, la posición normalizada del joystick se ajusta respecto a 16:9
        assertTrue(profile20by9.joystickCenterNorm.x != profile16by9.joystickCenterNorm.x)

        // Verificar actualización manual de un anclaje por el usuario sobre su pantalla real
        val updated = profile20by9.withUpdatedAnchor("attack", 0.88f, 0.74f)
        assertTrue(updated.isUserCalibrated)
        assertEquals(0.88f, updated.attackButtonNorm.x, 0.001f)
        assertEquals(0.74f, updated.attackButtonNorm.y, 0.001f)
    }

    @Test
    fun checkpointG6_plannerIdentifiesWhenBetterToolCanBeCrafted() {
        val jsonFile = File("src/main/assets/oxide_knowledge_base.json")
        val jsonText = if (jsonFile.exists()) {
            jsonFile.readText()
        } else {
            File("app/src/main/assets/oxide_knowledge_base.json").readText()
        }

        val planner = KnowledgeBasePlanner(null)
        val parsedTools = planner.parseToolsJson(jsonText)
        assertTrue("Debe cargar las herramientas del JSON", parsedTools.size >= 5)

        val upgrades = parsedTools.filter {
            it.category == ToolCategory.WOODCUTTING && it.tier > 1 && it.workbenchRequired <= 1
        }
        assertTrue(upgrades.any { it.id == "metal_hatchet" })
    }

    @Test
    fun checkpoints_abandonmentReportGeneratedAfterThreeFailedApproaches() {
        CompanionRepository.recordCheckpointAttempt(CheckpointId.G1, 1, false, "Fallo enfoque 1")
        CompanionRepository.recordCheckpointAttempt(CheckpointId.G1, 2, false, "Fallo enfoque 2")
        CompanionRepository.recordCheckpointAttempt(CheckpointId.G1, 3, false, "Fallo enfoque 3")

        val g1Record = CompanionRepository.checkpoints.value[CheckpointId.G1]
        assertNotNull(g1Record)
        assertEquals(CheckpointStatus.FAILED_ABANDONED, g1Record?.status)
        assertNotNull("Debe generar informe de hallazgo y abandono al fallar los 3 enfoques", g1Record?.abandonmentReport)
        assertFalse("G2 no debe desbloquearse si G1 fue abandonado", CompanionRepository.canStartCheckpoint(CheckpointId.G2))
    }
}
