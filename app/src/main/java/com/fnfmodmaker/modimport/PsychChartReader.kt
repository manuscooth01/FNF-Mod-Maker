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
    require(!song.has("events") || song.isNull("events") || song.optJSONArray("events") != null) { "Eventos Psych inválidos." }
    com.fnfmodmaker.mod.rejectDuplicateJsonKeys(text)
    val tree = com.google.gson.JsonParser.parseString(text).asJsonObject
    val body = tree.get("song")?.takeIf { it.isJsonObject }?.asJsonObject ?: tree
    if (body !== tree) require(tree.keySet().all { it == "song" }) { "La envoltura Psych contiene propiedades adicionales que no se pueden convertir sin pérdida." }
    val events = body.get("events")?.takeUnless { it.isJsonNull }?.toString() ?: "[]"
    val eventJson = com.fnfmodmaker.mod.validatePsychEvents(events)
    val sections = song.optJSONArray("notes") ?: error("Faltan secciones del chart.")
    require(sections.length() in 1..1000) { "El chart debe tener entre 1 y 1000 secciones." }
    val sourceSections = body.getAsJsonArray("notes")
    val layout = mutableListOf<com.fnfmodmaker.model.PsychSectionData>()
    val notesBySection = mutableListOf<Int>()
    val notes = mutableListOf<ChartNote>()
    for (i in 0 until sections.length()) {
        val sec = sections.optJSONObject(i) ?: error("Sección inválida: $i.")
        fun flag(key: String): Boolean {
            require(!sec.has(key) || sec.isNull(key) || sec.opt(key) is Boolean) { "Sección $i: $key no es booleano." }
            return sec.optBoolean(key, false)
        }
        fun number(key: String, fallback: Double): Double {
            require(!sec.has(key) || sec.isNull(key) || sec.opt(key) is Number) { "Sección $i: $key no es numérico." }
            return sec.optDouble(key, fallback)
        }
        // Both current Psych and 0.7.3 use sectionBeats, defaulting to four;
        // legacy lengthInSteps is retained as data, not interpreted as the engine duration.
        val beats = number("sectionBeats", 4.0)
        val mustHit = flag("mustHitSection")
        val changed = flag("changeBPM")
        require(!changed || sec.has("bpm") && !sec.isNull("bpm")) { "Sección $i: falta BPM para changeBPM." }
        val sectionBpm = number("bpm", bpmDouble)
        val raw = sourceSections[i].asJsonObject.deepCopy().apply { remove("sectionNotes") }
        layout.add(com.fnfmodmaker.model.PsychSectionData(beats, mustHit, flag("altAnim"), flag("gfSection"), sectionBpm, changed, raw.toString()))
        val rows = sec.optJSONArray("sectionNotes") ?: error("Faltan notas en la sección $i.")
        require(rows.length() <= 100_000 - notes.size) { "Demasiadas notas." }
        for (j in 0 until rows.length()) {
            val row = rows.optJSONArray(j) ?: error("Nota inválida en sección $i.")
            require(row.length() in 2..4) { "Datos adicionales de nota no compatibles." }
            require(row.opt(0) is Number && row.opt(1) is Number && (row.length() < 3 || row.opt(2) is Number)) { "Sección $i: valores de nota no numéricos." }
            val t = row.optDouble(0, Double.NaN); val data = row.optDouble(1, Double.NaN); val duration = if (row.length() >= 3) row.optDouble(2, Double.NaN) else 0.0
            require(t.isFinite() && duration.isFinite() && t >= 0 && duration >= 0 && t <= 86_400_000 && duration <= 86_400_000 && data.isFinite() && data % 1.0 == 0.0 && data in 0.0..7.0) { "Nota o evento no compatible en sección $i." }
            require(t % 1.0 == 0.0 && duration % 1.0 == 0.0) { "Sección $i: tiempos fraccionarios de nota no editables sin pérdida." }
            val kind = if (row.length() < 4 || row.isNull(3)) "" else when (val type = row.opt(3)) {
                is String -> type
                is Number -> if (type.toDouble() == 0.0) "" else error("Tipo de nota numérico no compatible en sección $i.")
                else -> error("Tipo de nota inválido en sección $i.")
            }
            require(kind.length <= 128 && kind.none(Char::isISOControl)) { "Tipo de nota inválido en sección $i." }
            val d = data.toInt()
            val lane = if (absoluteLanes) d else if (mustHit) d else (d + 4) % 8
            notes.add(ChartNote(t.roundToLong(), lane, duration.roundToLong(), kind = kind)); notesBySection.add(i)
        }
    }
    val spans = com.fnfmodmaker.model.psychSectionSpans(layout, bpmDouble.toInt())
    notes.forEachIndexed { i, note -> require(com.fnfmodmaker.model.psychSectionIndex(spans, note.timeMs) == notesBySection[i]) {
        "La nota en ${note.timeMs} ms pertenece a otra sección en el archivo; no se puede reagrupar sin pérdida."
    } }
    val extras = body.deepCopy()
    listOf("song", "notes", "events", "bpm", "needsVoices", "speed", "offset", "player1", "player2", "gfVersion", "stage", "format").forEach { extras.remove(it) }
    com.fnfmodmaker.model.psychObject(extras.toString(), "propiedades Psych")
    return ChartDraft(songFolder = folder, difficulty = difficulty, notes = notes, scrollSpeed = speed.toFloat(), bpm = bpmDouble.toInt(),
        offsetMs = offset.toInt(), needsVoices = song.optBoolean("needsVoices", true), player1 = song.optString("player1", "bf"),
        player2 = song.optString("player2", "dad"), gfVersion = song.optString("gfVersion", song.optString("player3", "gf")), stage = song.optString("stage", "stage"), psychEventsJson = if (eventJson == "[]") "" else eventJson,
        psychLayout = layout, psychSongExtras = if (extras.size() == 0) "" else extras.toString())
}
