package com.fnfmodmaker.model

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Representa una nota en el chart.
 * timeMs: tiempo en milisegundos desde el inicio de la canción.
 * lane: 0-3 (lado jugador), 4-7 (lado oponente) - formato Psych/V-Slice unificado.
 * lengthMs: 0 = nota normal, >0 = nota sostenida (hold).
 * isSustain: true si es cola de hold note.
 */
data class ChartNote(
    val timeMs: Long = 0L,
    val lane: Int = 0,
    val lengthMs: Long = 0L,
    val isSustain: Boolean = false
) {
    val endTimeMs: Long get() = timeMs + lengthMs
    val isHold: Boolean get() = lengthMs > 0
}

/**
 * Chart editable para una canción y dificultad específica.
 * Se guarda como JSON y se usa para sobrescribir el chart generado.
 */
data class ChartDraft(
    val songFolder: String = "",
    val difficulty: String = "normal",
    val notes: List<ChartNote> = emptyList(),
    val scrollSpeed: Float = 2.0f,
    val bpm: Int = 150,
    val offsetMs: Int = 0,
    val needsVoices: Boolean = false,
    val player1: String = "bf",
    val player2: String = "dad",
    val gfVersion: String = "gf",
    val stage: String = "stage"
) {
    /** Convierte a JSON Psych Engine (formato psych_v1). */
    fun toPsychJson(): String {
        import org.json.JSONArray
        import org.json.JSONObject
        import kotlin.math.max
        import kotlin.math.roundToLong

        val beatMs = if (bpm > 0) 60000.0 / bpm else 400.0
        // Estimar beats totales desde la última nota
        val lastNoteTime = notes.maxByOrNull { it.endTimeMs }?.endTimeMs ?: 0L
        val beats = max(1, ((lastNoteTime / beatMs).toInt() + 3) / 4 * 4)
        val sectionCount = max(1, (beats + 3) / 4)
        val sections = JSONArray()

        for (sectionIndex in 0 until sectionCount) {
            val mustHit = sectionIndex % 2 == 0
            val sectionNotes = JSONArray()
            val sectionStartBeat = sectionIndex * 4
            val sectionEndBeat = sectionStartBeat + 4
            val sectionStartMs = (sectionStartBeat * beatMs).roundToLong()
            val sectionEndMs = (sectionEndBeat * beatMs).roundToLong()

            // Filtrar notas de esta sección
            val sectionNotesList = notes.filter { it.timeMs >= sectionStartMs && it.timeMs < sectionEndMs }
            
            for (note in sectionNotesList) {
                val relativeBeat = ((note.timeMs - sectionStartMs) / beatMs)
                val isPlayerSide = note.lane in 0..3
                val lane = note.lane % 4
                val data = if (mustHit == isPlayerSide) lane else lane + 4
                val sustain = if (note.isHold) (note.lengthMs / beatMs).roundToLong() else 0
                
                sectionNotes.put(
                    JSONArray()
                        .put((note.timeMs - sectionStartMs).roundToLong())
                        .put(data)
                        .put(sustain)
                )
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
            .put("song", songFolder)
            .put("notes", sections)
            .put("events", JSONArray())
            .put("bpm", if (bpm > 0) bpm else 150)
            .put("needsVoices", needsVoices)
            .put("speed", scrollSpeed)
            .put("offset", offsetMs)
            .put("player1", player1)
            .put("player2", player2)
            .put("gfVersion", gfVersion)
            .put("stage", stage)
            .put("format", "psych_v1")
            .toString(2)
    }

    /** Convierte a JSON V-Slice (esquema 2.0.0). */
    fun toVSliceJson(): String {
        import org.json.JSONArray
        import org.json.JSONObject

        val scrollSpeed = JSONObject().put(difficulty, scrollSpeed)
        val notesJson = JSONObject()
        val notesArray = JSONArray()
        
        for (note in notes) {
            val data = if (note.lane in 0..3) note.lane else note.lane - 4
            notesArray.put(
                JSONObject()
                    .put("t", note.timeMs)
                    .put("d", data)
                    .put("l", if (note.isHold) 1 else 0)
            )
        }
        notesJson.put(difficulty, notesArray)

        return JSONObject()
            .put("version", "2.0.0")
            .put("scrollSpeed", scrollSpeed)
            .put("events", JSONArray())
            .put("notes", notesJson)
            .put("generatedBy", "FNF Mod Maker Chart Editor")
            .toString(2)
    }

    /** Convierte solo las notas a JSONArray para V-Slice (una dificultad). */
    fun toVSliceNotes(): JSONArray {
        import org.json.JSONArray
        import org.json.JSONObject
        val notesArray = JSONArray()
        for (note in notes) {
            val data = if (note.lane in 0..3) note.lane else note.lane - 4
            notesArray.put(
                JSONObject()
                    .put("t", note.timeMs)
                    .put("d", data)
                    .put("l", if (note.isHold) 1 else 0)
            )
        }
        return notesArray
    }
}

/** Estado del editor de chart para una canción. */
data class ChartEditorState(
    val songFolder: String = "",
    val currentDifficulty: String = "normal",
    val zoom: Float = 1.0f,
    val scrollX: Float = 0f,
    val isPlaying: Boolean = false,
    val currentTimeMs: Long = 0L,
    val selectionStartMs: Long? = null,
    val selectionEndMs: Long? = null
) {
    val visibleTimeRangeMs: Long get() = (4000 / zoom).toLong() // 4 segundos base
    val startTimeMs: Long get() = (scrollX * 100).toLong() // aprox
}