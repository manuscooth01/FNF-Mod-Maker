# Referencias de herramientas y límites actuales

Consulta realizada en noviembre de 2026. Las referencias sirven para entender flujos y formatos;
no significan que la aplicación implemente todas las funciones de esas herramientas.

- **Charts:** [editor oficial](https://fridaynightfunkin.wiki.gg/wiki/Chart_Editor),
  [código de entrada en vivo y sostenidos de Funkin](https://github.com/FunkinCrew/Funkin/blob/main/source/funkin/ui/debug/charting/ChartEditorState.hx),
  [editor de Psych](https://deepwiki.com/ShadowMario/FNF-PsychEngine/7.1-chart-editor) y
  [MuseGen FNF Chart Editor](https://www.musegen.ai/fnf-chart-editor) muestran carriles, cuadrícula,
  snap, sostenidos, reproducción y edición. La app graba en vivo por canción/dificultad/lado y
  guarda tiempo y duración; también permite cambiar duración. No ofrece todavía cuadrícula de
  edición, forma de onda, metrónomo, eventos ni playtest integrado. La captura usa el reloj del
  reproductor: pueden existir diferencias de latencia, especialmente con Bluetooth.
- **Estilos de notas:** la [documentación de Funkin](https://funkincrew.github.io/funkin-modding-docs/06-custom-notestyles/06-01-creating-a-notestyle.html)
  distingue cabeza, sostenido, receptor y efectos. Los iconos de esta app solo son controles de
  grabación, no un editor ni un paquete exportable de estilos de nota.
- **Personajes:** [Character Editor de Psych](https://deepwiki.com/ShadowMario/FNF-PsychEngine/7.2-character-editor)
  usa prefijos, FPS, bucle, offsets, posición y cámara. La app permite configurar esos elementos
  con vista previa orientativa, pero no sustituye la prueba en cada motor.
- **Atlas:** [Spritesheet and XML Generator](https://gamebanana.com/tools/7136) agrupa PNG por
  animación y crea PNG/XML; [Spritepacker](https://spritepacker.app/engines/fnf) explica los
  nombres por prefijo y los recortes Sparrow. La app admite frames PNG, cuadrícula y Sparrow,
  pero no atlas rotados ni todos los formatos de atlas de los motores.
- **Audio:** la [guía oficial de canciones](https://funkincrew.github.io/funkin-modding-docs/02-custom-songs-and-custom-levels/02-02-adding-the-custom-song.html)
  describe metadatos, chart e instrumentales/voces OGG. La app convierte y exporta audio; el
  grabador escucha solo la instrumental, no incluye mezcla o reproducción sincronizada de voces.
- **Escenarios:** el [editor de escenarios de Psych](https://github.com/junetried/psych-stage-editor)
  describe capas, posición y animación; la app aún no tiene un editor/exportador de escenarios.

## Recursos visuales

Los gráficos oficiales de Funkin están bajo [licencia propietaria](https://github.com/FunkinCrew/funkin.assets/blob/main/LICENSE.md)
y no se redistribuyen aquí. Para las flechas de la interfaz se descargaron cuatro iconos Material
Design con licencia Apache 2.0, convertidos a vectores Android y teñidos para distinguir carriles.
Originales, procedencia, modificación y licencia: [`third_party/material-icons`](../third_party/material-icons/README.md).
