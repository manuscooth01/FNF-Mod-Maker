# Importación ZIP BOTH: auditoría y trabajo pendiente

La importación real de ZIP BOTH **no está implementada**. No se deben combinar los resultados de los importadores individuales ni eliminar las restricciones del exportador para simularla.

## Dependencias que deben resolverse conjuntamente

- `ModConfig.charts` y `chartHistories` se indexan por canción y dificultad, no por motor. El editor, las propuestas de IA, undo/redo, el renombrado de canciones, las operaciones de dificultad y las validaciones comparten esos mapas. Ambos charts podrían tener notas, eventos, secciones y dificultades diferentes. También hay campos y audio compartidos en `SongConfig`, por lo que separar solamente los mapas de charts no evita todas las colisiones.
- Los importadores actuales preparan formatos individuales y el importador Psych aborta ante charts no convertibles. `ModZipInspector` aporta indicios por ruta, no una identificación de contenido ni una relación fiable entre canciones. El ViewModel elige un importador individual durante la preparación. `ModImportSession` y `ProjectStore` publican usando staging; debe conservarse ese límite transaccional también para BOTH.
- `ModBuilder`, `JsonBuilders` y la preservación V-Slice dependen de la representación compartida y de reglas de prioridad entre archivos originales y regenerados. El bloqueo de exportación de proyectos BOTH importados debe mantenerse hasta que se resuelvan todos los destinos, recursos, canciones exclusivas y colisiones sin pérdida.
- `.fnfproject` es un proyecto portable, no un ZIP instalable. Sus validaciones explícitas de charts e historial, reubicación de recursos y migración deben cubrir cualquier representación nueva. Los proyectos antiguos BOTH no demuestran que sus charts Psych y V-Slice fueran iguales; la migración no debe inventar una procedencia o sobrescribir datos.

## Requisitos para terminar

Crear un modelo por motor y canción, con dificultades, historiales, metadatos y originales independientes; relacionar canciones solo cuando la correspondencia sea segura. Adaptar todas las rutas de edición, historial y guardado, además de las operaciones de canción/dificultad. Mantener originales no editables y advertir cuando no sea posible convertirlos. Detectar conflictos de nombres y rutas antes de publicar o exportar. Usar fixtures propias y externas y probar ZIP BOTH → proyecto → edición aislada → reapertura y portable → ZIP → reimportación, además de regresiones individuales. No modificar ni redondear silenciosamente tiempos o holds fraccionarios; la edición de precisión completa es una fase posterior.

## Protección parcial añadida

El importador V-Slice rechaza indicios de charts Psych en rutas canónicas `data/<canción>/<canción>.json` o `data/<canción>/<canción>-<dificultad>.json`, incluso sin `pack.json` y aunque el contenido no sea convertible. Es una comprobación conservadora por nombre: puede rechazar un recurso V-Slice ajeno con esa misma forma y no detecta todos los nombres o estructuras Psych posibles. No constituye importación BOTH ni identificación completa del formato. El proyecto abierto y el ZIP original permanecen intactos cuando la preparación se rechaza.
