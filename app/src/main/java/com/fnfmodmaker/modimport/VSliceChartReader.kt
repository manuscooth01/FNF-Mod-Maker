package com.fnfmodmaker.modimport

import com.fnfmodmaker.model.ChartDraft
import com.fnfmodmaker.model.ChartNote
import org.json.JSONObject
import kotlin.math.roundToLong

/** Bound and depth-check untrusted JSON before JSONObject parses it. */
internal fun readVSliceJson(text: String, label: String): JSONObject {
    require(text.toByteArray(Charsets.UTF_8).size in 1..4_000_000) { "$label: JSON vacío o demasiado grande." }
    var depth = 0; var quote = false; var escaped = false
    for (c in text) {
        if (quote) { if (escaped) escaped = false else if (c == '\\') escaped = true else if (c == '"') quote = false }
        else when (c) { '"' -> quote = true; '{', '[' -> { depth++; require(depth <= 64) { "$label: JSON demasiado profundo." } }; '}', ']' -> { depth--; require(depth >= 0) { "$label: JSON inválido." } } }
    }
    require(depth == 0 && !quote) { "$label: JSON incompleto." }
    return JSONObject(text)
}

/** V-Slice schema 2.0: ordinary notes on the first two strumlines are editable in ms. */
internal fun readVSliceDifficulty(chart: JSONObject, folder: String, difficulty: String, bpm: Double, speed: Float): ChartDraft {
    val notes = chart.optJSONObject("notes")?.optJSONArray(difficulty) ?: error("Falta la dificultad $difficulty.")
    require(notes.length() <= 100_000) { "Demasiadas notas en $difficulty." }
    fun number(obj: JSONObject, key: String, default: Double): Double {
        if (obj.has(key)) require(obj.opt(key) is Number) { "$difficulty: $key debe ser un número." }
        val v = obj.optDouble(key, default)
        require(v.isFinite() && v >= 0 && v <= 86_400_000) { "$difficulty: $key está fuera de rango." }
        return v
    }
    val projected = mutableMapOf<ChartNote, Pair<Double, Double>>()
    val result = (0 until notes.length()).map { i ->
        val note = notes.optJSONObject(i) ?: error("$difficulty: nota $i inválida.")
        val allowed = setOf("t", "d", "l", "k", "p")
        val keys = note.keys(); while (keys.hasNext()) require(keys.next() in allowed) { "$difficulty: datos de nota desconocidos." }
        require(note.has("t") && note.has("d")) { "$difficulty: falta t o d." }
        val time = number(note, "t", Double.NaN); val lane = number(note, "d", Double.NaN); val length = number(note, "l", 0.0)
        require(lane % 1.0 == 0.0 && lane in 0.0..7.0 && time + length <= 86_400_000) { "$difficulty: carril o duración incompatibles." }
        require(!note.has("k") || note.isNull("k") || note.opt("k") is String) { "$difficulty: tipo de nota inválido." }
        val kind = if (note.isNull("k") || !note.has("k")) "" else note.getString("k")
        require(kind.length <= 128 && kind.none(Char::isISOControl)) { "$difficulty: tipo de nota inválido." }
        val params = note.optJSONArray("p")
        require(!note.has("p") || note.isNull("p") || (params != null && params.length() == 0)) { "$difficulty: contiene parámetros de nota." }
        val result = ChartNote(time.roundToLong(), lane.toInt(), length.roundToLong(), kind = kind)
        val exact = time to length
        val prior = projected.putIfAbsent(result, exact)
        require(prior == null || prior == exact) { "$difficulty: notas distintas coinciden al redondear; se conserva la dificultad original." }
        result
    }
    return ChartDraft(songFolder = folder, difficulty = difficulty, notes = result, bpm = bpm, scrollSpeed = speed)
}

/** Patch only explicitly editable difficulties, preserving events, extra difficulties and unknown fields. */
internal fun patchVSliceChart(original: String, drafts: Map<String, ChartDraft>, editable: Set<String>, speed: Float? = null, retained: Set<String> = emptySet()): String {
    // JSONObject is useful for decoding known notes but may round unknown JSON numbers.
    // Apply patches to a Gson tree instead, retaining the source token values.
    val projection = readVSliceJson(original, "chart")
    com.fnfmodmaker.mod.rejectDuplicateJsonKeys(original)
    val chart = com.google.gson.JsonParser.parseString(original).asJsonObject
    val notes = chart.get("notes")?.takeIf { it.isJsonObject }?.asJsonObject
        ?: com.google.gson.JsonObject().also { chart.add("notes", it) }
    notes.keySet().toList().filter { it !in drafts.keys && it !in retained }.forEach { notes.remove(it) }
    val speeds = chart.get("scrollSpeed")?.takeIf { it.isJsonObject }?.asJsonObject
        ?: com.google.gson.JsonObject().also { chart.add("scrollSpeed", it) }
    speeds.keySet().toList().filter { it != "default" && it !in drafts.keys && it !in retained }.forEach { speeds.remove(it) }
    if (speed != null) retained.forEach { speeds.addProperty(it, speed) }
    drafts.forEach { (difficulty, draft) ->
        val previous = if (difficulty in editable && notes.has(difficulty)) runCatching { readVSliceDifficulty(projection, draft.songFolder, difficulty, draft.bpm, draft.scrollSpeed) }.getOrNull() else null
        require(previous != null || difficulty !in editable || !notes.has(difficulty)) { "$difficulty: no se puede reconstruir el chart original sin pérdida." }
        if (previous == null || previous.notes != draft.notes) {
            draft.requireExportable()
            require(draft.notes.none { it.psychTypeJson.isNotEmpty() }) { "Los tipos numéricos Psych no tienen traducción reversible a V-Slice." }
            if (previous == null) notes.add(difficulty, com.google.gson.JsonParser.parseString(draft.toVSliceNotes().toString())) else {
                val originals = notes.getAsJsonArray(difficulty)
                val unmatched = mutableMapOf<ChartNote, java.util.ArrayDeque<Int>>()
                previous.notes.forEachIndexed { i, note -> unmatched.getOrPut(note) { java.util.ArrayDeque() }.addLast(i) }
                val updated = com.google.gson.JsonArray()
                for (note in draft.notes) {
                    val originalIndex = unmatched[note]?.pollFirst()
                    if (originalIndex != null) updated.add(originals[originalIndex].deepCopy())
                    else updated.add(com.google.gson.JsonObject().apply {
                        addProperty("t", note.timeMs); addProperty("d", note.lane); addProperty("l", note.lengthMs)
                        if (note.kind.isNotEmpty()) addProperty("k", note.kind)
                    })
                }
                // An edited/deleted projected note may have fractional source timing. Do not
                // silently replace its exact original t/l with the rounded editor values.
                for ((oldNote, indices) in unmatched) for (index in indices) {
                    val row = originals[index].asJsonObject
                    val originalTime = row.get("t").asBigDecimal
                    val originalLength = row.get("l")?.takeUnless { it.isJsonNull }?.asBigDecimal ?: java.math.BigDecimal.ZERO
                    require(originalTime.compareTo(java.math.BigDecimal.valueOf(oldNote.timeMs)) == 0 &&
                        originalLength.compareTo(java.math.BigDecimal.valueOf(oldNote.lengthMs)) == 0) {
                        "$difficulty: una nota original con tiempo o sostenido fraccionario fue modificada; la edición no se puede exportar sin pérdida. Restaura la nota original."
                    }
                }
                notes.add(difficulty, updated)
            }
        }
        if (speed != null || !speeds.has(difficulty)) speeds.addProperty(difficulty, speed ?: draft.scrollSpeed)
    }
    return chart.toString()
}

internal fun readVSliceDifficulty(chart: JSONObject, folder: String, difficulty: String, bpm: Int, speed: Float) = readVSliceDifficulty(chart, folder, difficulty, bpm.toDouble(), speed)
