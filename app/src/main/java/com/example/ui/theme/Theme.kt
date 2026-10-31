package com.example.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val OxideTacticalColorScheme = darkColorScheme(
    primary = OxideAmberPrimary,
    onPrimary = OxideCarbonBg,
    primaryContainer = OxideSlateElevated,
    onPrimaryContainer = OxideAmberBright,
    secondary = OxideRadarGreen,
    onSecondary = OxideCarbonBg,
    tertiary = OxideAmberBright,
    error = OxideAlertCrimson,
    onError = Color.White,
    background = OxideCarbonBg,
    onBackground = OxideTextPrimary,
    surface = OxideSlateSurface,
    onSurface = OxideTextPrimary,
    surfaceVariant = OxideSlateElevated,
    onSurfaceVariant = OxideTextSecondary,
    outline = OxideBorderSubtle
)

@Composable
fun MyApplicationTheme(
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = OxideTacticalColorScheme,
        typography = Typography,
        content = content
    )
}
