package com.example.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.model.CheckpointId
import com.example.model.CheckpointRecord
import com.example.model.CheckpointStatus
import com.example.model.ToolCategory
import com.example.planner.KnowledgeBasePlanner
import com.example.ui.theme.OxideAlertCrimson
import com.example.ui.theme.OxideAmberBright
import com.example.ui.theme.OxideAmberPrimary
import com.example.ui.theme.OxideBorderSubtle
import com.example.ui.theme.OxideCarbonBg
import com.example.ui.theme.OxideRadarGreen
import com.example.ui.theme.OxideSlateElevated
import com.example.ui.theme.OxideSlateSurface
import com.example.ui.theme.OxideTextSecondary

/**
 * Pestaña 3: Puntos de Control G1-G7 con Criterio de Abandono (máximo 3 enfoques)
 * + Simulador Interactivo del Planificador JSON (G6) y Guía del Pipeline TFLite (G4).
 */
@Composable
fun CheckpointsAndPlannerTab(
    checkpoints: Map<CheckpointId, CheckpointRecord>,
    planner: KnowledgeBasePlanner,
    canStartCheckpoint: (CheckpointId) -> Boolean,
    onRecordAttempt: (CheckpointId, Int, Boolean, String) -> Unit
) {
    var simWood by remember { mutableIntStateOf(150) }
    var simStone by remember { mutableIntStateOf(80) }
    var simMetal by remember { mutableIntStateOf(95) }
    var simWorkbench by remember { mutableIntStateOf(1) }

    val simulatedDecision = remember(simWood, simStone, simMetal, simWorkbench) {
        planner.evaluateUpgradeOrCraft(
            inventoryCounts = mapOf(
                "wood" to simWood,
                "stone" to simStone,
                "metal_fragments" to simMetal
            ),
            currentToolTierByCategory = mapOf(
                ToolCategory.WOODCUTTING to 1,
                ToolCategory.MINING to 1
            ),
            availableWorkbenchLevel = simWorkbench
        )
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Spacer(modifier = Modifier.height(4.dp))
            Card(
                colors = CardDefaults.cardColors(containerColor = OxideSlateSurface),
                border = BorderStroke(1.dp, OxideAmberPrimary),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "PLANIFICADOR DE CRAFTEO EN VIVO (G6 • JSON + OCR)",
                        style = MaterialTheme.typography.titleLarge,
                        color = OxideAmberBright
                    )
                    Text(
                        text = "Prueba el motor de decisión de oxide_knowledge_base.json con cantidades de inventario:",
                        style = MaterialTheme.typography.bodyMedium,
                        color = OxideTextSecondary
                    )
                    Spacer(modifier = Modifier.height(10.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        MaterialCounterControl("Madera", simWood, { simWood = (simWood + 50).coerceAtMost(1000) }) {
                            simWood = (simWood - 50).coerceAtLeast(0)
                        }
                        MaterialCounterControl("Piedra", simStone, { simStone = (simStone + 25).coerceAtMost(1000) }) {
                            simStone = (simStone - 25).coerceAtLeast(0)
                        }
                        MaterialCounterControl("Metal", simMetal, { simMetal = (simMetal + 25).coerceAtMost(1000) }) {
                            simMetal = (simMetal - 25).coerceAtLeast(0)
                        }
                        MaterialCounterControl("Mesa Nv.", simWorkbench, { simWorkbench = (simWorkbench + 1).coerceAtMost(2) }) {
                            simWorkbench = (simWorkbench - 1).coerceAtLeast(0)
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(
                                if (simulatedDecision.shouldCraft) OxideRadarGreen.copy(alpha = 0.2f)
                                else OxideSlateElevated
                            )
                            .padding(10.dp)
                            .testTag("planner_decision_box")
                    ) {
                        Text(
                            text = "Decisión G6: ${simulatedDecision.reasonEs}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color.White,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }

        item {
            Text(
                text = "PUNTOS DE CONTROL G1 A G7 (ORDEN ESTRICTO Y CRITERIO DE ABANDONO)",
                style = MaterialTheme.typography.titleLarge,
                color = Color.White
            )
            Text(
                text = "Avanza en orden. Si un punto falla en sus 3 enfoques legítimos, el sistema se detiene y genera el informe de hallazgo.",
                style = MaterialTheme.typography.bodyMedium,
                color = OxideTextSecondary
            )
        }

        items(CheckpointId.entries) { id ->
            val record = checkpoints[id] ?: CheckpointRecord(id)
            val unlocked = canStartCheckpoint(id)
            CheckpointCardItem(
                id = id,
                record = record,
                isUnlocked = unlocked,
                onRecordAttempt = onRecordAttempt
            )
        }

        item {
            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

@Composable
private fun MaterialCounterControl(
    label: String,
    value: Int,
    onIncrement: () -> Unit,
    onDecrement: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(OxideSlateElevated)
            .padding(horizontal = 8.dp, vertical = 6.dp)
    ) {
        Text(text = label, style = MaterialTheme.typography.labelMedium, color = OxideTextSecondary)
        Text(text = value.toString(), style = MaterialTheme.typography.titleMedium, color = Color.White)
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            OutlinedButton(onClick = onDecrement) { Text("-") }
            OutlinedButton(onClick = onIncrement) { Text("+") }
        }
    }
}

@Composable
private fun CheckpointCardItem(
    id: CheckpointId,
    record: CheckpointRecord,
    isUnlocked: Boolean,
    onRecordAttempt: (CheckpointId, Int, Boolean, String) -> Unit
) {
    val borderColor = when (record.status) {
        CheckpointStatus.PASSED -> OxideRadarGreen
        CheckpointStatus.FAILED_ABANDONED -> OxideAlertCrimson
        CheckpointStatus.IN_PROGRESS -> OxideAmberBright
        CheckpointStatus.PENDING -> OxideBorderSubtle
    }

    Card(
        colors = CardDefaults.cardColors(containerColor = OxideSlateSurface),
        border = BorderStroke(1.dp, borderColor),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("checkpoint_card_${id.code}")
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "${id.code} • ${id.titleEs}",
                    style = MaterialTheme.typography.titleMedium,
                    color = Color.White
                )
                Text(
                    text = if (!isUnlocked) "BLOQUEADO (Pasa el anterior)" else record.status.labelEs,
                    style = MaterialTheme.typography.labelMedium,
                    color = borderColor
                )
            }

            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = id.goalEs,
                style = MaterialTheme.typography.bodyMedium,
                color = OxideTextSecondary
            )

            Spacer(modifier = Modifier.height(8.dp))
            id.approachesEs.forEachIndexed { idx, approachText ->
                val num = idx + 1
                val attempt = record.attempts.find { it.approachNumber == num }
                val statusPrefix = when {
                    attempt == null -> "[Pendiente]"
                    attempt.passed -> "[PASÓ]"
                    else -> "[FALLÓ]"
                }
                Text(
                    text = "$statusPrefix $approachText",
                    style = MaterialTheme.typography.bodyMedium,
                    color = when {
                        attempt == null -> OxideTextSecondary
                        attempt.passed -> OxideRadarGreen
                        else -> OxideAlertCrimson
                    }
                )
            }

            if (record.abandonmentReport != null) {
                Spacer(modifier = Modifier.height(8.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(OxideAlertCrimson.copy(alpha = 0.16f))
                        .padding(10.dp)
                ) {
                    Text(
                        text = record.abandonmentReport,
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White
                    )
                }
            }

            if (isUnlocked && record.status != CheckpointStatus.FAILED_ABANDONED) {
                Spacer(modifier = Modifier.height(10.dp))
                val nextApproach = (record.attempts.size + 1).coerceIn(1, 3)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = {
                            onRecordAttempt(
                                id,
                                nextApproach,
                                true,
                                "Validado exitosamente en el dispositivo usando el Enfoque $nextApproach."
                            )
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = OxideRadarGreen,
                            contentColor = OxideCarbonBg
                        ),
                        modifier = Modifier
                            .weight(1f)
                            .testTag("pass_btn_${id.code}")
                    ) {
                        Text("Marcar ${id.code} Superado (Enf. $nextApproach)", fontWeight = FontWeight.Bold)
                    }

                    OutlinedButton(
                        onClick = {
                            onRecordAttempt(
                                id,
                                nextApproach,
                                false,
                                "El Enfoque $nextApproach no logró el umbral esperado en prueba de campo."
                            )
                        },
                        modifier = Modifier.testTag("fail_btn_${id.code}")
                    ) {
                        Text("Falló Enf. $nextApproach")
                    }
                }
            }
        }
    }
}
