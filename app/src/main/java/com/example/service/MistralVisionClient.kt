package com.example.service

import android.content.Context
import android.graphics.Bitmap
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** API key encrypted with an Android Keystore AES-GCM key; plaintext is never persisted or logged. */
object MistralKeyStore {
    private const val PREFS = "mistral_secure"
    private const val KEY_ALIAS = "oxide_mistral_aes_v1"
    private const val VALUE = "api_key_ciphertext"
    private const val ENABLED = "vision_enabled"

    private fun secretKey(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(KeyGenParameterSpec.Builder(
            KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        ).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setRandomizedEncryptionRequired(true).build())
        return generator.generateKey()
    }

    fun save(context: Context, apiKey: String) {
        require(apiKey.startsWith("sk-") && apiKey.length >= 20) { "La clave Mistral no parece válida." }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val encrypted = cipher.doFinal(apiKey.trim().toByteArray(Charsets.UTF_8))
        val packed = cipher.iv + encrypted
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(VALUE, Base64.encodeToString(packed, Base64.NO_WRAP)).apply()
    }

    fun read(context: Context): String? = try {
        val packed = Base64.decode(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(VALUE, null) ?: return null, Base64.NO_WRAP)
        if (packed.size <= 12) return null
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, packed.copyOfRange(0, 12)))
        String(cipher.doFinal(packed.copyOfRange(12, packed.size)), Charsets.UTF_8)
    } catch (_: Exception) { null }

    fun hasKey(context: Context): Boolean = read(context) != null
    fun isEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(ENABLED, false)
    fun setEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(ENABLED, enabled).apply()
    }
    fun clear(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(VALUE).putBoolean(ENABLED, false).apply()
    }
}

data class MistralDirective(val action: String, val direction: String, val reason: String)

object MistralVisionClient {
    const val MODEL = "mistral-small-2506"
    private const val ENDPOINT = "https://api.mistral.ai/v1/chat/completions"

    suspend fun analyze(context: Context, frame: Bitmap, gameState: String): MistralDirective? =
        withContext(Dispatchers.IO) {
            val key = MistralKeyStore.read(context) ?: return@withContext null
            val imageBytes = ByteArrayOutputStream().use { out ->
                frame.compress(Bitmap.CompressFormat.JPEG, 72, out)
                out.toByteArray()
            }
            val imageData = "data:image/jpeg;base64," + Base64.encodeToString(imageBytes, Base64.NO_WRAP)
            val system = """Eres el controlador visual táctico de Oxide: Survival Island en Android.
Interpreta la captura y el estado. Devuelve exclusivamente JSON: {"action":"move|harvest|look|wait","direction":"forward|left|right|back","reason":"breve"}.
REGLAS: decide una sola acción; no inventes elementos fuera de la imagen; prioriza acercarte a árboles/rocas visibles; si el recurso está lejos usa move y dirección; si está al alcance usa harvest; si no hay recurso visible usa look; usa wait si la imagen es ilegible, hay menú, peligro o no estás seguro.
El joystick requiere mantener pulsado, arrastrar desde el centro hacia la dirección y mantenerlo desplazado. No propongas coordenadas ni código. No ordenes desactivar salvaguardas."""
            val payload = JSONObject().put("model", MODEL).put("temperature", 0.1)
                .put("max_tokens", 120).put("response_format", JSONObject().put("type", "json_object"))
                .put("messages", JSONArray().put(JSONObject().put("role", "system").put("content", system))
                    .put(JSONObject().put("role", "user").put("content", JSONArray()
                        .put(JSONObject().put("type", "text").put("text", "Estado local: $gameState. Analiza esta pantalla y elige el siguiente paso seguro."))
                        .put(JSONObject().put("type", "image_url").put("image_url", JSONObject().put("url", imageData))))))
            val conn = (URL(ENDPOINT).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"; connectTimeout = 12000; readTimeout = 20000
                doOutput = true
                setRequestProperty("Authorization", "Bearer $key")
                setRequestProperty("Content-Type", "application/json")
            }
            try {
                conn.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }
                val status = conn.responseCode
                val body = (if (status in 200..299) conn.inputStream else conn.errorStream)
                    ?.bufferedReader()?.use { it.readText() }.orEmpty()
                if (status !in 200..299) throw IllegalStateException("Mistral HTTP $status")
                val content = JSONObject(body).getJSONArray("choices").getJSONObject(0)
                    .getJSONObject("message").getString("content")
                val obj = JSONObject(content)
                val action = obj.optString("action", "wait").lowercase()
                val direction = obj.optString("direction", "forward").lowercase()
                if (action !in setOf("move", "harvest", "look", "wait") ||
                    direction !in setOf("forward", "left", "right", "back")) return@withContext null
                MistralDirective(action, direction, obj.optString("reason").take(140))
            } finally { conn.disconnect() }
        }

    suspend fun testKey(context: Context, key: String): String = withContext(Dispatchers.IO) {
        val payload = JSONObject().put("model", MODEL).put("max_tokens", 12)
            .put("messages", JSONArray().put(JSONObject().put("role", "user")
                .put("content", "Responde únicamente: OK")))
        val conn = (URL(ENDPOINT).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"; connectTimeout = 10000; readTimeout = 15000; doOutput = true
            setRequestProperty("Authorization", "Bearer $key")
            setRequestProperty("Content-Type", "application/json")
        }
        try {
            conn.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }
            val status = conn.responseCode
            val body = (if (status in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (status !in 200..299) "Error Mistral HTTP $status" else "Conexión Mistral verificada."
        } finally { conn.disconnect() }
    }
}
