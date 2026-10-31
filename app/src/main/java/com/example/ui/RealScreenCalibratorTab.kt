package com.example.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CenterFocusStrong
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.model.ScreenCalibrationProfile
import com.example.ui.theme.OxideAmberBright
import com.example.ui.theme.OxideAmberPrimary
import com.example.ui.theme.OxideBorderSubtle
import com.example.ui.theme.OxideCarbonBg
import com.example.ui.theme.OxideRadarGreen
import com.example.ui.theme.OxideSlateElevated
import com.example.ui.theme.OxideSlateSurface
import com.example.ui.theme.OxideTextSecondary

/**
 * Pestaña 2: Calibrador Dinámico de Pantalla Real (Multi-Aspecto: 20:9, 19.5:9, 16:9, etc.).
 * Nunca asume una proporción fija de 16:9: mide la geometría real del celular, compensa el anclaje
 * de bordes de Unity y permite arrastrar las dianas tanto en el lienzo con la proporción exacta
 * del teléfono como en vivo encima del juego.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RealScreenCalibratorTab(
    calibrationProfile: ScreenCalibrationProfile,
    onUpdateAnchor: (anchorId: String, normX: Float, normY: Float) -> Unit,
    onResetToDetectedRealScreen: () -> Unit,
    onLaunchLiveOverlayCalibration: () -> Unit
) {
    val anchors = calibrationProfile.allEditableAnchors()
    var selectedAnchorId by remember { mutableStateOf(anchors.first().id) }
    val selectedAnchor = anchors.find { it.id == selectedAnchorId } ?: anchors.first()

    val exactPx = selectedAnchor.toScreenPx(
        calibrationProfile.realLandscapeWidthPx,
        calibrationProfile.realLandscapeHeightPx
    )

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
                        text = "GEOMETRÍA DETECTADA EN TU PANTALLA REAL",
                        style = MaterialTheme.typography.titleLarge,
                        color = OxideAmberBright
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Resolución horizontal nativa: ${calibrationProfile.realLandscapeWidthPx} × ${calibrationProfile.realLandscapeHeightPx} px • Proporción: ${calibrationProfile.aspectRatioLabel} • Densidad: ${calibrationProfile.densityDpi} dpi",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White
                    )
                    Text(
                        text = "Cutout/Notch seguro: Izq=${calibrationProfile.cutoutSafeLeftPx}px, Der=${calibrationProfile.cutoutSafeRightPx}px • Estado: ${if (calibrationProfile.isUserCalibrated) "Calibrado por el usuario" else "Auto-anclado a bordes reales"}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = OxideTextSecondary
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Button(
                            onClick = onLaunchLiveOverlayCalibration,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = OxideAmberPrimary,
                                contentColor = OxideCarbonBg
                            ),
                            modifier = Modifier
                                .weight(1f)
                                .testTag("launch_live_calibration_overlay_button")
                        ) {
                            Icon(Icons.Default.CenterFocusStrong, contentDescription = "Calibrar sobre el juego")
                            Spacer(modifier = Modifier.padding(4.dp))
                            Text("CALIBRAR EN VIVO SOBRE OXIDE", fontWeight = FontWeight.Bold)
                        }
                        OutlinedButton(
                            onClick = onResetToDetectedRealScreen,
                            modifier = Modifier.testTag("reset_calibration_button")
                        ) {
                            Icon(Icons.Default.Refresh, contentDescription = "Restablecer")
                            Spacer(modifier = Modifier.padding(2.dp))
                            Text("Auto-Anclar")
                        }
                    }
                }
            }
        }

        // Lienzo interactivo con la relación de aspecto exacta del dispositivo real (20:9, 19.5:9, 16:9, etc.)
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = OxideSlateSurface),
                border = BorderStroke(1.dp, OxideBorderSubtle),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text(
                        text = "MAPA INTERACTIVO DEL HUD (${calibrationProfile.aspectRatioLabel})",
                        style = MaterialTheme.typography.titleMedium,
                        color = Color.White
                    )
                    Text(
                        text = "Toca o arrastra en el lienzo para ubicar '${selectedAnchor.nameEs}' -> Píxel real: (${exactPx.x.toInt()}, ${exactPx.y.toInt()}) px",
                        style = MaterialTheme.typography.bodyMedium,
                        color = OxideAmberBright
                    )
                    Spacer(modifier = Modifier.height(10.dp))

                    val aspect = calibrationProfile.aspectRatioFloat.coerceIn(1.5f, 2.5f)
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(aspect)
                            .clip(RoundedCornerShape(10.dp))
                            .background(OxideCarbonBg)
                            .testTag("real_screen_calibration_canvas")
                    ) {
                        Canvas(
                            modifier = Modifier
                                .fillMaxSize()
                                .pointerInput(selectedAnchorId) {
                                    detectTapGestures { offset ->
                                        if (size.width > 0 && size.height > 0) {
                                            onUpdateAnchor(
                                                selectedAnchorId,
                                                offset.x / size.width.toFloat(),
                                                offset.y / size.height.toFloat()
                                            )
                                        }
                                    }
                                }
                                .pointerInput(selectedAnchorId) {
                                    detectDragGestures { change, _ ->
                                        change.consume()
                                        if (size.width > 0 && size.height > 0) {
                                            onUpdateAnchor(
                                                selectedAnchorId,
                                                change.position.x / size.width.toFloat(),
                                                change.position.y / size.height.toFloat()
                                            )
                                        }
                                    }
                                }
                        ) {
                            val w = size.width
                            val h = size.height

                            // Cuadrícula táctica de referencia proporcional a la pantalla real
                            for (i in 1..9) {
                                val x = w * (i / 10f)
                                drawLine(
                                    color = OxideBorderSubtle.copy(alpha = 0.45f),
                                    start = Offset(x, 0f),
                                    end = Offset(x, h),
                                    strokeWidth = 1f
                                )
                            }
                            for (j in 1..4) {
                                val y = h * (j / 5f)
                                drawLine(
                                    color = OxideBorderSubtle.copy(alpha = 0.45f),
                                    start = Offset(0f, y),
                                    end = Offset(w, y),
                                    strokeWidth = 1f
                                )
                            }

                            // Rectángulo de la Barra de Vida
                            val hb = calibrationProfile.healthBarRectNorm
                            drawRect(
                                color = OxideRadarGreen.copy(alpha = 0.35f),
                                topLeft = Offset(hb.left * w, hb.top * h),
                                size = Size(hb.width() * w, hb.height() * h)
                            )

                            // Radio del Joystick Virtual
                            val jc = calibrationProfile.joystickCenterNorm
                            val jr = calibrationProfile.joystickRadiusNormWidth * w
                            drawCircle(
                                color = OxideAmberPrimary.copy(alpha = 0.25f),
                                radius = jr,
                                center = Offset(jc.x * w, jc.y * h)
                            )
                            drawCircle(
                                color = OxideAmberPrimary,
                                radius = jr,
                                center = Offset(jc.x * w, jc.y * h),
                                style = Stroke(width = 2.5f)
                            )

                            // Dibujar todas las dianas calibrables
                            anchors.forEach { anchor ->
                                val cx = anchor.normX * w
                                val cy = anchor.normY * h
                                val isSelected = anchor.id == selectedAnchorId
                                val color = if (isSelected) OxideRadarGreen else OxideAmberBright
                                drawCircle(
                                    color = color,
                                    radius = if (isSelected) 16f else 10f,
                                    center = Offset(cx, cy)
                                )
                                drawCircle(
                                    color = Color.Black,
                                    radius = 4f,
                                    center = Offset(cx, cy)
                                )
                            }
                        }
                    }
                }
            }
        }

        // Selector de control del HUD a calibrar
        item {
            Text(
                text = "SELECCIONA EL PUNTO DEL HUD PARA AJUSTAR:",
                style = MaterialTheme.typography.titleMedium,
                color = Color.White
            )
            Spacer(modifier = Modifier.height(6.dp))
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                anchors.forEach { anchor ->
                    val px = anchor.toScreenPx(
                        calibrationProfile.realLandscapeWidthPx,
                        calibrationProfile.realLandscapeHeightPx
                    )
                    FilterChip(
                        selected = anchor.id == selectedAnchorId,
                        onClick = { selectedAnchorId = anchor.id },
                        label = {
                            Text("${anchor.nameEs} (${px.x.toInt()},${px.y.toInt()})")
                        },
                        modifier = Modifier.testTag("anchor_chip_${anchor.id}")
                    )
                }
            }
        }

        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = OxideSlateElevated),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(
                        text = "¿Por qué calibramos según tu pantalla real y no asumimos 16:9?",
                        style = MaterialTheme.typography.titleMedium,
                        color = OxideAmberBright
                    )
                    Text(
                        text = "En celulares modernos 20:9 o 19.5:9 (como 2400x1080), Oxide ancla el joystick y los botones de acción a los bordes laterales izquierdo y derecho mientras centra el hotbar. Este calibrador calcula los anclajes según tu resolución real y te permite arrastrar las dianas directamente encima del juego.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = OxideTextSecondary
                    )
                }
            }
            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}
