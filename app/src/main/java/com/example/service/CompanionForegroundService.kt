package com.example.service

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.NotificationCompat
import com.example.MainActivity
import com.example.R
import com.example.model.CheckpointId
import com.example.model.CompanionState
import com.example.model.HudAnchorPoint
import com.example.model.OverlayMode
import com.example.model.StopReason
import com.example.planner.KnowledgeBasePlanner
import com.example.vision.FrameDiagnostic
import com.example.vision.OxideVisionPipeline
import com.example.vision.ScreenCaptureManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Servicio en Primer Plano (ForegroundService tipo mediaProjection) + Botón Flotante sobre el Juego.
 * - Mantiene activa la captura MediaProjection a 5-10 FPS en Android 12/13+.
 * - Muestra una píldora táctica flotante con:
 *   1. Botón [DETENER] siempre visible y efectivo al instante (<50 ms).
 *   2. Botón [INICIAR / PAUSAR] y selector de modo (Recolectar, Dataset G1, Prueba G2, Calibrar HUD Real).
 *   3. Superposición de Calibración en Vivo sobre la pantalla real (20:9, 19.5:9, 16:9) con dianas arrastrables.
 */
class CompanionForegroundService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private lateinit var windowManager: WindowManager
    private lateinit var visionPipeline: OxideVisionPipeline
    private lateinit var planner: KnowledgeBasePlanner
    private lateinit var safetyGuardian: SafetyGuardian
    private lateinit var stateMachine: CompanionStateMachine
    private lateinit var captureManager: ScreenCaptureManager

    private var floatingBarView: View? = null
    private var liveCalibrationOverlayView: FrameLayout? = null
    private var statusTextView: TextView? = null
    private var startToggleBtn: TextView? = null
    private var modeCycleBtn: TextView? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        visionPipeline = OxideVisionPipeline(this)
        planner = KnowledgeBasePlanner(this)
        safetyGuardian = SafetyGuardian(this)
        stateMachine = CompanionStateMachine(visionPipeline, planner, safetyGuardian, applicationContext)

        captureManager = ScreenCaptureManager(
            context = this,
            onFrameCaptured = { bitmap, diagnostic ->
                handleCapturedFrame(bitmap, diagnostic)
            },
            onG1Abandoned = { abandonmentReport ->
                CompanionRepository.recordCheckpointAttempt(
                    id = CheckpointId.G1,
                    approachNumber = 3,
                    passed = false,
                    evidence = abandonmentReport
                )
                CompanionRepository.triggerImmediateStop(StopReason.GAME_BLOCKS_CAPTURE_G1)
                safetyGuardian.triggerUrgentPlayerAlert(StopReason.GAME_BLOCKS_CAPTURE_G1)
                updateOverlayUiOnMainThread()
            }
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_EMERGENCY_STOP -> {
                executeImmediateEmergencyStop(StopReason.USER_MANUAL_STOP)
                return START_NOT_STICKY
            }
            ACTION_SHUTDOWN_SERVICE -> {
                executeImmediateEmergencyStop(StopReason.USER_MANUAL_STOP)
                removeOverlays()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_START_WITH_PROJECTION -> {
                startForegroundWithNotification()
                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
                val resultData: Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(EXTRA_RESULT_INTENT, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(EXTRA_RESULT_INTENT)
                }

                if (resultCode == Activity.RESULT_OK && resultData != null) {
                    val mpManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                    val projection = mpManager.getMediaProjection(resultCode, resultData)
                    if (projection != null) {
                        val calib = CompanionRepository.calibrationProfile.value
                        captureManager.startCapture(
                            projection = projection,
                            realWidthPx = calib.realLandscapeWidthPx,
                            realHeightPx = calib.realLandscapeHeightPx,
                            densityDpi = calib.densityDpi,
                            approachNumber = 1
                        )
                        CompanionRepository.updateMediaProjectionActive(true)
                        CompanionRepository.appendLog(
                            "MediaProjection activo a ${calib.realLandscapeWidthPx}x${calib.realLandscapeHeightPx} (${calib.aspectRatioLabel})"
                        )
                    }
                }
                showFloatingControlBarIfPermitted()
            }
            else -> {
                startForegroundWithNotification()
                showFloatingControlBarIfPermitted()
            }
        }
        return START_STICKY
    }

    private fun handleCapturedFrame(bitmap: android.graphics.Bitmap, diagnostic: FrameDiagnostic) {
        if (!diagnostic.isBlackOrBlocked) {
            // Si G1 aún estaba pendiente, registrarlo como SUPERADO con evidencia real de luminancia
            val g1Status = CompanionRepository.checkpoints.value[CheckpointId.G1]?.status
            if (g1Status != com.example.model.CheckpointStatus.PASSED) {
                CompanionRepository.recordCheckpointAttempt(
                    id = CheckpointId.G1,
                    approachNumber = diagnostic.approachIndexUsed,
                    passed = true,
                    evidence = diagnostic.diagnosticMessageEs
                )
            }
        }

        if (!CompanionRepository.isAutomationRunning.value) {
            bitmap.recycle()
            return
        }

        when (CompanionRepository.overlayMode.value) {
            OverlayMode.DATASET_COLLECTOR_G1_G4 -> {
                if (!diagnostic.isBlackOrBlocked) {
                    val savedFile = captureManager.saveDatasetCapture(bitmap, "oxide_frame")
                    if (savedFile != null) {
                        val total = captureManager.getDatasetCapturesCount()
                        CompanionRepository.updateDatasetSavedCount(total)
                    }
                }
                bitmap.recycle()
            }
            OverlayMode.GESTURE_TEST_G2 -> {
                val calib = CompanionRepository.calibrationProfile.value
                val acc = OxideAccessibilityService.instance
                if (acc != null) {
                    val moved = acc.dispatchJoystickHold(calib, "forward", 1200L) { completed ->
                        if (completed) {
                            acc.dispatchHarvestHit(calib, g2Approach = 1) { hitCompleted ->
                                CompanionRepository.recordCheckpointAttempt(
                                    id = CheckpointId.G2, approachNumber = 1, passed = hitCompleted,
                                    evidence = if (hitCompleted) "Joystick mantenido y golpe posterior despachado (${calib.aspectRatioLabel})." else "Movimiento completado; golpe no confirmado."
                                )
                            }
                        }
                    }
                    if (!moved) CompanionRepository.appendLog("G2: no se pudo iniciar el gesto sostenido.")
                }
                bitmap.recycle()
            }
            OverlayMode.LIVE_HUD_CALIBRATION -> {
                bitmap.recycle()
            }
            OverlayMode.AUTONOMOUS_GATHER -> {
                serviceScope.launch {
                    try {
                        stateMachine.step(
                            frame = bitmap,
                            calibration = CompanionRepository.calibrationProfile.value,
                            accessibility = OxideAccessibilityService.instance
                        )
                        updateOverlayUiOnMainThread()
                    } finally {
                        bitmap.recycle()
                    }
                }
            }
        }
    }

    /**
     * Crea y muestra el botón flotante táctico sobre Oxide con:
     * - Botón rojo [DETENER] instantáneo
     * - Botón [INICIAR / PAUSAR]
     * - Selector de modo (incluyendo Calibrar HUD Real en vivo sobre el juego)
     */
    private fun showFloatingControlBarIfPermitted() {
        if (!Settings.canDrawOverlays(this) || floatingBarView != null) return

        val layoutType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            layoutType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 48
            y = 36
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(6), dp(10), dp(6))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#E6161B22"))
                cornerRadius = dp(20).toFloat()
                setStroke(dp(1), Color.parseColor("#E67E22"))
            }
        }

        // Indicador de estado compacto + Arrastre de la barra flotante
        val statusTv = TextView(this).apply {
            text = "OXIDE • EN ESPERA"
            setTextColor(Color.parseColor("#F0F6FC"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            typeface = Typeface.MONOSPACE
            setPadding(dp(6), dp(4), dp(8), dp(4))
        }
        this.statusTextView = statusTv

        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f
        statusTv.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    params.x = initialX + (event.rawX - initialTouchX).toInt()
                    params.y = initialY + (event.rawY - initialTouchY).toInt()
                    try {
                        windowManager.updateViewLayout(root, params)
                    } catch (_: Exception) {
                    }
                    true
                }
                else -> false
            }
        }

        // Selector de Modo (Recolectar / Dataset G1 / Prueba G2 / Calibrar HUD Real)
        val modeBtn = createPillButton(
            text = CompanionRepository.overlayMode.value.labelEs,
            bgColorHex = "#21262D",
            textColorHex = "#FF9F43"
        ) {
            val modes = OverlayMode.entries
            val nextIdx = (CompanionRepository.overlayMode.value.ordinal + 1) % modes.size
            val nextMode = modes[nextIdx]
            CompanionRepository.setOverlayMode(nextMode)
            if (nextMode == OverlayMode.LIVE_HUD_CALIBRATION) {
                showLiveCalibrationTargetsOverlay()
            } else {
                removeLiveCalibrationTargetsOverlay()
            }
            updateOverlayUiOnMainThread()
        }
        this.modeCycleBtn = modeBtn

        // Botón Iniciar / Pausar
        val startBtn = createPillButton(
            text = "INICIAR",
            bgColorHex = "#2ECC71",
            textColorHex = "#0D1117"
        ) {
            val nextRunning = !CompanionRepository.isAutomationRunning.value
            if (nextRunning) {
                safetyGuardian.resetBaselineHealth(100f)
                CompanionRepository.setAutomationRunning(true)
                CompanionRepository.updateState(CompanionState.IN_GAME_SCANNING)
            } else {
                CompanionRepository.triggerImmediateStop(StopReason.USER_MANUAL_STOP)
            }
            updateOverlayUiOnMainThread()
        }
        this.startToggleBtn = startBtn

        // Botón DETENER siempre visible y efectivo al instante
        val stopBtn = createPillButton(
            text = "DETENER",
            bgColorHex = "#E74C3C",
            textColorHex = "#FFFFFF"
        ) {
            executeImmediateEmergencyStop(StopReason.USER_MANUAL_STOP)
        }

        root.addView(statusTv)
        root.addView(modeBtn)
        root.addView(startBtn)
        root.addView(stopBtn)

        floatingBarView = root
        windowManager.addView(root, params)
    }

    /**
     * Superposición interactiva sobre la pantalla real (20:9, 19.5:9, 16:9) que permite arrastrar
     * cada marcador directamente sobre los botones reales de Oxide y guardar la calibración exacta.
     */
    private fun showLiveCalibrationTargetsOverlay() {
        if (!Settings.canDrawOverlays(this) || liveCalibrationOverlayView != null) return

        val layoutType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val fullParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            layoutType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )

        val container = FrameLayout(this).apply {
            setBackgroundColor(Color.parseColor("#330D1117"))
        }

        val currentCalib = CompanionRepository.calibrationProfile.value
        val screenW = resources.displayMetrics.widthPixels.coerceAtLeast(1280)
        val screenH = resources.displayMetrics.heightPixels.coerceAtLeast(720)

        for (anchor in currentCalib.allEditableAnchors()) {
            val targetChip = createDraggableAnchorTarget(anchor, screenW, screenH)
            container.addView(targetChip)
        }

        val doneBtn = createPillButton(
            text = "GUARDAR CALIBRACIÓN REAL",
            bgColorHex = "#E67E22",
            textColorHex = "#0D1117"
        ) {
            CompanionRepository.setOverlayMode(OverlayMode.AUTONOMOUS_GATHER)
            removeLiveCalibrationTargetsOverlay()
            updateOverlayUiOnMainThread()
        }
        val doneParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            topMargin = dp(20)
            rightMargin = dp(24)
        }
        container.addView(doneBtn, doneParams)

        liveCalibrationOverlayView = container
        windowManager.addView(container, fullParams)
    }

    private fun createDraggableAnchorTarget(
        anchor: HudAnchorPoint,
        screenW: Int,
        screenH: Int
    ): View {
        val chip = TextView(this).apply {
            text = "⊕ ${anchor.nameEs}"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(8), dp(6), dp(8), dp(6))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#CC161B22"))
                cornerRadius = dp(14).toFloat()
                setStroke(dp(2), Color.parseColor("#FF9F43"))
            }
        }

        val lp = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            leftMargin = (anchor.normX * screenW).toInt() - dp(40)
            topMargin = (anchor.normY * screenH).toInt() - dp(18)
        }
        chip.layoutParams = lp

        var startLeft = 0
        var startTop = 0
        var touchX = 0f
        var touchY = 0f

        chip.setOnTouchListener { v, ev ->
            val params = v.layoutParams as FrameLayout.LayoutParams
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startLeft = params.leftMargin
                    startTop = params.topMargin
                    touchX = ev.rawX
                    touchY = ev.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    params.leftMargin = (startLeft + (ev.rawX - touchX).toInt()).coerceIn(0, screenW - dp(60))
                    params.topMargin = (startTop + (ev.rawY - touchY).toInt()).coerceIn(0, screenH - dp(36))
                    v.layoutParams = params
                    true
                }
                MotionEvent.ACTION_UP -> {
                    val centerX = (params.leftMargin + v.width / 2f) / screenW.toFloat()
                    val centerY = (params.topMargin + v.height / 2f) / screenH.toFloat()
                    val updated = CompanionRepository.calibrationProfile.value
                        .copy(realLandscapeWidthPx = screenW, realLandscapeHeightPx = screenH)
                        .withUpdatedAnchor(anchor.id, centerX, centerY)
                    CompanionRepository.updateCalibrationProfile(updated)
                    true
                }
                else -> false
            }
        }
        return chip
    }

    private fun removeLiveCalibrationTargetsOverlay() {
        liveCalibrationOverlayView?.let {
            try {
                windowManager.removeView(it)
            } catch (_: Exception) {
            }
        }
        liveCalibrationOverlayView = null
    }

    private fun executeImmediateEmergencyStop(reason: StopReason) {
        CompanionRepository.triggerImmediateStop(reason)
        removeLiveCalibrationTargetsOverlay()
        updateOverlayUiOnMainThread()
    }

    private fun updateOverlayUiOnMainThread() {
        floatingBarView?.post {
            val running = CompanionRepository.isAutomationRunning.value
            val state = CompanionRepository.currentState.value
            val temp = CompanionRepository.batteryTempCelsius.value
            statusTextView?.text = "${state.titleEs.uppercase()} • ${"%.1f".format(temp)}°C"
            modeCycleBtn?.text = CompanionRepository.overlayMode.value.labelEs
            startToggleBtn?.text = if (running) "PAUSAR" else "INICIAR"
        }
    }

    private fun createPillButton(
        text: String,
        bgColorHex: String,
        textColorHex: String,
        onClick: () -> Unit
    ): TextView {
        return TextView(this).apply {
            this.text = text
            setTextColor(Color.parseColor(textColorHex))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(10), dp(6), dp(10), dp(6))
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                marginStart = dp(6)
            }
            layoutParams = lp
            background = GradientDrawable().apply {
                setColor(Color.parseColor(bgColorHex))
                cornerRadius = dp(14).toFloat()
            }
            setOnClickListener { onClick() }
        }
    }

    private fun startForegroundWithNotification() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                FOREGROUND_CHANNEL_ID,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.notification_channel_desc)
            }
            nm.createNotificationChannel(channel)
        }

        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, CompanionForegroundService::class.java).apply {
                action = ACTION_EMERGENCY_STOP
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notification: Notification = NotificationCompat.Builder(this, FOREGROUND_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentTitle("Oxide Companion Activo")
            .setContentText("Captura 5-10 FPS y Guardián de Seguridad activos. Toca DETENER para parada inmediata.")
            .setOngoing(true)
            .setContentIntent(openIntent)
            .addAction(android.R.drawable.ic_media_pause, "DETENER AHORA", stopIntent)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                FOREGROUND_NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        } else {
            startForeground(FOREGROUND_NOTIFICATION_ID, notification)
        }
    }

    private fun removeOverlays() {
        removeLiveCalibrationTargetsOverlay()
        floatingBarView?.let {
            try {
                windowManager.removeView(it)
            } catch (_: Exception) {
            }
        }
        floatingBarView = null
    }

    override fun onDestroy() {
        captureManager.stopCapture()
        CompanionRepository.updateMediaProjectionActive(false)
        removeOverlays()
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun dp(value: Int): Int =
        TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            value.toFloat(),
            resources.displayMetrics
        ).toInt()

    companion object {
        private const val FOREGROUND_CHANNEL_ID = "oxide_companion_foreground_channel"
        private const val FOREGROUND_NOTIFICATION_ID = 9901

        const val ACTION_START_WITH_PROJECTION = "com.example.action.START_WITH_PROJECTION"
        const val ACTION_SHOW_OVERLAY_ONLY = "com.example.action.SHOW_OVERLAY_ONLY"
        const val ACTION_EMERGENCY_STOP = "com.example.action.EMERGENCY_STOP"
        const val ACTION_SHUTDOWN_SERVICE = "com.example.action.SHUTDOWN_SERVICE"

        const val EXTRA_RESULT_CODE = "extra_result_code"
        const val EXTRA_RESULT_INTENT = "extra_result_intent"
    }
}
