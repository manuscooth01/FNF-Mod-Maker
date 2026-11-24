package com.fnfmodmaker.mod

import com.google.gson.JsonParser
import com.fnfmodmaker.modimport.readVSliceJson

/** Bounded validation for event editors. Unknown fields/values are kept as JSON, not executed. */
internal fun validateVSliceEvents(text: String): String {
    require(text.toByteArray(Charsets.UTF_8).size <= 64_000) { "Los eventos superan 64 KB." }
    val wrapped = "{\"events\":$text}"
    readVSliceJson(wrapped, "eventos")
    rejectDuplicateJsonKeys(wrapped)
    val parsed = JsonParser.parseString(wrapped).asJsonObject.get("events")
    require(parsed != null && parsed.isJsonArray) { "Los eventos deben ser una lista JSON." }
    val events = parsed.asJsonArray
    require(events.size() <= 1000) { "Se admiten hasta 1000 eventos." }
    fun time(item: com.google.gson.JsonElement?) {
        require(item?.isJsonPrimitive == true && item.asJsonPrimitive.isNumber) { "El tiempo debe ser un número." }
        val n = item.asDouble
        require(n.isFinite() && n in 0.0..86_400_000.0) { "Tiempo de evento fuera de rango." }
    }
    events.forEach { event ->
        require(event.isJsonObject) { "Cada evento V-Slice debe ser un objeto." }
        val obj = event.asJsonObject
        time(obj.get("t"))
        val name = obj.get("e")
        require(name?.isJsonPrimitive == true && name.asJsonPrimitive.isString && name.asString.isNotBlank() && name.asString.length <= 128) { "Falta el tipo de evento V-Slice." }
    }
    return events.toString()
}


/** Psych 0.7.3 and psych_v1 store events as [time, [[name, value1, value2], ...]]. */
internal fun validatePsychEvents(text: String): String {
    require(text.toByteArray(Charsets.UTF_8).size <= 64_000) { "Los eventos superan 64 KB." }
    val wrapped = "{\"events\":$text}"
    readVSliceJson(wrapped, "eventos Psych")
    rejectDuplicateJsonKeys(wrapped)
    val events = JsonParser.parseString(wrapped).asJsonObject.get("events")
    require(events != null && events.isJsonArray && events.asJsonArray.size() <= 1000) { "Lista de eventos Psych inválida o demasiado larga." }
    events.asJsonArray.forEach { row ->
        require(row.isJsonArray && row.asJsonArray.size() == 2) { "Evento Psych debe tener tiempo y lista de acciones." }
        val r = row.asJsonArray; val t = r[0]
        require(t.isJsonPrimitive && t.asJsonPrimitive.isNumber && t.asDouble.isFinite() && t.asDouble in 0.0..86_400_000.0) { "Tiempo de evento Psych inválido." }
        require(r[1].isJsonArray && r[1].asJsonArray.size() <= 100) { "Acciones Psych inválidas." }
        r[1].asJsonArray.forEach { action ->
            require(action.isJsonArray && action.asJsonArray.size() == 3 && action.asJsonArray.all { it.isJsonNull || it.isJsonPrimitive && it.asJsonPrimitive.isString }) { "Acción Psych debe tener nombre y dos valores de texto." }
            val name = action.asJsonArray[0]
            require(!name.isJsonNull && name.asString.isNotBlank() && name.asString.length <= 128) { "Nombre de evento Psych inválido." }
        }
    }
    return events.toString()
}

/** Visual editor operations preserve all unedited event properties and validate atomically. */
internal fun changeVSliceEvent(source: String, index: Int?, time: String, name: String, value: String): String {
    val events = JsonParser.parseString(validateVSliceEvents(source)).asJsonArray
    require(value.toByteArray(Charsets.UTF_8).size <= 64_000) { "Valor demasiado grande." }
    val wrapped = "{\"v\":$value}"
    readVSliceJson(wrapped, "valor de evento")
    rejectDuplicateJsonKeys(wrapped)
    val parsedValue = JsonParser.parseString(wrapped).asJsonObject.get("v")
    require(time.length <= 128) { "Tiempo inválido." }
    val timeWrapped = "{\"t\":$time}"
    readVSliceJson(timeWrapped, "tiempo de evento"); rejectDuplicateJsonKeys(timeWrapped)
    val timeObject = JsonParser.parseString(timeWrapped).asJsonObject
    require(timeObject.size() == 1) { "Tiempo inválido." }
    val parsedTime = timeObject.get("t")
    require(parsedTime != null && parsedTime.isJsonPrimitive && parsedTime.asJsonPrimitive.isNumber) { "El tiempo debe ser un número." }
    require(index == null || index in 0 until events.size()) { "Selección de evento obsoleta." }
    val item = index?.let { events[it].asJsonObject.deepCopy() } ?: com.google.gson.JsonObject()
    item.add("t", parsedTime); item.addProperty("e", name); item.add("v", parsedValue)
    if (index == null) events.add(item) else events.set(index, item)
    return validateVSliceEvents(events.toString())
}
internal fun duplicateVSliceEvent(source: String, index: Int): String {
    val events = JsonParser.parseString(validateVSliceEvents(source)).asJsonArray
    require(index in 0 until events.size()) { "Selección de evento obsoleta." }
    events.add(events[index].deepCopy())
    return validateVSliceEvents(events.toString())
}
internal fun removeVSliceEvent(source: String, index: Int): String {
    val events = JsonParser.parseString(validateVSliceEvents(source)).asJsonArray
    require(index in 0 until events.size()) { "Selección de evento obsoleta." }
    events.remove(index)
    return events.toString()
}
