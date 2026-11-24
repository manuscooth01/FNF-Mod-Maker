# Chart Studio: tiempo y compatibilidad Psych

- Psych 0.7.3 almacena BPM como `Float`; su `Conductor.mapBPMChanges` aplica un cambio al inicio de su sección y suma `Math.round(sectionBeats * 4)` pasos. Tanto 0.7.3 como el código actual usan cuatro beats si falta `sectionBeats`, no `lengthInSteps`. La propiedad heredada se conserva, pero no gobierna el reloj. Si se cambia la duración de una sección que tenía `lengthInSteps`, exigimos una equivalencia exacta en pasos antes de actualizarla; los valores históricos sin editar se conservan, incluso si discrepan.
- En BOTH, el usuario elige por canción el mapa de referencia para cuadrícula y grabación ajustada; V-Slice es la selección inicial. La preferencia se guarda en el proyecto. Los beats de la línea temporal usan ese mapa y los compases solo se muestran para V-Slice. El BPM de canción y las secciones Psych forman otro mapa, editado y exportado por separado. La grabación libre usa la posición absoluta. En Psych puro, el ajuste utiliza las secciones Psych; la línea muestra beats, sin inventar un compás musical.
- Los tiempos de las notas y duraciones del editor siguen siendo milisegundos enteros. Los tiempos fraccionarios importados se rechazan en vez de truncarse. Los BPM se calculan en `Double`; el BPM inicial, BPM de sección y duración de sección de entrada se rechazan si su valor decimal no puede recuperarse de la representación `Double` utilizada. El editor limita el BPM inicial a 1–400.
- Los tipos textuales se conservan. Psych 0.7.3 incluye por defecto los índices 0–5: vacío, `Alt Animation`, `Hey!`, `Hurt Note`, `GF Sing` y `No Animation`. Sin embargo, el propio código invita a los mods a ampliar o cambiar esa tabla. El editor conserva el token numérico sin interpretarlo, incluso si el índice es desconocido, y distingue texto, ausencia, cadena vacía y nulo. Los tipos numéricos u otros tokens explícitos que V-Slice no representa bloquean esa exportación, incluida BOTH. La interpretación real de índices desconocidos depende del motor y del mod.
- Las verificaciones automatizadas no sustituyen una prueba dentro de ambas versiones del juego o en Android. La reimportación directa de un ZIP BOTH como proyecto no está implementada. La importación V-Slice admite BPM iniciales decimales dentro de 1–400 que puedan recuperarse de su representación `Double`; conserva el token original y bloquea una precisión no representable.

Fuentes oficiales consultadas:
- <https://github.com/ShadowMario/FNF-PsychEngine/blob/0.7.3/source/backend/Conductor.hx>
- <https://github.com/ShadowMario/FNF-PsychEngine/blob/0.7.3/source/backend/Song.hx>
- <https://github.com/ShadowMario/FNF-PsychEngine/blob/0.7.3/source/states/editors/ChartingState.hx>
- <https://github.com/ShadowMario/FNF-PsychEngine/blob/0.7.3/source/states/PlayState.hx>
- <https://github.com/ShadowMario/FNF-PsychEngine/blob/main/source/backend/Song.hx>

Fuente V-Slice: <https://github.com/FunkinCrew/Funkin/blob/main/source/funkin/data/song/SongData.hx> y <https://github.com/FunkinCrew/Funkin/blob/main/source/funkin/Conductor.hx>.

V-Slice redondea a cuatro decimales el beat acumulado en cada cambio de tiempo (`Conductor.mapTimeChanges`); la cuadrícula aplica esa regla solamente al usar el mapa V-Slice. Psych redondea la duración de cada sección a pasos.
