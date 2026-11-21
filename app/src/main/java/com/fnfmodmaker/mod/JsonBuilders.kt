package com.fnfmodmaker.mod

import com.fnfmodmaker.model.ModConfig
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
    fun vsliceMetadata(config: ModConfig, durationMs: Long): String {
        val bpm = config.bpmValue
        val ratings = JSONObject()
        val difficulties = JSONArray()
        val ratingFor = mapOf("easy" to 1, "normal" to 2, "hard" to 3)
        for (difficulty in config.orderedDifficulties) {
            difficulties.put(difficulty)
            ratings.put(difficulty, ratingFor[difficulty] ?: 1)
        }

        val characters = JSONObject()
            .put("player", config.player)
            .put("girlfriend", config.girlfriend)
            .put("opponent", config.opponent)
            .put("instrumental", "")

        val previewEnd = if (durationMs > 0L) min(15000L, durationMs) else 15000L

        val playData = JSONObject()
            .put("songVariations", JSONArray())
            .put("difficulties", difficulties)
            .put("ratings", ratings)
            .put("characters", characters)
            .put("stage", config.stageVSlice)
            .put("noteStyle", "funkin")
            .put("previewStart", 0)
            .put("previewEnd", previewEnd)

        val timeChange = JSONObject()
            .put("t", 0)
            .put("bpm", bpm)
            .put("n", 4)
            .put("d", 4)
            .put("bt", JSONArray().put(4).put(4).put(4).put(4))

        return JSONObject()
            .put("version", "2.2.4")
            .put("songName", config.songName.trim())
            .put("artist", config.artist.trim().ifEmpty { "Desconocido" })
            .put("charter", "FNF Mod Maker")
            .put("timeFormat", "ms")
            .put("offsets", JSONObject().put("instrumental", 0))
            .put("timeChanges", JSONArray().put(timeChange))
            .put("playData", playData)
            .put("generatedBy", GENERATED_BY)
            .toString(2)
    }

    /** `<song>-chart.json` (esquema 2.0.0) */
    fun vsliceChart(config: ModConfig, durationMs: Long): String {
        val scrollSpeed = JSONObject()
        val notes = JSONObject()
        for (difficulty in config.orderedDifficulties) {
            scrollSpeed.put(difficulty, config.speedValue)
            notes.put(difficulty, vsliceNotes(config, durationMs))
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
    private fun vsliceNotes(config: ModConfig, durationMs: Long): JSONArray {
        val notes = JSONArray()
        if (!config.practiceNotes) return notes
        val bpm = config.bpmValue
        if (bpm <= 0) return notes
        val beatMs = 60000.0 / bpm
        val beats = totalBeats(config, durationMs)
        for (beat in 0 until beats) {
            val isPlayerSide = beat % 2 == 0
            val lane = beat % 4
            val data = if (isPlayerSide) lane else lane + 4
            notes.put(
                JSONObject()
                    .put("t", (beat * beatMs).roundToLong())
                    .put("d", data)
                    .put("l", 0)
            )
        }
        return notes
    }

    /** `data/levels/<id>.json` (esquema 1.0.0) */
    fun vsliceLevel(config: ModConfig): String {
        return JSONObject()
            .put("version", "1.0.0")
            .put("name", config.modTitle.trim().ifEmpty { "Mi Mod" })
            .put("titleAsset", "storymenu/titles/week1")
            .put("props", JSONArray())
            .put("visible", true)
            .put("background", config.weekColor)
            .put("songs", JSONArray().put(config.songFolder))
            .toString(2)
    }

    /** `_polymod_meta.json` */
    fun polymodMeta(config: ModConfig): String {
        val contributors = JSONArray().put(
            JSONObject().put("name", config.author.trim().ifEmpty { "Anónimo" })
        )
        return JSONObject()
            .put("title", config.modTitle.trim().ifEmpty { "Mi Mod" })
            .put("description", config.description.trim().ifEmpty { "Mod creado con FNF Mod Maker" })
            .put("contributors", contributors)
            .put("api_version", "0.8.0")
            .put("mod_version", "1.0.0")
            .put("license", "CC-BY-4.0")
            .toString(2)
    }

    // ------------------------------------------------------------------
    // Psych Engine
    // ------------------------------------------------------------------

    /** Chart en formato psych_v1. */
    fun psychChart(config: ModConfig, durationMs: Long): String {
        val bpm = config.bpmValue
        val beatMs = if (bpm > 0) 60000.0 / bpm else 400.0
        val beats = totalBeats(config, durationMs)
        val sectionCount = max(1, (beats + 3) / 4)
        val sections = JSONArray()

        for (sectionIndex in 0 until sectionCount) {
            val mustHit = sectionIndex % 2 == 0
            val sectionNotes = JSONArray()

            if (config.practiceNotes) {
                for (offset in 0 until 4) {
                    val beat = sectionIndex * 4 + offset
                    if (beat >= beats) break
                    val isPlayerSide = beat % 2 == 0
                    val lane = beat % 4
                    // En psych_v1: 0-3 = mismo lado que mustHitSection, 4-7 = el contrario
                    val data = if (mustHit == isPlayerSide) lane else lane + 4
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
            .put("song", config.songName.trim())
            .put("notes", sections)
            .put("events", JSONArray())
            .put("bpm", if (bpm > 0) bpm else 150)
            .put("needsVoices", config.vocals != null)
            .put("speed", config.speedValue)
            .put("offset", 0)
            .put("player1", config.player)
            .put("player2", config.opponent)
            .put("gfVersion", config.girlfriend)
            .put("stage", config.stagePsych)
            .put("format", "psych_v1")
            .toString(2)
    }

    /** `weeks/<id>.json`: sin esto, Psych no muestra la canción en Freeplay. */
    fun psychWeek(config: ModConfig): String {
        val songEntry = JSONArray()
            .put(config.songName.trim())
            .put(config.opponent.trim().ifEmpty { "dad" })
            .put(rgbArray(config.weekColor))
        return JSONObject()
            .put("songs", JSONArray().put(songEntry))
            .put(
                "weekCharacters",
                JSONArray()
                    .put(config.opponent.trim().ifEmpty { "dad" })
                    .put(config.player.trim().ifEmpty { "bf" })
                    .put(config.girlfriend.trim().ifEmpty { "gf" })
            )
            .put("weekBackground", "stage")
            .put("weekBefore", "")
            .put("storyName", config.modTitle.trim().ifEmpty { "Mi Mod" })
            .put("weekName", config.modFolder)
            .put("startUnlocked", true)
            .put("hiddenUntilUnlocked", false)
            // De momento el mod aparece en Freeplay; para modo historia
            // hay que añadir un título propio y poner esto en false.
            .put("hideStoryMode", true)
            .put("hideFreeplay", false)
            .put("difficulties", "")
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

    private fun totalBeats(config: ModConfig, durationMs: Long): Int {
        val bpm = config.bpmValue
        if (bpm <= 0) return 32
        val beatMs = 60000.0 / bpm
        val counted = if (durationMs > 0L) (durationMs / beatMs).toInt() else 64
        return max(8, min(counted, 4000))
    }
}
