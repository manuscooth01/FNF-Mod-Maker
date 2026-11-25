package com.fnfmodmaker.project

import com.fnfmodmaker.model.ChartEngine
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser

/**
 * Chart schema versions:
 *   null        — original unversioned shared-chart schema (legacy).
 *   1           — same shared-chart schema, explicit marker.
 *   2           — independent charts per engine: [ModConfig.independentCharts] required.
 * Any other value is rejected before deserialization so an older app never silently
 * erases a newer chart schema on the next save.
 */
internal const val CHART_SCHEMA_LEGACY: Int? = null
internal const val CHART_SCHEMA_V1 = 1
internal const val CHART_SCHEMA_V2 = 2

/** Reject unknown schema markers and the v2 payload until the app can read it safely. */
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

/**
 * Validate the v2 independent-chart schema before it reaches persistence or editors.
 * Returns the parsed [IndependentChartCollection] so callers do not re-parse.
 */
internal fun requireIndependentChartSchema(config: JsonObject, gson: Gson): IndependentChartCollection {
    val version = config.get("chartSchemaVersion")
    require(version.isJsonPrimitive && version.asJsonPrimitive.isNumber && version.asString == "2") {
        "El proyecto requiere el esquema de charts independientes v2; chartSchemaVersion ausente o inválido."
    }
    val independent = config.get("independentCharts") ?: error("Falta independentCharts en el esquema v2.")
    require(independent.isJsonObject) { "independentCharts debe ser un objeto." }
    val collection = parseIndependentCharts(independent.asJsonObject, gson)
    require(collection.songs.isNotEmpty()) { "El esquema v2 requiere al menos una canción." }
    return collection
}

/** Parse an independentCharts JSON block into [IndependentChartCollection]. */
internal fun parseIndependentCharts(root: JsonObject, gson: Gson): IndependentChartCollection {
    val songs = mutableMapOf<String, com.fnfmodmaker.model.IndependentSong>()
    root.entrySet().forEach { (songId, songNode) ->
        require(songNode.isJsonObject) { "Canción inválida: $songId." }
        val songObj = songNode.asJsonObject
        val psychNode = songObj.get("psych")
        val vSliceNode = songObj.get("vslice")
        require((psychNode == null || psychNode.isJsonObject || psychNode.isJsonNull) &&
            (vSliceNode == null || vSliceNode.isJsonObject || vSliceNode.isJsonNull)) {
            "Variante de canción inválida: $songId."
        }
        val psych = if (psychNode == null || psychNode.isJsonNull) null else parseVariant(psychNode.asJsonObject, gson)
        val vSlice = if (vSliceNode == null || vSliceNode.isJsonNull) null else parseVariant(vSliceNode.asJsonObject, gson)
        require(psych != null || vSlice != null) { "Canción $songId sin variantes." }
        songs[songId] = com.fnfmodmaker.model.IndependentSong(songId, psych, vSlice)
    }
    val legacy = root.get("legacyDocumentJson")
    return com.fnfmodmaker.model.IndependentChartCollection(songs = songs.build(),
        legacyDocumentJson = if (legacy == null || legacy.isJsonNull) "" else legacy.asString)
}

/** Parse one engine variant. */
private fun parseVariant(node: JsonObject, gson: Gson): com.fnfmodmaker.model.IndependentVariant {
    val metadataNode = node.get("metadata") ?: error("Falta metadata de la variante.")
    require(metadataNode.isJsonObject) { "metadata debe ser un objeto." }
    val metadata = gson.fromJson(metadataNode, com.fnfmodmaker.model.SongConfig::class.java)
        ?: error("metadata inválido.")
    val difficulties = node.get("difficulties")?.let {
        require(it.isJsonArray) { "difficulties debe ser una lista." }
        it.asJsonArray.map { it.asString }.toSet()
    } ?: emptySet()
    val charts = node.get("charts")?.let {
        require(it.isJsonObject) { "charts debe ser un objeto." }
        it.asJsonObject.mapValues { (_, chartNode) ->
            require(chartNode.isJsonObject) { "Chart inválido." }
            gson.fromJson(chartNode, com.fnfmodmaker.model.ChartDraft::class.java)
                ?: error("Chart inválido.")
        }
    } ?: emptyMap()
    val histories = node.get("histories")?.let {
        require(it.isJsonObject) { "histories debe ser un objeto." }
        it.asJsonObject.mapValues { (_, histNode) ->
            require(histNode.isJsonObject) { "Historial inválido." }
            gson.fromJson(histNode, com.fnfmodmaker.model.ChartPersistentHistory::class.java)
                ?: error("Historial inválido.")
        }
    } ?: emptyMap()
    val uneditable = node.get("uneditableDifficulties")?.let {
        require(it.isJsonObject) { "uneditableDifficulties debe ser un objeto." }
        it.asJsonObject.mapValues { (_, v) -> v.asString }.toMap()
    } ?: emptyMap()
    val originals = node.get("originals")?.let {
        require(it.isJsonArray) { "originals debe ser una lista." }
        it.asJsonArray.map { refNode ->
            require(refNode.isJsonObject) { "Original inválido." }
            gson.fromJson(refNode, com.fnfmodmaker.model.IndependentOriginal::class.java)
                ?: error("Original inválido.")
        }
    } ?: emptyList()
    val unknownMetadataJson = node.get("unknownMetadataJson")?.takeUnless { it.isJsonNull }?.asString ?: ""
    return com.fnfmodmaker.model.IndependentVariant(
        metadata = metadata,
        difficulties = difficulties,
        charts = charts,
        histories = histories,
        uneditableDifficulties = uneditable,
        originals = originals,
        unknownMetadataJson = unknownMetadataJson
    )
}

/** Serialize [IndependentChartCollection] into the independentCharts JSON block. */
internal fun independentChartsToJson(collection: com.fnfmodmaker.model.IndependentChartCollection, gson: Gson): JsonObject {
    val root = JsonObject()
    collection.songs.forEach { (id, song) ->
        val songObj = JsonObject()
        song.psych?.let { songObj.add("psych", variantToJson(it, gson)) }
        song.vSlice?.let { songObj.add("vslice", variantToJson(it, gson)) }
        root.add(id, songObj)
    }
    if (collection.legacyDocumentJson.isNotEmpty()) root.addProperty("legacyDocumentJson", collection.legacyDocumentJson)
    return root
}

private fun variantToJson(v: com.fnfmodmaker.model.IndependentVariant, gson: Gson): JsonObject {
    val obj = JsonObject()
    obj.add("metadata", gson.toJsonTree(v.metadata).asJsonObject)
    obj.add("difficulties", JsonArray().apply { v.difficulties.sorted().forEach { addProperty(it) } })
    val charts = JsonObject()
    v.charts.forEach { (d, c) -> charts.add(d, gson.toJsonTree(c).asJsonObject) }
    obj.add("charts", charts)
    val histories = JsonObject()
    v.histories.forEach { (d, h) -> histories.add(d, gson.toJsonTree(h).asJsonObject) }
    obj.add("histories", histories)
    val uneditable = JsonObject()
    v.uneditableDifficulties.forEach { (d, p) -> uneditable.addProperty(d, p) }
    obj.add("uneditableDifficulties", uneditable)
    val originals = com.google.gson.JsonArray()
    v.originals.forEach { o ->
        originals.add(gson.toJsonTree(o).asJsonObject)
    }
    obj.add("originals", originals)
    if (v.unknownMetadataJson.isNotEmpty()) obj.addProperty("unknownMetadataJson", v.unknownMetadataJson)
    return obj
}

/** Migration result: migrated v2 collection plus evidence of what was preserved. */
internal data class MigrationResult(
    val collection: IndependentChartCollection,
    val legacyPreserved: Boolean,
    val engineAttributedCount: Int
)
