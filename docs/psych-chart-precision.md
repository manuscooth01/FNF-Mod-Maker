# Chart Studio: tiempo y compatibilidad Psych

- Psych 0.7.3 almacena BPM como `Float`; su `Conductor.mapBPMChanges` aplica un cambio al inicio de su sección y suma `Math.round(sectionBeats * 4)` pasos. Tanto 0.7.3 como el código actual usan cuatro beats si falta `sectionBeats`, no `lengthInSteps`. La propiedad heredada se conserva, pero no gobierna el reloj. Si se cambia la duración de una sección que tenía `lengthInSteps`, exigimos una equivalencia exacta en pasos antes de actualizarla; los valores históricos sin editar se conservan, incluso si discrepan.
- En BOTH, la cuadrícula, la grabación ajustada y sus sostenidos utilizan el mapa V-Slice (también los beats/compases de la línea temporal). El BPM de canción y las secciones Psych forman otro mapa, editado y exportado por separado. La grabación libre usa la posición absoluta. En Psych puro, el ajuste utiliza las secciones Psych; la línea muestra beats, sin inventar un compás musical.
- Los tiempos de las notas y duraciones del editor siguen siendo milisegundos enteros. Los tiempos fraccionarios importados se rechazan en vez de truncarse. Los BPM se calculan en `Double`; el BPM inicial, BPM de sección y duración de sección de entrada se rechazan si su valor decimal no puede recuperarse de la representación `Double` utilizada. El editor limita el BPM inicial a 1–400.
- Los tipos textuales se conservan. Psych 0.7.3 incluye por defecto los índices 0–5: vacío, `Alt Animation`, `Hey!`, `Hurt Note`, `GF Sing` y `No Animation`. Sin embargo, el propio código invita a los mods a ampliar o cambiar esa tabla. Actualmente todos los tipos numéricos, incluido el cero, se rechazan porque **no** existe todavía una conversión reversible que preserve su representación original. Los charts originales se conservan sin convertir.
- Las verificaciones automatizadas no sustituyen una prueba dentro de ambas versiones del juego o en Android. La reimportación directa de un ZIP BOTH como proyecto no está implementada. La importación V-Slice admite BPM iniciales decimales dentro de 1–400 que puedan recuperarse de su representación `Double`; conserva el token original y bloquea una precisión no representable.

Fuentes oficiales consultadas:
- <https://github.com/ShadowMario/FNF-PsychEngine/blob/0.7.3/source/backend/Conductor.hx>
- <https://github.com/ShadowMario/FNF-PsychEngine/blob/0.7.3/source/backend/Song.hx>
- <https://github.com/ShadowMario/FNF-PsychEngine/blob/0.7.3/source/states/editors/ChartingState.hx>
- <https://github.com/ShadowMario/FNF-PsychEngine/blob/0.7.3/source/states/PlayState.hx>
- <https://github.com/ShadowMario/FNF-PsychEngine/blob/main/source/backend/Song.hx>

Fuente V-Slice: <https://github.com/FunkinCrew/Funkin/blob/main/source/funkin/data/song/SongData.hx> y <https://github.com/FunkinCrew/Funkin/blob/main/source/funkin/Conductor.hx>.
