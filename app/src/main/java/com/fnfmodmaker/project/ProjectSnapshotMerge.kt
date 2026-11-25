package com.fnfmodmaker.project

import com.google.gson.JsonElement
import com.google.gson.JsonObject

/**
 * Apply model edits to a loaded document without discarding fields Gson does not model.
 * Array elements have no universal stable identity in the legacy schema. If an edited
 * array contains unknown fields, fail instead of attaching them to another element.
 */
internal fun mergeProjectSnapshot(original: JsonObject, baseline: JsonObject, edited: JsonObject): JsonObject {
    fun hasUnknown(raw: JsonElement, base: JsonElement): Boolean {
        if (raw.isJsonObject && base.isJsonObject) return raw.asJsonObject.entrySet().any { (key, value) ->
            val old = base.asJsonObject.get(key)
            old == null || hasUnknown(value, old)
        }
        if (raw.isJsonArray && base.isJsonArray) {
            val a = raw.asJsonArray; val b = base.asJsonArray
            return a.size() != b.size() || (0 until a.size()).any { hasUnknown(a[it], b[it]) }
        }
        return false
    }
    fun merge(raw: JsonElement, base: JsonElement, next: JsonElement): JsonElement {
        if (base == next) return raw.deepCopy()
        if (raw.isJsonObject && base.isJsonObject && next.isJsonObject) {
            val out = raw.asJsonObject.deepCopy()
            val previous = base.asJsonObject; val editedObject = next.asJsonObject
            previous.keySet().filter { !editedObject.has(it) }.forEach { out.remove(it) }
            editedObject.entrySet().forEach { (key, value) ->
                val old = previous.get(key); val stored = raw.asJsonObject.get(key)
                out.add(key, if (old != null && stored != null) merge(stored, old, value) else value.deepCopy())
            }
            return out
        }
        if (raw.isJsonArray && base.isJsonArray && next.isJsonArray) {
            require(!hasUnknown(raw, base)) {
                "La lista modificada contiene datos desconocidos; no se puede guardar sin riesgo de pérdida."
            }
        }
        return next.deepCopy()
    }
    return merge(original, baseline, edited).asJsonObject
}
