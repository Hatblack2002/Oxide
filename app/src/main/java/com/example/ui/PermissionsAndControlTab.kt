package com.example.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.model.CompanionState
import com.example.model.OverlayMode
import com.example.model.ScreenCalibrationProfile
import com.example.model.StopReason
import com.example.ui.theme.OxideAlertCrimson
import com.example.ui.theme.OxideAmberBright
import com.example.ui.theme.OxideAmberPrimary
import com.example.ui.theme.OxideBorderSubtle
import com.example.ui.theme.OxideCarbonBg
import com.example.ui.theme.OxideRadarGreen
import com.example.ui.theme.OxideSlateElevated
import com.example.ui.theme.OxideSlateSurface
import com.example.ui.theme.OxideTextSecondary
import com.example.ui.MistralVisionSettingsCard

/**
 * Pestaña 1: Centro de Permisos con Accesos Directos + Telemetría y Control de Servicio.
 */
@Composable
fun PermissionsAndControlTab(
    isAccessibilityEnabled: Boolean,
    isOverlayGranted: Boolean,
    isBatteryOptimizationIgnored: Boolean,
    isNotificationGranted: Boolean,
    isMediaProjectionActive: Boolean,
    isAutomationRunning: Boolean,
    currentState: CompanionState,
    lastStopReason: StopReason,
    overlayMode: OverlayMode,
    calibrationProfile: ScreenCalibrationProfile,
    batteryTempC: Float,
    batteryPct: Int,
    healthPct: Float,
    datasetSavedCount: Int,
    telemetryLogs: List<String>,
    onOpenAccessibilitySettings: () -> Unit,
    onOpenOverlaySettings: () -> Unit,
    onRequestIgnoreBatteryOptimizations: () -> Unit,
    onRequestNotificationPermission: () -> Unit,
    onStartMediaProjectionAndOverlay: () -> Unit,
    onEmergencyStopNow: () -> Unit,
    onCycleOverlayMode: () -> Unit
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Spacer(modifier = Modifier.height(4.dp))
            HeroTelemetryHeader(
                currentState = currentState,
                isMediaProjectionActive = isMediaProjectionActive,
                calibrationProfile = calibrationProfile,
                batteryTempC = batteryTempC,
                batteryPct = batteryPct,
                healthPct = healthPct,
                datasetSavedCount = datasetSavedCount
            )
        }

        // Tarjeta de Control Inmediato (Iniciar Captura / Botón Flotante / Parada de Emergencia)
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = OxideSlateSurface),
                border = BorderStroke(1.dp, if (isAutomationRunning) OxideRadarGreen else OxideAmberPrimary),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "CONTROL DE SERVICIO Y BOTÓN FLOTANTE",
                                style = MaterialTheme.typography.titleMedium,
                                color = OxideAmberBright
                            )
                            Text(
                                text = "Modo actual: ${overlayMode.labelEs} • Pantalla real: ${calibrationProfile.aspectRatioLabel}",
                                style = MaterialTheme.typography.bodyMedium,
                                color = OxideTextSecondary
                            )
                        }
                        OutlinedButton(
                            onClick = onCycleOverlayMode,
                            modifier = Modifier.testTag("cycle_overlay_mode_button")
                        ) {
                            Text("Cambiar Modo")
                        }
                    }

                    if (lastStopReason != StopReason.NONE) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(OxideAlertCrimson.copy(alpha = 0.18f))
                                .padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.Warning,
                                contentDescription = "Alerta de parada",
                                tint = OxideAlertCrimson
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = lastStopReason.titleEs,
                                style = MaterialTheme.typography.bodyMedium,
                                color = Color.White
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Button(
                            onClick = onStartMediaProjectionAndOverlay,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = OxideAmberPrimary,
                                contentColor = OxideCarbonBg
                            ),
                            modifier = Modifier
                                .weight(1f)
                                .height(50.dp)
                                .testTag("start_projection_overlay_button")
                        ) {
                            Icon(Icons.Default.PlayArrow, contentDescription = "Activar captura y botón flotante")
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = if (isMediaProjectionActive) "REINICIAR CAPTURA" else "ACTIVAR CAPTURA + OVERLAY",
                                fontWeight = FontWeight.Bold
                            )
                        }

                        Button(
                            onClick = onEmergencyStopNow,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = OxideAlertCrimson,
                                contentColor = Color.White
                            ),
                            modifier = Modifier
                                .height(50.dp)
                                .testTag("emergency_stop_button")
                        ) {
                            Icon(Icons.Default.Stop, contentDescription = "Detener ahora")
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("DETENER", fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }

        // Centro de Permisos con Accesos Directos a cada ajuste del sistema
        item {
            Text(
                text = "ACCESOS DIRECTOS A PERMISOS OBLIGATORIOS (ANDROID 12/13)",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.padding(top = 4.dp)
            )
        }

        item {
            PermissionShortcutCard(
                title = "1. Servicio de Accesibilidad (dispatchGesture)",
                subtitle = "Mueve el joystick virtual, golpea recursos, abre mochila/cofre y detecta si sales de Oxide.",
                isGranted = isAccessibilityEnabled,
                icon = Icons.Default.TouchApp,
                buttonLabel = if (isAccessibilityEnabled) "Configurado (Abrir Ajustes)" else "Activar Accesibilidad",
                testTag = "perm_accessibility_button",
                onClick = onOpenAccessibilitySettings
            )
        }

        item {
            PermissionShortcutCard(
                title = "2. Superposición sobre Otras Apps (Overlay)",
                subtitle = "Muestra el botón flotante Iniciar/Detener y las dianas de calibración sobre el juego.",
                isGranted = isOverlayGranted,
                icon = Icons.Default.Layers,
                buttonLabel = if (isOverlayGranted) "Permitido (Ver Ajuste)" else "Permitir Superposición",
                testTag = "perm_overlay_button",
                onClick = onOpenOverlaySettings
            )
        }

        item {
            PermissionShortcutCard(
                title = "3. Sin Restricción de Batería (30 min G3)",
                subtitle = "Evita que Android 12/13 cierre el servicio en primer plano mientras recolecta.",
                isGranted = isBatteryOptimizationIgnored,
                icon = Icons.Default.BatteryChargingFull,
                buttonLabel = if (isBatteryOptimizationIgnored) "Excluido de Ahorro" else "Desactivar Ahorro Batería",
                testTag = "perm_battery_button",
                onClick = onRequestIgnoreBatteryOptimizations
            )
        }

        item {
            PermissionShortcutCard(
                title = "4. Notificaciones Fijas y Alertas de Jugador",
                subtitle = "Necesario para el servicio MediaProjection y avisos inmediatos si aparece otro jugador.",
                isGranted = isNotificationGranted,
                icon = Icons.Default.NotificationsActive,
                buttonLabel = if (isNotificationGranted) "Notificaciones Activas" else "Conceder Notificaciones",
                testTag = "perm_notifications_button",
                onClick = onRequestNotificationPermission
            )
        }

        item {
            PermissionShortcutCard(
                title = "5. Captura de Pantalla MediaProjection (5-10 FPS)",
                subtitle = "Analiza árboles, rocas, cofre y HUD localmente sin modificar ni leer la memoria del juego.",
                isGranted = isMediaProjectionActive,
                icon = Icons.Default.Videocam,
                buttonLabel = if (isMediaProjectionActive) "Captura Activa" else "Conceder Captura Ahora",
                testTag = "perm_mediaprojection_button",
                onClick = onStartMediaProjectionAndOverlay
            )
        }

        item { MistralVisionSettingsCard() }

        // Registro de Telemetría en Vivo
        item {
            Text(
                text = "REGISTRO DE EVENTOS Y SEGURIDAD EN VIVO",
                style = MaterialTheme.typography.titleMedium,
                color = OxideAmberBright
            )
        }

        items(telemetryLogs.take(12)) { logLine ->
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(6.dp))
                    .background(OxideSlateElevated)
                    .padding(horizontal = 10.dp, vertical = 6.dp)
            ) {
                Text(
                    text = logLine,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        }

        item {
            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

@Composable
private fun HeroTelemetryHeader(
    currentState: CompanionState,
    isMediaProjectionActive: Boolean,
    calibrationProfile: ScreenCalibrationProfile,
    batteryTempC: Float,
    batteryPct: Int,
    healthPct: Float,
    datasetSavedCount: Int
) {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = OxideSlateSurface),
        border = BorderStroke(1.dp, OxideBorderSubtle),
        modifier = Modifier.fillMaxWidth()
    ) {
        Box(modifier = Modifier.fillMaxWidth()) {
            Image(
                painter = painterResource(id = R.drawable.img_tactical_banner),
                contentDescription = "Banner táctico de telemetría Oxide Companion",
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(148.dp)
            )
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(148.dp)
                    .background(OxideCarbonBg.copy(alpha = 0.72f))
                    .padding(14.dp)
            ) {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.Security,
                                contentDescription = "Estado seguro",
                                tint = OxideAmberBright,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "ESTADO: ${currentState.titleEs.uppercase()}",
                                style = MaterialTheme.typography.titleLarge,
                                color = Color.White
                            )
                        }
                        Text(
                            text = if (isMediaProjectionActive) "VISIÓN ON" else "VISIÓN OFF",
                            style = MaterialTheme.typography.labelMedium,
                            color = if (isMediaProjectionActive) OxideRadarGreen else OxideAmberBright
                        )
                    }

                    Text(
                        text = currentState.descriptionEs,
                        style = MaterialTheme.typography.bodyMedium,
                        color = OxideTextSecondary
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        TelemetryMetricBadge("PANTALLA REAL", calibrationProfile.aspectRatioLabel)
                        TelemetryMetricBadge("TEMP / BAT", "${"%.1f".format(batteryTempC)}°C • $batteryPct%")
                        TelemetryMetricBadge("SALUD / DATASET", "${healthPct.toInt()}% • $datasetSavedCount img")
                    }
                }
            }
        }
    }
}

@Composable
private fun TelemetryMetricBadge(label: String, value: String) {
    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(OxideSlateElevated.copy(alpha = 0.9f))
            .padding(horizontal = 8.dp, vertical = 4.dp)
    ) {
        Text(text = label, style = MaterialTheme.typography.labelMedium, color = OxideTextSecondary)
        Text(text = value, style = MaterialTheme.typography.bodyMedium, color = Color.White, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun PermissionShortcutCard(
    title: String,
    subtitle: String,
    isGranted: Boolean,
    icon: ImageVector,
    buttonLabel: String,
    testTag: String,
    onClick: () -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = OxideSlateSurface),
        border = BorderStroke(1.dp, if (isGranted) OxideRadarGreen.copy(alpha = 0.6f) else OxideBorderSubtle),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = if (isGranted) Icons.Default.CheckCircle else icon,
                    contentDescription = title,
                    tint = if (isGranted) OxideRadarGreen else OxideAmberBright,
                    modifier = Modifier.size(28.dp)
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium,
                        color = Color.White
                    )
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodyMedium,
                        color = OxideTextSecondary
                    )
                }
            }
            Spacer(modifier = Modifier.width(10.dp))
            OutlinedButton(
                onClick = onClick,
                modifier = Modifier.testTag(testTag)
            ) {
                Text(text = buttonLabel, style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}
