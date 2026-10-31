package com.example

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.WindowInsets
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets as ComposeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.FactCheck
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.model.OverlayMode
import com.example.model.StopReason
import com.example.planner.KnowledgeBasePlanner
import com.example.service.CompanionForegroundService
import com.example.service.CompanionRepository
import com.example.service.OxideAccessibilityService
import com.example.ui.CheckpointsAndPlannerTab
import com.example.ui.PermissionsAndControlTab
import com.example.ui.RealScreenCalibratorTab
import com.example.ui.theme.MyApplicationTheme
import kotlin.math.max
import kotlin.math.min

/**
 * Actividad Principal del Compañero de Recolección de Oxide: Survival Island.
 * - Mide las dimensiones reales de la pantalla en horizontal (20:9, 19.5:9, 16:9) y los márgenes de cutout/notch.
 * - Proporciona accesos directos inmediatos a cada permiso requerido en Android 12/13+.
 * - Permite calibrar el HUD según la pantalla real, revisar el planificador JSON y validar G1..G7.
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        initializeRealScreenMetrics()

        setContent {
            MyApplicationTheme {
                OxideCompanionApp(
                    onStartProjectionService = { resultCode, data ->
                        startCompanionForegroundService(resultCode, data)
                    },
                    onShowOverlayOnly = {
                        startOverlayOnlyService()
                    },
                    onEmergencyStop = {
                        CompanionRepository.triggerImmediateStop(StopReason.USER_MANUAL_STOP)
                    }
                )
            }
        }
    }

    /**
     * Detecta la resolución horizontal real del celular (sin asumir 16:9) y los recortes de cámara (notch/cutout).
     */
    private fun initializeRealScreenMetrics() {
        val dm = resources.displayMetrics
        var rawW = dm.widthPixels
        var rawH = dm.heightPixels
        var cutoutLeft = 0
        var cutoutRight = 0

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val metrics = windowManager.currentWindowMetrics
            val bounds = metrics.bounds
            rawW = bounds.width()
            rawH = bounds.height()
            val cutout = metrics.windowInsets.getInsetsIgnoringVisibility(WindowInsets.Type.displayCutout())
            cutoutLeft = max(cutout.left, cutout.top)
            cutoutRight = max(cutout.right, cutout.bottom)
        }

        val realLandscapeW = max(rawW, rawH).coerceAtLeast(1280)
        val realLandscapeH = min(rawW, rawH).coerceAtLeast(720)

        CompanionRepository.initialize(
            context = this,
            realLandscapeWidthPx = realLandscapeW,
            realLandscapeHeightPx = realLandscapeH,
            densityDpi = dm.densityDpi,
            cutoutLeftPx = cutoutLeft,
            cutoutRightPx = cutoutRight
        )
    }

    private fun startCompanionForegroundService(resultCode: Int, resultData: Intent) {
        val serviceIntent = Intent(this, CompanionForegroundService::class.java).apply {
            action = CompanionForegroundService.ACTION_START_WITH_PROJECTION
            putExtra(CompanionForegroundService.EXTRA_RESULT_CODE, resultCode)
            putExtra(CompanionForegroundService.EXTRA_RESULT_INTENT, resultData)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }
    }

    private fun startOverlayOnlyService() {
        val serviceIntent = Intent(this, CompanionForegroundService::class.java).apply {
            action = CompanionForegroundService.ACTION_SHOW_OVERLAY_ONLY
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }
    }
}

@Composable
fun OxideCompanionApp(
    onStartProjectionService: (Int, Intent) -> Unit,
    onShowOverlayOnly: () -> Unit,
    onEmergencyStop: () -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val planner = remember { KnowledgeBasePlanner(context) }

    var selectedTab by rememberSaveable { mutableIntStateOf(0) }

    // Estados del repositorio en tiempo real
    val currentState by CompanionRepository.currentState.collectAsState()
    val overlayMode by CompanionRepository.overlayMode.collectAsState()
    val isAutomationRunning by CompanionRepository.isAutomationRunning.collectAsState()
    val lastStopReason by CompanionRepository.lastStopReason.collectAsState()
    val isMediaProjectionActive by CompanionRepository.isMediaProjectionActive.collectAsState()
    val calibrationProfile by CompanionRepository.calibrationProfile.collectAsState()
    val batteryTempC by CompanionRepository.batteryTempCelsius.collectAsState()
    val batteryPct by CompanionRepository.batteryPercent.collectAsState()
    val healthPct by CompanionRepository.estimatedHealthPercent.collectAsState()
    val datasetSavedCount by CompanionRepository.datasetSavedCount.collectAsState()
    val telemetryLogs by CompanionRepository.telemetryLogs.collectAsState()
    val checkpoints by CompanionRepository.checkpoints.collectAsState()

    // Verificación de permisos del sistema al volver de Ajustes
    var isAccessibilityEnabled by remember { mutableStateOf(checkAccessibilityEnabled(context)) }
    var isOverlayGranted by remember { mutableStateOf(Settings.canDrawOverlays(context)) }
    var isBatteryOptimizationIgnored by remember { mutableStateOf(checkBatteryIgnored(context)) }
    var isNotificationGranted by remember { mutableStateOf(checkNotificationGranted(context)) }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                isAccessibilityEnabled = checkAccessibilityEnabled(context)
                isOverlayGranted = Settings.canDrawOverlays(context)
                isBatteryOptimizationIgnored = checkBatteryIgnored(context)
                isNotificationGranted = checkNotificationGranted(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val notificationLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        isNotificationGranted = granted
    }

    val mediaProjectionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            onStartProjectionService(result.resultCode, result.data!!)
        }
    }

    if (selectedTab != 0) {
        BackHandler {
            selectedTab = 0
        }
    }

    Scaffold(
        contentWindowInsets = ComposeWindowInsets.safeDrawing,
        bottomBar = {
            NavigationBar(windowInsets = ComposeWindowInsets.navigationBars) {
                NavigationBarItem(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    icon = { Icon(Icons.Default.Shield, contentDescription = "Permisos y Control") },
                    label = { Text("Permisos y Control") },
                    modifier = Modifier.testTag("nav_tab_permissions")
                )
                NavigationBarItem(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    icon = { Icon(Icons.Default.Build, contentDescription = "Calibrador HUD Real") },
                    label = { Text("Calibrar Pantalla Real") },
                    modifier = Modifier.testTag("nav_tab_calibrator")
                )
                NavigationBarItem(
                    selected = selectedTab == 2,
                    onClick = { selectedTab = 2 },
                    icon = { Icon(Icons.AutoMirrored.Filled.FactCheck, contentDescription = "Puntos de Control G1-G7") },
                    label = { Text("Checkpoints G1-G7") },
                    modifier = Modifier.testTag("nav_tab_checkpoints")
                )
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            when (selectedTab) {
                0 -> PermissionsAndControlTab(
                    isAccessibilityEnabled = isAccessibilityEnabled,
                    isOverlayGranted = isOverlayGranted,
                    isBatteryOptimizationIgnored = isBatteryOptimizationIgnored,
                    isNotificationGranted = isNotificationGranted,
                    isMediaProjectionActive = isMediaProjectionActive,
                    isAutomationRunning = isAutomationRunning,
                    currentState = currentState,
                    lastStopReason = lastStopReason,
                    overlayMode = overlayMode,
                    calibrationProfile = calibrationProfile,
                    batteryTempC = batteryTempC,
                    batteryPct = batteryPct,
                    healthPct = healthPct,
                    datasetSavedCount = datasetSavedCount,
                    telemetryLogs = telemetryLogs,
                    onOpenAccessibilitySettings = {
                        context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        })
                    },
                    onOpenOverlaySettings = {
                        val intent = Intent(
                            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:${context.packageName}")
                        )
                        context.startActivity(intent)
                    },
                    onRequestIgnoreBatteryOptimizations = {
                        try {
                            val intent = Intent(
                                Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                                Uri.parse("package:${context.packageName}")
                            )
                            context.startActivity(intent)
                        } catch (_: Exception) {
                            context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                        }
                    },
                    onRequestNotificationPermission = {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        }
                    },
                    onStartMediaProjectionAndOverlay = {
                        val mpManager = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                        mediaProjectionLauncher.launch(mpManager.createScreenCaptureIntent())
                    },
                    onEmergencyStopNow = onEmergencyStop,
                    onCycleOverlayMode = {
                        val modes = OverlayMode.entries
                        val next = modes[(overlayMode.ordinal + 1) % modes.size]
                        CompanionRepository.setOverlayMode(next)
                    }
                )

                1 -> RealScreenCalibratorTab(
                    calibrationProfile = calibrationProfile,
                    onUpdateAnchor = { anchorId, nx, ny ->
                        val updated = calibrationProfile.withUpdatedAnchor(anchorId, nx, ny)
                        CompanionRepository.updateCalibrationProfile(updated)
                    },
                    onResetToDetectedRealScreen = {
                        CompanionRepository.resetCalibrationToRealScreen(
                            realLandscapeWidthPx = calibrationProfile.realLandscapeWidthPx,
                            realLandscapeHeightPx = calibrationProfile.realLandscapeHeightPx,
                            densityDpi = calibrationProfile.densityDpi,
                            cutoutLeftPx = calibrationProfile.cutoutSafeLeftPx,
                            cutoutRightPx = calibrationProfile.cutoutSafeRightPx
                        )
                    },
                    onLaunchLiveOverlayCalibration = {
                        CompanionRepository.setOverlayMode(OverlayMode.LIVE_HUD_CALIBRATION)
                        onShowOverlayOnly()
                    }
                )

                2 -> CheckpointsAndPlannerTab(
                    checkpoints = checkpoints,
                    planner = planner,
                    canStartCheckpoint = { id -> CompanionRepository.canStartCheckpoint(id) },
                    onRecordAttempt = { id, approach, passed, evidence ->
                        CompanionRepository.recordCheckpointAttempt(id, approach, passed, evidence)
                    }
                )
            }
        }
    }
}

private fun checkAccessibilityEnabled(context: Context): Boolean {
    if (OxideAccessibilityService.instance != null) return true
    val expectedComponent = "${context.packageName}/${OxideAccessibilityService::class.java.canonicalName}"
    val enabledServices = Settings.Secure.getString(
        context.contentResolver,
        Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
    ) ?: return false
    return enabledServices.contains(expectedComponent, ignoreCase = true)
}

private fun checkBatteryIgnored(context: Context): Boolean {
    val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return false
    return pm.isIgnoringBatteryOptimizations(context.packageName)
}

private fun checkNotificationGranted(context: Context): Boolean {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
    } else {
        true
    }
}
