# P0-2.1.4 — inventario de persistencia y ciclo de vida

Base: `6a7094a7879a5758c339b025a59eaad4b1e45536`. Inspección estática; los resultados de ejecución deben comprobarse por separado en Actions para el SHA publicado.

## SafeFile y proyectos

| Consumidor de producción | Acceso | Resultado de la inspección |
|---|---|---|
| `ProjectStore.projectFile` / `readRecord` | `SafeFile.read(recover=false)` | `load` y `list` inspeccionan candidatos previamente; la lectura no restaura el backup. `readRecord` es privado. |
| `ProjectStore.projectFile` / `save` | `SafeFile.write` | Comprueba candidatos y vuelve a compararlos bajo bloqueo por proyecto. La implementación genérica rota principal, backup y temporal: no constituye una transacción frente a escritores que ignoran el bloqueo. |
| `ProjectStore.activeFile` / `activeId` | `SafeFile.read` con recuperación por defecto | Puede restaurar `active.txt.bak` sobre `active.txt` si el principal no se interpreta como UUID. No opera sobre `project.json`; esta recuperación genérica no está protegida por el bloqueo de escritura `active.lock`. |
| `ProjectStore.activeFile` / `setActive` | `SafeFile.write` | Valida el proyecto y usa bloqueo de proyecto y `active.lock`. El conjunto de operaciones de cambio de proyecto del ViewModel no es una transacción entre procesos. |
| `publishImport` y `publishModImport` | `SafeFile.write` sobre `project.json` en staging | Cada publicación valida esquema y utiliza bloqueo para el ID final; el staging se publica con `renameTo`. El bloqueo es asesor y no garantiza atomicidad de proveedores o escritores externos. |
| `recoverBackup` | Copia temporal propia e instalación sin reemplazo mediante enlace | No llama `SafeFile.write`, no consume el backup; valida configuración y rutas conocidas de recursos, compara candidatos antes de instalar. |

`ModImportSession` usa `load`, `list`, `save` y `setActive`. `WizardViewModel` los usa para arranque, cambio, instantáneas y autoguardado, importación y exportación portable; la recuperación manual solo se invoca después de confirmación. La búsqueda de producción no encontró otros constructores de `SafeFile`. `SafeFile` genérico también tiene pruebas directas de su recuperación antigua; no se cambia indiscriminadamente en esta fase.

## Trabajos y observadores

- `WizardViewModel`: bucle de autoguardado de 30 s, trabajo diferido de 1 s, trabajos de guardado IO y mutex de instancia; `projectRevision` y `projectSession` restringen estos resultados a la sesión actual, incluso si se reabre el mismo ID.
- `playbackJob`: sondeo de `MediaPlayer` cada 16 ms; `livePolling`: sondeo de reproducción/voz cada 50 ms. El sondeo clásico y su callback de finalización comprueban identidad de reproductor y sesión; `livePolling` y sus callbacks comprueban identidad del reproductor y/o pertenencia a las voces. MediaPlayer tiene callbacks preparados, finalización, búsqueda y error. `closeLiveChart` y `releaseMediaPlayer` liberan los recursos en cambios exitosos y cierre.
- `FNFModMakerApp` registra un `LifecycleEventObserver` de ON_STOP para autoguardado; pertenece a la composición, no a un ID de proyecto. `LiveChartEditor` registra otro para pausar y cerrar; `AudioPreview` registra otro para detener su controlador. Se retiran al abandonar sus respectivas composiciones.
- `AudioPreviewController` dispone de corrutina de sondeo y callbacks de MediaPlayer; las vistas paran preview al cambiar entradas. `TouchChartGridEditor` abre y cierra una sesión de cuadrícula mediante `DisposableEffect`.
- `CharacterPreview` y `ModPreviewScreen` tienen animaciones con `LaunchedEffect`. Apariencia, biblioteca ZIP, importación, generación de audio/imagen, IA y construcción ejecutan otras corrutinas/callbacks; algunas usan `busy`, `aiBusy`, tokens de progreso o snapshots, pero no todas comparten un token de sesión de proyecto.

## Límites

El mutex del ViewModel no coordina otras instancias ni procesos. Los bloqueos de archivo solo coordinan escritores que respeten el protocolo; existen ventanas de carrera con terceros que lo ignoran. `active.txt`, staging y recursos externos a `project.json` tienen rutas adicionales y no debe afirmarse aislamiento integral sin pruebas específicas. Las pruebas JVM/Robolectric y el APK de Actions no sustituyen pruebas en dispositivo Android, incluidos API 24–25. No se declara P0-2 resuelto.
