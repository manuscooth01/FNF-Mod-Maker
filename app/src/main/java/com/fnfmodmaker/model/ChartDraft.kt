package com.fnfmodmaker.model

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.lang.Math.max
import java.lang.Math.min

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

/** Evento de chart (cámara, BPM change, speed change, etc.). */
data class ChartEvent(
    val timeMs: Long = 0L,
    val type: EventType = EventType.CAMERA,
    val value1: Float = 0f,  // zoom, bpm, speed, etc.
    val value2: Float = 0f,  // param adicional
    val value3: Float = 0f,  // param adicional
    val durationMs: Long = 0L // para transiciones
) {
    enum class EventType(val label: String) {
        CAMERA("Cámara"),
        BPM_CHANGE("Cambio BPM"),
        SPEED_CHANGE("Cambio Speed"),
        CAMERA_SHAKE("Shake Cámara"),
        FLASH("Flash"),
        BG_COLOR("Color Fondo"),
        CUSTOM("Personalizado")
    }
}

/** Sección de chart (para Psych: mustHit, altAnim, gfSection). */
data class ChartSection(
    val startTimeMs: Long = 0L,
    val lengthMs: Long = 0L,
    val mustHit: Boolean = true,
    val altAnim: Boolean = false,
    val gfSection: Boolean = false,
    val bpm: Float = 150f,
    val speed: Float = 2.0f
)

/**
 * Chart editable para una canción y dificultad específica.
 * Se guarda como JSON y se usa para sobrescribir el chart generado.
 */
data class ChartDraft(
    val songFolder: String = "",
    val difficulty: String = "normal",
    val notes: List<ChartNote> = emptyList(),
    val events: List<ChartEvent> = emptyList(),
    val sections: List<ChartSection> = emptyList(),
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
        val beatMs = if (bpm > 0) 60000.0 / bpm else 400.0
        // Estimar beats totales desde la última nota
        val lastNoteTime = notes.maxByOrNull { it.endTimeMs }?.endTimeMs ?: 0L
        val beats = Math.max(1, ((lastNoteTime / beatMs).toInt() + 3) / 4 * 4)
        val sectionCount = Math.max(1, (beats + 3) / 4)
        val sections = JSONArray()

        for (sectionIndex in 0 until sectionCount) {
            val mustHit = sectionIndex % 2 == 0
            val sectionNotes = JSONArray()
            val sectionStartBeat = sectionIndex * 4
            val sectionEndBeat = sectionStartBeat + 4
            val sectionStartMs = (sectionStartBeat * beatMs).toLong()
            val sectionEndMs = (sectionEndBeat * beatMs).toLong()

            // Filtrar notas de esta sección
            val sectionNotesList = notes.filter { it.timeMs >= sectionStartMs && it.timeMs < sectionEndMs }
            
            for (note in sectionNotesList) {
                val relativeBeat = ((note.timeMs - sectionStartMs) / beatMs)
                val isPlayerSide = note.lane in 0..3
                val lane = note.lane % 4
                val data = if (mustHit == isPlayerSide) lane else lane + 4
                val sustain = if (note.isHold) (note.lengthMs / beatMs).toLong() else 0
                
                sectionNotes.put(
                    JSONArray()
                        .put((note.timeMs - sectionStartMs).toLong())
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
    val selectionEndMs: Long? = null,
    val selectedNotes: List<SelectedNote> = emptyList(),
    val dragState: DragState? = null,
    // Historial para undo/redo
    val history: List<ChartDraft> = emptyList(),
    val historyIndex: Int = -1,
    // Snap configurado
    val snapResolution: SnapResolution = SnapResolution.SIXTEENTH
) {
    val visibleTimeRangeMs: Long get() = (4000 / zoom).toLong()
    val startTimeMs: Long get() = (scrollX * 100).toLong() // aprox
    val canUndo: Boolean get() = historyIndex > 0
    val canRedo: Boolean get() = historyIndex < history.size - 1
}

/** Resolución de snap (subdivisiones por beat). */
enum class SnapResolution(val label: String, val divisionsPerBeat: Int) {
    QUARTER("1/4", 4),
    EIGHTH("1/8", 8),
    SIXTEENTH("1/16", 16),
    THIRTYSECOND("1/32", 32)
}

/** Nota seleccionada en el editor (para multi-selección). */
data class SelectedNote(
    val timeMs: Long,
    val lane: Int,
    val noteIndex: Int // índice en la lista de notas del draft
)

/** Estado de drag activo. */
sealed interface DragState {
    data class Scroll(val startX: Float, val startScroll: Float) : DragState
    data class HoldResize(val noteIndex: Int, val startTimeMs: Long, val lane: Int, val startLengthMs: Long, val dragStartX: Float) : DragState
    data class SelectionBox(val startX: Float, val startY: Float) : DragState
    data class MultiDrag(val selectedNotes: List<SelectedNote>, val dragStartX: Float, val dragStartY: Float) : DragState
}