package com.fnfmodmaker.project

import com.google.gson.JsonObject

/**
 * Unversioned documents use the existing shared-chart schema. Never let an older
 * app deserialize a newer chart schema and silently erase it on the next save.
 * This guard does not migrate or enable independent charts.
 */
internal fun requireSupportedChartSchema(config: JsonObject) {
    val version = config.get("chartSchemaVersion")
    require(version == null || (version.isJsonPrimitive && version.asJsonPrimitive.isNumber && version.asString == "1")) {
        "La versión de charts no es compatible con esta versión de la app; no se modificó el proyecto."
    }
    // Reserved for the independent-chart schema, which this version cannot safely open.
    // An absent or forged v1 marker must never allow Gson to discard this payload.
    require(!config.has("independentCharts")) {
        "El proyecto contiene charts independientes no compatibles con esta versión; no se modificó el proyecto."
    }
}
