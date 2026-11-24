package com.fnfmodmaker.model

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlin.math.floor
import kotlin.math.roundToLong

/** Section start and length are derived from the preceding sections and the initial BPM. */
data class PsychSectionData(
    val beats: Double = 4.0,
    val mustHit: Boolean = true,
    val altAnim: Boolean = false,
    val gfSection: Boolean = false,
    val bpm: Double = 150.0,
    val changeBpm: Boolean = false,
    /** Original section object without sectionNotes; unrepresented keys stay intact. */
    val sourceJson: String = "{}"
)
data class PsychSectionSpan(val startMs: Double, val endMs: Double, val startBeat: Double, val bpm: Double)

internal fun psychObject(text: String, label: String): JsonObject {
    require(text.toByteArray(Charsets.UTF_8).size <= 256_000) { "$label demasiado grande." }
    com.fnfmodmaker.modimport.readVSliceJson(text, label)
    com.fnfmodmaker.mod.rejectDuplicateJsonKeys(text)
    return JsonParser.parseString(text).asJsonObject
}

/** Psych 0.7.3 and current Conductor round sectionBeats * 4 to whole steps. */
internal fun psychSectionSpans(sections: List<PsychSectionData>, initialBpm: Int): List<PsychSectionSpan> {
    require(initialBpm in 1..400 && sections.size in 1..1000) { "Secciones o BPM inicial Psych inválidos." }
    require(sections.sumOf { it.sourceJson.length.toLong() } <= 256_000) { "Propiedades de secciones demasiado grandes." }
    var time = 0.0; var beat = 0.0; var tempo = initialBpm.toDouble()
    return sections.map { section ->
        require(section.beats.isFinite() && section.beats in 0.25..128.0 && section.bpm.isFinite() && section.bpm >= 0.0 && section.bpm <= 10_000.0 && (!section.changeBpm || section.bpm > 0.0)) { "Duración o BPM Psych inválidos." }
        val source = psychObject(section.sourceJson, "propiedades de sección")
        require(!source.has("sectionNotes")) { "La sección contiene notas duplicadas." }
        source.get("lengthInSteps")?.let { steps ->
            require(steps.isJsonNull || steps.isJsonPrimitive && steps.asJsonPrimitive.isNumber && steps.asDouble.isFinite()) { "lengthInSteps no es numérico y no se puede preservar al editar la duración." }
        }
        if (section.changeBpm) tempo = section.bpm
        val effectiveBeats = floor(section.beats * 4.0 + 0.5) / 4.0
        val next = time + effectiveBeats * 60_000.0 / tempo
        require(next.isFinite() && next <= 86_400_000.0) { "Las secciones exceden la duración máxima." }
        PsychSectionSpan(time, next, beat, tempo).also { time = next; beat += effectiveBeats }
    }
}
internal fun psychSectionIndex(spans: List<PsychSectionSpan>, time: Long): Int {
    if (spans.isEmpty() || time < 0) return -1
    var low = 0; var high = spans.lastIndex
    while (low < high) {
        val mid = (low + high + 1) ushr 1
        if (spans[mid].startMs <= time.toDouble()) low = mid else high = mid - 1
    }
    return if (time.toDouble() >= spans[low].startMs && time.toDouble() < spans[low].endMs) low else -1
}

/** Round-trip section metadata and notes; unknown properties are retained verbatim as JSON tokens. */
internal fun psychSectionsToJson(chart: ChartDraft): String {
    val spans = psychSectionSpans(chart.psychLayout, chart.bpm)
    val groups = List(spans.size) { JsonArray() }
    for (note in chart.notes) {
        val index = psychSectionIndex(spans, note.timeMs)
        require(index >= 0) { "La nota en ${note.timeMs} ms queda fuera de las secciones Psych; amplía las secciones antes de exportar." }
        val row = JsonArray().apply {
            add(note.timeMs); add(note.lane); add(note.lengthMs)
            if (note.kind.isNotEmpty()) add(note.kind)
        }
        groups[index].add(row)
    }
    val sections = JsonArray()
    chart.psychLayout.forEachIndexed { index, section ->
        val obj = psychObject(section.sourceJson, "sección Psych")
        val oldBeats = obj.get("sectionBeats")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asDouble ?: 4.0
        if (obj.has("lengthInSteps") && oldBeats != section.beats) obj.addProperty("lengthInSteps", floor(section.beats * 4 + 0.5).toInt())
        if (!obj.has("sectionBeats") || oldBeats != section.beats) obj.addProperty("sectionBeats", section.beats)
        obj.addProperty("mustHitSection", section.mustHit)
        obj.addProperty("altAnim", section.altAnim)
        obj.addProperty("gfSection", section.gfSection)
        if (!obj.has("bpm") || obj.get("bpm").isJsonNull || !obj.get("bpm").isJsonPrimitive || !obj.get("bpm").asJsonPrimitive.isNumber || obj.get("bpm").asDouble != section.bpm) obj.addProperty("bpm", section.bpm)
        obj.addProperty("changeBPM", section.changeBpm)
        obj.add("sectionNotes", groups[index])
        sections.add(obj)
    }
    val song = if (chart.psychSongExtras.isBlank()) JsonObject() else psychObject(chart.psychSongExtras, "propiedades de canción Psych")
    val reserved = setOf("notes", "events", "bpm", "needsVoices", "speed", "offset", "player1", "player2", "gfVersion", "stage", "format")
    require(song.keySet().none { it in reserved }) { "Propiedades Psych ambiguas." }
    song.get("song")?.let { require(it.isJsonPrimitive && it.asJsonPrimitive.isString) { "Nombre original Psych inválido." } }
    song.addProperty("song", song.get("song")?.asString ?: chart.songFolder); song.add("notes", sections)
    song.add("events", JsonParser.parseString(if (chart.psychEventsJson.isBlank()) "[]" else com.fnfmodmaker.mod.validatePsychEvents(chart.psychEventsJson)))
    song.addProperty("bpm", chart.bpm); song.addProperty("needsVoices", chart.needsVoices); song.addProperty("speed", chart.scrollSpeed)
    song.addProperty("offset", chart.offsetMs); song.addProperty("player1", chart.player1); song.addProperty("player2", chart.player2)
    song.addProperty("gfVersion", chart.gfVersion); song.addProperty("stage", chart.stage); song.addProperty("format", "psych_v1")
    return song.toString()
}

/** Snap follows Psych section tempo map, without changing absolute note timestamps. */
internal fun psychGridTempos(sections: List<PsychSectionData>, bpm: Int): List<GridTempo> {
    val spans = psychSectionSpans(sections, bpm)
    return spans.mapIndexedNotNull { i, span ->
        if (i == 0 || span.bpm != spans[i - 1].bpm) GridTempo(span.startMs, span.bpm) else null
    }
}
internal fun materializePsychLayout(chart: ChartDraft, initialBpm: Int): List<PsychSectionData> {
    require(initialBpm in 1..400) { "BPM inicial Psych inválido." }
    if (chart.psychLayout.isNotEmpty()) return chart.psychLayout
    val last = chart.notes.maxOfOrNull { it.timeMs } ?: 0L
    val count = (last / (240_000.0 / initialBpm)).toInt() + 1
    require(count in 1..1000) { "Demasiadas secciones Psych." }
    return List(count) { PsychSectionData(mustHit = it % 2 == 0, bpm = initialBpm.toDouble()) }
}

/** Append a copy of the section and its notes; existing timestamps never move. */
internal fun duplicatePsychSection(chart: ChartDraft, index: Int, bpm: Int): ChartDraft {
    val layout = materializePsychLayout(chart, bpm)
    require(index in layout.indices && layout.size < 1000) { "Sección no disponible o límite alcanzado." }
    val spans = psychSectionSpans(layout, bpm)
    val next = layout + layout[index]
    val nextSpans = psychSectionSpans(next, bpm)
    val shift = spans.last().endMs - spans[index].startMs
    val copies = chart.notes.filter { psychSectionIndex(spans, it.timeMs) == index }.map { note ->
        note.copy(timeMs = (note.timeMs + shift).roundToLong()).also {
            require(psychSectionIndex(nextSpans, it.timeMs) == next.lastIndex) { "Una nota copiada cae fuera de la nueva sección al redondear; no se duplicó." }
        }
    }
    val combined = replaceGridNotes(chart.notes, emptyList(), copies, 86_400_001L)
        ?: error("No se puede duplicar: límite de notas, colisión o sostenido fuera de rango.")
    return chart.copy(psychLayout = next, notes = combined)
}
