# FNF Mod Maker (Android)

Una app Android para crear mods de **Friday Night Funkin'** sin saber nada de JSON, carpetas ni
conversores de audio. Rellenas un formulario, eliges tu canción y la app te devuelve un `.zip`
listo para copiar al juego.

Funciona con los dos motores que pediste:

| Motor | Dónde se juega | Formato de chart |
|---|---|---|
| **V-Slice** | FNF oficial de Android 0.8.x (`me.funkin.fnf`) | `data/songs/<id>/<id>-chart.json` + `-metadata.json` + `data/levels/<id>.json` |
| **Psych Engine** | Psych Engine de Android **y** PC | `data/<id>/<id>.json` + `weeks/<id>.json` + `songs/<id>/Inst.ogg` |

La opción **Ambos** genera las dos estructuras en la misma carpeta, así que el mismo ZIP sirve
para los dos juegos (cada uno ignora los archivos del otro).

---

## Estado actual (v1)

La app abre en un **inicio con herramientas**: puedes hacer un mod completo o usar cada
herramienta por separado. Hace esto:

- **Pantalla de inicio** con tres herramientas independientes:
  - **Crear un mod completo**: asistente de 6 pasos (mod → canciones → personaje → audio → notas → exportar).
  - **Crear un personaje**: solo sprites, **sin pedirte el nombre del mod** ni la canción.
  - **Convertir audio a OGG**: un archivo suelto, sin crear nada más.
- En el asistente puedes **saltar entre pasos** tocando los números de arriba; solo se valida al
  continuar en cada paso y al exportar; el resumen te indica qué falta y te lleva al paso correspondiente.
- Genera la estructura de carpetas y **todos** los JSON que necesitan los dos motores.
- **Convierte audio a OGG Vorbis dentro del teléfono** con FFmpeg (MP3, WAV, M4A, FLAC…).
- Si el archivo ya es `.ogg`, lo copia sin recomprimir.
- Reescala el icono del mod a PNG de 512 px.
- **Voces separadas por personaje** (`Voices-bf.ogg`, `Voices-dad.ogg`), como los mods reales de
  GameBanana. Si das una sola pista, se guarda como `Voices.ogg` (compatible con todo); si das dos
  y el destino incluye Psych, las **mezcla** automáticamente en un `Voices.ogg`.
- **Offset en milisegundos** para corregir la sincronía del chart.
- Genera el **título de la semana** (`images/storymenu/titles/<mod>.png`, 359x89 como los del
  juego) y te deja elegir entre aparecer **solo en Freeplay** o también en el **modo historia**.
- Rellena el chart con **notas de práctica** siguiendo el BPM, para que puedas entrar al juego y
  comprobar que el audio, los personajes y el escenario funcionan.
- **Creador de personajes** (lo que en escritorio hacía falta con el *spritesheet and XML
  generator*): le das una hoja de sprites en cuadrícula y la app la **corta en frames**, escribe el
  **XML Sparrow**, el **JSON de personaje para Psych y para V-Slice** y el **icono de vida**
  300x150. Puede ser el jugador o el oponente.
- Exporta un `.zip` con la carpeta del mod + un `LEEME.txt` con las instrucciones.

Lo que **todavía no** hace (siguiente fase):

- Eventos avanzados y cambios de BPM dentro del editor de notas.
- Escenarios avanzados con capas, animaciones y cámara dinámica; cinemáticas y diálogos exportables.
- Iconos y animaciones *miss* por personaje (la app usa los de `bf`/`dad`).
- Importar un mod completo existente para editarlo.

Puedes grabar notas en vivo dentro de la app y usar el editor del juego (Psych o V-Slice) para
las funciones avanzadas que aún faltan, como eventos y cambios de BPM.

---

## 1. Consigue el APK

El APK se compila solo en GitHub Actions en cada push (unos 2 minutos, gratis). No hace falta
instalar nada ni tener Android Studio.

1. Abre **Actions → Build APK**: <https://github.com/manuscooth01/FNF-Mod-Maker/actions/workflows/build-apk.yml>
2. Entra en la ejecución más reciente (la que tiene el ✓ verde).
3. Abajo, en **Artifacts**, descarga **`fnf-mod-maker-apk`**.
4. Descomprime el ZIP que se descarga: dentro está **`app-debug.apk`** (~29 MB).
5. Pásalo al teléfono e instálalo (Android te pedirá permitir "instalar apps desconocidas").

> El APK solo incluye binarios ARM (arm64-v8a y armeabi-v7a), que es lo que usan todos los
> móviles normales. No funciona en emuladores x86.

### Compilarlo tú mismo (opcional)

Con Android Studio (Ladybug o superior):

```
File → Open → carpeta del proyecto → Sync
Build → Build Bundle(s)/APK(s) → Build APK(s)
```

El APK queda en `app/build/outputs/apk/debug/`. Para ello Android Studio generará el wrapper de
Gradle automáticamente la primera vez.

---

## Editor de personajes

El mismo espacio de trabajo se abre tanto desde **Crear un personaje** como desde el paso
Personaje del mod. En pantallas anchas muestra vista previa y controles lado a lado; en pantallas
estrechas usa una vista previa encima y pestañas para Personaje, Imágenes, Animación, Posición y
Revisar. Si queda muy poco espacio, la vista previa se oculta para dejar sitio a los controles.

En Imágenes elige **Frames PNG**, **Cuadrícula** o **Atlas PNG + XML**. Para un atlas selecciona
el PNG y un XML Sparrow UTF-8; en Animación asigna a cada nombre el prefijo que aparece en los
SubTexture del XML (idle es obligatorio). Se conservan los metadatos de recorte del XML. La vista
previa muestra como máximo los primeros 100 frames de cada animación; el atlas completo se exporta.
Los atlas con frames rotados y los formatos que no sean Sparrow no son compatibles.

Se pueden añadir animaciones personalizadas, indicar sus frames o prefijos y ajustar FPS, bucle,
offsets, posición y cámara. Se escriben las definiciones para Psych y/o V-Slice según el destino;
que una animación personalizada se reproduzca depende del juego y del mod. La escena es una
referencia y el resultado debe comprobarse en el juego. No se generan recursos adicionales de
selección ni resultados para un personaje totalmente seleccionable en V-Slice.

### Escenario de referencia y asistente de IA

El editor muestra siluetas orientativas del rival, acompañante y jugador. Solo el personaje que
estás editando se mueve y se exporta; las posiciones exactas dependen del escenario y del motor.

La pestaña **Asistente IA** es opcional y requiere Internet. Configura una URL **HTTPS** de un
proveedor de confianza compatible con `chat/completions` y, para generar ilustraciones,
`images/generations`, más los modelos y tu propia clave API. La clave solo permanece en memoria
mientras la app está abierta; no se guarda en el proyecto. Puedes borrar la clave o limpiar toda la
sesión y sus imágenes temporales desde la configuración. No todos los servicios o modelos
compatibles admiten visión o imágenes, y pueden aplicar cargos y sus propias políticas de datos.
Cada solicitud requiere confirmación: los consejos envían la consulta y los ajustes básicos; el
análisis envía además la imagen que elijas (reducida a 1024 px y sin metadatos, con transparencia
sobre fondo blanco); la generación envía la descripción. Nunca se envía la imagen seleccionada
al pedir solo consejos. Las respuestas son sugerencias y no modifican el personaje automáticamente.
La ilustración generada se puede guardar como PNG, pero no es un atlas ni una animación terminada.

En el asistente del mod, **Proponer cambios al mod** pide a un modelo compatible con herramientas
un lote revisable. Puede proponer ajustes de personaje y animación, datos de canciones existentes,
añadir canciones sin audio, reemplazar notas de una dificultad o añadir/reemplazar scripts Lua
globales de Psych (`scripts/<nombre>.lua`). Desde la herramienta de personaje independiente solo
se admiten propuestas de personaje. Antes de enviar se explica que se comparten metadatos del
proyecto, muestras de notas y nombres de scripts, pero no audio, imágenes ni el código de scripts.
La app valida el lote y muestra los cambios antes de aprobarlos juntos; si el borrador cambia, la
propuesta debe solicitarse otra vez. Los scripts son código ejecutable en Psych: revisa todo su
contenido antes de aprobar, exportar o instalar el mod. La app no los ejecuta ni comprueba que
funcionen, y no genera scripts para V-Slice. Los charts propuestos no se sincronizan escuchando
audio y deben revisarse y probarse en el juego. El modelo y proveedor deben admitir herramientas
(`tool_calls`); algunos servicios compatibles con texto no las admiten.

## 2. Cómo se usa

El borrador se guarda automáticamente. En Inicio puedes retomarlo o elegir **Empezar un
proyecto nuevo** (tras confirmar se reemplaza el borrador, no los ZIP exportados). Los archivos
seleccionados se copian a la caché de la app; si Android los elimina, vuelve a seleccionarlos.
En canciones y audio usa las pestañas para elegir qué canción editar. En el paso Notas abre
**Grabación en vivo**, elige canción, dificultad y lado, pulsa Grabar y toca las flechas mientras
escuchas la instrumental. Las notas se añaden al chart de esa dificultad; puedes pausar, escuchar,
buscar otro momento, deshacer la última pulsación y quitar notas. Se escuchará solo la instrumental,
no las voces. Toca para notas cortas o mantén la flecha al menos 120 ms y suéltala para grabar una
nota sostenida. Puedes mantener varias flechas a la vez. Al pausar, usa **Editar notas cercanas**
para cambiar la duración en milisegundos (0 = corta) o quitar una nota. El ajuste al ritmo puede
redondear la duración hasta cero; prueba otra resolución o edítala. La duración se limita a la
siguiente nota del mismo carril o al final de la canción. Para eventos usa el editor del juego.
El ajuste al ritmo es opcional y usa el BPM; la corrección de toque permite compensar latencia.
Un chart propio reemplaza las notas de práctica automáticas solo en esa dificultad. La corrección
de toque es independiente del offset de la canción; prueba la sincronización en el motor de destino.
Los charts con eventos o secciones existentes no se pueden grabar aquí para evitar perder datos.
Importación de mods y escenarios avanzados todavía no están disponibles.


### Inicio
La primera pantalla te pregunta **qué quieres hacer**. Cada tarjeta abre una herramienta que
funciona sola:

| Herramienta | Para qué sirve | Necesita datos del mod |
|---|---|---|
| **Crear un mod completo** | ZIP listo para instalar con canción, dificultades y audio | sí |
| **Crear un personaje** | XML Sparrow + JSON de Psych y V-Slice + icono de vida | **no** |
| **Convertir audio a OGG** | Pasar un MP3/WAV/M4A/FLAC al formato del juego | **no** |

### Asistente del mod
Los números de la barra superior son **tocables**: puedes ir directo a "Personaje" o "Audio" sin
pasar por los demás. Al pulsar **Crear .zip** la app comprueba lo imprescindible y, si falta algo,
te lo dice con la lista exacta.

### Paso 1 — Tu mod
- **Nombre del mod**: lo que verás en la lista de mods.
- **Carpeta**: se rellena solo (minúsculas y guiones). Es el nombre de la carpeta dentro de `mods/`.
- **Autor / descripción**: aparecen en la información del mod.
- **Juego**: V-Slice, Psych o Ambos.

### Paso 2 — La canción
- **Nombre**: el que saldrá en Freeplay. Evita acentos y símbolos raros.
- **Artista**, **BPM** (el real de la canción, imprescindible), **velocidad** de las flechas.
- **Personajes**: por defecto `bf`, `gf`, `dad`. Si escribes un personaje que no existe en el
  juego, la canción fallará al cargar.
- **Dificultades**: `easy`, `normal`, `hard` (puedes elegir varias).
- **Notas de práctica**: déjalo activado la primera vez.
- **Avanzado**: escenario y color del mod.

### Paso 3 — Personaje (opcional)
Si quieres tu propio personaje en vez de `bf`/`dad`:

- **Activa "Usar un personaje propio"**.
- **ID del personaje**: solo minúsculas, números y guiones (`mi-personaje`). Se usa para los
  nombres de archivo; si lo dejas vacío se saca del nombre visible.
- **Hoja de sprites**: un PNG con todas las animaciones **en cuadrícula**, cada frame del mismo
  tamaño y sin huecos. El orden de reparto es `idle`, `singLEFT`, `singDOWN`, `singUP`,
  `singRIGHT`, de izquierda a derecha y de arriba abajo.
- **Ancho / alto del frame**: mira el tamaño del PNG (la app lo muestra) y divide. Por ejemplo una
  hoja de `900x600` con frames de `150x150` son 6 columnas y 4 filas = 24 frames.
- **Frames por animación**: cuántos cuadros tiene cada una. Los que no uses, ponlos a `0`.
- **Escala**, **voltear horizontalmente** (`flipX`) y **lado** (jugador u oponente).
- **Icono de vida** (opcional): la app lo deja en 300x150 con dos frames (normal/perdiendo). Si tu
  imagen ya trae los dos, los respeta. Si no eliges ninguno, se usa el del personaje base.

El editor también está disponible como herramienta independiente. Abre el espacio de personaje
para seleccionar **Frames PNG**, **Cuadrícula** o **Atlas PNG + XML**. Las pestañas Animación y
Posición permiten elegir animaciones y ajustar FPS, bucle, offsets, escala y cámara; la vista previa
permite arrastrar, pausar y avanzar frames. En pantallas anchas o en horizontal se muestran paneles
lado a lado. La escena es orientativa: comprueba el resultado en el juego.

Para PNG individuales, el límite es de 100 frames y una hoja generada de 4096 × 4096 píxeles.
`idle` necesita al menos un frame y la escala debe ser mayor que cero. El atlas Sparrow existente
se conserva, incluidos sus recortes; los atlas con rotación no se admiten.

La app comprueba que los frames quepan en la hoja antes de dejarte continuar, y luego genera
`images/characters/<id>.png` + `.xml`, `characters/<id>.json` (Psych),
`data/characters/<id>.json` (V-Slice) e `images/icons/icon-<id>.png`.

### Paso 4 — Audio e imagen
- **Instrumental (obligatoria)**: MP3, WAV, M4A, FLAC u OGG.
- **Voces (opcional)**: pista única con las voces. Si no tienes, el mod funciona igual.
- **Icono (opcional)**: se convierte a PNG de 512 px.

La conversión ocurre en el teléfono. Un tema de 2 minutos suele tardar entre 10 y 40 segundos.

> Si solo quieres el audio convertido, usa la herramienta **Convertir audio a OGG** del inicio: no
> hace falta crear un mod.

### Pasos 5 y 6 — Notas y exportación
Puedes grabar notas en vivo, o elegir notas de práctica o un chart vacío. En Exportar pulsa **Guardar ZIP** y elige dónde guardarlo (por ejemplo, `Descargas`). Al terminar verás la lista
exacta de archivos generados.

---

## 3. Instalar el mod

### V-Slice (FNF oficial de Android)

1. Abre FNF → **Options** → **Open Data Folder**.
2. Entra en `mods/`.
3. Descomprime el ZIP ahí. Debe quedar `mods/<tu-mod>/...`.
4. Cierra el juego del todo (quítalo de recientes) y vuelve a abrirlo.
5. La canción aparece en **Freeplay**.

Ruta real de la carpeta: `Android/obb/me.funkin.fnf/mods/` (en algunas versiones
`Android/data/me.funkin.fnf/files/mods/`).

### Psych Engine

- **PC**: descomprime en `<Psych Engine>/mods/`.
- **Android**: descomprime en `Android/data/<paquete-de-psych>/files/mods/`
  (cada port usa su propio paquete; también puedes usar el botón de carpeta de datos del juego).
- La canción aparece en **Freeplay**. Si no aparece, abre el menú **Mods** de Psych y comprueba
  que `mi-mod` está activado (Psych activa los mods nuevos automáticamente, pero algunos ports
  no lo hacen).

### Y ahora, ¿las notas?

El chart de práctica sirve para probar el mod, no para publicarlo. Para chartear de verdad:

- **Psych Engine**: abre el juego → Freeplay → selecciona la canción → **tecla `7`** (Chart Editor)
  → coloca notas → `Ctrl+S`. Se guarda encima de tu archivo.
- **V-Slice**: activa las herramientas de debug y usa el **Chart Editor** del juego; puedes cargar
  la canción, editarla y guardar.

---

## 4. Cómo se hace un mod de verdad (y qué automatiza la app)

Esto es el flujo real que piden la documentación oficial de Funkin' y las guías de GameBanana,
contrastado con el código del juego. La app hace los pasos 1-4; los 5-7 siguen siendo manuales.

| Paso | Qué hay que hacer | ¿Lo hace la app? |
|---|---|---|
| 1 | Crear la carpeta del mod dentro de `mods/` | ✅ |
| 2 | Escribir `_polymod_meta.json` (V-Slice) o `pack.json` (Psych) con título, autor y versión de API | ✅ |
| 3 | Poner `Inst.ogg` y las voces en `songs/<id>/` | ✅ (convierte a OGG) |
| 4 | Crear el chart y **meterlo en un nivel/semana**, o no aparece en Freeplay | ✅ (plantilla con notas de práctica) |
| 5 | Chartear en serio en el editor del juego (Psych: tecla `7`) | ❌ manual |
| 6 | Personajes/escenario propios: sprites + XML + JSON en `characters/`, `images/`, `stages/` | ❌ manual |
| 7 | Empaquetar el ZIP y subirlo a GameBanana indicando el motor | ✅ el ZIP; la subida es tuya |

Detalles que la documentación oficial deja claros y que la app ya respeta:

- **`_polymod_meta.json` tiene que estar en la raíz de la carpeta del mod**, no dentro de otra
  carpeta, y con `api_version` compatible con la versión del juego (`0.8.0` para FNF 0.8.x).
- **Toda canción debe pertenecer a un nivel** (`data/levels/*.json`) para salir en Freeplay; si
  pones `"visible": false`, sale solo en Freeplay y no ocupa hueco en el modo historia.
- **`titleAsset` y `background` son rutas relativas a `images/`**, así que el título del nivel se
  guarda en` images/storymenu/titles/<mod>.png`.
- En V-Slice las voces van **por personaje** (`Voices-bf.ogg`, `Voices-pico.ogg`), y `Voices.ogg`
  se acepta como respaldo si no hay archivos por personaje.
- En **Psych Engine** los nombres son fijos: `Inst.ogg` y `Voices.ogg`, y la carpeta de la canción
  se llama igual que la canción con los espacios convertidos en guiones (`Dad Battle` →
  `dad-battle`). La app replica esa función exacta (`formatToSongPath`).
- En Psych el mod aparece en Freeplay **a través de `weeks/*.json`**; sin ese archivo el chart
  existe pero nadie lo ve. Y si el mod no sale, hay que activarlo en el menú **Mods** del juego.

Enlaces útiles para seguir aprendiendo:

- Documentación oficial: <https://funkincrew.github.io/funkin-modding-docs/>
- Guía de charting en Psych (GameBanana): <https://gamebanana.com/tuts/15107>
- Mods y tutoriales: <https://gamebanana.com/games/8694>

## 5. Qué genera exactamente

Ejemplo con mod `mi-mod`, canción `mi-cancion`, motor *Ambos*:

```
mi-mod/
├─ songs/
│  └─ mi-cancion/
│     ├─ Inst.ogg                  ← instrumental convertida
│     ├─ Voices-bf.ogg             ← si diste voces del jugador (V-Slice)
│     ├─ Voices-dad.ogg            ← si diste voces del oponente (V-Slice)
│     └─ Voices.ogg                ← pista única, o la mezcla para Psych
├─ data/
│  ├─ characters/
│  │  └─ mi-personaje.json         ← V-Slice: renderType "sparrow"
│  ├─ mi-cancion/                  ← Psych
│  │  ├─ mi-cancion.json           ← dificultad normal
│  │  ├─ mi-cancion-easy.json
│  │  └─ mi-cancion-hard.json
│  ├─ songs/                       ← V-Slice
│  │  └─ mi-cancion/
│  │     ├─ mi-cancion-chart.json
│  │     └─ mi-cancion-metadata.json
│  └─ levels/
│     └─ mi-mod.json               ← V-Slice: sin esto no sale en Freeplay
├─ images/
│  ├─ characters/
│  │  ├─ mi-personaje.png          ← tu hoja de sprites (si creaste personaje)
│  │  └─ mi-personaje.xml          ← atlas Sparrow con los frames cortados
│  ├─ icons/
│  │  └─ icon-mi-personaje.png     ← icono de vida 300x150, dos frames
│  └─ storymenu/
│     └─ titles/
│        └─ mi-mod.png             ← título de la semana (359x89)
├─ characters/
│  └─ mi-personaje.json            ← Psych: animaciones + icono
├─ weeks/
│  └─ mi-mod.json                  ← Psych: sin esto no sale en Freeplay
├─ _polymod_meta.json              ← V-Slice (nombre, autor, api_version)
├─ _polymod_icon.png               ← V-Slice (si elegiste icono)
├─ pack.json                       ← Psych (nombre, color)
└─ LEEME.txt
```

Los detalles técnicos están calcados de los archivos reales del juego:

- Metadata de V-Slice: esquema `2.2.4`, `timeFormat: "ms"`, `playData` con dificultades,
  personajes, escenario y `noteStyle: "funkin"`.
- Chart de V-Slice: esquema `2.0.0`, notas `{t, d, l}` con `d` 0-3 = jugador y 4-7 = oponente.
- Nivel de V-Slice: esquema `1.0.0`, con `titleAsset`, `background` y la lista `songs`.
- Chart de Psych: `format: "psych_v1"`, secciones de 4 beats y notas `[tiempo, carril, sustain]`
  con el desplazamiento 0-3 / 4-7 que espera `mustHitSection`, y `offset` en milisegundos.
- Semana de Psych: array `songs` + `weekCharacters`; `hideStoryMode` activo para que aparezca solo
  en Freeplay y no necesite arte de título.
- Personaje de V-Slice: esquema `1.0.0`, `renderType: "sparrow"`, `assetPath` sin extensión
  (`characters/mi-personaje` carga el `.png` + `.xml`), `healthIcon.id`, `flipX`, `offsets`.
- Personaje de Psych: `image` sin extensión y con comas para varios atlas, `healthicon`,
  `sing_duration`, `position`, `flip_x`, `healthbar_colors`, y las animaciones
  `{anim, name, fps, loop, offsets}`.
- El XML usa `SubTexture` con `name="<id> <animación>0000"` (así Psych encuentra las animaciones
  con `addByPrefix`) y las coordenadas reales de cada frame en la hoja.

---

## 6. Estructura del código

```
app/src/main/java/com/fnfmodmaker/
├─ MainActivity.kt              arranque + tema
├─ model/ModConfig.kt           estado del formulario + reglas de nombres (incluye
│                               la réplica exacta de Psych: formatToSongPath)
├─ model/CharacterDraft.kt      datos del personaje propio (hoja, frames, lado)
├─ media/MediaTools.kt          copiar archivos, duración, FFmpeg → OGG, reescalar PNG
├─ mod/JsonBuilders.kt          los JSON de V-Slice y Psych
├─ mod/ModBuilder.kt            crea la carpeta del mod y el ZIP
├─ mod/CharacterBuilder.kt      corta la hoja de sprites, XML Sparrow, JSON e icono
└─ ui/
   ├─ Theme.kt                  colores tipo FNF
   ├─ WizardViewModel.kt        estado, herramienta activa, validación y exportación
   ├─ ToolScreens.kt            inicio + herramientas sueltas (personaje, audio)
   └─ WizardScreens.kt          el asistente del mod (6 pasos)
```

Dependencias: Jetpack Compose (Material 3) y `ffmpeg-kit-audio` (recompilación comunitaria,
porque el proyecto original de FFmpegKit fue retirado). No se pide ningún permiso peligroso:
los archivos se leen y se guardan con el selector de Android (SAF).

---

## 7. Problemas conocidos

- **"Falta la instrumental"**: el paso 3 exige elegir el archivo; sin él no hay canción.
- **La canción no sale en Freeplay**: revisa que descomprimiste el ZIP dejando la carpeta del mod
  directamente dentro de `mods/` (no `mods/mi-mod/mi-mod/`).
- **El audio no suena o va desincronizado**: casi siempre es el BPM. Si tu canción tiene cambios
  de tempo, este asistente (una sola BPM) no basta.
- **"No se pudo convertir"**: el error de FFmpeg se muestra en pantalla. Si tu archivo es raro
  (DRM, opus dentro de ogg), expórtalo a WAV/MP3 y vuelve a intentarlo.
- **Psych carga la canción en "Normal" y no la encuentra**: Psych siempre abre el archivo sin
  sufijo (`<cancion>.json`). La app lo crea siempre; si no marcaste `normal`, copia ahí la primera
  dificultad que elegiste y te avisa en el resultado.
- **El personaje sale en gris / con la animación equivocada**: revisa el orden de los frames de la
  hoja y el tamaño de frame. Si el personaje mira al lado contrario, activa *voltear
  horizontalmente*.
- **El APK no instala**: revisa que el teléfono sea ARM (todos los móviles normales lo son); el
  APK no incluye binarios x86 para emuladores.

## 8. Siguientes pasos sugeridos

1. Editor de notas con scroll y audio (lo que convierte esto en una herramienta completa).
2. Varias canciones y semanas por mod.
3. Importar un `.zip` existente y editarlo.
4. Voces separadas por personaje y soporte de variaciones (Erect, Pico…).

## Créditos y licencias

- Los formatos son de [FunkinCrew/Funkin](https://github.com/FunkinCrew/Funkin) (Apache-2.0) y
  [ShadowMario/FNF-PsychEngine](https://github.com/ShadowMario/FNF-PsychEngine) (Apache-2.0).
- FFmpegKit: [arthenica/ffmpeg-kit](https://github.com/arthenica/ffmpeg-kit) (LGPL-3.0), reconstruido
  por [maitrungduc1410](https://github.com/maitrungduc1410/ffmpeg-kit).
- Friday Night Funkin' es propiedad de Funkin' Crew Inc. Esta herramienta no incluye assets del juego.

Referencias y comparación de herramientas: [docs/referencias-herramientas.md](docs/referencias-herramientas.md).

## Escenario sencillo y ayuda para principiantes

En Canciones, activa **Escenario sencillo**, escribe un nombre y elige una imagen de fondo propia.
Elige un encuadre inicial: el escenario estático se aplica a todas las canciones al exportar.
V-Slice recibe un JSON de escenario y Psych recibe JSON y un script Lua de fondo. El personaje,
las posiciones y el encuadre son orientativos: prueba el ZIP en los motores que uses. Cambios de
cámara durante la canción, capas animadas, diálogos y cinemáticas no se generan automáticamente.

Desde Inicio o el mod puedes abrir el asistente: hay consultas de ejemplo sobre escenarios, cámaras,
cinemáticas y código. Requiere tu proveedor HTTPS y clave propios; revisa el coste y privacidad
antes de enviar. Los consejos no aplican cambios. Las propuestas usan modelos compatibles con
herramientas y siempre se revisan y aprueban por lote; el zoom del escenario sencillo se puede
proponer si está activado. La IA no escucha el audio, no ejecuta ni comprueba el código, y no
puede crear cinemáticas o escenarios animados completos en esta versión. Los scripts Lua propuestos
solo se exportan para Psych y necesitan revisión y prueba.

Los assets oficiales de FNF no se redistribuyen: puedes seleccionar imágenes propias. Los iconos
de interfaz descargados, su procedencia y licencia están en `third_party/material-icons/`.

## Vistas previas y referencia de plantilla

El paso Canciones muestra una composición orientativa del escenario con fondo, siluetas y zoom;
no reproduce las posiciones exactas ni sustituye la prueba dentro del juego. El espacio de personaje
muestra la animación y las posiciones de referencia; el icono elegido tiene miniatura. En Audio se
pueden escuchar instrumental y voces **por separado** y buscar dentro de cada pista. La herramienta
independiente de conversión también permite escuchar el archivo original; no reproduce el OGG
convertido. En Notas hay una vista general del chart personalizado por dificultad y el grabador
muestra la línea de tiempo. No son un playtest y no muestran notas de práctica generadas.

La página de [Funkin' Modding Template](https://gamebanana.com/tools/21719), de Fmale3k, lo presenta
como una plantilla de carpetas para V-Slice 0.8.6–0.8.8 que se extrae en `mods/`; requiere experiencia
o tutoriales. Se usó como referencia de flujo, no se ha incluido ni analizado su ZIP ni se ha
verificado que nuestro ZIP sea idéntico o compatible con esas versiones específicas. El contenido
oficial de FNF sigue sin redistribuirse; los usuarios pueden elegir sus propios recursos.

### Cuadrícula táctil de charts

En **Grabación en vivo**, elige canción y dificultad y abre **Cuadrícula táctil** con la instrumental disponible, duración conocida y BPM válido. Cada página contiene cuatro pulsos: rival a la izquierda y jugador a la derecha. Toca para añadir o seleccionar; arrastra una nota para moverla, o elige «Sostenido» y arrastra para ajustar su final. También hay controles de duración, eliminación y edición por botones. Se rechazan solapamientos y notas fuera de la canción. Escucha solo la instrumental; la edición se bloquea mientras suena. Deshacer y rehacer conservan hasta 50 cambios mientras la cuadrícula está abierta. Si no había chart propio, la primera nota sustituye las notas de práctica de esa dificultad (se avisa antes de entrar); deshacer o borrar el chart propio recupera ese comportamiento. Los charts con eventos o secciones no editables están protegidos. La visualización no es una prueba de juego ni incluye eventos o cambios de BPM.

### Vista conjunta y prueba táctil orientativa

En cualquier paso de **Tu mod**, pulsa **Vista conjunta y prueba**. Elige canción y dificultad para
ver el fondo propio, el personaje propio animado y las notas sobre una misma escena, escuchar la
instrumental y tocar las flechas del jugador. Puedes elegir la animación manualmente y revisar los
datos y recursos del borrador. Los gráficos y posiciones son aproximados; los personajes ausentes
aparecen como siluetas. Si no existe chart propio, se muestran las notas de práctica generadas cuando
estén activadas y el BPM sea válido. La prueba cuenta toques cercanos una sola vez por nota; reinicia
el contador si quieres repetirlos. No simula la puntuación, precisión ni latencia del juego.

No es Psych ni V-Slice: no ejecuta scripts, eventos, cámara dinámica ni reglas de sostenidos, no
mezcla las voces, no aplica el offset y usa velocidad visual fija. Las animaciones no reaccionan a
las notas. La vista se basa en el borrador actual, no en un ZIP exportado, y puede diferir del mod
final; hay que probarlo en el motor correspondiente. Los archivos originales de FNF no están incluidos.
Todavía no se pueden importar ZIP de mods externos de forma segura. El autoguardado permite volver
al borrador actual en la misma instalación, pero no constituye una copia de seguridad portable.

## Proyectos locales (primera fase)

La pantalla de inicio permite continuar, crear, abrir y duplicar proyectos locales. Los archivos
nuevos seleccionados para el proyecto se copian a `filesDir/projects/<id>/assets`, fuera de la caché;
el proyecto usa un documento interno versionado y guardado con reemplazo temporal y una copia
anterior. El borrador antiguo se migra al primer proyecto: si algún archivo de la caché ya no existe,
se indica y es necesario volver a seleccionarlo. No se borra el autoguardado anterior durante la
migración. La duplicación hace copias independientes de los recursos disponibles y se rechaza si
falta alguno. El indicador de guardado y los errores aparecen en el asistente.

Los proyectos permanecen en esta instalación de la app: desinstalarla o borrar sus datos puede
eliminarlos. Todavía no hay copia portable ni importación de ZIP de mods existentes. Los formatos
V-Slice y Psych continúan necesitando pruebas reales en sus motores. No hay prueba en dispositivo
para la migración ni garantía ante una interrupción forzada durante un guardado pendiente.
