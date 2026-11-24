package com.fnfmodmaker.mod

import com.fnfmodmaker.modimport.readVSliceJson
import com.google.gson.JsonArray
import com.google.gson.JsonParser

/** V-Slice SongTimeChange 2.2.x: t (ms), bpm, n, d, optional b and bt. */
internal fun validateVSliceTempo(text: String): String {
    require(text.toByteArray(Charsets.UTF_8).size <= 64_000) { "Mapa de tempo demasiado grande." }
    val wrapped = "{\"timeChanges\":$text}"
    readVSliceJson(wrapped, "tempo"); rejectDuplicateJsonKeys(wrapped)
    val data = JsonParser.parseString(wrapped).asJsonObject
    require(data.size() == 1 && data.get("timeChanges")?.isJsonArray == true) { "Mapa de tempo inválido." }
    val rows = data.getAsJsonArray("timeChanges")
    require(rows.size() in 1..1000) { "El mapa debe contener entre 1 y 1000 cambios." }
    var previous = -1.0
    rows.forEachIndexed { index, entry ->
        require(entry.isJsonObject) { "Cambio de tempo inválido." }
        val row = entry.asJsonObject
        fun number(key: String): Double? {
            val item = row.get(key) ?: return null
            require(item.isJsonPrimitive && item.asJsonPrimitive.isNumber) { "$key debe ser numérico." }
            return item.asDouble.also { require(it.isFinite()) { "$key inválido." } }
        }
        val time = number("t") ?: error("Falta el tiempo del cambio.")
        require(time in 0.0..86_400_000.0 && time > previous && (index != 0 || time == 0.0)) { "Los tiempos deben comenzar en cero y estar ordenados sin duplicados." }
        previous = time
        val bpm = number("bpm")
        require((bpm != null || index > 0) && (bpm == null || bpm > 0 && bpm <= 10000)) { "BPM inválido." }
        if (index == 0) require(bpm != null && bpm in 1.0..400.0 && bpm % 1.0 == 0.0) { "El BPM inicial debe ser entero entre 1 y 400." }
        val n = number("n") ?: 4.0; val d = number("d") ?: 4.0
        require(n % 1.0 == 0.0 && n in 1.0..64.0 && d % 1.0 == 0.0 && d.toInt() in setOf(1, 2, 4, 8, 16, 32, 64)) { "Compás inválido." }
        val bt = row.get("bt")
        if (bt != null && !bt.isJsonNull) {
            fun validTuplet(value: com.google.gson.JsonElement): Boolean = value.isJsonPrimitive && value.asJsonPrimitive.isNumber &&
                value.asDouble.isFinite() && value.asDouble % 1.0 == 0.0 && value.asDouble in 1.0..64.0
            require(if (bt.isJsonArray) (bt.asJsonArray.size() == 1 || bt.asJsonArray.size() == n.toInt()) && bt.asJsonArray.all(::validTuplet) else validTuplet(bt)) { "bt no corresponde al numerador; conserva o ajusta la subdivisión antes de editar." }
        }
    }
    return rows.toString()
}
internal fun tempoWithInitialBpm(source: String, bpm: Int): String {
    require(bpm in 1..400) { "BPM inicial inválido." }
    val array = JsonParser.parseString(validateVSliceTempo(source)).asJsonArray
    array[0].asJsonObject.addProperty("bpm", bpm)
    return validateVSliceTempo(array.toString())
}
internal fun changeVSliceTempo(source: String, index: Int?, time: String, bpm: String, numerator: String, denominator: String): String {
    val array = JsonParser.parseString(validateVSliceTempo(source)).asJsonArray
    require(index == null || index in 0 until array.size()) { "Selección obsoleta." }
    fun parseNumber(value: String): com.google.gson.JsonElement {
        require(value.length <= 128) { "Número inválido." }
        val wrapped = "{\"value\":$value}"
        readVSliceJson(wrapped, "número"); rejectDuplicateJsonKeys(wrapped)
        val obj = JsonParser.parseString(wrapped).asJsonObject
        require(obj.size() == 1 && obj.get("value")?.isJsonPrimitive == true && obj.get("value").asJsonPrimitive.isNumber) { "Número inválido." }
        return obj.get("value")
    }
    val item = index?.let { array[it].asJsonObject.deepCopy() } ?: com.google.gson.JsonObject()
    item.add("t", parseNumber(time)); if (bpm.isBlank()) item.remove("bpm") else item.add("bpm", parseNumber(bpm))
    item.add("n", parseNumber(numerator)); item.add("d", parseNumber(denominator))
    if (index == null) array.add(item) else array.set(index, item)
    val sorted = array.toList().sortedBy { it.asJsonObject.get("t").asBigDecimal }
    val out = JsonArray().apply { sorted.forEach { add(it) } }
    return validateVSliceTempo(out.toString())
}
internal fun removeVSliceTempo(source: String, index: Int): String {
    val array = JsonParser.parseString(validateVSliceTempo(source)).asJsonArray
    require(index in 1 until array.size()) { "El BPM inicial no se puede eliminar." }
    array.remove(index)
    return validateVSliceTempo(array.toString())
}
/** Conductor's beat-time calculation; denominator changes the length of a beat. */
internal fun vSliceBeatAt(source: String, time: Double): Double {
    require(time.isFinite() && time >= 0)
    val rows = JsonParser.parseString(validateVSliceTempo(source)).asJsonArray
    var previousTime = 0.0; var beats = 0.0; var bpm = 0.0; var den = 4.0
    rows.forEach { entry ->
        val row = entry.asJsonObject; val t = row.get("t").asDouble
        if (t <= time) {
            if (bpm > 0) beats = kotlin.math.round((beats + (t - previousTime) * bpm / 60_000.0 * den / 4.0) * 10_000.0) / 10_000.0
            bpm = row.get("bpm")?.asDouble ?: bpm
            den = row.get("d")?.asDouble ?: 4.0; previousTime = t
        }
    }
    return beats + (time - previousTime) * bpm / 60_000.0 * den / 4.0
}
internal fun duplicateVSliceTempo(source: String, index: Int, time: String): String {
    val original = JsonParser.parseString(validateVSliceTempo(source)).asJsonArray
    require(index in 0 until original.size()) { "Selección obsoleta." }
    val item = original[index].asJsonObject
    val created = changeVSliceTempo(source, null, time, item.get("bpm")?.asString ?: "", item.get("n")?.asString ?: "4", item.get("d")?.asString ?: "4")
    val result = JsonParser.parseString(created).asJsonArray
    val newTime = java.math.BigDecimal(time)
    val target = (0 until result.size()).single { result[it].asJsonObject.get("t").asBigDecimal.compareTo(newTime) == 0 }
    val copy = item.deepCopy()
    copy.add("t", result[target].asJsonObject.get("t").deepCopy())
    result.set(target, copy)
    return validateVSliceTempo(result.toString())
}
