# Estado de arquitectura y entregas (noviembre de 2026)

El documento de reconstrucción describe el commit `a318679`; la rama actual ha evolucionado
hasta `eea7d8b`. El árbol observado coincide con ese commit. La última ejecución base de Actions
fue [45262204697](https://github.com/manuscooth01/FNF-Mod-Maker/actions/runs/45262204697),
con pruebas y APK exitosos. No se ejecutaron pruebas en dispositivo.

## Inventario inicial (antes de Project Studio y Chart Studio)

- `ModConfig` reúne canciones, un personaje, un escenario sencillo, charts por dificultad y scripts.
  `WizardViewModel` lo comparte entre el asistente, personaje, IA y herramientas; tiene demasiadas
  responsabilidades y aún no hay repositorios/adaptadores de motor separados.
- El asistente editaba canción/audio/personaje y abría grabación y una cuadrícula de ocho carriles.
  Las novedades posteriores de edición múltiple y onda se describen al final de este documento.
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
copiado de recursos. Los ZIP de mods siguen sin importarse. La fase posterior añade copia portable de proyectos propios.
La cuadrícula incorpora rehacer de sesión y snap de 1/4; el historial sigue sin persistir entre
sesiones y no equivale al Chart Studio completo del plan.


## Project Studio 2.0

La copia `.fnfproject` es un paquete privado versionado, no un mod instalable. Empaqueta datos y
recursos con SHA-256, valida entradas y límites, prepara en una carpeta oculta y publica mediante
rename con un UUID nuevo únicamente después de confirmar. El selector de Android gestiona el archivo
externo. Un hash detecta corrupción, no certifica que los recursos o scripts sean confiables.
Las pruebas cubren ida y vuelta, duplicación, rutas peligrosas, entradas duplicadas, tamaño excesivo,
recursos dañados/ausentes y limpieza de importaciones interrumpidas. Falta verificación del flujo SAF
y de la restauración entre teléfonos físicos.

## Avance de Chart Studio 2.0 (histórico)

La cuadrícula incorpora operaciones atómicas de grupos, tiempo exacto, edición durante escucha
y onda general de pista mediante decodificación por streaming. El historial sigue siendo de sesión.
Los charts activos con eventos, secciones personalizadas, colas independientes o notas inválidas
se bloquean en la validación y el serializador: los datos siguen en el proyecto editable. La onda
y los gestos requieren prueba en Android; no se han validado mods exportados en los motores.
Cambios de tempo, adaptadores reales de eventos, tipos de nota y voces sincronizadas siguen pendientes.

## Avance de Chart Studio 2.1

El proyecto guarda historial por canción y dificultad con deltas acotados en el mismo JSON que
el chart; una huella impide aplicar una acción a datos cambiados externamente. Hay respaldo y
recuperación del proyecto, pero un cierre forzado antes de terminar la escritura puede perder el
último cambio pendiente. La copia `.fnfproject` incluye ambos. El mod `.zip` no incluye historial.

La cuadrícula muestra ondas simultáneas mediante decodificación secuencial, con el mismo eje y
offsets de previsualización. La escucha usa la instrumental como reloj y alinea pistas de voz con
MediaPlayer y corrección de deriva; esto NO ofrece sincronización a nivel de muestras. El volumen,
silencio y offsets son ajustes del editor, no transforman el audio exportado. El cálculo y la
serialización tienen pruebas unitarias, pero MediaCodec, MediaPlayer, latencia, foco de audio y
sincronización aún necesitan pruebas instrumentadas o físicas en varios dispositivos Android.

## JSON de personajes y copias ZIP

El editor compartido permite revisar y aplicar parcialmente JSON Psych/V-Slice; el original se
conserva en el proyecto, no en el ZIP del mod generado. Los índices de frames, AnimateAtlas y
otros campos no representados no se convierten. Los recursos gráficos se seleccionan aparte.
La biblioteca de ZIP conserva archivos intactos y permite exportar copias, sin extracción ni
ejecución; no convierte mods a proyectos editables ni verifica integridad de cada entrada. Se
retiró el placeholder inseguro de importación de mod y stage. Falta probar los selectores y los
resultados en Android y en los motores.
