package com.example.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.os.BatteryManager
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.core.app.NotificationCompat
import com.example.R
import com.example.model.DetectedObject
import com.example.model.StopReason

/**
 * Estado térmico y energético en tiempo real para el Punto de Control G3 (30 min sin sobrecalentar).
 */
data class ThermalAndBatteryStatus(
    val temperatureCelsius: Float,
    val batteryPercent: Int,
    val isCharging: Boolean,
    val recommendedFps: Int,
    val shouldPauseThermally: Boolean,
    val shouldEmergencyStop: Boolean
)

/**
 * Guardián de Seguridad Obligatorio:
 * - Verifica que Oxide esté en primer plano y que no haya llamadas telefónicas activas.
 * - Si aparece otro jugador, baja la vida, muere el personaje o desaparece el HUD:
 *   detiene al instante, vibra y envía notificación crítica.
 * - Vigila temperatura de batería y porcentaje de carga para pausar y avisar antes de calentar demasiado.
 */
class SafetyGuardian(private val context: Context) {

    private val notificationManager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    private var baselineHealthPercent: Float = 100f
    private var consecutiveMissingHudFrames: Int = 0

    init {
        ensureAlertChannelCreated()
    }

    private fun ensureAlertChannelCreated() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                ALERT_CHANNEL_ID,
                context.getString(R.string.alert_channel_name),
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = context.getString(R.string.alert_channel_desc)
                enableVibration(true)
            }
            notificationManager.createNotificationChannel(channel)
        }
    }

    /**
     * Lee la temperatura real de la batería (en décimas de °C) y el nivel de carga.
     */
    fun readThermalAndBatteryStatus(): ThermalAndBatteryStatus {
        val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val tempTenths = intent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 310) ?: 310
        val tempC = tempTenths / 10.0f
        val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, 80) ?: 80
        val scale = (intent?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100).coerceAtLeast(1)
        val pct = ((level * 100f) / scale).toInt().coerceIn(0, 100)
        val status = intent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL

        // Escalado térmico progresivo para cumplir G3 (30 min conectados sin sobrecalentar)
        val recFps = when {
            tempC >= 40.0f -> 4
            tempC >= 38.5f -> 5
            else -> 7
        }

        return ThermalAndBatteryStatus(
            temperatureCelsius = tempC,
            batteryPercent = pct,
            isCharging = charging,
            recommendedFps = recFps,
            shouldPauseThermally = tempC >= 41.2f,
            shouldEmergencyStop = tempC >= 43.0f || (pct <= 14 && !charging)
        )
    }

    /**
     * Verifica si hay una llamada entrante o activa usando AudioManager (sin requerir permiso READ_PHONE_STATE).
     */
    fun isPhoneCallActive(): Boolean {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return false
        val mode = audioManager.mode
        return mode == AudioManager.MODE_RINGTONE ||
            mode == AudioManager.MODE_IN_CALL ||
            mode == AudioManager.MODE_IN_COMMUNICATION
    }

    /**
     * Verifica si el paquete en primer plano es Oxide: Survival Island (o la propia app durante pruebas G2).
     */
    fun isAllowedForegroundPackage(
        foregroundPackage: String?,
        allowCompanionAppForTesting: Boolean = false
    ): Boolean {
        if (foregroundPackage.isNullOrBlank()) return true
        if (OXIDE_PACKAGES.any { foregroundPackage.contains(it, ignoreCase = true) }) {
            return true
        }
        if (allowCompanionAppForTesting && foregroundPackage == context.packageName) {
            return true
        }
        return false
    }

    /**
     * Evalúa cada frame procesado por el pipeline de visión para decidir si debe activarse
     * una parada inmediata de seguridad.
     */
    fun evaluateFrameSafety(
        detections: List<DetectedObject>,
        currentHealthPercent: Float,
        isHudVisible: Boolean,
        isInventoryOrModalOpen: Boolean,
        ocrText: String
    ): StopReason {
        // 1. ¿Apareció otro jugador en pantalla? -> Parada inmediata (Prohibido combate o evasión)
        val playerDetected = detections.firstOrNull {
            it.label.equals("player", ignoreCase = true) && it.confidence >= 0.45f
        }
        if (playerDetected != null) {
            return StopReason.PLAYER_DETECTED_NEARBY
        }

        // 2. ¿Pantalla de muerte detectada por OCR?
        val lowerOcr = ocrText.lowercase()
        if (lowerOcr.contains("has muerto") || lowerOcr.contains("you died") ||
            lowerOcr.contains("reaparecer") || lowerOcr.contains("respawn")
        ) {
            return StopReason.CHARACTER_DIED
        }

        // 3. ¿Bajó la vida respecto a la línea base?
        if (currentHealthPercent < baselineHealthPercent - 4.5f && currentHealthPercent < 92f) {
            baselineHealthPercent = currentHealthPercent
            return StopReason.HEALTH_DROP_DETECTED
        } else if (currentHealthPercent > baselineHealthPercent) {
            baselineHealthPercent = currentHealthPercent
        }

        // 4. ¿Dejó de verse el HUD mientras no estamos en inventario/menú?
        if (!isHudVisible && !isInventoryOrModalOpen) {
            consecutiveMissingHudFrames++
            if (consecutiveMissingHudFrames >= 6) {
                return StopReason.HUD_LOST_UNEXPECTEDLY
            }
        } else {
            consecutiveMissingHudFrames = 0
        }

        return StopReason.NONE
    }

    fun resetBaselineHealth(healthPercent: Float = 100f) {
        baselineHealthPercent = healthPercent
        consecutiveMissingHudFrames = 0
    }

    /**
     * Ejecuta vibración táctica y envía notificación de alerta al jugador.
     */
    fun triggerUrgentPlayerAlert(reason: StopReason) {
        vibrateAlert(isCritical = reason.isCriticalAlert)
        val notification = NotificationCompat.Builder(context, ALERT_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle("Oxide Companion — Parada de Seguridad")
            .setContentText(reason.titleEs)
            .setStyle(NotificationCompat.BigTextStyle().bigText(reason.titleEs))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()
        notificationManager.notify(ALERT_NOTIFICATION_ID, notification)
    }

    private fun vibrateAlert(isCritical: Boolean) {
        try {
            val vibrator: Vibrator? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vm = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                vm?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            }

            if (vibrator?.hasVibrator() == true) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    val timings = if (isCritical) {
                        longArrayOf(0, 260, 110, 260, 110, 400)
                    } else {
                        longArrayOf(0, 140)
                    }
                    vibrator.vibrate(VibrationEffect.createWaveform(timings, -1))
                } else {
                    @Suppress("DEPRECATION")
                    vibrator.vibrate(if (isCritical) 650L else 140L)
                }
            }
        } catch (_: Exception) {
        }
    }

    companion object {
        private const val ALERT_CHANNEL_ID = "oxide_safety_alerts_channel"
        private const val ALERT_NOTIFICATION_ID = 9902
        val OXIDE_PACKAGES = listOf(
            "com.catsbit.oxidesurvivalisland",
            "com.hyperhug.oxide",
            "oxide"
        )
    }
}
