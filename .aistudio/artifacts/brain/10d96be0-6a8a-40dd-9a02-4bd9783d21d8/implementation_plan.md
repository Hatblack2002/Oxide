# Oxide Survival Companion — Compañero Autónomo de Recolección

Aplicación nativa Android (Kotlin + Jetpack Compose) que automatiza de forma ética y segura la recolección de recursos (madera, piedra, menas), el crafteo de herramientas y el depósito en cofre dentro de **Oxide: Survival Island** en el mismo dispositivo, deteniéndose al instante ante otros jugadores, daño o pérdida de foco.

## Revisión de Usuario y Decisiones Confirmadas

> [!IMPORTANT]
> **Alcance ético estricto y criterio de abandono**: El sistema tiene prohibido participar en combate, puntería, evasión de ataques, lectura de memoria o modificación del cliente. Opera exclusivamente mediante visión de pantalla (`MediaProjection`) y gestos estándar (`AccessibilityService`). Si el juego bloquea la captura (`FLAG_SECURE`) o rechaza gestos sintéticos tras 3 enfoques legítimos en cualquiera de los puntos de control **G1–G7**, el sistema se detiene y genera el informe técnico de hallazgo.

- **Entorno Android Confirmado**: **Android 12 / 13 (API 31–33)**. Permite mantener la sesión de `MediaProjection` activa dentro del servicio en primer plano (`foregroundServiceType="mediaProjection"`) sin el reinicio por toma de Android 14+, e incorpora permisos explícitos de notificación si el dispositivo corre Android 13.
- **Calibración Dinámica Según tu Pantalla Real (Sin Asumir 16:9)**: En lugar de asumir una relación de aspecto fija de 16:9, la app detecta automáticamente la resolución exacta en píxeles de tu dispositivo en modo horizontal (`WindowMetrics`, por ejemplo `2400x1080` en 20:9, `2340x1080` en 19.5:9 o `1920x1080` en 16:9) y las zonas de recorte de cámara (*display cutout / notch*). Además, incluye un **Modo de Calibración sobre Pantalla Real** (tanto en superposición transparente en vivo sobre Oxide como sobre una captura real de tu partida) para posicionar con precisión milimétrica los puntos del HUD (joystick, golpear, mochila, hotbar, barra de vida y grillas de cofre/mesa) en tu pantalla exacta.
- **Idioma del Juego y OCR**: **Español con HUD por defecto** (con soporte bilingüe Español/Inglés en las expresiones regulares de ML Kit para reconocer nombres de ítems, cantidades como `x150` y requisitos de mesa de trabajo).
- **Modo Recolector de Dataset Integrado (G1–G4)**: El botón flotante incluye un modo **Captura de Dataset / Verificación G1** que guarda fotogramas reales a la resolución nativa de tu pantalla sin distorsión de aspecto y comprueba automáticamente si el frame es negro o válido antes de pasar al entrenamiento TFLite.

---

## 1. Resumen y Concepto Central

- **Qué Hace**: Observa la pantalla del juego entre 5 y 10 fps adaptándose a la geometría exacta del celular, detecta recursos naturales (árboles, rocas, menas), cofres, elementos del HUD y otros jugadores mediante un detector local TensorFlow Lite, lee cantidades e inventario con ML Kit OCR, decide qué herramienta usar o fabricar mediante una base de conocimiento en JSON, y ejecuta caminatas y golpes mediante gestos suaves de accesibilidad.
- **Para Quién y Contexto de Uso**: Jugador de *Oxide: Survival Island* que desea automatizar las tareas repetitivas de tala, minería y almacenamiento en su propio teléfono mientras supervisa o descansa, retomando el control manual inmediato ante cualquier amenaza o combate.
- **Valor Principal**: Ahorro de tiempo en recolección rutinaria con máxima seguridad operativa (parada instantánea con vibración y alerta sonora si aparece un jugador, baja la salud, sube la temperatura de la batería o cambia la app en primer plano).

---

## 2. Experiencia de Usuario y Diseño Visual

### Flujos Principales de Usuario

1. **Centro de Permisos y Preparación**:
   - Pantalla inicial con indicadores de estado en tiempo real para los 4 permisos críticos: **Servicio de Accesibilidad**, **Superposición sobre otras apps (`SYSTEM_ALERT_WINDOW`)**, **Notificaciones / Servicio en Primer Plano**, y **Exclusión de Ahorro de Batería**, más el botón para conceder la **Captura de Pantalla (`MediaProjection`)**. Cada tarjeta lleva directamente al ajuste exacto del sistema.
2. **Calibrador Interactivo de Pantalla Real (Multi-Aspecto: 20:9, 19.5:9, 16:9)**:
   - Lee automáticamente `widthPx x heightPx`, relación de aspecto real y márgenes de *notch/cutout* del teléfono.
   - Permite calibrar de dos formas complementarias:
     1. **Superposición de Calibración en Vivo**: Muestra dianas arrastrables semitransparentes directamente encima de Oxide abierto para que coloques cada marcador (centro de joystick, botón golpear, abrir mochila, slots 1–5 del hotbar, región de barra de vida y botón Jugar/Reconectar) exactamente sobre tu HUD real y guardes el perfil de tu dispositivo.
     2. **Calibración sobre Captura Real + Auto-Anclaje**: Carga cualquier captura tomada con el botón flotante y ajusta automáticamente los anclajes relativos a los bordes izquierdo/derecho (compensando el desplazamiento horizontal que sufren los controles de Unity en pantallas ultra-alargadas de 20:9 o 21:9).
3. **Validación Progresiva por Puntos de Control (G1 → G7)**:
   - Pestaña dedicada donde el usuario ejecuta y valida en orden cada punto de control (**G1** Captura no negra, **G2** Movimiento y golpeo con las coordenadas calibradas de tu pantalla, **G3** Estabilidad térmica 30 min, **G4** Precisión TFLite ≥85%, **G5** Ciclo de recolección y cofre 10 min, **G6** Planificador de crafteo, **G7** Arranque desde menú y reconexión). Incluye registro de evidencia exportable.
4. **Superposición Táctica Flotante (Overlay sobre el Juego)**:
   - Píldora compacta y arrastrable siempre visible sobre Oxide con botón **DETENER** de emergencia (1 toque, respuesta inmediata <50 ms), acceso rápido a **Calibrar HUD Aquí**, selector de modo (**Recolectar**, **Dataset G1/G4**, **Prueba G2**), estado actual de la máquina de estados, FPS de visión y temperatura de batería.
5. **Base de Conocimiento y Pipeline de Entrenamiento**:
   - Visor interactivo del catálogo JSON de herramientas/materiales y guía paso a paso con scripts listos para etiquetar, entrenar YOLOv8n/SSD-MobileNetV2 (con *letterboxing* automático para conservar la proporción real de tu pantalla sin deformar cajas delimitadoras) y exportar a `.tflite`.

### Identidad Visual y Tema

- **Dirección Estética**: *Tactical Survival Telemetry* (Telemetría Industrial de Supervivencia). Interfaz oscura de alto contraste inspirada en consolas de campo e instrumentación táctica, evitando distracciones visuales y reduciendo el consumo de pantalla y calor.
- **Paleta de Colores**:
  - `--background`: Carbón profundo (`#0D1117`) y pizarra táctica (`#161B22`) para tarjetas con elevación tonal.
  - `--primary`: Ámbar óxido (`#E67E22` / `#FF9F43`) para acciones principales, dianas de calibración y progreso de recolección.
  - `--accent`: Verde radar (`#2ECC71`) para validaciones superadas (G1–G7) y estado seguro; Rojo alerta (`#E74C3C`) para parada de emergencia, detección de jugador o bloqueo `FLAG_SECURE`.
- **Tipografía y Jerarquía**:
  - Encabezados y métricas de estado con tipografía técnica condensada/geométrica y fuente monoespaciada local (`Roboto Mono`) para telemetría en vivo (FPS, resolución nativa detectada, coordenadas exactas en píxeles y normalizadas, temperatura en °C y conteos OCR).
- **Retroalimentación Interactiva**:
  - Indicadores pulsantes cuando la captura y el servicio están activos, previsualización visual de los toques calibrados en modo prueba (**G2**), alertas hápticas diferenciadas (patrón corto al pausar, patrón urgente triple ante jugador detectado o pérdida de vida) y transiciones limpias entre pestañas.

---

## 3. Decisiones Clave de Producto y Compromisos Técnicos

- **Decisión 1: Sistema de Coordenadas Adaptativo Basado en la Pantalla Real + Superposición de Calibración**
  - *Enfoque Elegido*: Medir en tiempo de ejecución la geometría real en horizontal (`realWidthPx`, `realHeightPx`, `aspectRatio = realWidthPx / realHeightPx`, `cutoutSafeInsets`) mediante `WindowManager.currentWindowMetrics`. Ofrecer preajustes calculados por anclaje de borde (ya que en motores como Unity los paneles laterales del HUD se anclan a las esquinas izquierda/derecha y los centrales al centro de la pantalla cuando pasa de 16:9 a 20:9) y permitir al usuario arrastrar las dianas directamente sobre el juego en vivo o sobre una captura real para guardar los píxeles exactos de su celular en almacenamiento persistente.
  - *Por Qué*: Evita que los toques fallen en teléfonos 19.5:9, 20:9 o con recorte de cámara frontal, garantizando que **G2** (moverse y golpear) y **G5/G6** (inventario, cofre y crafteo) pulsen exactamente donde están los botones en el dispositivo real del usuario.
  - *Alternativas Descartadas*: Asumir proporciones fijas de 16:9 desplaza horizontalmente el joystick, el hotbar y la grilla del inventario en pantallas alargadas modernas.

- **Decisión 2: Captura de Pantalla a 5–10 FPS con `ImageReader` a Proporción Nativa**
  - *Enfoque Elegido*: Usar `VirtualDisplay` configurado con la relación de aspecto exacta del dispositivo conectado a un `ImageReader` (`PixelFormat.RGBA_8888`) con descarte de frames intermedios (throttling configurable entre 100 ms y 200 ms) y preprocesamiento con *letterboxing* (relleno simétrico sin deformación) al alimentar el tensor de entrada de TFLite.
  - *Por Qué*: Cumple con los puntos de control **G1** (detección inmediata de frames negros por `FLAG_SECURE`), **G3** (mantener 30 minutos de sesión sin sobrecalentar el procesador) y **G4** (evita aplastar los árboles o rocas al convertir una pantalla 20:9 al tensor cuadrado del detector).

- **Decisión 3: Visión Híbrida (Detector TFLite Local + Analizador de Barra de Vida Calibrada + ML Kit OCR bajo demanda)**
  - *Enfoque Elegido*: Ejecutar el detector TFLite en cada ciclo (5–10 fps) para objetos dinámicos (`tree`, `rock_stone`, `rock_metal`, `rock_sulfur`, `chest`, `player`, `hud_anchor`) complementado con verificación rápida de barra de vida por muestreo cromático en el rectángulo exacto calibrado por el usuario en su pantalla, y disparar **ML Kit Text Recognition** únicamente cuando el estado es `INVENTORY_OPEN`, `CRAFTING_MENU`, `CHEST_OPEN` o `DIALOG_UNEXPECTED`.
  - *Por Qué*: Ejecutar OCR en cada frame de caminata es innecesario y costoso; activarlo solo al abrir menús o diálogos reduce el uso de CPU en un 60% y mejora la precisión de lectura de cantidades (`Madera`, `Piedra`, `Metal`, `Azufre`).

- **Decisión 4: Planificador Determinista en JSON con Respaldo LLM Opcional**
  - *Enfoque Elegido*: El motor principal evalúa una base de conocimiento local en JSON (jerarquía de hachas y picos: Roca → Hacha/Pico de Piedra → Hacha/Pico de Metal → Hacha/Pico Salvaje, costos de madera/piedra/fragmentos de metal, nivel de mesa requerido y velocidad de recolección). Solo ante un diálogo o estado desconocido tras varios intentos se consulta de forma asíncrona al modelo Gemini con timeout estricto; si no hay red o clave configurada, aplica una regla conservadora de seguridad (cerrar diálogo o pausar y avisar).
  - *Por Qué*: Garantiza determinismo total para el punto de control **G6**, funcionamiento 100% offline y cero riesgo de acciones erráticas en partida.

---

## 4. Arquitectura Técnica y Estrategia de Datos

### Diagrama de Arquitectura y Componentes

```
┌────────────────────────────────────────────────────────────────────────────┐
│                   UI DE CONTROL & TELEMETRÍA (Jetpack Compose)             │
│  ┌─────────────────────┐  ┌──────────────────────┐  ┌───────────────────┐  │
│  │ Centro de Permisos  │  │ Consola Checkpoints  │  │ Calibrador HUD    │  │
│  │ (Accesib/Overlay/   │  │ G1..G7 + Recolector  │  │ en Pantalla Real  │  │
│  │  Batería/MediaProj) │  │ de Dataset & Reporte │  │ (20:9/19.5:9/16:9)│  │
│  └──────────┬──────────┘  └──────────┬───────────┘  └─────────┬─────────┘  │
└─────────────┼────────────────────────┼────────────────────────┼────────────┘
              │                        │                        │
              ▼                        ▼                        ▼
┌────────────────────────────────────────────────────────────────────────────┐
│            SERVICIO EN PRIMER PLANO + OVERLAY FLOTANTE INMEDIATO           │
│  ┌──────────────────────────────────────────────────────────────────────┐  │
│  │ Guardián de Seguridad (Prioridad 0 - Ciclo de 100 ms):               │  │
│  │ • Verifica packageName == Oxide en primer plano + Estado de Llamada  │  │
│  │ • Alerta si Jugador Detectado / Vida baja / Muerte / Sin HUD         │  │
│  │ • Monitor Térmico (BatteryManager > 41°C pausa, > 43°C detiene)      │  │
│  └──────────────────────────────────┬───────────────────────────────────┘  │
│                                     │                                      │
│  ┌───────────────────────┐          ▼          ┌────────────────────────┐  │
│  │ Captura MediaProjection│───► Pipeline ─────►│ Máquina de Estados FSM │  │
│  │ (5-10 FPS, Geometría  │     de Visión:      │ • LOADING / MAIN_MENU  │  │
│  │  Nativa del Celular,  │     1. TFLite Int8  │ • SERVER_SELECT        │  │
│  │  Detector Frame Negro │        (Letterbox)  │ • IN_GAME_GATHERING    │  │
│  │  para Checkpoint G1)  │     2. HUD/Vida RGB │ • INVENTORY_FULL       │  │
│  └───────────────────────┘     3. ML Kit OCR   │ • DEPOSIT_TO_CHEST     │  │
│                                   (Español)    │ • CRAFT_OR_UPGRADE     │  │
│                                                │ • STUCK_RECOVERY       │  │
│                                                │ • EMERGENCY_STOP       │  │
│                                                └───────────┬────────────┘  │
│                                                            │               │
│  ┌─────────────────────────────────────────────────────────▼────────────┐  │
│  │ Planificador JSON + Controlador AccessibilityService (dispatchGesture)│  │
│  │ • Mapa de Coordenadas Calibradas a la Resolución Real del Dispositivo│  │
│  │ • Joystick Virtual (Swipe continuo) + Golpear + Hotbar + Cofre/Mesa  │  │
│  └──────────────────────────────────────────────────────────────────────┘  │
└────────────────────────────────────────────────────────────────────────────┘
```

### Modelo de Datos y Estados

1. **Perfil de Calibración de Pantalla Real (`ScreenCalibrationProfile`)**:
   - Almacena la resolución real detectada (`screenWidthPx`, `screenHeightPx`, `densityDpi`, `aspectRatioLabel` como `20:9`, `19.5:9`, `16:9`, e insets de *cutout*) junto con las coordenadas exactas calibradas por el usuario sobre su pantalla para:
     - `joystickCenter` y `joystickRadiusPx`
     - `attackButton`, `jumpButton`, `crouchButton`, `interactOpenButton`
     - `inventoryButton`, `craftingTabButton`, `craftActionButton`
     - `hotbarSlots[0..4]` (5 ranuras de acceso rápido)
     - `healthBarRect` (rectángulo exacto de la barra de salud para detección instantánea de daño)
     - `playButtonMainMenu`, `serverSelectSlot`, `reconnectButton` y `respawnButton`
     - `inventoryGridBounds` y `chestGridBounds` (regiones para transferencia de recursos al cofre)

2. **Máquina de Estados Completa (`CompanionState`)**:
   - `IDLE`: Bot detenido; overlay en espera o en modo de calibración interactiva.
   - `LOADING`: Pantalla de carga detectada; espera pasiva con timeout máximo antes de declarar desconexión.
   - `MAIN_MENU` y `SERVER_SELECT`: Reconoce botón "Jugar" / servidor configurado usando las posiciones calibradas y OCR para el arranque completo y reconexión (**G7**).
   - `IN_GAME_SCANNING`: HUD visible, salud estable, sin jugadores. Busca el recurso más cercano (`tree`, `rock_stone`, `rock_metal`, `rock_sulfur`) o gira la cámara en patrón sistemático de exploración si no hay recursos en vista.
   - `IN_GAME_APPROACHING`: Desplaza el joystick virtual hacia el centroide del recurso (deshaciendo el *letterboxing* del detector para mapear la caja delimitadora al píxel exacto de la pantalla real); incluye detector de atascos (si el flujo óptico / tamaño de caja delimitadora no cambia en 3.5 s o detecta agua/obstáculo, ejecuta maniobra de desatasco lateral/salto).
   - `IN_GAME_HARVESTING`: Selecciona en el hotbar calibrado la herramienta óptima según el tipo de recurso (hacha para madera, pico para roca/mena) y pulsa el botón de golpeo verificando recolección.
   - `TOOL_BROKEN_OR_UPGRADEABLE` (**G6**): Abre inventario/mesa, ejecuta ML Kit OCR sobre materiales disponibles, consulta el planificador JSON y fabrica la mejor herramienta permitida.
   - `INVENTORY_FULL_RETURN_CHEST` (**G5**): Cuando el OCR o el indicador visual de mochila llena se activa, localiza y se dirige al cofre registrado, lo abre y deposita los materiales recolectados conservando las herramientas equipadas.
   - `EMERGENCY_STOP`: Activado por presencia de otro jugador (`player`), caída de barra de salud en `healthBarRect`, pantalla de muerte (`DEAD`), pérdida del HUD en partida, cambio de app, llamada entrante o exceso de temperatura. Cancela cualquier gesto en curso, vibra y emite alerta.

3. **Base de Conocimiento en JSON (`oxide_knowledge_base.json`)**:
   - Define las herramientas (`rock_starter`, `stone_hatchet`, `stone_pickaxe`, `metal_hatchet`, `metal_pickaxe`, `salvaged_axe`, `salvaged_pickaxe`), su categoría (`wood` vs `ore`), nivel de mesa de trabajo requerido (`0`, `1`, `2`), costos exactos de materiales (`madera`, `piedra`, `fragmentos_metal`, `componentes`), durabilidad estimada y multiplicador de velocidad de recolección.

4. **Sistema de Puntos de Control (G1 a G7) con Criterio de Abandono (3 Enfoques)**:
   - Cada punto de control registra en un log persistente el estado (`PENDING`, `IN_PROGRESS`, `PASSED`, `FAILED_ABANDONED`), los hasta 3 enfoques técnicos legítimos probados y exporta automáticamente un informe Markdown/JSON detallando evidencia y causa raíz si los 3 enfoques son bloqueados por el juego.

5. **Pipeline de Entrenamiento y Exportación TFLite Entregable**:
   - Se incluye dentro del repositorio el directorio `training_pipeline/` con scripts completos y comentados en Python (`prepare_dataset.py`, `train_yolov8_tflite.py`, `evaluate_g4_accuracy.py`, `requirements.txt` y guía de etiquetado en CVAT/LabelImg) que preservan cualquier relación de aspecto de entrada (20:9, 19.5:9, 16:9) mediante *letterbox* y verifican el umbral de **≥85% mAP@50** exigido en **G4** antes de copiar el archivo `oxide_detector.tflite` a `app/src/main/assets/`.
