package com.fnfmodmaker.modimport

import com.fnfmodmaker.model.ChartDraft
import com.fnfmodmaker.model.ChartNote
import org.json.JSONArray
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
internal fun readVSliceDifficulty(chart: JSONObject, folder: String, difficulty: String, bpm: Int, speed: Float): ChartDraft {
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
        require(!note.has("k") || note.isNull("k") || note.optString("k") == "") { "$difficulty: contiene tipos de nota especiales." }
        val params = note.optJSONArray("p")
        require(!note.has("p") || note.isNull("p") || (params != null && params.length() == 0)) { "$difficulty: contiene parámetros de nota." }
        val result = ChartNote(time.roundToLong(), lane.toInt(), length.roundToLong())
        val exact = time to length
        val prior = projected.putIfAbsent(result, exact)
        require(prior == null || prior == exact) { "$difficulty: notas distintas coinciden al redondear; se conserva la dificultad original." }
        result
    }
    return ChartDraft(songFolder = folder, difficulty = difficulty, notes = result, bpm = bpm, scrollSpeed = speed)
}

/** Patch only explicitly editable difficulties, preserving events, extra difficulties and unknown fields. */
internal fun patchVSliceChart(original: String, drafts: Map<String, ChartDraft>, editable: Set<String>): String {
    val chart = readVSliceJson(original, "chart")
    val notes = chart.optJSONObject("notes") ?: error("Faltan notas en el chart original.")
    require(drafts.keys.all { it in editable }) { "La dificultad solicitada no es editable sin pérdida." }
    drafts.forEach { (difficulty, draft) ->
        val previous = readVSliceDifficulty(chart, draft.songFolder, difficulty, draft.bpm, draft.scrollSpeed)
        // Keep the exact original object (including fractional times) for every unchanged note.
        if (previous.notes != draft.notes) {
            draft.requireExportable()
            val originals = notes.getJSONArray(difficulty)
            val unmatched = mutableMapOf<ChartNote, java.util.ArrayDeque<Int>>()
            previous.notes.forEachIndexed { i, note -> unmatched.getOrPut(note) { java.util.ArrayDeque() }.addLast(i) }
            val updated = JSONArray()
            for (note in draft.notes) {
                val originalIndex = unmatched[note]?.pollFirst()
                if (originalIndex != null) updated.put(originals.getJSONObject(originalIndex))
                else updated.put(JSONObject().put("t", note.timeMs).put("d", note.lane).put("l", note.lengthMs))
            }
            notes.put(difficulty, updated)
        }
    }
    return chart.toString(2)
}
