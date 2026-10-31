package com.example.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.example.service.MistralKeyStore
import com.example.service.MistralVisionClient
import kotlinx.coroutines.launch

@Composable
fun MistralVisionSettingsCard() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var apiKey by remember { mutableStateOf("") }
    var enabled by remember { mutableStateOf(MistralKeyStore.isEnabled(context)) }
    var status by remember { mutableStateOf(if (MistralKeyStore.hasKey(context)) "Clave cifrada guardada en este dispositivo." else "Introduce tu clave API de Mistral.") }
    var busy by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("MISTRAL VISION — CONTROL TÁCTICO", style = MaterialTheme.typography.titleMedium)
            Text("Envía fotogramas seleccionados a Mistral Small 3.2 para interpretar la escena y decidir movimiento, recolección o exploración. El resto del análisis local y las salvaguardas siguen activos.")
            OutlinedTextField(
                value = apiKey, onValueChange = { apiKey = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Clave API Mistral") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation()
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(enabled = !busy && apiKey.isNotBlank(), onClick = {
                    scope.launch {
                        busy = true
                        status = try {
                            val result = MistralVisionClient.testKey(context, apiKey.trim())
                            if (result.startsWith("Conexión")) {
                                MistralKeyStore.save(context, apiKey.trim())
                                apiKey = ""
                            }
                            result
                        } catch (e: Exception) { "No se pudo validar: ${e.message ?: "error de red"}" }
                        busy = false
                    }
                }) { Text(if (busy) "Validando…" else "Validar y guardar") }
                OutlinedButton(onClick = {
                    MistralKeyStore.clear(context)
                    enabled = false
                    apiKey = ""
                    status = "Clave eliminada del almacenamiento local."
                }) { Text("Eliminar clave") }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Column(Modifier.weight(1f)) {
                    Text("Usar Mistral durante la automatización", style = MaterialTheme.typography.bodyMedium)
                    Text("Llamadas limitadas a un fotograma cada 2,2 s.", style = MaterialTheme.typography.bodySmall)
                }
                Switch(checked = enabled && MistralKeyStore.hasKey(context), onCheckedChange = {
                    enabled = it
                    MistralKeyStore.setEnabled(context, it && MistralKeyStore.hasKey(context))
                    if (it && !MistralKeyStore.hasKey(context)) status = "Guarda primero una clave válida."
                })
            }
            Text(status, style = MaterialTheme.typography.bodySmall)
            Text("La clave se cifra con Android Keystore. Las capturas se transmiten a la API de Mistral solo cuando esta opción está activada.", style = MaterialTheme.typography.bodySmall)
        }
    }
}
