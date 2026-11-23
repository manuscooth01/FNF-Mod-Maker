# Estado de arquitectura y entregas (noviembre de 2026)

El documento de reconstrucción describe el commit `a318679`; la rama actual ha evolucionado
hasta `eea7d8b`. El árbol observado coincide con ese commit. La última ejecución base de Actions
fue [45262204697](https://github.com/manuscooth01/FNF-Mod-Maker/actions/runs/45262204697),
con pruebas y APK exitosos. No se ejecutaron pruebas en dispositivo.

## Inventario real

- `ModConfig` reúne canciones, un personaje, un escenario sencillo, charts por dificultad y scripts.
  `WizardViewModel` lo comparte entre el asistente, personaje, IA y herramientas; tiene demasiadas
  responsabilidades y aún no hay repositorios/adaptadores de motor separados.
- El asistente edita canción/audio/personaje, abre grabación y cuadrícula de ocho carriles. Esta
  permite añadir, mover, borrar y redimensionar notas, con undo de sesión. No hay redo persistente,
  selección múltiple funcional, waveform, cambios de BPM ni exportación de eventos/secciones.
- El constructor usa duración real de holds y charts por dificultad; hay pruebas de serialización.
  No se ha validado la compatibilidad ejecutando ambas versiones concretas de los motores.
- Personaje: PNG individuales, cuadrícula y atlas Sparrow, animaciones y offsets; variaciones aún
  requieren revisión. Escenario: únicamente fondo estático y zoom, no editor de múltiples objetos.
- Vista conjunta y práctica táctil son aproximadas; no son motores. IA produce propuestas tipadas y
  revisables, no ejecuta código ni modifica automáticamente sin aprobación.
- Importador de mods y stage es un placeholder inseguro y no está habilitado. No usarlo hasta
  implementar extracción segura, análisis real y preservación de archivos desconocidos.
- El autoguardado existente escribe JSON en `filesDir`, pero los recursos escogidos se copian a
  `cacheDir/inputs`; Android puede eliminarlos. La escritura del JSON no es atómica y los errores
  se silencian. Solo hay un borrador: no existe gestión de múltiples proyectos.

## Primeros commits previstos

1. Documentar este estado sin cambiar la compilación.
2. Crear persistencia versionada y transaccional, migrar el borrador y sus recursos disponibles,
   añadir gestión local de proyectos y pruebas. Informar de recursos ausentes y errores de guardado.
3. Continuar Chart Studio con historial/operaciones adicionales en cambios posteriores, sin
   anunciar eventos ni round-trips que los adaptadores aún no soportan.

No se distribuyen assets oficiales de FNF. No se incluirán motores, cambios de esquema no
verificados ni importación ficticia como si fueran funcionalidades terminadas.

## Avance de la primera entrega

Se introdujo `project/ProjectStore.kt`: documentos internos versión 1, directorios de recursos,
reemplazo temporal con copia previa, migración no destructiva del borrador existente y manejo de
varios proyectos en Inicio. Hay pruebas unitarias para recuperación, validación de IDs/versión y
copiado de recursos. Los ZIP de mods siguen sin importarse y no hay copia de proyecto portable.
La cuadrícula incorpora rehacer de sesión y snap de 1/4; el historial sigue sin persistir entre
sesiones y no equivale al Chart Studio completo del plan.
