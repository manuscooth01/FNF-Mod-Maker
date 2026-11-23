package com.fnfmodmaker.model

import android.net.Uri
import com.fnfmodmaker.model.ChartDraft
import com.fnfmodmaker.model.StageDraft

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

/** Ranura de audio que se está eligiendo en el paso de audio. */
enum class AudioSlot { INSTRUMENTAL, VOICES_PLAYER, VOICES_OPPONENT }

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

/** Configuración de una canción individual dentro de un mod. */
data class SongConfig(
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
    val offsetMs: String = "0",
    val instrumental: PickedAudio? = null,
    val vocalsPlayer: PickedAudio? = null,
    val vocalsOpponent: PickedAudio? = null
) {
    val songFolder: String get() = psychSongPath(songName)
    val bpmValue: Int get() = bpm.toIntOrNull() ?: 0
    val speedValue: Float get() = speed.toFloatOrNull() ?: 0f
    val offsetValue: Int get() = offsetMs.toIntOrNull() ?: 0

    val orderedDifficulties: List<String>
        get() = ALL_DIFFICULTIES.filter { it in difficulties }.ifEmpty { listOf("normal") }

    val hasVocals: Boolean get() = vocalsPlayer != null || vocalsOpponent != null
    val hasSplitVocals: Boolean get() = vocalsPlayer != null && vocalsOpponent != null
}

/**
 * Toda la información que el usuario rellena en el asistente.
 * Ahora soporta varias canciones y semanas por mod.
 */
data class ModConfig(
    val engine: TargetEngine = TargetEngine.VSLICE,

    // Datos del mod
    val modTitle: String = "",
    val modId: String = "",
    val author: String = "",
    val description: String = "",

    // Canciones (una o varias semanas)
    val songs: List<SongConfig> = listOf(SongConfig()),

    // Color global del mod (se usa como fondo por defecto si la canción no define uno)
    val weekColor: String = "#C24B99",

    /** Si es true, las semanas también aparecen en el modo historia, no solo en Freeplay. */
    val showInStoryMode: Boolean = false,

    /** Personaje propio opcional (spritesheet + JSON) */
    val character: CharacterDraft = CharacterDraft(),

    /** Stage propio opcional (fondo, sprites, cámara) */
    val stageDraft: StageDraft = StageDraft(),

    // Archivos globales
    val icon: PickedImage? = null,

    // Charts editados por canción/dificultad (songFolder -> difficulty -> ChartDraft)
    val charts: Map<String, Map<String, ChartDraft>> = emptyMap(),
    // Editable project only; never emitted in engine mod ZIPs.
    val chartHistories: Map<String, Map<String, ChartPersistentHistory>> = emptyMap(),

    /** Scripts Lua de Psych aprobados por el usuario; nunca se ejecutan en la app. */
    val psychScripts: Map<String, String> = emptyMap()
) {
    val hasAnyVocals: Boolean get() = songs.any { it.hasVocals }

    /** Obtiene el chart editado para una canción y dificultad, o null si no se editó. */
    fun getChart(songFolder: String, difficulty: String): ChartDraft? =
        charts[songFolder]?.get(difficulty)

    /** Retorna un ModConfig con el chart actualizado. */
    fun withChart(songFolder: String, difficulty: String, chart: ChartDraft): ModConfig {
        val songCharts = charts.getOrDefault(songFolder, emptyMap()) + (difficulty to chart)
        return copy(charts = charts + (songFolder to songCharts))
    }

    /** Carpeta del mod: se usa como nombre de carpeta dentro de mods/. */
    val modFolder: String
        get() {
            val fromId = slugify(modId)
            if (fromId.isNotEmpty()) return fromId
            val fromTitle = slugify(modTitle)
            return fromTitle.ifEmpty { "mi-mod" }
        }

    /** Primera canción (compatibilidad con flujos simples). */
    val firstSong: SongConfig
        get() = songs.firstOrNull() ?: SongConfig()

    /** Nombre de carpetas de canción, una por cada canción. */
    val songFolders: List<String> get() = songs.map { it.songFolder }

    /** Nombre de carpetas de canción, una por cada canción. */
    val songNames: List<String> get() = songs.map { it.songName }

    /**
     * Si hay un personaje propio activo, el chart debe apuntar a él en el lado
     * elegido. Se aplica a todas las canciones.
     */
    fun withCharacter(): ModConfig {
        val draft = character
        if (!draft.enabled || songs.isEmpty()) return this
        val id = draft.charFolder
        val updated = songs.map { song ->
            val (player, opponent) = if (draft.side == CharacterSide.PLAYER) {
                id to song.opponent
            } else {
                song.player to id
            }
            song.copy(player = player, opponent = opponent)
        }
        return copy(songs = updated)
    }
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
