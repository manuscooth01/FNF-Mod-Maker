package com.fnfmodmaker.modimport

import com.fnfmodmaker.model.ChartDraft
import com.fnfmodmaker.model.ChartNote
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.roundToLong

/** Conservative subset of Psych's SwagSong. Unknown gameplay data rejects conversion, never silently discarded. */
internal fun readPsychChart(text: String, folder: String, difficulty: String): ChartDraft {
    require(text.toByteArray(Charsets.UTF_8).size <= 4_000_000) { "Chart demasiado grande." }
    var depth = 0; var quoted = false; var escaped = false
    text.forEach { c ->
        if (quoted) { if (escaped) escaped = false else if (c == '\\') escaped = true else if (c == '"') quoted = false }
        else when (c) { '"' -> quoted = true; '{', '[' -> { depth++; require(depth <= 64) { "JSON demasiado profundo." } }; '}', ']' -> { depth--; require(depth >= 0) { "JSON inválido." } } }
    }
    require(depth == 0 && !quoted) { "JSON incompleto." }
    val outer = JSONObject(text)
    val song = outer.optJSONObject("song") ?: outer
    val format = song.optString("format", "")
    val absoluteLanes = format.startsWith("psych_v1")
    require(format.isEmpty() || absoluteLanes) { "Formato de chart no reconocido: $format" }
    val bpmDouble = song.optDouble("bpm", Double.NaN)
    require(bpmDouble.isFinite() && bpmDouble in 1.0..400.0 && bpmDouble % 1.0 == 0.0) { "El editor necesita BPM entero entre 1 y 400." }
    val speed = song.optDouble("speed", 1.0)
    require(speed.isFinite() && speed > 0 && speed <= Float.MAX_VALUE) { "Velocidad inválida." }
    val offset = song.optDouble("offset", 0.0)
    require(offset.isFinite() && offset % 1 == 0.0 && offset in Int.MIN_VALUE.toDouble()..Int.MAX_VALUE.toDouble()) { "Offset no compatible." }
    val events = song.optJSONArray("events")
    require(events == null || events.length() == 0) { "El chart contiene eventos que todavía no se pueden editar sin pérdida." }
    val sections = song.optJSONArray("notes") ?: error("Faltan secciones del chart.")
    require(sections.length() <= 10_000) { "Demasiadas secciones." }
    val notes = mutableListOf<ChartNote>()
    for (i in 0 until sections.length()) {
        val sec = sections.optJSONObject(i) ?: error("Sección inválida: $i.")
        require(!sec.optBoolean("changeBPM", false) && !sec.optBoolean("altAnim", false) && !sec.optBoolean("gfSection", false)) { "La sección $i usa cambios BPM, animaciones o GF que no se pueden regenerar." }
        val beats = sec.optDouble("sectionBeats", if (sec.has("lengthInSteps")) sec.optDouble("lengthInSteps", 16.0) / 4 else 4.0)
        require(beats == 4.0) { "La sección $i tiene duración no compatible." }
        val mustHit = sec.optBoolean("mustHitSection", false)
        val rows = sec.optJSONArray("sectionNotes") ?: error("Faltan notas en la sección $i.")
        require(rows.length() <= 100_000 - notes.size) { "Demasiadas notas." }
        for (j in 0 until rows.length()) {
            val row = rows.optJSONArray(j) ?: error("Nota inválida en sección $i.")
            require(row.length() in 2..4) { "Datos adicionales de nota no compatibles." }
            val t = row.optDouble(0, Double.NaN); val data = row.optDouble(1, Double.NaN); val duration = if (row.length() >= 3) row.optDouble(2, Double.NaN) else 0.0
            require(t.isFinite() && duration.isFinite() && t >= 0 && duration >= 0 && t <= 86_400_000 && duration <= 86_400_000 && data.isFinite() && data % 1.0 == 0.0 && data in 0.0..7.0) { "Nota o evento no compatible en sección $i." }
            if (row.length() == 4 && !row.isNull(3)) {
                val type = row.opt(3)
                require(type == "" || (type is Number && type.toInt() == 0 && type.toDouble() == 0.0)) { "Tipo de nota no compatible en sección $i." }
            }
            val d = data.toInt()
            val lane = if (absoluteLanes) d else if (mustHit) d else (d + 4) % 8
            notes.add(ChartNote(t.roundToLong(), lane, duration.roundToLong()))
        }
    }
    return ChartDraft(songFolder = folder, difficulty = difficulty, notes = notes, scrollSpeed = speed.toFloat(), bpm = bpmDouble.toInt(),
        offsetMs = offset.toInt(), needsVoices = song.optBoolean("needsVoices", true), player1 = song.optString("player1", "bf"),
        player2 = song.optString("player2", "dad"), gfVersion = song.optString("gfVersion", song.optString("player3", "gf")), stage = song.optString("stage", "stage"))
}
