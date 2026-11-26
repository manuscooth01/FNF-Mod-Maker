package com.fnfmodmaker.project

import com.fnfmodmaker.model.IndependentChartCollection
import com.fnfmodmaker.model.IndependentSong
import com.fnfmodmaker.model.IndependentVariant
import com.fnfmodmaker.model.SongConfig
import com.fnfmodmaker.model.ChartDraft
import com.fnfmodmaker.model.ChartPersistentHistory
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject

/**
 * Chart schema versions:
 *   null        — original unversioned shared-chart schema (legacy).
 *   1           — same shared-chart schema, explicit marker.
 *   2           — independent charts per engine: [ModConfig.independentCharts] required.
 * Any other value is rejected before deserialization so an older app never silently
 * erases a newer chart schema on the next save.
 */
internal val CHART_SCHEMA_LEGACY: Int? = null
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
    require(version != null && version.isJsonPrimitive && version.asJsonPrimitive.isNumber && version.asString == "2") {
        "El proyecto requiere el esquema de charts independientes v2; chartSchemaVersion ausente o inválido."
    }
    val independentNode = config.get("independentCharts")
    require(independentNode != null) { "Falta independentCharts en el esquema v2." }
    require(independentNode.isJsonObject) { "independentCharts debe ser un objeto." }
    val collection = parseIndependentCharts(independentNode.asJsonObject, gson)
    require(collection.songs.isNotEmpty()) { "El esquema v2 requiere al menos una canción." }
    return collection
}

/** Parse an independentCharts JSON block into [IndependentChartCollection]. */
internal fun parseIndependentCharts(root: JsonObject, gson: Gson): IndependentChartCollection {
    val songs = mutableMapOf<String, IndependentSong>()
    root.entrySet().filterNot { it.key == "legacyDocumentJson" }.forEach { (songId, songNode) ->
        require(songNode.isJsonObject) { "Canción inválida: $songId." }
        val songObj = songNode.asJsonObject
        // Song nodes carry only their identity and engine variants; any other key is a
        // malformed v2 payload and is rejected instead of being read as hidden data.
        require(songObj.keySet().all { it == "id" || it == "psych" || it == "vslice" }) {
            "Variante de canción inválida: $songId."
        }
        val psychNode = songObj.get("psych")
        val vSliceNode = songObj.get("vslice")
        require((psychNode == null || psychNode.isJsonObject || psychNode.isJsonNull) &&
            (vSliceNode == null || vSliceNode.isJsonObject || vSliceNode.isJsonNull)) {
            "Variante de canción inválida: $songId."
        }
        val psych = if (psychNode == null || psychNode.isJsonNull) null else parseVariant(psychNode.asJsonObject, gson)
        val vSlice = if (vSliceNode == null || vSliceNode.isJsonNull) null else parseVariant(vSliceNode.asJsonObject, gson)
        // A song without attributed variants is legal: unattributable data stays preserved
        // verbatim in the legacy deposit. No empty variants are fabricated to pass validation.
        songs[songId] = IndependentSong(songId, psych, vSlice)
    }
    val legacy = root.get("legacyDocumentJson")
    require(legacy == null || legacy.isJsonNull || (legacy.isJsonPrimitive && legacy.asJsonPrimitive.isString)) {
        "legacyDocumentJson debe ser texto."
    }
    return IndependentChartCollection(songs = songs.toMap(),
        legacyDocumentJson = if (legacy == null || legacy.isJsonNull) "" else legacy.asString)
}

/** Parse one engine variant. */
private fun parseVariant(node: JsonObject, gson: Gson): IndependentVariant {
    val metadataNode = node.get("metadata") ?: error("Falta metadata de la variante.")
    require(metadataNode.isJsonObject) { "metadata debe ser un objeto." }
    val metadata = gson.fromJson(metadataNode, SongConfig::class.java)
        ?: error("metadata inválido.")
    val difficulties = node.get("difficulties")?.let {
        require(it.isJsonArray) { "difficulties debe ser una lista." }
        it.asJsonArray.map { it.asString }.toSet()
    } ?: emptySet()
    val charts = node.get("charts")?.let {
        require(it.isJsonObject) { "charts debe ser un objeto." }
        it.asJsonObject.entrySet().associate { entry ->
            require(entry.value.isJsonObject) { "Chart inválido." }
            entry.key to (gson.fromJson(entry.value, ChartDraft::class.java)
                ?: error("Chart inválido."))
        }
    } ?: emptyMap()
    val histories = node.get("histories")?.let {
        require(it.isJsonObject) { "histories debe ser un objeto." }
        it.asJsonObject.entrySet().associate { entry ->
            require(entry.value.isJsonObject) { "Historial inválido." }
            entry.key to (gson.fromJson(entry.value, ChartPersistentHistory::class.java)
                ?: error("Historial inválido."))
        }
    } ?: emptyMap()
    val uneditable = node.get("uneditableDifficulties")?.let {
        require(it.isJsonObject) { "uneditableDifficulties debe ser un objeto." }
        it.asJsonObject.entrySet().associate { entry -> entry.key to entry.value.asString }
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
    return IndependentVariant(
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
internal fun independentChartsToJson(collection: IndependentChartCollection, gson: Gson): JsonObject {
    val root = JsonObject()
    collection.songs.forEach { (id, song) ->
        val songObj = JsonObject()
        songObj.addProperty("id", id)
        song.psych?.let { songObj.add("psych", variantToJson(it, gson)) }
        song.vSlice?.let { songObj.add("vslice", variantToJson(it, gson)) }
        root.add(id, songObj)
    }
    if (collection.legacyDocumentJson.isNotEmpty()) root.addProperty("legacyDocumentJson", collection.legacyDocumentJson)
    return root
}

private fun variantToJson(v: IndependentVariant, gson: Gson): JsonObject {
    val obj = JsonObject()
    obj.add("metadata", gson.toJsonTree(v.metadata).asJsonObject)
    obj.add("difficulties", JsonArray().apply { v.difficulties.sorted().forEach { add(it) } })
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
