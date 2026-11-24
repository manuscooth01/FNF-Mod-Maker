package com.fnfmodmaker.project

import com.google.gson.JsonArray
import com.google.gson.JsonObject

/** Convert the old single-draft JSON without copying or discarding its fields/resources. */
internal fun migrateCharacterLibrary(input: JsonObject): JsonObject {
    val result = input.deepCopy()
    if (result.has("character")) {
        require(!result.has("characterLibrary")) { "El proyecto contiene dos bibliotecas de personajes incompatibles." }
        val legacy = result.remove("character")
        require(legacy.isJsonObject) { "Personaje antiguo inválido." }
        val entry = JsonObject().apply { addProperty("id", "legacy"); add("draft", legacy) }
        result.add("characterLibrary", JsonArray().apply { add(entry) })
        result.addProperty("selectedCharacterId", "legacy")
    }
    return result
}
