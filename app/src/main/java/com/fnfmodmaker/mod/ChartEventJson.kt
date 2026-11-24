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

