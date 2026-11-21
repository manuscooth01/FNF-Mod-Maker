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

Esto es la **v1: un asistente**. Hace esto:

- Formulario guiado en 4 pasos (mod → canción → audio → exportar).
- Genera la estructura de carpetas y **todos** los JSON que necesitan los dos motores.
- **Convierte audio a OGG Vorbis dentro del teléfono** con FFmpeg (MP3, WAV, M4A, FLAC…).
- Si el archivo ya es `.ogg`, lo copia sin recomprimir.
- Reescala el icono del mod a PNG de 512 px.
- Rellena el chart con **notas de práctica** siguiendo el BPM, para que puedas entrar al juego y
  comprobar que el audio, los personajes y el escenario funcionan.
- Exporta un `.zip` con la carpeta del mod + un `LEEME.txt` con las instrucciones.

Lo que **todavía no** hace (siguiente fase):

- Editor visual de notas (grilla) dentro de la app.
- Varias canciones por mod / varias semanas.
- Voces separadas por personaje (`Voices-bf.ogg` / `Voices-dad.ogg` en V-Slice).
- Importar un mod existente para editarlo.

Mientras tanto, el flujo recomendado es: **la app crea el esqueleto y tú charteas en el editor
que ya trae el juego** (Psych: tecla `7`; V-Slice: menú de debug). Así no peleas con carpetas ni
JSON, que es justo la parte que nadie te explica.

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

## 2. Cómo se usa

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

### Paso 3 — Audio e imagen
- **Instrumental (obligatoria)**: MP3, WAV, M4A, FLAC u OGG.
- **Voces (opcional)**: pista única con las voces. Si no tienes, el mod funciona igual.
- **Icono (opcional)**: se convierte a PNG de 512 px.

La conversión ocurre en el teléfono. Un tema de 2 minutos suele tardar entre 10 y 40 segundos.

### Paso 4 — Crear el mod
Pulsa **Crear .zip** y elige dónde guardarlo (por ejemplo, `Descargas`). Al terminar verás la lista
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

## 4. Qué genera exactamente

Ejemplo con mod `mi-mod`, canción `mi-cancion`, motor *Ambos*:

```
mi-mod/
├─ songs/
│  └─ mi-cancion/
│     ├─ Inst.ogg                  ← instrumental convertida
│     └─ Voices.ogg                ← solo si elegiste voces
├─ data/
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
  con el desplazamiento 0-3 / 4-7 que espera `mustHitSection`.
- Semana de Psych: array `songs` + `weekCharacters`; `hideStoryMode` activo para que aparezca solo
  en Freeplay y no necesite arte de título.

---

## 5. Estructura del código

```
app/src/main/java/com/fnfmodmaker/
├─ MainActivity.kt              arranque + tema
├─ model/ModConfig.kt           estado del formulario + reglas de nombres (incluye
│                               la réplica exacta de Psych: formatToSongPath)
├─ media/MediaTools.kt          copiar archivos, duración, FFmpeg → OGG, reescalar PNG
├─ mod/JsonBuilders.kt          los JSON de V-Slice y Psych
├─ mod/ModBuilder.kt            crea la carpeta del mod y el ZIP
└─ ui/
   ├─ Theme.kt                  colores tipo FNF
   ├─ WizardViewModel.kt        estado + validación + exportación
   └─ WizardScreens.kt          las 4 pantallas
```

Dependencias: Jetpack Compose (Material 3) y `ffmpeg-kit-audio` (recompilación comunitaria,
porque el proyecto original de FFmpegKit fue retirado). No se pide ningún permiso peligroso:
los archivos se leen y se guardan con el selector de Android (SAF).

---

## 6. Problemas conocidos

- **"Falta la instrumental"**: el paso 3 exige elegir el archivo; sin él no hay canción.
- **La canción no sale en Freeplay**: revisa que descomprimiste el ZIP dejando la carpeta del mod
  directamente dentro de `mods/` (no `mods/mi-mod/mi-mod/`).
- **El audio no suena o va desincronizado**: casi siempre es el BPM. Si tu canción tiene cambios
  de tempo, este asistente (una sola BPM) no basta.
- **"No se pudo convertir"**: el error de FFmpeg se muestra en pantalla. Si tu archivo es raro
  (DRM, opus dentro de ogg), expórtalo a WAV/MP3 y vuelve a intentarlo.
- **El APK no instala**: revisa que el teléfono sea ARM (todos los móviles normales lo son); el
  APK no incluye binarios x86 para emuladores.

## 7. Siguientes pasos sugeridos

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
