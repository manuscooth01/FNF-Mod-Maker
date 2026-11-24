package com.fnfmodmaker.mod

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive
import com.fnfmodmaker.modimport.readVSliceJson

/** Address an action within its original timestamp group, without flattening other actions. */
internal data class PsychEventRef(val row: Int, val action: Int, val time: String, val name: String, val value1: String?, val value2: String?)
private fun parsePsych(source: String) = JsonParser.parseString(validatePsychEvents(source)).asJsonArray
internal fun psychEventRefs(source: String): List<PsychEventRef> = buildList {
    parsePsych(source).forEachIndexed { i, group ->
        val row = group.asJsonArray
        row[1].asJsonArray.forEachIndexed { j, item ->
            val action = item.asJsonArray
            add(PsychEventRef(i, j, row[0].asString, action[0].asString,
                action[1].takeUnless { it.isJsonNull }?.asString, action[2].takeUnless { it.isJsonNull }?.asString))
        }
    }
}
private fun parseTime(time: String): JsonElement {
    require(time.length <= 128) { "Tiempo inválido." }
    val input = "{\"t\":$time}"
    readVSliceJson(input, "tiempo"); rejectDuplicateJsonKeys(input)
    val obj = JsonParser.parseString(input).asJsonObject
    require(obj.size() == 1 && obj.get("t")?.isJsonPrimitive == true && obj.get("t").asJsonPrimitive.isNumber) { "El tiempo debe ser un número." }
    return obj.get("t")
}
private fun checkedAction(events: JsonArray, ref: PsychEventRef): JsonArray {
    require(ref.row in 0 until events.size()) { "Selección obsoleta." }
    val row = events[ref.row].asJsonArray
    val actions = row[1].asJsonArray
    require(ref.action in 0 until actions.size()) { "Selección obsoleta." }
    val action = actions[ref.action].asJsonArray
    require(row[0].asString == ref.time && action[0].asString == ref.name &&
        action[1].takeUnless { it.isJsonNull }?.asString == ref.value1 &&
        action[2].takeUnless { it.isJsonNull }?.asString == ref.value2) { "El evento cambió; selecciónalo de nuevo." }
    return action
}
private fun action(name: String, value1: String?, value2: String?): JsonArray = JsonArray().apply {
    add(name); add(value1?.let(::JsonPrimitive) ?: JsonNull.INSTANCE); add(value2?.let(::JsonPrimitive) ?: JsonNull.INSTANCE)
}
private fun group(time: JsonElement, action: JsonArray): JsonArray = JsonArray().apply {
    add(time); add(JsonArray().apply { add(action) })
}
internal fun changePsychEvent(source: String, ref: PsychEventRef?, time: String, name: String, value1: String?, value2: String?): String {
    val events = parsePsych(source)
    val newTime = parseTime(time)
    val updated = action(name, value1, value2)
    if (ref == null) events.add(group(newTime, updated))
    else {
        checkedAction(events, ref)
        val row = events[ref.row].asJsonArray
        val actions = row[1].asJsonArray
        // Leave co-timed actions grouped. Only move the selected action when its time changes.
        if (row[0].asBigDecimal.compareTo(newTime.asBigDecimal) == 0) actions.set(ref.action, updated)
        else if (actions.size() == 1) { row.set(0, newTime); actions.set(ref.action, updated) }
        else { actions.remove(ref.action); events.add(group(newTime, updated)) }
    }
    return validatePsychEvents(events.toString())
}
internal fun duplicatePsychEvent(source: String, ref: PsychEventRef): String {
    val events = parsePsych(source)
    val copy = checkedAction(events, ref).deepCopy()
    events.add(group(events[ref.row].asJsonArray[0].deepCopy(), copy))
    return validatePsychEvents(events.toString())
}
internal fun removePsychEvent(source: String, ref: PsychEventRef): String {
    val events = parsePsych(source)
    checkedAction(events, ref)
    val actions = events[ref.row].asJsonArray[1].asJsonArray
    actions.remove(ref.action)
    if (actions.size() == 0) events.remove(ref.row)
    return validatePsychEvents(events.toString())
}
internal fun sortPsychEvents(source: String): String {
    val events = parsePsych(source)
    val sorted = events.toList().sortedWith(compareBy { it.asJsonArray[0].asBigDecimal })
    return JsonArray().apply { sorted.forEach { add(it.deepCopy()) } }.toString()
}
