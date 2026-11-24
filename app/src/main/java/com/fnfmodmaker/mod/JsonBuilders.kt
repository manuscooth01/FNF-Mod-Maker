package com.fnfmodmaker.mod

import com.fnfmodmaker.model.ChartDraft
import com.fnfmodmaker.model.ModConfig
import com.fnfmodmaker.model.SongConfig
import com.fnfmodmaker.model.hexToRgb
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToLong

/**
 * Construye los JSON que entienden V-Slice y Psych Engine.
 * Los formatos están calcados de los archivos reales que usa cada juego.
 */
object JsonBuilders {

    private const val GENERATED_BY = "FNF Mod Maker 1.0.0"

    // ------------------------------------------------------------------
    // V-Slice (FNF 0.8.x de Android)
    // ------------------------------------------------------------------

    /** `<song>-metadata.json` (esquema 2.2.4) */
    fun vsliceMetadata(song: SongConfig, durationMs: Long): String {
        val bpm = song.bpmValue
        val ratings = JSONObject()
        val difficulties = JSONArray()
        val ratingFor = mapOf("easy" to 1, "normal" to 2, "hard" to 3)
        for (difficulty in song.orderedDifficulties) {
            difficulties.put(difficulty)
            ratings.put(difficulty, ratingFor[difficulty] ?: 1)
        }

        val characters = JSONObject()
            .put("player", song.player)
            .put("girlfriend", song.girlfriend)
            .put("opponent", song.opponent)
            .put("instrumental", "")

        val previewEnd = if (durationMs > 0L) min(15000L, durationMs) else 15000L

        val playData = JSONObject()
            .put("songVariations", JSONArray())
            .put("difficulties", difficulties)
            .put("ratings", ratings)
            .put("characters", characters)
            .put("stage", song.stageVSlice)
            .put("noteStyle", "funkin")
            .put("previewStart", 0)
            .put("previewEnd", previewEnd)

        val timeChange = JSONObject()
            .put("t", 0)
            .put("bpm", bpm)
            .put("n", 4)
            .put("d", 4)
            .put("bt", JSONArray().put(4).put(4).put(4).put(4))

        val metadata = JSONObject()
            .put("version", "2.2.4")
            .put("songName", song.songName.trim())
            .put("artist", song.artist.trim().ifEmpty { "Desconocido" })
            .put("charter", "FNF Mod Maker")
            .put("timeFormat", "ms")
            .put("offsets", JSONObject().put("instrumental", song.offsetValue))
            .put("timeChanges", JSONArray().put(timeChange))
            .put("playData", playData)
            .put("generatedBy", GENERATED_BY)
        val tree = com.google.gson.JsonParser.parseString(metadata.toString()).asJsonObject
        song.vSliceTempoJson?.let { tree.add("timeChanges", com.google.gson.JsonParser.parseString(if (song.vSliceIndependentInitialBpm) validateVSliceTempo(it) else tempoWithInitialBpm(it, song.bpmValue))) }
        return tree.toString()
    }

    /** `<song>-chart.json` (esquema 2.0.0) - usa chart editado si existe. */
    fun vsliceChart(config: ModConfig, song: SongConfig, durationMs: Long): String {
        val scrollSpeed = JSONObject()
        val notes = JSONObject()
        for (difficulty in song.orderedDifficulties) {
            scrollSpeed.put(difficulty, song.speedValue)
            val customChart = config.getChart(song.songFolder, difficulty)
            notes.put(difficulty, if (customChart != null) {
                customChart.toVSliceNotes(preservePsychSeparately = config.engine.includesPsych)
            } else {
                vsliceNotes(song, durationMs)
            })
        }
        val result = JSONObject()
            .put("version", "2.0.0")
            .put("scrollSpeed", scrollSpeed)
            .put("events", JSONArray())
            .put("notes", notes)
            .put("generatedBy", GENERATED_BY)
        val tree = com.google.gson.JsonParser.parseString(result.toString()).asJsonObject
        song.vSliceEventsJson?.let { tree.add("events", com.google.gson.JsonParser.parseString(validateVSliceEvents(it))) }
        return tree.toString()
    }

    /** Versión legacy sin ModConfig (para compatibilidad). */
    fun vsliceChart(song: SongConfig, durationMs: Long): String {
        val scrollSpeed = JSONObject()
        val notes = JSONObject()
        for (difficulty in song.orderedDifficulties) {
            scrollSpeed.put(difficulty, song.speedValue)
            notes.put(difficulty, vsliceNotes(song, durationMs))
        }
        return JSONObject()
            .put("version", "2.0.0")
            .put("scrollSpeed", scrollSpeed)
            .put("events", JSONArray())
            .put("notes", notes)
            .put("generatedBy", GENERATED_BY)
            .toString(2)
    }

    /**
     * Notas de V-Slice: "d" 0-3 = lado del jugador, 4-7 = lado del oponente.
     * Si el usuario desactiva las notas de práctica, el chart va vacío.
     */
    private fun vsliceNotes(song: SongConfig, durationMs: Long): JSONArray {
        val notes = JSONArray()
        if (!song.practiceNotes) return notes
        val bpm = song.bpmValue
        if (bpm <= 0) return notes
        val beatMs = 60000.0 / bpm
        val tempoGrid = song.vSliceTempoJson?.let { text ->
            val source = if (song.vSliceIndependentInitialBpm) validateVSliceTempo(text) else tempoWithInitialBpm(text, bpm)
            com.fnfmodmaker.model.TouchGrid(bpm, 1, durationMs.coerceAtLeast(1L), vSliceGridTempos(source))
        }
        val beats = if (tempoGrid != null && durationMs > 0) max(8, min(tempoGrid.beatAt(durationMs.toDouble()).toInt(), 4000)) else totalBeats(song, durationMs)
        for (beat in 0 until beats) {
            val isPlayerSide = beat % 2 == 0
            val lane = beat % 4
            val data = if (isPlayerSide) lane else lane + 4
            notes.put(
                JSONObject()
                    .put("t", (tempoGrid?.timeAtBeat(beat.toDouble()) ?: (beat * beatMs)).roundToLong())
                    .put("d", data)
                    .put("l", 0)
            )
        }
        return notes
    }

    /** `data/levels/<mod>.json` (esquema 1.0.0) */
    fun vsliceLevel(song: SongConfig, modFolder: String, modTitle: String, showInStoryMode: Boolean): String {
        return JSONObject()
            .put("version", "1.0.0")
            .put("name", modTitle.trim().ifEmpty { "Mi Mod" })
            // La imagen la genera la app (359x89), igual que los títulos del juego.
            .put("titleAsset", "storymenu/titles/$modFolder")
            .put("props", JSONArray())
            .put("visible", showInStoryMode)
            .put("background", song.weekColor)
            .put("songs", JSONArray().put(song.songFolder))
            .toString(2)
    }

    /** `_polymod_meta.json` */
    fun polymodMeta(config: ModConfig): String {
        val contributors = JSONArray().put(
            JSONObject()
                .put("name", config.author.trim().ifEmpty { "Anónimo" })
                .put("role", "Creador del mod")
        )
        return JSONObject()
            .put("title", config.modTitle.trim().ifEmpty { "Mi Mod" })
            .put("description", config.description.trim().ifEmpty { "Mod creado con FNF Mod Maker" })
            .put("contributors", contributors)
            .put("api_version", "0.8.0")
            .put("mod_version", "1.0.0")
            .toString(2)
    }

    // ------------------------------------------------------------------
    // Psych Engine
    // ------------------------------------------------------------------

    /** Chart en formato psych_v1 - usa chart editado si existe. */
    fun psychChart(config: ModConfig, song: SongConfig, durationMs: Long,
        difficulty: String = song.orderedDifficulties.firstOrNull() ?: "normal"): String {
        val customChart = config.getChart(song.songFolder, difficulty)
        if (customChart != null) {
            // Imported difficulties can have independent speed, offset and needsVoices.
            // Preserve them unless the shared song control or the voice assets actually changed.
            val imported = song.importedPsychFolder.isNotEmpty()
            val base = if (imported) config.getChart(song.songFolder, "normal")
                ?: song.orderedDifficulties.firstNotNullOfOrNull { config.getChart(song.songFolder, it) } else null
            val originalVoice = imported && config.importedModResources.any { it.originalPath == "songs/${song.importedPsychFolder}/Voices.ogg" }
            val speed = if (base != null && song.speedValue == base.scrollSpeed) customChart.scrollSpeed else song.speedValue
            val offset = if (base != null && song.offsetValue == base.offsetMs) customChart.offsetMs else song.offsetValue
            val voices = if (imported && song.hasVocals == originalVoice) customChart.needsVoices else song.hasVocals
            val originalName = if (imported && song.songName.trim() == song.importedPsychFolder && customChart.psychSongExtras.isNotBlank()) {
                com.fnfmodmaker.model.psychObject(customChart.psychSongExtras, "propiedades Psych").get("song")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
            } else null
            val selectedName = originalName ?: song.songName.trim()
            val extras = if (customChart.psychSongExtras.isNotBlank()) com.fnfmodmaker.model.psychObject(customChart.psychSongExtras, "propiedades Psych").apply { addProperty("song", selectedName) }.toString() else ""
            val json = customChart.copy(songFolder = selectedName, psychSongExtras = extras, bpm = song.bpmValue, scrollSpeed = speed,
                offsetMs = offset, needsVoices = voices, player1 = song.player,
                player2 = song.opponent, gfVersion = song.girlfriend, stage = song.stagePsych).toPsychJson()
            return if (config.psychChartFormat == com.fnfmodmaker.model.PsychChartFormat.PSYCH_073) psych073Chart(json) else json
        }
        val json = psychChartGenerated(song, durationMs)
        return if (config.psychChartFormat == com.fnfmodmaker.model.PsychChartFormat.PSYCH_073) psych073Chart(json) else json
    }

    /** Psych 0.7.3 reads Json.parse(raw).song and treats lanes as relative to mustHitSection. */
    internal fun psych073Chart(v1: String): String {
        val song = com.google.gson.JsonParser.parseString(v1).asJsonObject
        song.getAsJsonArray("notes").forEach { item ->
            val section = item.asJsonObject
            val mustHit = section.get("mustHitSection")?.asBoolean ?: false
            section.getAsJsonArray("sectionNotes").forEach { note ->
                val row = note.asJsonArray
                val lane = row[1].asInt
                require(lane in 0..7) { "Carril Psych inválido." }
                if (!mustHit) row.set(1, com.google.gson.JsonPrimitive((lane + 4) % 8))
            }
        }
        song.remove("format")
        return com.google.gson.JsonObject().apply { add("song", song) }.toString()
    }

    /** Versión legacy sin ModConfig (para compatibilidad). */
    fun psychChart(song: SongConfig, durationMs: Long): String = psychChartGenerated(song, durationMs)

    private fun psychChartGenerated(song: SongConfig, durationMs: Long): String {
        val bpm = song.bpmValue
        val beatMs = if (bpm > 0) 60000.0 / bpm else 400.0
        val beats = totalBeats(song, durationMs)
        val sectionCount = max(1, (beats + 3) / 4)
        val sections = JSONArray()

        for (sectionIndex in 0 until sectionCount) {
            val mustHit = sectionIndex % 2 == 0
            val sectionNotes = JSONArray()

            if (song.practiceNotes) {
                for (offset in 0 until 4) {
                    val beat = sectionIndex * 4 + offset
                    if (beat >= beats) break
                    val isPlayerSide = beat % 2 == 0
                    val lane = beat % 4
                    // psych_v1 stores absolute lanes (0–3 player, 4–7 opponent).
                    val data = if (isPlayerSide) lane else lane + 4
                    sectionNotes.put(
                        JSONArray()
                            .put((beat * beatMs).roundToLong())
                            .put(data)
                            .put(0)
                    )
                }
            }

            sections.put(
                JSONObject()
                    .put("sectionNotes", sectionNotes)
                    .put("sectionBeats", 4)
                    .put("mustHitSection", mustHit)
                    .put("altAnim", false)
                    .put("gfSection", false)
                    .put("bpm", if (bpm > 0) bpm else 150)
                    .put("changeBPM", false)
            )
        }

        return JSONObject()
            .put("song", song.songName.trim())
            .put("notes", sections)
            .put("events", JSONArray())
            .put("bpm", if (bpm > 0) bpm else 150)
            .put("needsVoices", song.hasVocals)
            .put("speed", song.speedValue)
            .put("offset", song.offsetValue)
            .put("player1", song.player)
            .put("player2", song.opponent)
            .put("gfVersion", song.girlfriend)
            .put("stage", song.stagePsych)
            .put("format", "psych_v1")
            .toString(2)
    }

    /** `weeks/<mod>.json`: sin esto, Psych no muestra la canción en Freeplay. */
    fun psychWeek(
        song: SongConfig,
        modFolder: String,
        modTitle: String,
        showInStoryMode: Boolean,
        player: String,
        girlfriend: String
    ): String {
        val songEntry = JSONArray()
            .put(song.songName.trim())
            .put(song.opponent.trim().ifEmpty { "dad" })
            .put(rgbArray(song.weekColor))
        return JSONObject()
            .put("songs", JSONArray().put(songEntry))
            .put(
                "weekCharacters",
                JSONArray()
                    .put(song.opponent.trim().ifEmpty { "dad" })
                    .put(player.trim().ifEmpty { "bf" })
                    .put(girlfriend.trim().ifEmpty { "gf" })
            )
            // Cadena vacía = el menú de historia no carga imagen de fondo
            // (Psych lo contempla explícitamente y la oculta).
            .put("weekBackground", "")
            .put("weekBefore", "")
            .put("storyName", modTitle.trim().ifEmpty { "Mi Mod" })
            .put("weekName", modFolder)
            .put("startUnlocked", true)
            .put("hiddenUntilUnlocked", false)
            .put("hideStoryMode", !showInStoryMode)
            .put("hideFreeplay", false)
            // Psych lista las dificultades a nivel global; si la semana no las
            // declara, ofrece también las que no tienen chart. Con esto solo
            // aparecen las que realmente generamos.
            .put("difficulties", song.orderedDifficulties.joinToString(",") { name ->
                name.replaceFirstChar { it.uppercase() }
            })
            .toString(2)
    }

    /** `pack.json` del mod (nombre, autor visual, color). */
    fun psychPack(config: ModConfig): String {
        return JSONObject()
            .put("name", config.modTitle.trim().ifEmpty { "Mi Mod" })
            .put("description", config.description.trim().ifEmpty { "Mod creado con FNF Mod Maker" })
            .put("restart", false)
            .put("runsGlobally", false)
            .put("color", rgbArray(config.weekColor))
            .toString(2)
    }

    // ------------------------------------------------------------------

    private fun rgbArray(hex: String): JSONArray {
        val array = JSONArray()
        for (channel in hexToRgb(hex)) array.put(channel)
        return array
    }

    private fun totalBeats(song: SongConfig, durationMs: Long): Int {
        val bpm = song.bpmValue
        if (bpm <= 0) return 32
        val beatMs = 60000.0 / bpm
        val counted = if (durationMs > 0L) (durationMs / beatMs).toInt() else 64
        return max(8, min(counted, 4000))
    }
}