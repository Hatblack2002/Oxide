# Oxide Companion — Compañero Autónomo de Recolección (Android / Kotlin)

Asistente nativo Android en Kotlin y Jetpack Compose para **Oxide: Survival Island** (HYPERHUG). Diseñado para ejecutarse en el mismo teléfono donde juegas y automatizar exclusivamente la recolección rutinaria de madera y minerales, el crafteo de herramientas y el depósito en tu cofre, manteniendo una política estricta de **cero combate, cero lectura de memoria y parada inmediata ante otros jugadores**. El procesamiento de visión, OCR y planificación funciona localmente; no requiere Gemini, Firebase, Google AI Studio ni claves de API.

---

## 1. Límites Éticos y Seguridad Obligatoria

- **Fuera de alcance (prohibido en código)**: Combate, puntería, esquivar ataques, leer memoria del proceso, modificar archivos del juego, uso de `root` o cualquier ventaja competitiva frente a otros jugadores.
- **Guardián de Seguridad (`SafetyGuardian.kt`)**:
  1. **Verificación de app en primer plano**: Solo despacha gestos si `Oxide: Survival Island` está activo en pantalla y no hay llamadas telefónicas en curso (`AudioManager`).
  2. **Parada instantánea por amenaza**: Si el detector visual encuentra un `player`, si baja el porcentaje de la barra de vida calibrada, si aparece la pantalla de muerte o si se pierde el HUD sin haber abierto el inventario, el servicio cancela todos los gestos al instante, vibra con patrón de alerta y lanza una notificación crítica.
  3. **Botón Flotante `DETENER`**: Siempre visible sobre el juego; detiene la máquina de estados en `< 50 ms`.
  4. **Protección térmica y de batería (`G3`)**: Reduce automáticamente los FPS de captura si la batería supera `38.5 °C`, pausa en `41.2 °C` y ejecuta parada total en `43.0 °C` o `< 15%` de batería.

---

## 2. Calibración Dinámica Según tu Pantalla Real (Sin Asumir 16:9)

Muchos teléfonos actuales tienen pantallas alargadas **20:9** (`2400×1080`), **19.5:9** (`2340×1080`) o recortes de cámara (*notch/cutout*) que desplazan las posiciones de los controles respecto a un monitor 16:9.

1. Al abrir la app, `MainActivity` mide con `WindowMetrics` la resolución real en horizontal (`realLandscapeWidthPx × realLandscapeHeightPx`), la relación de aspecto exacta y los márgenes seguros del *cutout*.
2. `ScreenCalibrationProfile` calcula las posiciones iniciales anclando los controles laterales (joystick izquierdo, golpear/saltar/interactuar a la derecha) a los bordes seguros reales y centrando el hotbar, tal como hace el `CanvasScaler` de Unity.
3. En la pestaña **Calibrar Pantalla Real** o pulsando **Calibrar HUD Real** en el botón flotante sobre el juego, puedes arrastrar cada diana (`⊕ Joystick`, `⊕ Botón Golpear`, `⊕ Abrir Cofre`, `⊕ Abrir Mochila`, `⊕ Hotbar Slot 1/2`, `⊕ Barra de Vida`) directamente encima de los botones de tu partida real.
4. En visión (`OxideVisionPipeline.kt`), los fotogramas pasan por **Letterboxing** simétrico antes de entrar al tensor de TensorFlow Lite para no aplastar ni deformar las cajas delimitadoras en pantallas 20:9.

---

## 3. Instrucciones Exactas de Instalación y Activación de Permisos

1. **Compilar e instalar el APK**:
   ```bash
   gradle :app:assembleDebug
   ```
2. **Activar los 5 permisos desde la Pestaña 1 ("Permisos y Control")**:
   - **Paso 1 — Servicio de Accesibilidad**: Toca *Activar Accesibilidad* → Servicios descargados → **Oxide Compañero de Recolección** → Activar.
   - **Paso 2 — Superposición sobre otras apps**: Toca *Permitir Superposición* → Activa el interruptor para permitir mostrar el botón flotante encima del juego.
   - **Paso 3 — Sin restricción de batería**: Toca *Desactivar Ahorro Batería* → Selecciona *Permitir / Sin restricciones* para evitar cierres del sistema durante sesiones de 30 minutos (**G3**).
   - **Paso 4 — Notificaciones**: Toca *Conceder Notificaciones* (requerido en Android 13+ para mostrar el estado del servicio y alertas de seguridad).
   - **Paso 5 — Captura MediaProjection**: Toca *ACTIVAR CAPTURA + OVERLAY* y acepta el diálogo del sistema. Aparecerá la píldora flotante en la parte superior.

---

## 4. Pipeline para Recolectar Capturas, Etiquetar, Entrenar y Exportar TFLite

1. Abre Oxide con el botón flotante activo, cambia el modo del botón flotante a **`Dataset / G1`** y pulsa **`INICIAR`**.
2. Camina unos minutos cerca de árboles, rocas de piedra/metal/azufre, tu cofre y otros jugadores. Las capturas reales sin compresión se guardan en:
   `Android/data/com.aistudio.oxidecompanion.rxkmpz/files/dataset_captures/`
3. Copia las capturas a tu PC, etiquétalas en formato YOLO con las 7 clases (`tree`, `rock_stone`, `rock_metal`, `rock_sulfur`, `chest`, `player`, `hud_anchor`) usando `labelImg` o CVAT, y ejecuta:
   ```bash
   cd training_pipeline
   pip install -r requirements.txt
   python3 prepare_and_split_dataset.py --raw_dir ./mis_capturas
   python3 train_and_export_tflite.py --data ./dataset_config.yaml --epochs 80 --imgsz 320
   ```
4. Si el script confirma **`mAP@50 >= 85%`** en capturas de validación no vistas (**G4**), exportará automáticamente `app/src/main/assets/oxide_detector.tflite`.

---

## 5. Estado y Resultado de los Puntos de Control (G1 – G7)

| Punto | Descripción | Estado en Código / Pruebas | Enfoques Implementados y Criterio de Abandono |
| :--- | :--- | :--- | :--- |
| **G1** | **La captura no sale en negro** | **Listo para validar en celular** (Verificador automático integrado en `ScreenCaptureManager.kt`) | Prueba hasta 3 enfoques (`RGBA_8888` con compensación de `rowStride`, banderas `AUTO_MIRROR`/`PUBLIC` y muestreo multi-cuadrante). Si los 3 devuelven luminancia `< 2.2` por `FLAG_SECURE`, se detiene sin evadirlo y genera el informe de hallazgo. |
| **G2** | **Los toques mueven al personaje y golpean** | **Listo para validar en celular** (Modo `Prueba Toques G2` en el botón flotante) | Implementa 3 enfoques en `OxideAccessibilityService.kt` (trazo continuo, trazos encadenados con `willContinue=true` para Unity y trayectoria curva humanizada) usando las coordenadas calibradas de tu pantalla real. |
| **G3** | **30 min conectado sin sobrecalentar** | **Verificado en arquitectura** (`SafetyGuardian.kt` + throttling 4–7 FPS) | Reutiliza buffers `ByteBuffer`, ejecuta OCR solo con inventario abierto y reduce FPS automáticamente al subir la temperatura de batería. |
| **G4** | **Detector >= 85% en capturas no vistas** | **Pipeline completo entregado** (`training_pipeline/train_and_export_tflite.py`) | El script evalúa `metrics.box.map50` en `val/` y bloquea la exportación si es `< 0.85`. |
| **G5** | **10 min de recolección continua + cofre** | **Implementado en `CompanionStateMachine.kt`** | Incluye búsqueda visual, desatasco automático a los 3.5 s y retorno al cofre tras llenar mochila. |
| **G6** | **Planificador identifica cuándo mejorar herramienta** | **PASÓ (Verificado con test unitario JVM)** | `KnowledgeBasePlanner.kt` + `oxide_knowledge_base.json` + parser OCR bilingüe validados al 100% en tests unitarios. |
| **G7** | **Arranque desde menú y reconexión** | **Implementado en `CompanionStateMachine.kt` + `KnowledgeBasePlanner.kt`** | Reconoce `"Jugar"`, `"Servidor"`, `"Cargando"` y `"Reconectar"` por OCR mediante OCR y reglas locales; cualquier diálogo desconocido provoca una parada segura. |
