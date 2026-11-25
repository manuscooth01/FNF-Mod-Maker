# FNF Mod Maker — Auditoría completa (SHA 8fca18ce)

## Resumen ejecutivo

El repositorio contiene una base de código **sustancial y bien estructurada** con ~70 archivos Kotlin, ~50 tests unitarios, y arquitectura clara. Sin embargo, **la funcionalidad BOTH (Psych + V-Slice simultáneo) no está implementada** — el modelo actual usa `ModConfig` compartido con mapas `charts`/`chartHistories` indexados solo por `songFolder` + `difficulty`, no por motor. La estructura experimental `IndependentCharts.kt` existe pero **no está integrada** en `ModConfig`, `ProjectStore`, validación, autoguardado, ni `.fnfproject`.

---

## 1. Arquitectura y modelos

| Funcionalidad | Estado | Evidencia |
|---------------|--------|-----------|
| `ModConfig` (modelo principal) | **2. Parcial** | `ModConfig.kt:137-266` — comparte charts por `songFolder`/`difficulty`, no distingue motor. `chartSchemaVersion` null/1 protege esquema legacy. |
| `IndependentCharts.kt` (modelo experimental) | **3. Interfaz sin integración** | `IndependentCharts.kt:49-65` — `IndependentChartCollection` con `(songId, ChartEngine, difficulty)`, `IndependentVariant` con metadata/chart/histories/originales propios, `legacyDocumentJson`. **No se usa en `ModConfig`, `ProjectStore`, `PortableProject`, validación, autoguardado, ni UI.** |
| `ChartEngine` enum | **4. No implementado en flujo real** | Solo `PSYCH`/`VSLICE`; `BOTH` es `TargetEngine`, nunca `ChartEngine`. |
| `SongConfig` | **2. Parcial** | Contiene campos Psych y V-Slice mezclados (`vSliceOrigin`, `vSliceEventsJson`, `vSliceTempoJson`, `psychSnapReference`, etc.). No separación por variante. |
| `ChartDraft` | **1. Completo** | Notas, eventos, secciones Psych, `psychTypeJson`, `psychLayout`, `psychSongExtras`, serialización Psych/V-Slice. `requireExportable()` valida antes de exportar. |
| `ChartPersistentHistory` | **1. Completo** | Undo/redo por canción/dificultad con huellas SHA-256, plantillas, límites acotados. |
| `CharacterDraft` / `CharacterVariation` | **2. Parcial** | Estructura completa (spritesheet, atlas Sparrow, frames, animaciones, offsets, variaciones, importados). **Falta: previsualización en tiempo real, validación de frames rotados en atlas, exportación por motor verificada.** |
| `StageDraft` / `StageSprite` / `StageLayout` | **3. Interfaz sin funcionalidad completa** | Modelos definidos (`StageDraft.kt:12-146`), pero `hasUnsupportedFeatures()` devuelve true para casi todo. `SimpleStagePanel` es placeholder. |
| `TargetEngine` enum | **1. Completo** | `VSLICE`, `PSYCH`, `BOTH` con `includesVSlice/includesPsych`. |

---

## 2. Pantallas y navegación

| Pantalla | Estado | Evidencia |
|----------|--------|-----------|
| `HomeScreen` | **1. Completa** | `ToolScreens.kt:91-271` — tres herramientas: Mod, Personaje, Audio. Navegación a `WizardScreen`, `CharacterToolScreen`, `AudioToolScreen`. |
| `WizardScreen` (asistente 6 pasos) | **1. Completa** | `WizardScreens.kt:39-157` — pasos: Mod, Canciones, Personaje, Audio, Notas, Exportar. Navegación por chips tocables. |
| `WizardModStep` (paso 0) | **1. Completa** | `WizardScreens.kt:190-219` — título, autor, engine (V-Slice/Psych/BOTH), formato Psych, color, story mode. |
| `WizardSongsStep` (paso 1) | **2. Parcial** | `WizardScreens.kt:232-406` — CRUD canciones, dificultades, duplicar charts, `SimpleStagePanel` (placeholder). **Falta: selección de motor por canción en BOTH.** |
| `WizardCharacterStep` (paso 2) | **2. Parcial** | `CharacterWorkspace.kt:46-144` — pestañas: Personaje, Imágenes, Animación, Posición, Asistente IA, Revisar. **Falta: previsualización animada en tiempo real, validación atlas rotado, exportación por motor.** |
| `WizardAudioStep` (paso 3) | **1. Completa** | `WizardScreens.kt:400-407` (fragmento) — instrumental, voces jugador/oponente, icono, conversión FFmpeg. |
| `WizardNotesStep` (paso 4) | **3. Interfaz sin funcionalidad completa** | `WizardScreens.kt:387-407` — solo notas de práctica. **El editor real está en `LiveChartEditor` (accesible desde paso 5? No — hay confusión).** |
| `WizardExportStep` (paso 5) | **1. Completa** | `WizardScreens.kt:410-444` — resumen, validación, warnings, scripts, guardar ZIP. |
| `LiveChartEditor` | **2. Parcial** | `LiveChartEditor.kt:75-134` — grabación en vivo, selección canción/dificultad/lado, snap Psych/V-Slice, corrección toque, timeline, arrow pad. **Falta: Piano Roll, edición de notas existentes, eventos, secciones, tempo map, undo/redo persistente.** |
| `TouchChartGridEditor` | **4. No implementado** | Referenciado pero no integrado en flujo principal. |
| `CharacterToolScreen` (herramienta independiente) | **2. Parcial** | `ToolScreens.kt:366-394` — usa `CharacterWorkspace` compartido. Exporta ZIP personaje. |
| `StageToolScreen` | **4. Placeholder** | `ToolScreens.kt:321-359` — solo texto "En desarrollo". |
| `AudioToolScreen` | **1. Completa** | `ToolScreens.kt:401-524` — conversión a OGG, preview, waveform. |
| `ImportScreen` | **3. Solo biblioteca ZIP** | `ToolScreens.kt:278-314` — guarda/exporta copias ZIP, **no importa a proyecto editable**. |
| `ModPreviewScreen` | **1. Completa** | `ModPreviewScreen.kt` — vista conjunta del mod. |

---

## 3. ViewModels y estado

| Componente | Estado | Evidencia |
|------------|--------|-----------|
| `WizardViewModel` | **2. Parcial / God Object** | `WizardViewModel.kt:76-3216` (3216 líneas) — maneja TODO: config, live recording, character, audio, import/export, AI, project store, portable, validation, undo/redo, media playback. **Demasiadas responsabilidades, sin repositorios/adaptadores de motor separados.** |
| `liveChart` / `liveSong` / `getChart` / `withChart` | **2. Parcial** | Usan `ModConfig.charts[songFolder][difficulty]` — **no distinguen motor**. En BOTH, edita el mismo chart para ambos motores. |
| `liveUsesVSliceSnap` / `psychSnapReference` | **2. Parcial** | `WizardViewModel.kt:405-419` — preferencia de snap separada del chart, pero **no hay chart separado por motor**. |
| `vSliceEventsJson` / `vSliceTempoJson` undo/redo | **1. Completos** | Por canción, stacks acotados (10), validación en `ProjectConfigValidator.kt:54-75`. |
| `chartHistories` (grid undo/redo) | **2. Parcial** | `ChartPersistentHistory` por `songFolder`/`difficulty` — **no por motor**. |
| Auto-guardado | **2. Parcial** | Escribe JSON en `filesDir`, recursos en `cacheDir/inputs` (Android los puede borrar). **No atómico, errores silenciados, un solo borrador.** |
| Gestión de proyectos (`ProjectStore`) | **1. Completa** | `ProjectStore.kt:49-210` — UUID, `project.json`, `active.txt`, staging `.pending-*`, `publishImport`, `copyAssets`, validación SHA-256, límites. |
| Portable (`.fnfproject`) | **1. Completa** | `PortableProject.kt:28-206` — export/prepare/materialize, checksums, límites (4096 archivos, 1 GiB, 256 MiB/asset, 8 MiB JSON), traversal protection. |

---

## 4. Persistencia y autoguardado

| Funcionalidad | Estado | Evidencia |
|---------------|--------|-----------|
| `ProjectStore` (proyectos internos) | **1. Completa** | Transaccional (write to `.tmp`, rename), backup `.bak`, `active.txt`, staging para imports, limpieza abandoned. |
| `PortableProject` (`.fnfproject`) | **1. Completa** | Versionado, checksums, validación manifiesto, materialización con reubicación rutas relativas. |
| `ProjectConfigValidator` | **1. Completa** | `ProjectConfigValidator.kt:12-195` — valida esquema, assets, characters, stage, charts, histories, Psych/V-Slice fields, note types, precision. **Rechaza `chartSchemaVersion` desconocido y `independentCharts`.** |
| `chartSchemaVersion` guard | **1. Completa** | `ChartSchema.kt:10-19` — null/1 aceptado; otro valor o presencia de `independentCharts` → rechazo antes de deserializar. |
| Autoguardado (`WizardViewModel`) | **5. Con errores/limitaciones** | `WizardViewModel.kt` — escribe JSON directo, no transaccional, recursos en cache (volátiles), un solo proyecto activo. |
| Migración `characterLibrary` | **1. Completa** | `CharacterLibraryMigration.kt` + `migrateCharacterLibrary` en validator. |

---

## 5. Importadores de mods

| Importador | Estado | Evidencia |
|------------|--------|-----------|
| `PsychModImporter` | **1. Completo (solo Psych)** | `PsychModImporter.kt:18-134` — prepara en staging, copia assets, parsea `pack.json`, charts, audio, personajes, recursos. **Rechaza V-Slice/BOTH** (línea 26: `require(!relative.keys.any { it.equals("_polymod_meta.json") || it.startsWith("data/songs/") })`). |
| `VSliceModImporter` | **1. Completo (solo V-Slice)** | `VSliceModImporter.kt:17-203` — similar, parsea `_polymod_meta.json`, `data/songs/*/metadata.json`, charts, audio, personajes. |
| `ModZipInspector` | **1. Completo** | `ModZipInspector.kt` — inspección segura sin extraer, detección estructura, SHA-256, límites. |
| **Importación BOTH (ZIP)** | **4. No implementada** | `both-zip-audit.md` confirma: "La importación real de ZIP BOTH **no está implementada**." Bloqueada en `ModBuilder.kt:70-72` y `PsychModImporter.kt:26`. |
| `ModImportSession` | **1. Completa** | Orquesta importación individual, staging, publicación transaccional. |

---

## 6. Exportadores de mods

| Exportador | Estado | Evidencia |
|------------|--------|-----------|
| `ModBuilder.buildInternal` (Psych) | **1. Completo** | `ModBuilder.kt:206-274` — `pack.json`, charts por dificultad (`data/<folder>/<folder>[-diff].json`), weeks, scripts, audio, personajes, stage, icono, recursos originales preservados. |
| `ModBuilder.buildInternal` (V-Slice) | **1. Completo** | `ModBuilder.kt:160-203` — `_polymod_meta.json`, `data/songs/<folder>/<folder>-metadata.json`, `-chart.json`, `data/levels/*.json`, title image, audio, personajes, stage. |
| `ModBuilder.buildInternal` (BOTH) | **5. Bloqueado / Inseguro** | `ModBuilder.kt:70-73` — **requiere `engine != BOTH` si hay `importedModResources`**. `require(!importedVSlice || engine == VSLICE)`. Exporta ambas estructuras pero **charts compartidos** (mismo `ChartDraft` para ambos motores). |
| `JsonBuilders` | **1. Completo** | Genera JSON Psych/V-Slice para metadata, charts, weeks, levels, polymod, pack. |
| `VSlicePreservation` / `patchVSliceChart` | **1. Completo** | Conserva originales no editables, parchea solo dificultades editables. |
| Exportación personaje | **2. Parcial** | `CharacterBuilder.kt` + `ModBuilder.kt:104-130` — genera atlas, JSON Psych/V-Slice, icono. **Falta validar que animaciones personalizadas funcionen en motor.** |
| Exportación stage | **4. Placeholder** | `StageBuilder.kt` existe pero `StageDraft.hasUnsupportedFeatures()` bloquea casi todo. |

---

## 7. Editor de charts (Chart Studio)

| Funcionalidad | Estado | Evidencia |
|---------------|--------|-----------|
| Grabación en vivo (`LiveChartEditor`) | **2. Parcial** | `LiveChartEditor.kt:75-468` — arrow pad, timeline, waveform, snap Psych/V-Slice, corrección toque, transport play/pause/seek. **Solo añade notas; no edita existentes.** |
| Piano Roll / Grid editor | **4. No implementado** | `TouchChartGridEditor.kt` existe pero no integrado. `TouchChartGrid.kt` modelo sin UI completa. |
| Selección/edición de notas existentes | **4. No implementado** | No hay multi-selección, drag, cambio duración, delete individual en UI. |
| Sostenidos (hold notes) | **2. Parcial** | Grabación: mantener 120ms → hold. `ChartNote.lengthMs` soportado en modelo. **No editable visualmente.** |
| Tipos de nota (Psych `psychTypeJson`, V-Slice `kind`) | **1. Modelo completo** | `ChartNote.psychTypeJson` (token exacto) + `kind` (V-Slice). Validación en readers. **No UI para editar.** |
| Secciones Psych (`psychLayout`) | **2. Parcial** | `PsychSectionEditor.kt` + `PsychVisualEvents.kt` existen. **No integrados en flujo principal de grabación.** |
| Eventos Psych (`psychEventsJson`) | **2. Parcial** | `PsychEventsEditor` en `LiveChartEditor.kt:271-293`. **Solo visualización, no edición persistente por motor.** |
| Eventos V-Slice | **2. Parcial** | `VSliceVisualEvents.kt` + `VSliceEventsEditor` en `LiveChartEditor.kt:296-322`. Undo/redo en `SongConfig`. **No por variante de motor.** |
| Mapas de tempo V-Slice | **2. Parcial** | `VSliceTempoEditor.kt` + undo/redo en `SongConfig`. **No por variante de motor.** |
| Audio sincronizado / waveform | **2. Parcial** | `ChartWaveform.kt`, `AudioPreview.kt`, `WaveformPeaks.kt` + `WaveformDecoder.kt` (streaming). `MediaPlayer` para playback. **No sample-accurate sync.** |
| Undo/Redo persistente (grid) | **2. Parcial** | `ChartPersistentHistory` por `songFolder`/`difficulty` en `ModConfig.chartHistories`. **No separado por motor.** |
| Snap / referencias de tempo | **2. Parcial** | `RecordingSnap` enum, `psychSnapReference` / `liveUsesVSliceSnap` en `SongConfig`/`ViewModel`. **La selección de motor de edición NO está separada de la referencia de snap.** |

---

## 8. Character Studio

| Funcionalidad | Estado | Evidencia |
|---------------|--------|-----------|
| Crear personaje | **2. Parcial** | `CharacterWorkspace.kt` + `CharacterDraft` — campos completos. |
| Importar spritesheet (grid) | **1. Completa** | `CharacterImages` tab: PNG grid, frame size, atlas generation. |
| Importar atlas Sparrow (PNG + XML) | **1. Completa** | `parseSparrowAtlas`, `atlasFrames`, `atlasPrefixes`, conserva recortes XML. |
| Cargar y validar XML | **1. Completa** | `parseSparrowAtlas` + validación `atlasFramesFit` + tamaño PNG. |
| Editar posiciones/dimensiones | **2. Parcial** | `CharacterPosition` tab + `CharacterAdjustment` (POSITION/ANIMATION/CAMERA). `nudged()` para ajustes. |
| Visualizar animaciones | **3. Interfaz sin funcionalidad completa** | `CharacterPreview.kt` — vista previa estática/frame a frame. **No animación en tiempo real (play/pause/loop).** |
| Crear/modificar animaciones | **2. Parcial** | `CharacterAnimation` tab: custom animations, frame counts, FPS, loop, offsets. `animationSettings` map. |
| Configurar offsets/velocidades | **2. Parcial** | `CharacterAnimationSettings` (fps, offsetX/Y, loop). `CharacterPlacement` + `psychCameraX`. |
| Administrar variaciones | **2. Parcial** | `CharacterVariation` + `variations` list en `CharacterDraft`. `isDefault`, sheets/iconos propios. |
| Editar frames individuales | **2. Parcial** | `useIndividualFrames` + `animationFrames` map + `pickCharacterFrames`. **Límite 100 frames, atlas 4096x4096.** |
| Previsualizar en tiempo real | **4. No implementado** | `CharacterPreview` solo muestra frame actual. **No playback animado.** |
| Guardar/recuperar proyecto | **1. Completa** | Parte de `ModConfig.characterLibrary` + `ProjectStore`/`PortableProject`. |
| Exportar ZIP personaje | **2. Parcial** | `CharacterBuilder.kt` + `CharacterToolScreen` bottom bar. Genera atlas, JSON Psych/V-Slice, icono. **No validado en motor real.** |

---

## 9. Stage Studio

| Funcionalidad | Estado | Evidencia |
|---------------|--------|-----------|
| Crear escenario | **4. Placeholder** | `StageToolScreen` solo muestra texto "En desarrollo". |
| Importar fondos/sprites | **4. No implementado** | `StageDraft.backgroundImage` + `sprites` list existen en modelo, sin UI. |
| Añadir/eliminar/organizar elementos | **4. No implementado** | `StageSprite` modelo completo, sin editor. |
| Cambiar posiciones/escalas/capas | **4. No implementado** | `StageSprite` tiene x, y, scale, rotation, layer, zIndex — sin UI. |
| Configurar cámara/visuales | **3. Modelo sin UI** | `StageDraft.cameraPosition`, `vSliceLayout`/`psychLayout`, `zoneColors`, `psychCameraSpeed`. |
| Previsualizar escenario | **4. No implementado** | `SimpleStagePanel` en `WizardSongsStep` es placeholder mínimo. |
| Guardar/reabrir | **1. Modelo listo** | `StageDraft` en `ModConfig.stageDraft` + `ProjectStore`/`PortableProject`. |
| Exportar Psych/V-Slice | **4. No implementado** | `StageBuilder` existe pero `hasUnsupportedFeatures()` devuelve true para casi todo. |

---

## 10. Sistema de audio

| Funcionalidad | Estado | Evidencia |
|---------------|--------|-----------|
| Importación instrumental | **1. Completa** | `WizardAudioStep` + `pickActiveSongAudio` → `PickedAudio` (uri, path, durationMs). |
| Voces separadas jugador/oponente | **1. Completa** | `AudioSlot.VOICES_PLAYER` / `VOICES_OPPONENT` + mezcla automática para Psych. |
| Reproducción/pausa | **2. Parcial** | `MediaPlayer` en `WizardViewModel` + `AudioPreview.kt`. **Latencia, foco audio, deriva no verificados en dispositivo.** |
| Sincronización pistas | **5. Limitación conocida** | `ChartAudioMix` (volumen, offset, mute) — **ajustes de editor, no transforman audio exportado**. `MediaPlayer` deriva corregida heurísticamente. **No sample-accurate.** |
| Búsqueda temporal (seek) | **2. Parcial** | `LiveChartEditor` timeline + `MediaPlayer.seekTo`. **Precisión depende de Android/dispositivo.** |
| Forma de onda | **1. Completa** | `WaveformDecoder` (streaming MediaMetadataRetriever/FFmpeg), `WaveformPeaks` (downsampling), `ChartWaveform` UI. |
| Alineación audio-chart | **5. Limitación conocida** | Instrumental = reloj maestro. Voces alineadas con corrección deriva. **Offset de toque (`liveCorrectionMs`) independiente del offset canción.** Precisión depende de dispositivo; requiere auriculares cable + prueba en juego. |
| Ajuste de offset | **1. Completa** | `SongConfig.offsetMs` + `ChartDraft.offsetMs` + `liveCorrectionMs`. |
| Conservación archivos originales | **1. Completa** | `PickedAudio.path` apunta a cache; `ImportedModResource` conserva originales en proyecto. |

**Limitaciones de precisión Android/dispositivo declaradas:**
- `MediaPlayer` no ofrece sincronización a nivel de muestras
- Latencia de audio variable por dispositivo (requiere prueba con auriculares cable)
- Corrección de deriva heurística, no garantizada
- `MediaCodec`/`AudioTrack` no usados para playback de baja latencia

---

## 11. Historial Undo/Redo

| Historial | Estado | Evidencia |
|-----------|--------|-----------|
| Grid (`ChartPersistentHistory`) | **2. Parcial** | Por `songFolder`/`difficulty` en `ModConfig.chartHistories`. **No por motor.** Huellas SHA-256, plantillas, límites (50 undo/redo, 40k notas totales). |
| Grabación en vivo | **2. Parcial** | `restoreChartHistory` en `WizardViewModel.kt:586-589` usa `currentGridHistory()`. **Solo deshace última acción de grid, no grabación completa.** |
| Eventos V-Slice | **1. Completo** | `vSliceEventsUndo/Redo` + `vSliceTempoUndo/Redo` en `SongConfig` (stack 10). Validados en `ProjectConfigValidator.kt:54-75`. |
| Eventos Psych | **4. No implementado** | No hay undo/redo para `psychEventsJson` / `psychLayout`. |
| Secciones Psych | **4. No implementado** | No hay historial para `psychLayout`. |
| Persistencia entre sesiones | **1. Completa** | `chartHistories` serializado en `project.json` / `.fnfproject`. |
| Transaccionalidad | **2. Parcial** | `ChartPersistentHistory` atómico por edición, pero **autoguardado no transaccional**. |

---

## 12. Gestión de proyectos

| Funcionalidad | Estado | Evidencia |
|---------------|--------|-----------|
| Múltiples proyectos | **1. Completa** | `ProjectStore.list()`, `activeId()`, `setActive()`, `newId()` (UUID). Inicio muestra lista. |
| Crear/abrir/eliminar | **1. Completa** | `HomeScreen` + `ProjectStore`. |
| Duplicar proyecto | **2. Parcial** | `PortableProject.export` + `prepare` + `materialize` → nuevo UUID. **No expuesto en UI directamente.** |
| Recuperación proyecto incompatible | **1. Completa** | `ProjectStore.list()` marca `problem`, `HomeScreen` permite abrir otro/crear nuevo/importar portable. |
| Backup automático | **2. Parcial** | `SafeFile` escribe `.tmp` + renombra, mantiene `.bak`. **Solo `project.json`, no recursos.** |
| Recursos referenciados | **1. Completa** | `ProjectAssetReferences.kt` — ubicaciones tipadas, validación traversal/symlink/size. |

---

## 13. Referencias multimedia y validaciones

| Funcionalidad | Estado | Evidencia |
|---------------|--------|-----------|
| `ProjectAssetReferences.visitProjectAssets` | **1. Completa** | Recorrido tipado: icon, songs[audio], characterLibrary[draft[sheet,icon,atlas,variations,animationFrames]], stageDraft[backgroundImage,sprites], importedModResources[asset]. **Nunca desciende a campos desconocidos.** |
| Validación rutas (traversal, symlinks) | **1. Completa** | `PortableProject.validName`, `under`, `ProjectStore.permittedAssetRoot`, `ProjectConfigValidator` checks. |
| Conservación JSON desconocido | **1. Completa** | `ChartDraft.psychSongExtras`, `IndependentVariant.unknownMetadataJson`, `SongConfig.vSliceOrigin` campos originales. `ProjectConfigValidator` rechaza guardado si lista con campos desconocidos cambia. |
| `chartSchemaVersion` guard | **1. Completa** | `ChartSchema.kt` — rechaza versión desconocida y `independentCharts` antes de deserializar. |
| Importación staging + publicación transaccional | **1. Completa** | `ProjectStore.newImportStage()`, `discardImportStage()`, `publishImport()` / `publishModImport()` con validación antes de rename final. |
| Límites (archivos, tamaño, profundidad JSON) | **1. Completa** | `PortableProject`: 4096 archivos, 1 GiB total, 256 MiB/asset, 8 MiB JSON, profundidad 64. |

---

## 14. Pruebas automatizadas

| Suite | Estado | Evidencia |
|-------|--------|-----------|
| Unit tests (`:app:testDebugUnitTest`) | **1. Completa** | ~50 tests en `app/src/test/` cubriendo: AI, appearance, media, mod (builders, events, metadata, zip), model (charts, character, history, independent, recording, rename, stage, waveform, wizard), modimport (inspector, psych/vslice readers, import cycles), project (character library, schema, portable, references, store), ui (mod zip confirmation). |
| Instrumented tests | **4. No implementadas** | No hay `androidTest` directory. |
| Integración GitHub Actions | **1. Completa** | `.github/workflows/build-apk.yml` — `gradle :app:testDebugUnitTest :app:assembleDebug`, artifact APK, annotate build log on failure. |

---

## 15. Compatibilidad Android

| Aspecto | Estado | Evidencia |
|---------|--------|-----------|
| `minSdk` 24, `targetSdk` 34 | **1. Completa** | `app/build.gradle.kts:13-14` |
| ARM only (arm64-v8a, armeabi-v7a) | **1. Completa** | `ndk.abiFilters` — no x86 (emuladores). |
| Compose Material3 | **1. Completa** | BOM 2024.10.01, UI responsiva (ancho/estrecho, landscape). |
| Permisos (storage, audio) | **2. Parcial** | Usa `ActivityResultContracts.OpenDocument` / `CreateDocument` (SAF) — **no `READ_EXTERNAL_STORAGE` legacy**. Audio recording no implementado (solo playback). |
| Memoria (imágenes grandes, waveform) | **2. Parcial** | `WaveformDecoder` streaming, límites atlas 4096x4096, `PickedImage` en cache. **No tests de presión memoria.** |
| Operaciones largas (FFmpeg, ZIP) | **2. Parcial** | Corrutinas `Dispatchers.IO`, `vm.busy` + `CircularProgressIndicator`. **Sin timeout/cancelación visible al usuario.** |
| Estados carga/error | **1. Completa** | `vm.status`, `vm.error`, `vm.busy`, `MessageCard`, `ToolStatus`. |
| Recuperación tras cierre/app kill | **2. Parcial** | `ProjectStore` + `active.txt` + `SafeFile.backup`. **Autoguardado no transaccional → posible pérdida último cambio.** |
| Orientación/dimensiones | **1. Completa** | `BoxWithConstraints` en `CharacterWorkspace`, `Modifier.horizontalScroll` en chips, previews adaptables. |

---

## 16. BOTH (Psych + V-Slice) — Estado real

| Requisito | Estado | Evidencia |
|-----------|--------|-----------|
| Charts independientes por motor | **4. No implementado** | `ModConfig.charts: Map<songFolder, Map<difficulty, ChartDraft>>` — **una sola entrada por canción/dificultad**. `IndependentCharts.kt` existe pero **no integrado**. |
| Metadatos separados por motor | **4. No implementado** | `SongConfig` mezcla campos Psych (`stagePsych`, `player`, `opponent`) y V-Slice (`stageVSlice`, `vSliceOrigin`, `vSliceEventsJson`, `vSliceTempoJson`). |
| Dificultades independientes | **4. No implementado** | `SongConfig.difficulties: Set<String>` — **compartido**. `IndependentVariant.difficulties` + `uneditableDifficulties` existen en modelo experimental. |
| Historiales separados | **4. No implementado** | `chartHistories: Map<songFolder, Map<difficulty, ChartPersistentHistory>>` — **compartido**. |
| Eventos Psych / V-Slice separados | **4. No implementado** | `psychEventsJson`/`psychLayout` en `ChartDraft` (compartido). `vSliceEventsJson`/`vSliceTempoJson` en `SongConfig` (compartido). |
| Importación ZIP BOTH transaccional | **4. No implementado** | `both-zip-audit.md` — bloqueada hasta resolver modelo independiente. |
| Exportación BOTH segura | **5. Bloqueada** | `ModBuilder.kt:70-73` — `require(engine != BOTH || importedModResources.isEmpty())`. Exporta ambas estructuras pero **charts compartidos = pérdida de datos**. |
| Identidad `(songId, engine, difficulty)` | **3. Solo en modelo experimental** | `IndependentChartKey` en `IndependentCharts.kt:6-10`. **No usado en `ModConfig`, `ProjectStore`, `PortableProject`, validación, UI.** |
| Migración legacy segura | **4. No implementada** | `ChartSchema.kt` rechaza `independentCharts`. `independent-charts-architecture.md` describe plan: depósito `legacy` sin asignación automática. **No codificado.** |

---

## 17. Prioridades críticas (según specs)

### A. Sistema de charts independientes (PRIORIDAD MÁXIMA)
- **Falta:** Integración completa de `IndependentCharts.kt` en `ModConfig`, `ProjectStore`, `PortableProject`, `ProjectConfigValidator`, autoguardado, `.fnfproject`.
- **Falta:** Migración transaccional legacy → independiente (depósito `legacy` para charts ambiguos).
- **Falta:** UI para seleccionar motor al editar chart (separado de snap reference).
- **Falta:** Validación de colisiones rutas/historiales al renombrar/duplicar/borrar variante.

### B. Character Studio
- **Falta:** Previsualización animada en tiempo real (play/pause/loop, fps control).
- **Falta:** Validación frames rotados en atlas Sparrow (actualmente rechazados silenciosamente).
- **Falta:** Exportación verificada por motor (Psych vs V-Slice JSON differences).
- **Falta:** Controles decorativos sin acción → eliminar o implementar.

### C. Stage Studio
- **Falta:** **Todo el editor** — `StageToolScreen` es placeholder. Modelo `StageDraft` completo sin UI.
- **Falta:** Importar fondos/sprites, drag&drop, capas, cámara, animaciones sprites.
- **Falta:** Exportación Psych/V-Slice (`StageBuilder` bloqueado por `hasUnsupportedFeatures()`).

### D. Chart Editor y herramientas musicales
- **Falta:** Piano Roll / Grid editor completo (selección, drag, edición notas existentes, holds, tipos).
- **Falta:** Secciones Psych editor integrado (`PsychSectionEditor` existe pero desconectado).
- **Falta:** Eventos Psych/V-Slice editor persistente por motor.
- **Falta:** Mapas de tempo editor por variante.
- **Falta:** Undo/Redo persistente **por motor** (no compartido).
- **Falta:** Separar selección motor de edición de referencia snap (actualmente `psychSnapReference` en `SongConfig` compartido).

### E. Sistema de audio
- **Completo** en funcionalidad básica. **Limitaciones declaradas:** no sample-accurate, latencia variable, `MediaPlayer` deriva heurística. Documentar claramente.

### F. Importación ZIP BOTH
- **Bloqueada** hasta completar A (modelo independiente). Requiere: charts independientes, metadatos separados, recursos tipados, conflictos detectados, transaccional, sin proyecto parcial en fallo.

### G. Exportación mods
- **Psych 0.7.3 / psych_v1 / V-Slice:** Completos para proyectos single-engine.
- **BOTH:** Inseguro — exporta charts compartidos. Requiere A completado.
- **Regla:** `.fnfproject` = editable/backup; `.zip` = instalable. Distinguir siempre.

---

## 18. Deuda técnica y riesgos

| Área | Riesgo | Mitigación |
|------|--------|------------|
| `WizardViewModel` (3216 líneas) | God object, acoplamiento extremo, difícil testear | Extraer repositorios: `ChartRepository(engine)`, `AudioRepository`, `ProjectRepository`, `CharacterRepository`, `StageRepository`. |
| Autoguardado no transaccional | Pérdida de datos en crash/kill | Usar `ProjectStore.SafeFile` pattern para config + recursos atómicos. |
| Recursos en `cacheDir` | Android los borra bajo presión memoria | Copiar a `filesDir/assets/` en proyecto persistido; `PortableProject` ya lo hace. |
| `MediaPlayer` sync | No fiable para chartear preciso | Investigar `AudioTrack` + `MediaCodec` decoding para playback bajo control. |
| Sin tests instrumentados | No verifican UI, audio, device behavior | Añadir `androidTest` con Robolectric/Compose UI tests + device farm. |
| `StageDraft` sin editor | Funcionalidad fantasma | Eliminar `SimpleStagePanel` placeholder o implementar Stage Studio completo. |
| BOTH export bloqueado | Usuarios esperan funcionalidad | Documentar claramente: "BOTH exporta estructuras pero charts compartidos — usar single-engine para mods serios". |

---

## 19. Plan de implementación por etapas (alineado con specs)

### Etapa 1: Auditoría y correcciones estructurales ✅ **ESTA AUDITORÍA**
- [x] Inventario completo
- [ ] Refactor `WizardViewModel` → repositorios por dominio (iniciar)
- [ ] Hacer autoguardado transaccional (`SafeFile` pattern)

### Etapa 2: Modelo independiente y migración
- [ ] Integrar `IndependentChartCollection` en `ModConfig` (reemplazar `charts`/`chartHistories`/`songs` metadata)
- [ ] Actualizar `ProjectConfigValidator` para esquema v2 (`chartSchemaVersion = 2`, `independentCharts` requerido)
- [ ] Implementar migración legacy → independiente (depósito `legacy` para ambiguos)
- [ ] Actualizar `PortableProject` export/import/materialize para nuevo esquema
- [ ] Tests: round-trip legacy → v2 → legacy, proyectos ambiguos, version unknown rejection

### Etapa 3: Persistencia y proyectos portables
- [ ] Autoguardado transaccional completo (config + recursos)
- [ ] UI gestión proyectos (crear, duplicar, eliminar, importar/exportar `.fnfproject`)
- [ ] Recuperación robusta tras crash/kill

### Etapa 4: Integración editor Psych y V-Slice
- [ ] UI selector motor al editar chart (separado de snap reference)
- [ ] Adaptar `LiveChartEditor` / `TouchChartGridEditor` para variante activa
- [ ] Historiales por `(songId, engine, difficulty)`
- [ ] Secciones Psych / Eventos Psych / Eventos V-Slice / Tempo V-Slice por variante
- [ ] Tests: editar Psych no afecta V-Slice, undo/redo aislados, reapertura preserva ambos

### Etapa 5: Character Studio
- [ ] Previsualización animada tiempo real (play/pause/loop, scrub)
- [ ] Validación atlas rotado (rechazar con error claro)
- [ ] Exportación verificada Psych/V-Slice (test round-trip)
- [ ] Eliminar controles sin acción

### Etapa 6: Stage Studio
- [ ] Implementar `StageToolScreen` completo (fondo, sprites, capas, cámara, animaciones)
- [ ] Integrar con `StageDraft` model
- [ ] Exportación Psych/V-Slice funcional

### Etapa 7: Audio y herramientas musicales
- [ ] Documentar limitaciones precisión Android/dispositivo
- [ ] Investigar `AudioTrack` + `MediaCodec` para playback bajo control (opcional, fase posterior)
- [ ] Waveform sync improvements

### Etapa 8: Importación BOTH
- [ ] Solo después de Etapas 1-4 completas y verificadas
- [ ] Transaccional, staging, validación conflictos, rollback en fallo

### Etapa 9: Exportación completa
- [ ] Psych 0.7.3 / psych_v1 / V-Slice / BOTH con charts independientes
- [ ] Errores específicos cuando exportación no segura (dato que impide continuar)

### Etapa 10: Revisión integral Android y pruebas
- [ ] Tests instrumentados (UI, audio, device)
- [ ] Performance, memoria, orientación, keyboard, touch targets
- [ ] Limpieza: eliminar placeholders, código muerto, `TODO`s

---

## 20. Verificación de compilación (GitHub Actions)

> **IMPORTANTE:** No se puede compilar localmente. Toda verificación debe hacerse vía GitHub Actions.

```bash
# Para verificar tras cada etapa:
git push origin main
# Luego revisar: https://github.com/manuscooth01/FNF-Mod-Maker/actions/workflows/build-apk.yml
# Verificar: :app:testDebugUnitTest PASS, :app:assembleDebug PASS, APK artifact subido
```

---

## 21. Conclusión

**La aplicación tiene una base sólida y profesional** — arquitectura limpia, validaciones robustas, persistencia transaccional, tests unitarios extensivos, protección de datos desconocidos, y separación clara entre proyecto editable (`.fnfproject`) e instalable (`.zip`).

**El bloqueo crítico es el modelo de charts compartido** que impide BOTH real. La estructura experimental `IndependentCharts.kt` está bien diseñada pero **completamente desconectada** del flujo real. Completar la **Etapa 2 (modelo independiente + migración)** es el prerequisito indispensable para todo lo demás.

**Recomendación:** Iniciar refactor de `WizardViewModel` en paralelo (extraer `ChartRepository` por motor) para no arrastrar god-object mientras se integra el modelo independiente.

---

*Generado en SHA 8fca18ce0e27a22114d15fd27bcd8fb86f6e8c05 (origin/main)*