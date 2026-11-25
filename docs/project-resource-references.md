# Referencias de recursos del proyecto (esquema actual)

`PickedAudio` y `PickedImage` se reconocen **por ubicación dentro del modelo**, no por nombres de propiedades dentro de JSON arbitrario. El registro compartido `ProjectAssetReferences.kt` enumera:

- `icon` global; `songs[]` → `instrumental`, `vocalsPlayer`, `vocalsOpponent`.
- `character` antiguo o `characterLibrary[].draft` → `sheet`, `icon`, `atlasSheet`, `atlasXml`, `variations[].sheet/icon` y `animationFrames.<animación>[].image`.
- `stageDraft.backgroundImage` y `stageDraft.sprites[].image`.
- `importedModResources[].asset`, donde se retienen originales de mods.

`chartAudioMix.instrumental` son ajustes numéricos, no un archivo. Los strings con JSON de origen Psych, eventos o tempo V-Slice y todas las propiedades desconocidas quedan fuera del recorrido. Igual sucede con los nombres de archivo originales de `VSliceSongOrigin`: no son rutas de recursos de la aplicación.

Portable export/import/materialización, copia de proyecto, publicación de mod importado y validación utilizan el mismo registro. Las referencias conocidas inválidas o ausentes detienen la operación; las rutas del portátil se validan contra manifiesto y checksums, y se mantienen las comprobaciones de traversal, enlaces, tamaño y carpetas de staging. Los recursos no referenciados existentes dentro de `assets` se incluyen en el portable, pero un objeto desconocido nunca añade un archivo al paquete por su forma.

El ViewModel conserva el JSON no representado por Gson al guardar o duplicar. Cuando una lista con campos desconocidos cambia, o se elimina/reemplaza un objeto que los contiene, se rechaza el guardado para evitar reasignarlos o perderlos. No existe aún una interfaz para resolver ese conflicto: el usuario debe conservar el original y evitar esa edición hasta implementar una migración segura. Esto puede bloquear también algunas ediciones de listas antiguas ambiguas.

El registro corresponde solo al esquema actual; una futura estructura independiente necesita sus propias ubicaciones tipadas y validación antes de aceptarse. Se mantiene el bloqueo de `chartSchemaVersion` desconocido y de `independentCharts`; no hay migración automática ni importación ZIP BOTH.
