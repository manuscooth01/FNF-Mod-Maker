# Chart Studio: tiempo y compatibilidad Psych

- Psych 0.7.3 almacena BPM como `Float`; su `Conductor.mapBPMChanges` aplica un cambio al inicio de su sección y suma `Math.round(sectionBeats * 4)` pasos. Tanto 0.7.3 como el código actual usan cuatro beats si falta `sectionBeats`, no `lengthInSteps`. La propiedad heredada se conserva, pero no gobierna el reloj. Si se cambia la duración de una sección que tenía `lengthInSteps`, exigimos una equivalencia exacta en pasos antes de actualizarla; los valores históricos sin editar se conservan, incluso si discrepan.
- En BOTH, la cuadrícula, la grabación ajustada y sus sostenidos utilizan el mapa V-Slice (también los beats/compases de la línea temporal). El BPM de canción y las secciones Psych forman otro mapa, editado y exportado por separado. La grabación libre usa la posición absoluta. En Psych puro, el ajuste utiliza las secciones Psych; la línea muestra beats, sin inventar un compás musical.
- Los tiempos de las notas y duraciones del editor siguen siendo milisegundos enteros. Los tiempos fraccionarios importados se rechazan en vez de truncarse. Los BPM se calculan en `Double`; el BPM inicial de entrada se rechaza si su valor decimal no puede recuperarse de la representación `Double` utilizada. El editor limita el BPM inicial a 1–400.
- Los tipos textuales se conservan. Psych 0.7.3 incluye por defecto los índices 0–5: vacío, `Alt Animation`, `Hey!`, `Hurt Note`, `GF Sing` y `No Animation`. Sin embargo, el propio código invita a los mods a ampliar o cambiar esa tabla. Actualmente solo se acepta el cero como nota normal; otros números se rechazan y **no** existe aún una conversión reversible general de tipos numéricos. La reexportación no conserva la forma numérica original del cero. No usar este camino si es necesario conservar el token original.
- Las verificaciones automatizadas no sustituyen una prueba dentro de ambas versiones del juego o en Android. La importación V-Slice inicial sigue exigiendo BPM entero; el editor de tempo V-Slice ya admite decimales después de importar o al crear un proyecto.

Fuentes oficiales consultadas:
- <https://github.com/ShadowMario/FNF-PsychEngine/blob/0.7.3/source/backend/Conductor.hx>
- <https://github.com/ShadowMario/FNF-PsychEngine/blob/0.7.3/source/backend/Song.hx>
- <https://github.com/ShadowMario/FNF-PsychEngine/blob/0.7.3/source/states/editors/ChartingState.hx>
- <https://github.com/ShadowMario/FNF-PsychEngine/blob/0.7.3/source/states/PlayState.hx>
- <https://github.com/ShadowMario/FNF-PsychEngine/blob/main/source/backend/Song.hx>
