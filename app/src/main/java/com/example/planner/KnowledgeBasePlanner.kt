package com.example.planner

import android.content.Context
import android.util.Log
import com.example.model.ToolCategory
import org.json.JSONArray
import org.json.JSONObject

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
 * 4. Ante un diálogo imprevisto, aplica reglas locales deterministas; si no lo reconoce, se detiene por seguridad.
 */
class KnowledgeBasePlanner(private val context: Context? = null) {

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
     * Resuelve diálogos imprevistos sin servicios externos: reglas locales y parada segura si no reconoce el texto.
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

        // 2. Respaldo local: nunca se envían capturas ni texto OCR a servicios externos.
        return UnexpectedDialogResolution(
            actionType = DialogActionType.EMERGENCY_STOP_UNKNOWN,
            explanationEs = "Respaldo sin conexión: Diálogo desconocido ('$ocrScreenText'). Se detiene por seguridad para no tocar a ciegas.",
            usedLlmApi = false
        )
    }

    companion object {
        private const val TAG = "KnowledgeBasePlanner"
    }
}
