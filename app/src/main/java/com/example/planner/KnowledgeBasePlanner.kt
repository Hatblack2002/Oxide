package com.example.planner

import android.content.Context
import android.util.Log
import com.example.BuildConfig
import com.example.model.ToolCategory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Representa una herramienta definida en `assets/oxide_knowledge_base.json`.
 */
data class KnowledgeTool(
    val id: String,
    val nameEs: String,
    val nameEn: String,
    val category: ToolCategory,
    val tier: Int,
    val workbenchRequired: Int,
    val speedMultiplier: Double,
    val yieldEfficiency: Double,
    val estimatedHitsToBreak: Int,
    val craftingTimeSec: Int,
    val requirements: Map<String, Int>,
    val ocrAliases: List<String>
)

/**
 * Resultado determinista de la evaluación del Planificador (Checkpoint G6).
 */
data class CraftingDecision(
    val shouldCraft: Boolean,
    val targetTool: KnowledgeTool?,
    val reasonEs: String,
    val missingMaterials: Map<String, Int> = emptyMap()
)

/**
 * Decisión tomada ante un diálogo o situación imprevista (Reglas + Respaldo LLM por API con fallback offline).
 */
data class UnexpectedDialogResolution(
    val actionType: DialogActionType,
    val explanationEs: String,
    val usedLlmApi: Boolean
)

enum class DialogActionType {
    CLICK_RECONNECT_OR_PLAY,
    CLICK_CLOSE_MODAL,
    WAIT_LOADING,
    EMERGENCY_STOP_UNKNOWN
}

/**
 * Planificador del Compañero de Recolección.
 * 1. Carga la base de conocimiento desde `assets/oxide_knowledge_base.json`.
 * 2. Compara el inventario leído por ML Kit OCR contra los requisitos de cada herramienta y nivel de mesa.
 * 3. Decide qué herramienta usar según el recurso (`tree` -> Hacha, `rock_*` -> Pico) y cuándo fabricar una mejor (G6).
 * 4. Ante un diálogo imprevisto, aplica primero reglas seguras; si sigue siendo ambiguo, consulta un LLM por API
 *    (si hay clave configurada y conexión) y cuenta con respaldo 100% local si no hay internet.
 */
class KnowledgeBasePlanner(private val context: Context? = null) {

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build()

    val tools: List<KnowledgeTool> by lazy { loadToolsFromAssets() }

    private fun loadToolsFromAssets(): List<KnowledgeTool> {
        return try {
            val ctx = context ?: return emptyList()
            val jsonText = ctx.assets.open("oxide_knowledge_base.json")
                .bufferedReader()
                .use { it.readText() }
            parseToolsJson(jsonText)
        } catch (e: Exception) {
            Log.e(TAG, "Error leyendo oxide_knowledge_base.json, usando respaldo interno", e)
            emptyList()
        }
    }

    /**
     * Parsea el JSON de la base de conocimiento (también accesible desde tests unitarios G6).
     */
    fun parseToolsJson(jsonText: String): List<KnowledgeTool> {
        val root = JSONObject(jsonText)
        val toolsArray = root.getJSONArray("tools")
        val list = mutableListOf<KnowledgeTool>()
        for (i in 0 until toolsArray.length()) {
            val obj = toolsArray.getJSONObject(i)
            val reqObj = obj.getJSONObject("requirements")
            val reqMap = mutableMapOf<String, Int>()
            reqObj.keys().forEach { key ->
                reqMap[key] = reqObj.getInt(key)
            }
            val aliasesArr = obj.optJSONArray("ocr_aliases") ?: JSONArray()
            val aliases = (0 until aliasesArr.length()).map { aliasesArr.getString(it) }
            val catStr = obj.optString("category", "UNIVERSAL")
            val category = when (catStr.uppercase()) {
                "WOODCUTTING" -> ToolCategory.WOODCUTTING
                "MINING" -> ToolCategory.MINING
                else -> ToolCategory.UNIVERSAL
            }
            list.add(
                KnowledgeTool(
                    id = obj.getString("id"),
                    nameEs = obj.getString("name_es"),
                    nameEn = obj.getString("name_en"),
                    category = category,
                    tier = obj.getInt("tier"),
                    workbenchRequired = obj.getInt("workbench_level_required"),
                    speedMultiplier = obj.getDouble("gather_speed_multiplier"),
                    yieldEfficiency = obj.getDouble("yield_efficiency"),
                    estimatedHitsToBreak = obj.getInt("estimated_hits_to_break"),
                    craftingTimeSec = obj.getInt("crafting_time_sec"),
                    requirements = reqMap,
                    ocrAliases = aliases
                )
            )
        }
        return list.sortedBy { it.tier }
    }

    /**
     * Selecciona el slot del hotbar (0 para Hacha/Madera, 1 para Pico/Mineral) según la etiqueta del recurso.
     */
    fun selectOptimalHotbarSlotForResource(resourceLabel: String): Int {
        return when {
            resourceLabel.startsWith("tree") -> 0 // Slot 1: Hacha
            resourceLabel.startsWith("rock") -> 1 // Slot 2: Pico
            else -> 0
        }
    }

    /**
     * Evalúa si el jugador puede fabricar una herramienta mejor que la actual para la categoría indicada (Checkpoint G6).
     *
     * @param inventoryCounts Mapa de materiales leídos por OCR, ej: {"wood": 250, "stone": 120, "metal_fragments": 90}
     * @param currentToolTierByCategory Mapa del tier actual equipado para WOODCUTTING y MINING (0 = roca o rota).
     * @param availableWorkbenchLevel Nivel de mesa de trabajo disponible cerca o en base (0, 1 o 2).
     */
    fun evaluateUpgradeOrCraft(
        inventoryCounts: Map<String, Int>,
        currentToolTierByCategory: Map<ToolCategory, Int>,
        availableWorkbenchLevel: Int
    ): CraftingDecision {
        val candidateCategories = listOf(ToolCategory.WOODCUTTING, ToolCategory.MINING)

        for (category in candidateCategories) {
            val currentTier = currentToolTierByCategory[category] ?: 0
            // Buscamos la herramienta de mayor tier posible para esta categoría que supere nuestro tier actual
            val upgrades = tools
                .filter { it.category == category && it.tier > currentTier && it.workbenchRequired <= availableWorkbenchLevel }
                .sortedByDescending { it.tier }

            for (candidate in upgrades) {
                val missing = mutableMapOf<String, Int>()
                for ((materialKey, requiredQty) in candidate.requirements) {
                    val haveQty = inventoryCounts[materialKey] ?: 0
                    if (haveQty < requiredQty) {
                        missing[materialKey] = requiredQty - haveQty
                    }
                }
                if (missing.isEmpty()) {
                    return CraftingDecision(
                        shouldCraft = true,
                        targetTool = candidate,
                        reasonEs = "Materiales completos y Mesa Nv.${candidate.workbenchRequired} disponible para fabricar ${candidate.nameEs} (Tier ${candidate.tier}, velocidad x${candidate.speedMultiplier})."
                    )
                }
            }
        }

        return CraftingDecision(
            shouldCraft = false,
            targetTool = null,
            reasonEs = "No hay mejora fabricable con el inventario y nivel de mesa actuales (Mesa Nv.$availableWorkbenchLevel)."
        )
    }

    /**
     * Resuelve situaciones o diálogos imprevistos combinando reglas deterministas locales
     * y un LLM por API (Gemini) con respaldo automático si no hay conexión o clave.
     */
    suspend fun resolveUnexpectedSituation(ocrScreenText: String): UnexpectedDialogResolution {
        val normalized = ocrScreenText.lowercase()

        // 1. Reglas locales deterministas (instantáneas y sin red)
        if (normalized.contains("reconectar") || normalized.contains("reconnect") ||
            normalized.contains("reintentar") || normalized.contains("retry") ||
            normalized.contains("jugar") || normalized.contains("play")
        ) {
            return UnexpectedDialogResolution(
                actionType = DialogActionType.CLICK_RECONNECT_OR_PLAY,
                explanationEs = "Regla local: Detectado botón de reconexión o inicio de partida.",
                usedLlmApi = false
            )
        }

        if (normalized.contains("cargando") || normalized.contains("loading") ||
            normalized.contains("conectando") || normalized.contains("connecting")
        ) {
            return UnexpectedDialogResolution(
                actionType = DialogActionType.WAIT_LOADING,
                explanationEs = "Regla local: Pantalla de carga en curso, esperando pasivamente.",
                usedLlmApi = false
            )
        }

        if (normalized.contains("cerrar") || normalized.contains("close") ||
            normalized.contains("cancelar") || normalized.contains("aceptar") || normalized.contains("ok")
        ) {
            return UnexpectedDialogResolution(
                actionType = DialogActionType.CLICK_CLOSE_MODAL,
                explanationEs = "Regla local: Diálogo informativo detectado, cerrando ventana modal.",
                usedLlmApi = false
            )
        }

        // 2. Si el texto no coincide con ninguna regla conocida, intentamos LLM por API con respaldo offline
        val apiKey = BuildConfig.GEMINI_API_KEY
        if (apiKey.isNotBlank() && apiKey != "MY_GEMINI_API_KEY") {
            val llmDecision = queryGeminiFallback(ocrScreenText, apiKey)
            if (llmDecision != null) {
                return llmDecision
            }
        }

        // 3. Respaldo seguro si no hay conexión o la API no está configurada: detenerse sin tocar a ciegas
        return UnexpectedDialogResolution(
            actionType = DialogActionType.EMERGENCY_STOP_UNKNOWN,
            explanationEs = "Respaldo sin conexión: Diálogo desconocido ('$ocrScreenText'). Se detiene por seguridad para no tocar a ciegas.",
            usedLlmApi = false
        )
    }

    private suspend fun queryGeminiFallback(
        ocrScreenText: String,
        apiKey: String
    ): UnexpectedDialogResolution? = withContext(Dispatchers.IO) {
        try {
            val url = "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent?key=$apiKey"
            val prompt = """
                Eres el módulo de seguridad de un asistente de recolección en Oxide: Survival Island.
                Texto OCR detectado en un diálogo imprevisto: "$ocrScreenText".
                Elige UNA sola acción segura entre: CLICK_RECONNECT_OR_PLAY, CLICK_CLOSE_MODAL, WAIT_LOADING, EMERGENCY_STOP_UNKNOWN.
                Responde solo la palabra clave.
            """.trimIndent()

            val payload = JSONObject().apply {
                put("contents", JSONArray().put(
                    JSONObject().put("parts", JSONArray().put(JSONObject().put("text", prompt)))
                ))
            }

            val request = Request.Builder()
                .url(url)
                .post(payload.toString().toRequestBody("application/json".toMediaType()))
                .build()

            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext null
                val bodyStr = response.body?.string() ?: return@withContext null
                val root = JSONObject(bodyStr)
                val text = root.optJSONArray("candidates")
                    ?.optJSONObject(0)
                    ?.optJSONObject("content")
                    ?.optJSONArray("parts")
                    ?.optJSONObject(0)
                    ?.optString("text", "")
                    ?.trim()
                    ?.uppercase() ?: return@withContext null

                val action = when {
                    text.contains("CLICK_RECONNECT_OR_PLAY") -> DialogActionType.CLICK_RECONNECT_OR_PLAY
                    text.contains("CLICK_CLOSE_MODAL") -> DialogActionType.CLICK_CLOSE_MODAL
                    text.contains("WAIT_LOADING") -> DialogActionType.WAIT_LOADING
                    else -> DialogActionType.EMERGENCY_STOP_UNKNOWN
                }
                UnexpectedDialogResolution(
                    actionType = action,
                    explanationEs = "Decisión LLM por API ante diálogo imprevisto: $action",
                    usedLlmApi = true
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "Sin conexión o timeout en LLM API, activando respaldo local seguro", e)
            null
        }
    }

    companion object {
        private const val TAG = "KnowledgeBasePlanner"
    }
}
