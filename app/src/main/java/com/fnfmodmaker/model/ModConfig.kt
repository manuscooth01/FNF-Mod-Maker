package com.fnfmodmaker.model

import android.net.Uri

/** Motor del juego para el que se genera el mod. */
enum class TargetEngine(val label: String, val description: String) {
    VSLICE(
        "V-Slice (FNF móvil)",
        "FNF 0.8.x de Android. Chart en data/songs + nivel en data/levels."
    ),
    PSYCH(
        "Psych Engine",
        "Psych Engine de Android y PC. Chart en data + semana en weeks."
    ),
    BOTH(
        "Ambos",
        "Genera las dos estructuras en la misma carpeta. Funciona en los dos juegos."
    );

    val includesVSlice: Boolean get() = this == VSLICE || this == BOTH
    val includesPsych: Boolean get() = this == PSYCH || this == BOTH
}

val ALL_DIFFICULTIES: List<String> = listOf("easy", "normal", "hard")

/** Archivo copiado a la caché de la app, listo para procesar. */
data class PickedAudio(
    val uri: Uri,
    val displayName: String,
    val path: String,
    val durationMs: Long = 0L
) {
    val isOgg: Boolean
        get() = displayName.endsWith(".ogg", ignoreCase = true) || path.endsWith(".ogg", ignoreCase = true)

    val prettyDuration: String
        get() {
            if (durationMs <= 0L) return "duración desconocida"
            val totalSeconds = durationMs / 1000
            val minutes = totalSeconds / 60
            val seconds = totalSeconds % 60
            return "%d:%02d".format(minutes, seconds)
        }
}

data class PickedImage(
    val uri: Uri,
    val displayName: String,
    val path: String
)

/**
 * Toda la información que el usuario rellena en el asistente.
 * Los campos de texto numéricos se guardan como String para poder
 * validarlos sin que el teclado se resista.
 */
data class ModConfig(
    val engine: TargetEngine = TargetEngine.VSLICE,

    // Datos del mod
    val modTitle: String = "",
    val modId: String = "",
    val author: String = "",
    val description: String = "",

    // Datos de la canción
    val songName: String = "",
    val artist: String = "",
    val bpm: String = "150",
    val speed: String = "2.0",
    val player: String = "bf",
    val girlfriend: String = "gf",
    val opponent: String = "dad",
    val stageVSlice: String = "mainStage",
    val stagePsych: String = "stage",
    val weekColor: String = "#C24B99",
    val difficulties: Set<String> = setOf("easy", "normal", "hard"),
    val practiceNotes: Boolean = true,

    // Archivos
    val instrumental: PickedAudio? = null,
    val vocals: PickedAudio? = null,
    val icon: PickedImage? = null
) {
    /** Carpeta del mod: se usa como nombre de carpeta dentro de mods/. */
    val modFolder: String
        get() {
            val fromId = slugify(modId)
            if (fromId.isNotEmpty()) return fromId
            val fromTitle = slugify(modTitle)
            return fromTitle.ifEmpty { "mi-mod" }
        }

    /**
     * Carpeta/ID de la canción. Psych resuelve el chart con
     * formatToSongPath(nombreDeLaCanción), así que este valor NO puede ser
     * arbitrario: se deriva siempre del nombre visible.
     */
    val songFolder: String get() = psychSongPath(songName)

    val bpmValue: Int get() = bpm.toIntOrNull() ?: 0
    val speedValue: Float get() = speed.toFloatOrNull() ?: 0f

    val orderedDifficulties: List<String>
        get() = ALL_DIFFICULTIES.filter { it in difficulties }.ifEmpty { listOf("normal") }
}

/** Slug ASCII para nombres de carpeta del mod. */
fun slugify(raw: String): String {
    val lower = raw.trim().lowercase()
    val builder = StringBuilder()
    for (ch in lower) {
        when {
            ch in 'a'..'z' || ch in '0'..'9' -> builder.append(ch)
            ch == ' ' || ch == '-' || ch == '_' || ch == '.' -> {
                if (builder.isNotEmpty() && builder.last() != '-') builder.append('-')
            }
            else -> Unit
        }
    }
    return builder.toString().trim('-')
}

/**
 * Réplica exacta de Psych Engine: Paths.formatToSongPath().
 * Reemplaza [~&;:<>#\s] por "-", elimina [.,'"%?!], y pasa a minúsculas.
 */
fun psychSongPath(raw: String): String {
    var value = raw.trim()
    value = Regex("[~&;:<>#\\s]").replace(value, "-")
    value = Regex("[.,'\"%?!]").replace(value, "")
    value = value.trim().lowercase()
    return value.ifEmpty { "song" }
}

/** "#C24B99" -> [194, 75, 153] */
fun hexToRgb(hex: String): List<Int> {
    val clean = hex.trim().removePrefix("#")
    if (clean.length != 6) return listOf(194, 75, 153)
    return try {
        listOf(
            clean.substring(0, 2).toInt(16),
            clean.substring(2, 4).toInt(16),
            clean.substring(4, 6).toInt(16)
        )
    } catch (t: Throwable) {
        listOf(194, 75, 153)
    }
}
