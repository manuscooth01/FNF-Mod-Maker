# Auditoría estática inicial de main — 62f28fb

**Alcance y límites:** revisión estática priorizada del código real de `62f28fb3ec95c44b91353d3300398d9b3cfcf170`, no auditoría integral cerrada ni validación de recorridos completos en dispositivo. No se ejecutó Gradle ni se compilaron pruebas localmente. Los riesgos indicados son inferencias del código salvo que se indique una prueba. No se extrapolan resultados de la antigua rama a main.

## Mapa por subsistema

- Arquitectura: Android/Kotlin/Compose en un módulo `app`; `WizardViewModel` centraliza navegación, proyectos, editores y exportación. Modelo y persistencia en `model` y `project`; generadores por motor en `mod`; importadores en `modimport`.
- Proyecto: `project/ProjectStore.kt` usa `SafeFile`, stage de importación y copia de assets; `WizardViewModel.kt` coordina guardado y autoguardado.
- Canciones/charts/eventos: `IndependentCharts.kt`, `ChartRepositoryImpl.kt`, `LiveChartEditor.kt`, lectores Psych/V-Slice y generadores JSON. Coexisten mapas legacy y modelo v2.
- Character Studio: `CharacterWorkspace.kt`, `CharacterPreview.kt`, `CharacterBuilder.kt` y ZIP/JSON importadores. Existe una vista real con frames y controles, lo que no demuestra seguridad integral.
- Stage: `StagePositionStudio.kt` implementa una vista de posición en el asistente; `ToolScreens.kt:319` muestra por separado una herramienta independiente todavía en desarrollo.
- Assets: `third_party/material-icons` contiene iconos con licencia; no se ha constatado que el proyecto incluya todos los assets originales FNF. Falta comprobar las licencias, rutas y recursos en dispositivos y exportaciones.
- IA: `AiClient`, `AiProtocol`, `ProjectProposal` y `WizardViewModel` conectan propuestas con configuración y guardado; los límites de confianza y concurrencia requieren pruebas adicionales.
- Cámara/controles/preview: `CharacterPreview.kt:74–116` resuelve fuentes, limita 100 frames y decodifica regiones; `StagePositionStudio` usa Canvas. Hay que medir memoria y latencia en dispositivos físicos, no inferir rendimiento del código.
- Navegación/UI: `ToolScreens.kt`, `WizardScreens.kt`, `CharacterWorkspace.kt` contienen controles y callbacks; los recorridos completos y accesibilidad no han sido verificados.

## Hallazgos y orden recomendado

### P0-1 — ID v2 contradictorio se normaliza silenciosamente (defectuoso)
- Ubicación: `model/IndependentCharts.kt:129–138` y `project/ChartSchema.kt:57–75`.
- Actual: el adaptador Gson sustituye el `id` interior diferente por la clave; el parser de esquema construye el ID desde la clave sin comprobar el valor interior. Un documento con identidades contradictorias puede aceptarse con una identidad cambiada.
- Esperado: rechazar explícitamente una contradicción conservando el documento y permitiendo compatibilidad con entradas válidas. Causa: ambos lectores consideran autoritativa la clave sin validar el valor redundante. Afecta carga, validación y edición v2.
- Reparación inicial: validar el ID redundante en ambas rutas, sin modificar documentos ni generar IDs. Pruebas: lectura válida, ID ausente si el esquema anterior lo admite, ID contradictorio y no string, documento original intacto y rechazo por ambas rutas. Rechazar no soluciona otras rutas v2.

### P0-2 — edición v2 y exportación no son independientes de extremo a extremo (incompleto/defectuoso)
- `ui/WizardViewModel.kt:375–389, 1741–1747`: se usa `songFolder` como ID y `saveChartDraft` fuerza `ChartEngine.PSYCH`. `mod/JsonBuilders.kt:82,183–190` lee `config.getChart` legacy; no se demuestra exportación v2 por variante.
- Esperado: identidad estable `(songId, engine, difficulty)` en todas las rutas y exportación específica de motor. Causa: el modelo v2 no está conectado homogéneamente al editor y exportador. Afecta editor, eventos, historial, persistencia y ZIP. Reparar mediante integración de extremo a extremo o bloqueo seguro; no atribuir datos ambiguos ni convertir con pérdida. Pruebas: dos motores/misma dificultad, IDs distintos del nombre visible, undo/redo, exportaciones separadas y originales desconocidos.

### P0-3 — migración legacy con identidad/atribución ambigua (defectuoso)
- `project/LegacyChartMigration.kt:50–64,101–115,162,203`: usa `songFolder` como ID, combina colisiones y elimina mapas legacy al aplicar; con BOTH ambas funciones de evidencia pueden leer el mismo mapa. Puede atribuir datos ambiguos o combinar identidades.
- Esperado: diagnóstico sin escritura, preservar original y rechazar ambigüedad/colisiones hasta una migración demostrablemente íntegra. Afecta apertura, migración y referencia de charts. Pruebas: BOTH sin evidencia o con evidencia doble, carpetas colisionantes y comparación byte a byte.

### P0-4 — persistencia y recuperación sin coordinación global (riesgo de pérdida)
- `project/ProjectStore.kt:15–41,62–69,88–105`; `ui/WizardViewModel.kt:1846,1926–1981`: temporales `.tmp`/`.bak` compartidos, recuperación mediante rename, y mutex por ViewModel. Dos instancias o fallos interrumpidos pueden interferir; `clearAbandonedImports` no identifica stages activos de otra instancia.
- Esperado: exclusión y control de revisión coordinados por proyecto; recuperación que no sobrescriba datos desconocidos. Afecta autosave, importación y assets. Pruebas deterministas de dos instancias, fases interrumpidas y recuperación, sin asumir atomicidad de `renameTo` o SAF.

### P0-5 — escritura directa a destino y publicación ZIP (riesgo de sobrescritura/parciales)
- `ui/WizardViewModel.kt:2807–2824,3428–3461,3483–3495`: importación de personajes crea archivos persistentes; exportación abre el URI directamente sin demostrar destino nuevo, staging verificado ni limpieza de parciales. Otras rutas SAF aparecen en las líneas 1455 y 2053–2054.
- Esperado: preparación temporal aislada y publicación/exportación con garantías verificadas o rechazo específico sin efectos. Afecta Character Studio, proyecto y destinos externos. Pruebas: colisión, fallos parciales, rollback, preservación del borrador y proveedor real. No asumir que cualquier proveedor SAF garantiza exclusión.

### P1-1 — Character Studio y AI sin sesión aislada de proyecto (incompleto)
- `ui/WizardViewModel.kt:229–256,2807` aplica propuestas a `config` y fuerza autosave; ZIP publica en biblioteca, no existe `CharacterDraftSession` en main. Esperado: borrador separado, recursos y Undo/Redo por sesión, publicación separada. Pruebas: dos personajes, IA y manual, PNG/XML/icono, cancelar y comparar todo el proyecto.

### P1-2 — herramienta Stage independiente ausente (ausente; asistente parcial existente)
- `ui/ToolScreens.kt:319–365` muestra texto «En desarrollo» en lugar de editor; `ui/StagePositionStudio.kt:24–76` sí implementa parte de la calibración en otro recorrido. Esperado: conectar un recorrido completo sin sustituir componentes; probar generación y vista por motor. No priorizar antes de P0.

### P2 — experiencia, activos y rendimiento (no verificado)
- `CharacterPreview.kt:74–133` y `StagePositionStudio.kt`: existen vistas y límites, pero no evidencia de paridad visual FNF ni de rendimiento real. Validar assets/licencias, controles, cámara, rotación, ciclos de vida y memoria en dispositivos; contrastar vista con exportación. No marcar como defectuosos ni completos sin prueba.

### P3 — mantenimiento y presentación (pendiente de inspección)
- Revisar duplicación de navegación, mensajes y documentación una vez protegidos datos; no hay evidencia suficiente para fijar cambios concretos ahora.

## Cobertura pendiente

La auditoría integral requiere revisar exhaustivamente llamadas y pruebas de todos los módulos, proveedor SAF real, dispositivos físicos, accesibilidad, ciclos de vida y fallos de I/O. Este documento no afirma esa cobertura. Primer cambio P0 propuesto: P0-1, acotado a rechazo de identidad contradictoria y sus pruebas; no corrige migración, guardado, editor o exportación por sí solo.
