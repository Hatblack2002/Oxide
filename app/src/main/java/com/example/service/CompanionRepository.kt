package com.example.service

import android.content.Context
import android.content.SharedPreferences
import android.graphics.PointF
import android.graphics.RectF
import com.example.model.ApproachAttempt
import com.example.model.CheckpointId
import com.example.model.CheckpointRecord
import com.example.model.CheckpointStatus
import com.example.model.CompanionState
import com.example.model.DetectedObject
import com.example.model.OverlayMode
import com.example.model.ScreenCalibrationProfile
import com.example.model.StopReason
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Repositorio central de estado, telemetría en vivo, calibración de pantalla real y puntos de control G1-G7.
 * Conecta en tiempo real el Servicio en Primer Plano, el AccessibilityService, el Botón Flotante y la UI Compose.
 */
object CompanionRepository {

    private const val PREFS_NAME = "oxide_companion_prefs"
    private const val KEY_CALIBRATION_JSON = "screen_calibration_profile_v2"
    private const val KEY_CHECKPOINTS_JSON = "checkpoints_records_v1"

    private val _currentState = MutableStateFlow(CompanionState.IDLE)
    val currentState: StateFlow<CompanionState> = _currentState.asStateFlow()

    private val _overlayMode = MutableStateFlow(OverlayMode.AUTONOMOUS_GATHER)
    val overlayMode: StateFlow<OverlayMode> = _overlayMode.asStateFlow()

    private val _isAutomationRunning = MutableStateFlow(false)
    val isAutomationRunning: StateFlow<Boolean> = _isAutomationRunning.asStateFlow()

    private val _isEmergencyStopped = MutableStateFlow(false)
    val isEmergencyStopped: StateFlow<Boolean> = _isEmergencyStopped.asStateFlow()

    private val _lastStopReason = MutableStateFlow(StopReason.NONE)
    val lastStopReason: StateFlow<StopReason> = _lastStopReason.asStateFlow()

    private val _isAccessibilityConnected = MutableStateFlow(false)
    val isAccessibilityConnected: StateFlow<Boolean> = _isAccessibilityConnected.asStateFlow()

    private val _isMediaProjectionActive = MutableStateFlow(false)
    val isMediaProjectionActive: StateFlow<Boolean> = _isMediaProjectionActive.asStateFlow()

    private val _foregroundPackage = MutableStateFlow("")
    val foregroundPackage: StateFlow<String> = _foregroundPackage.asStateFlow()

    private val _calibrationProfile = MutableStateFlow(
        ScreenCalibrationProfile.createAdaptiveDefault(2400, 1080, 420)
    )
    val calibrationProfile: StateFlow<ScreenCalibrationProfile> = _calibrationProfile.asStateFlow()

    private val _latestDetections = MutableStateFlow<List<DetectedObject>>(emptyList())
    val latestDetections: StateFlow<List<DetectedObject>> = _latestDetections.asStateFlow()

    private val _inventoryCounts = MutableStateFlow<Map<String, Int>>(
        mapOf("wood" to 0, "stone" to 0, "metal_fragments" to 0, "sulfur_ore" to 0)
    )
    val inventoryCounts: StateFlow<Map<String, Int>> = _inventoryCounts.asStateFlow()

    private val _batteryTempCelsius = MutableStateFlow(31.5f)
    val batteryTempCelsius: StateFlow<Float> = _batteryTempCelsius.asStateFlow()

    private val _batteryPercent = MutableStateFlow(85)
    val batteryPercent: StateFlow<Int> = _batteryPercent.asStateFlow()

    private val _estimatedHealthPercent = MutableStateFlow(100f)
    val estimatedHealthPercent: StateFlow<Float> = _estimatedHealthPercent.asStateFlow()

    private val _currentFps = MutableStateFlow(0)
    val currentFps: StateFlow<Int> = _currentFps.asStateFlow()

    private val _datasetSavedCount = MutableStateFlow(0)
    val datasetSavedCount: StateFlow<Int> = _datasetSavedCount.asStateFlow()

    private val _lastPlannerDecision = MutableStateFlow("Listo: usando Roca / Hacha de Piedra según recurso.")
    val lastPlannerDecision: StateFlow<String> = _lastPlannerDecision.asStateFlow()

    private val _telemetryLogs = MutableStateFlow<List<String>>(emptyList())
    val telemetryLogs: StateFlow<List<String>> = _telemetryLogs.asStateFlow()

    private val _checkpoints = MutableStateFlow<Map<CheckpointId, CheckpointRecord>>(
        CheckpointId.entries.associateWith { CheckpointRecord(it) }
    )
    val checkpoints: StateFlow<Map<CheckpointId, CheckpointRecord>> = _checkpoints.asStateFlow()

    private var prefs: SharedPreferences? = null

    fun initialize(
        context: Context,
        realLandscapeWidthPx: Int,
        realLandscapeHeightPx: Int,
        densityDpi: Int,
        cutoutLeftPx: Int,
        cutoutRightPx: Int
    ) {
        val sp = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs = sp

        val savedCalib = sp.getString(KEY_CALIBRATION_JSON, null)
        if (savedCalib != null) {
            val loaded = deserializeCalibration(
                savedCalib,
                realLandscapeWidthPx,
                realLandscapeHeightPx,
                densityDpi,
                cutoutLeftPx,
                cutoutRightPx
            )
            _calibrationProfile.value = loaded
        } else {
            val adaptive = ScreenCalibrationProfile.createAdaptiveDefault(
                landscapeWidthPx = realLandscapeWidthPx,
                landscapeHeightPx = realLandscapeHeightPx,
                densityDpi = densityDpi,
                cutoutLeftPx = cutoutLeftPx,
                cutoutRightPx = cutoutRightPx
            )
            _calibrationProfile.value = adaptive
        }

        val savedCheckpoints = sp.getString(KEY_CHECKPOINTS_JSON, null)
        if (savedCheckpoints != null) {
            deserializeCheckpoints(savedCheckpoints)
        }
    }

    fun updateCalibrationProfile(newProfile: ScreenCalibrationProfile) {
        _calibrationProfile.value = newProfile
        prefs?.edit()?.putString(KEY_CALIBRATION_JSON, serializeCalibration(newProfile))?.apply()
        appendLog("Calibración HUD guardada para pantalla real ${newProfile.aspectRatioLabel}")
    }

    fun resetCalibrationToRealScreen(
        realLandscapeWidthPx: Int,
        realLandscapeHeightPx: Int,
        densityDpi: Int,
        cutoutLeftPx: Int,
        cutoutRightPx: Int
    ) {
        val profile = ScreenCalibrationProfile.createAdaptiveDefault(
            landscapeWidthPx = realLandscapeWidthPx,
            landscapeHeightPx = realLandscapeHeightPx,
            densityDpi = densityDpi,
            cutoutLeftPx = cutoutLeftPx,
            cutoutRightPx = cutoutRightPx
        )
        updateCalibrationProfile(profile)
    }

    fun updateState(newState: CompanionState) {
        if (_currentState.value != newState) {
            _currentState.value = newState
            appendLog("Estado FSM -> ${newState.titleEs}")
        }
    }

    fun setOverlayMode(mode: OverlayMode) {
        _overlayMode.value = mode
        appendLog("Modo flotante -> ${mode.labelEs}")
    }

    fun setAutomationRunning(running: Boolean) {
        _isAutomationRunning.value = running
        if (running) {
            _isEmergencyStopped.value = false
            _lastStopReason.value = StopReason.NONE
        }
    }

    fun triggerImmediateStop(reason: StopReason) {
        _isAutomationRunning.value = false
        _isEmergencyStopped.value = reason.isCriticalAlert
        _lastStopReason.value = reason
        _currentState.value = if (reason.isCriticalAlert) {
            CompanionState.EMERGENCY_STOP
        } else {
            CompanionState.IDLE
        }
        appendLog("DETENIDO: ${reason.titleEs}")
    }

    fun updateAccessibilityConnected(connected: Boolean) {
        _isAccessibilityConnected.value = connected
    }

    fun updateMediaProjectionActive(active: Boolean) {
        _isMediaProjectionActive.value = active
    }

    fun updateForegroundPackage(pkg: String) {
        _foregroundPackage.value = pkg
    }

    fun updateVisionTelemetry(
        detections: List<DetectedObject>,
        healthPct: Float,
        fps: Int
    ) {
        _latestDetections.value = detections
        _estimatedHealthPercent.value = healthPct
        _currentFps.value = fps
    }

    fun updateInventoryCounts(counts: Map<String, Int>) {
        if (counts.isNotEmpty()) {
            _inventoryCounts.update { current ->
                val merged = current.toMutableMap()
                counts.forEach { (k, v) -> merged[k] = v }
                merged
            }
        }
    }

    fun updateThermalAndBattery(tempC: Float, pct: Int) {
        _batteryTempCelsius.value = tempC
        _batteryPercent.value = pct
    }

    fun updateDatasetSavedCount(count: Int) {
        _datasetSavedCount.value = count
    }

    fun updatePlannerDecision(decisionText: String) {
        _lastPlannerDecision.value = decisionText
    }

    /**
     * Registra un intento en el punto de control indicado aplicando la regla de orden G1..G7
     * y el criterio de abandono tras 3 enfoques fallidos.
     */
    fun recordCheckpointAttempt(
        id: CheckpointId,
        approachNumber: Int,
        passed: Boolean,
        evidence: String
    ) {
        _checkpoints.update { map ->
            val current = map[id] ?: CheckpointRecord(id)
            val description = id.approachesEs.getOrElse(approachNumber - 1) { "Enfoque $approachNumber" }
            val newAttempt = ApproachAttempt(
                approachNumber = approachNumber,
                description = description,
                passed = passed,
                evidenceLog = evidence
            )
            val updatedAttempts = (current.attempts.filterNot { it.approachNumber == approachNumber } + newAttempt)
                .sortedBy { it.approachNumber }

            val allThreeFailed = updatedAttempts.size >= 3 && updatedAttempts.none { it.passed }
            val newStatus = when {
                passed -> CheckpointStatus.PASSED
                allThreeFailed -> CheckpointStatus.FAILED_ABANDONED
                else -> CheckpointStatus.IN_PROGRESS
            }

            val report = if (allThreeFailed) {
                buildString {
                    appendLine("INFORME DE ABANDONO — PUNTO DE CONTROL ${id.code} (${id.titleEs})")
                    appendLine("Objetivo: ${id.goalEs}")
                    appendLine("Se intentaron 3 enfoques técnicos legítimos sin éxito:")
                    updatedAttempts.forEach { att ->
                        appendLine("  [${att.approachNumber}] ${att.description}")
                        appendLine("      Evidencia: ${att.evidenceLog}")
                    }
                    appendLine("Conclusión: Siguiendo la directiva de no parchar ni evadir protecciones del juego, se detiene el avance en ${id.code}.")
                }
            } else current.abandonmentReport

            val updatedRecord = current.copy(
                status = newStatus,
                attempts = updatedAttempts,
                abandonmentReport = report
            )
            map + (id to updatedRecord)
        }
        saveCheckpointsToPrefs()
        appendLog("Checkpoint ${id.code} (Enfoque $approachNumber): ${if (passed) "SUPERADO" else "FALLÓ"} - $evidence")
    }

    fun canStartCheckpoint(id: CheckpointId): Boolean {
        val ordinal = id.ordinal
        if (ordinal == 0) return true
        val prevId = CheckpointId.entries[ordinal - 1]
        return _checkpoints.value[prevId]?.status == CheckpointStatus.PASSED
    }

    fun appendLog(message: String) {
        val stamp = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
        val line = "[$stamp] $message"
        _telemetryLogs.update { list ->
            (listOf(line) + list).take(60)
        }
    }

    private fun serializeCalibration(p: ScreenCalibrationProfile): String {
        val obj = JSONObject()
        obj.put("w", p.realLandscapeWidthPx)
        obj.put("h", p.realLandscapeHeightPx)
        obj.put("dpi", p.densityDpi)
        obj.put("userCalibrated", p.isUserCalibrated)
        p.allEditableAnchors().forEach { anchor ->
            obj.put("${anchor.id}_x", anchor.normX.toDouble())
            obj.put("${anchor.id}_y", anchor.normY.toDouble())
        }
        return obj.toString()
    }

    private fun deserializeCalibration(
        json: String,
        realW: Int,
        realH: Int,
        dpi: Int,
        cutoutL: Int,
        cutoutR: Int
    ): ScreenCalibrationProfile {
        var profile = ScreenCalibrationProfile.createAdaptiveDefault(realW, realH, dpi, cutoutL, cutoutR)
        return try {
            val obj = JSONObject(json)
            for (anchor in profile.allEditableAnchors()) {
                if (obj.has("${anchor.id}_x") && obj.has("${anchor.id}_y")) {
                    val nx = obj.getDouble("${anchor.id}_x").toFloat()
                    val ny = obj.getDouble("${anchor.id}_y").toFloat()
                    profile = profile.withUpdatedAnchor(anchor.id, nx, ny)
                }
            }
            profile.copy(
                realLandscapeWidthPx = realW,
                realLandscapeHeightPx = realH,
                densityDpi = dpi,
                isUserCalibrated = obj.optBoolean("userCalibrated", false)
            )
        } catch (_: Exception) {
            profile
        }
    }

    private fun saveCheckpointsToPrefs() {
        try {
            val root = JSONObject()
            for ((id, rec) in _checkpoints.value) {
                val item = JSONObject()
                item.put("status", rec.status.name)
                item.put("report", rec.abandonmentReport ?: "")
                val arr = JSONArray()
                rec.attempts.forEach { att ->
                    arr.put(
                        JSONObject().apply {
                            put("num", att.approachNumber)
                            put("desc", att.description)
                            put("passed", att.passed)
                            put("ev", att.evidenceLog)
                        }
                    )
                }
                item.put("attempts", arr)
                root.put(id.name, item)
            }
            prefs?.edit()?.putString(KEY_CHECKPOINTS_JSON, root.toString())?.apply()
        } catch (_: Exception) {
        }
    }

    private fun deserializeCheckpoints(json: String) {
        try {
            val root = JSONObject(json)
            val updated = _checkpoints.value.toMutableMap()
            for (id in CheckpointId.entries) {
                val item = root.optJSONObject(id.name) ?: continue
                val status = CheckpointStatus.valueOf(item.optString("status", CheckpointStatus.PENDING.name))
                val report = item.optString("report", "").takeIf { it.isNotBlank() }
                val arr = item.optJSONArray("attempts") ?: JSONArray()
                val attempts = (0 until arr.length()).map { idx ->
                    val o = arr.getJSONObject(idx)
                    ApproachAttempt(
                        approachNumber = o.getInt("num"),
                        description = o.getString("desc"),
                        passed = o.getBoolean("passed"),
                        evidenceLog = o.getString("ev")
                    )
                }
                updated[id] = CheckpointRecord(id, status, attempts, report)
            }
            _checkpoints.value = updated
        } catch (_: Exception) {
        }
    }
}
