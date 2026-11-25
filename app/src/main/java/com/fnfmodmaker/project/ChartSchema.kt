package com.fnfmodmaker.project

import com.google.gson.JsonObject

/**
 * Unversioned documents use the existing shared-chart schema. Never let an older
 * app deserialize a newer chart schema and silently erase it on the next save.
 * This guard does not migrate or enable independent charts.
 */
internal fun requireSupportedChartSchema(config: JsonObject) {
    val version = config.get("chartSchemaVersion") ?: return
    require(version.isJsonPrimitive && version.asJsonPrimitive.isNumber && version.asString == "1") {
        "La versión de charts no es compatible con esta versión de la app; no se modificó el proyecto."
    }
}
