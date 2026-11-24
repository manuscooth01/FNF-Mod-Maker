# Stage & Character Studio: implementación incremental

## Entregado en esta fase

- Posiciones y offsets de cámara BF/DAD/GF separados para V-Slice y Psych, almacenados en `StageDraft`; zoom compartido y velocidad de cámara Psych.
- V-Slice también permite escala, Z y scroll de cada posición. Psych no tiene esos campos de personaje en su `StageFile` y la validación bloquea valores no representables.
- Vista orientativa 16:9 con selección y arrastre de puntos y controles numéricos. No es una simulación del motor ni muestra todos los sprites de personajes.
- Los proyectos antiguos reciben los valores predeterminados que generaba antes `StageTemplate`; no se modifican offsets de animaciones o datos del personaje.

## Pendiente antes de considerar terminado el estudio

1. Migración versionada y biblioteca de varios `CharacterDraft` independientes; selección, asignación a cada rol, duplicación y exportación sin colisiones. `CharacterVariation` sigue siendo una variación, no un personaje independiente.
2. Edición de objetos estáticos y animados con recursos, jerarquía y adaptadores seguros; los datos de `StageSprite` existentes siguen preservados y bloquean exportaciones con pérdida.
3. Renderizado real de los personajes y elementos, escalado y gestos, enfoque y zoom; disposición adaptable con dos paneles en tabletas.
4. Cámara global, límites y seguimiento solo cuando exista una implementación comprobada para el motor elegido; personajes adicionales con adaptador específico o bloqueo explícito.
5. Pruebas integrales con `.fnfproject`, archivos de recursos y ZIP de ambos motores, además de pruebas en dispositivos y en el juego.

Los formatos se contrastaron con [StageData.hx de Funkin](https://github.com/FunkinCrew/Funkin/blob/main/source/funkin/data/stage/StageData.hx) y [StageData.hx de Psych](https://github.com/ShadowMario/FNF-PsychEngine/blob/main/source/backend/StageData.hx); las versiones y forks pueden comportarse de forma distinta. Los scripts nunca se ejecutan en la aplicación.

## Biblioteca de personajes (fase 2)

- El proyecto guarda una colección con IDs internos estables, una selección de edición y asignaciones BF/DAD/GF. Las variaciones permanecen dentro de su personaje; un personaje asignado a más de un rol se exporta una sola vez. Los no asignados permanecen en el proyecto pero no se incluyen en el mod.
- Al abrir un proyecto antiguo, el borrador único se transforma en una entrada `legacy`; una representación ambigua con ambos formatos se rechaza. Los recursos continúan usando `ProjectStore` y el formato `.fnfproject` existente.
- Se editan las imágenes, atlas y animaciones mediante el editor anterior; importar un JSON aplica los datos al personaje seleccionado y no importa automáticamente sus imágenes. La duplicación de JSON importados está bloqueada para evitar pérdidas de campos no representados.
- La importación de ZIP Psych/V-Slice sigue convirtiendo como máximo un personaje compatible; el resto de los recursos se conservan sin edición. No se ha añadido la conversión múltiple de ZIP. Los mods importados se exportan solo para su motor original.
- V-Slice importado mantiene la metadata de canciones original; una asignación que la cambiaría se bloquea. IDs/campos originales del personaje convertido se preservan conforme al adaptador existente. Colisiones con archivos conservados bloquean la exportación.
- Psych invierte `camera_position` horizontal para el jugador: con cámara X distinta de cero se bloquea asignar el mismo JSON a lados opuestos o a un lado incompatible con su referencia, para no desplazar la cámara silenciosamente. Las posiciones del escenario no se modificaron.
- Los objetos de escenario animados, simulación de escena y render completo siguen pendientes; hay que probar el resultado en los motores y versiones concretos.
