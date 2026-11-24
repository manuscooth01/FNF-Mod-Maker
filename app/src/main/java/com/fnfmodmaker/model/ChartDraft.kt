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
    val isSustain: Boolean = false,
    // Engine-specific note type (V-Slice k / Psych noteType); empty means ordinary note.
    val kind: String = "",
    // Exact JSON token for a numeric, explicit empty-string, or null Psych type. Empty means no extra token.
    val psychTypeJson: String = ""
) {
    val endTimeMs: Long get() = timeMs + lengthMs
    val isHold: Boolean get() = lengthMs > 0
}

/** Validate opaque Psych note-type tokens without mapping numbers to default engine names. */
internal fun psychNoteTypeToken(note: ChartNote): com.google.gson.JsonElement? {
    val raw = note.psychTypeJson
    if (raw.isEmpty()) return null
    require(note.kind.isEmpty() && raw.length <= 128) { "Tipo Psych ambiguo o demasiado largo." }
    require(raw == "null" || raw == "\"\"" || raw.matches(Regex("-?(0|[1-9][0-9]*)(\\.[0-9]+)?([eE][+-]?[0-9]+)?"))) { "Tipo numérico Psych inválido." }
    return com.google.gson.JsonParser.parseString(raw)
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
    val bpm: Double = 150.0,
    val offsetMs: Int = 0,
    val needsVoices: Boolean = false,
    val player1: String = "bf",
    val player2: String = "dad",
    val gfVersion: String = "gf",
    val stage: String = "stage",
    /** Exact Psych event groups. Empty string denotes an empty list in older projects. */
    val psychEventsJson: String = "",
    val psychLayout: List<PsychSectionData> = emptyList(),
    val psychSongExtras: String = ""
) {
    /** Current exporters have no mapping for these fields; never discard them silently. */
    fun exportProblems(): List<String> = buildList {
        if (events.isNotEmpty()) add("eventos")
        if (psychLayout.isNotEmpty() && runCatching { psychSectionSpans(psychLayout, bpm) }.isFailure) add("secciones Psych inválidas")
        if (psychSongExtras.isNotBlank() && (psychLayout.isEmpty() || runCatching {
            val obj = psychObject(psychSongExtras, "propiedades Psych")
            require(obj.keySet().none { it in setOf("notes", "events", "bpm", "needsVoices", "speed", "offset", "player1", "player2", "gfVersion", "stage", "format") })
            obj.get("song")?.let { require(it.isJsonPrimitive && it.asJsonPrimitive.isString) }
        }.isFailure)) add("propiedades Psych inválidas")
        if (psychEventsJson.isNotBlank() && runCatching { com.fnfmodmaker.mod.validatePsychEvents(psychEventsJson) }.isFailure) add("eventos Psych inválidos")
        if (sections.isNotEmpty()) add("secciones personalizadas")
        if (notes.any { it.kind.length > 128 || it.kind.any(Char::isISOControl) || runCatching { psychNoteTypeToken(it) }.isFailure }) add("tipos de nota inválidos")
        if (notes.any { it.isSustain }) add("notas de cola independientes")
        if (notes.any { it.lane !in 0..7 || it.timeMs < 0 || it.lengthMs < 0 || it.timeMs > Long.MAX_VALUE - it.lengthMs }) add("notas inválidas")
    }

    fun requireExportable() {
        require(exportProblems().isEmpty()) { "El chart contiene datos que no se pueden exportar sin pérdida: ${exportProblems().joinToString()}. Conserva el proyecto editable." }
    }

    /** Convierte a JSON Psych Engine (formato psych_v1). */
    fun toPsychJson(): String {
        require(bpm.isFinite() && bpm in 1.0..400.0) { "BPM inicial Psych inválido; no se exportó el chart." }
        requireExportable()
        if (psychLayout.isNotEmpty()) return psychSectionsToJson(this)
        val beatMs = if (bpm > 0) 60000.0 / bpm else 400.0
        // Estimar beats totales desde la última nota
        val lastNoteTime = notes.maxByOrNull { it.endTimeMs }?.endTimeMs ?: 0L
        // A note exactly on a section boundary belongs to the next section.
        val beats = Math.max(1, (lastNoteTime / beatMs).toInt() + 1)
        val sectionCount = Math.max(1, (beats + 3) / 4)
        val sections = JSONArray()

        for (sectionIndex in 0 until sectionCount) {
            val mustHit = sectionIndex % 2 == 0
            val sectionNotes = JSONArray()
            val sectionStartBeat = sectionIndex * 4
            val sectionEndBeat = sectionStartBeat + 4
            val sectionStartMs = sectionStartBeat * beatMs
            val sectionEndMs = sectionEndBeat * beatMs

            // Filtrar notas de esta sección
            val sectionNotesList = notes.filter { it.timeMs >= sectionStartMs && it.timeMs < sectionEndMs }
            
            for (note in sectionNotesList) {
                val isPlayerSide = note.lane in 0..3
                val lane = note.lane % 4
                // psych_v1 stores the absolute player/opponent lane, independent of mustHitSection.
                val data = if (isPlayerSide) lane else lane + 4
                val sustain = note.lengthMs.coerceAtLeast(0L)
                
                sectionNotes.put(
                    JSONArray()
                        .put(note.timeMs)
                        .put(data)
                        .put(sustain).apply {
                            val token = psychNoteTypeToken(note)
                            if (token != null) put(0) // Replaced with the exact Gson token below.
                            else if (note.kind.isNotEmpty()) put(note.kind)
                        }
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

        val json = JSONObject()
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
        // Gson retains the original event number tokens instead of rounding them through org.json.
        val tree = com.google.gson.JsonParser.parseString(json.toString()).asJsonObject
        // JSONObject numbers can normalize or round opaque type identifiers. Restore exact
        // Gson tokens after constructing the ordinary generated section structure.
        val generatedSections = tree.getAsJsonArray("notes")
        for (sectionIndex in 0 until sectionCount) {
            val start = sectionIndex * 4 * beatMs; val end = (sectionIndex + 1) * 4 * beatMs
            val sectionNotes = notes.filter { it.timeMs >= start && it.timeMs < end }
            val rows = generatedSections[sectionIndex].asJsonObject.getAsJsonArray("sectionNotes")
            sectionNotes.forEachIndexed { i, note -> psychNoteTypeToken(note)?.let { rows[i].asJsonArray.set(3, it) } }
        }
        val events = if (psychEventsJson.isBlank()) "[]" else com.fnfmodmaker.mod.validatePsychEvents(psychEventsJson)
        tree.add("events", com.google.gson.JsonParser.parseString(events))
        return tree.toString()
    }

    /** Convierte a JSON V-Slice (esquema 2.0.0). */
    fun toVSliceJson(): String {
        requireExportable()
        require(notes.none { it.psychTypeJson.isNotEmpty() }) { "V-Slice no admite tipos Psych numéricos o explícitos sin conversión reversible." }
        require(psychLayout.isEmpty() && psychSongExtras.isBlank() && (psychEventsJson.isBlank() || psychEventsJson == "[]")) { "Los datos Psych no se traducen automáticamente a V-Slice." }
        val scrollSpeed = JSONObject().put(difficulty, scrollSpeed)
        val notesJson = JSONObject()
        val notesArray = JSONArray()
        
        for (note in notes) {
            val data = note.lane
            notesArray.put(
                JSONObject()
                    .put("t", note.timeMs)
                    .put("d", data)
                    .put("l", note.lengthMs.coerceAtLeast(0L)).apply { if (note.kind.isNotEmpty()) put("k", note.kind) }
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
    fun toVSliceNotes(preservePsychSeparately: Boolean = false): JSONArray {
        requireExportable()
        require(notes.none { it.psychTypeJson.isNotEmpty() }) { "V-Slice no admite tipos Psych numéricos o explícitos sin conversión reversible." }
        require(preservePsychSeparately || (psychLayout.isEmpty() && psychSongExtras.isBlank() && (psychEventsJson.isBlank() || psychEventsJson == "[]"))) { "Los datos Psych no se traducen automáticamente a V-Slice." }
        val notesArray = JSONArray()
        for (note in notes) {
            val data = note.lane
            notesArray.put(
                JSONObject()
                    .put("t", note.timeMs)
                    .put("d", data)
                    .put("l", note.lengthMs.coerceAtLeast(0L)).apply { if (note.kind.isNotEmpty()) put("k", note.kind) }
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