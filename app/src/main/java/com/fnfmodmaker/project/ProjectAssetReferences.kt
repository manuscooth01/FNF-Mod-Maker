package com.fnfmodmaker.project

import com.google.gson.JsonElement
import com.google.gson.JsonObject

/**
 * Schema locations of PickedImage and PickedAudio in the v1 project model.
 * Never descend into unknown fields: an arbitrary object with path/displayName/uri
 * is not evidence that it references an app-owned file.
 *
 * For v2 projects, song media references live inside
 * `independentCharts.<songId>.<psych|vslice>.metadata.{instrumental,vocalsPlayer,vocalsOpponent}`
 * and original assets live inside `...<engine>.originals[].asset`. [visitProjectAssets]
 * visits both v1 and v2 locations; [visitIndependentMedia] covers only v2.
 */
internal fun visitProjectAssets(root: JsonObject, action: (JsonObject) -> Unit) {
    visitV1Assets(root, action)
    visitIndependentMedia(root, action)
}

private fun visitV1Assets(root: JsonObject, action: (JsonObject) -> Unit) {
    fun obj(node: JsonElement, label: String): JsonObject {
        require(node.isJsonObject) { "$label debe ser un objeto." }
        return node.asJsonObject
    }
    fun field(parent: JsonObject, key: String, required: Boolean = false) {
        val value = parent.get(key)
        if (value == null || value.isJsonNull) {
            require(!required) { "Falta el recurso $key." }
            return
        }
        val asset = obj(value, "Recurso $key")
        val path = asset.get("path"); val name = asset.get("displayName"); val uri = asset.get("uri")
        require(path?.isJsonPrimitive == true && path.asJsonPrimitive.isString && path.asString.isNotEmpty() &&
            name?.isJsonPrimitive == true && name.asJsonPrimitive.isString &&
            (uri == null || uri.isJsonNull || (uri.isJsonPrimitive && uri.asJsonPrimitive.isString))) {
            "Referencia de recurso $key inválida."
        }
        action(asset)
    }
    fun objectField(parent: JsonObject, key: String, action: (JsonObject) -> Unit) {
        val value = parent.get(key) ?: return
        if (!value.isJsonNull) action(obj(value, key))
    }
    fun arrayField(parent: JsonObject, key: String, action: (JsonObject) -> Unit) {
        val value = parent.get(key) ?: return
        if (value.isJsonNull) return
        require(value.isJsonArray) { "$key debe ser una lista." }
        value.asJsonArray.forEach { action(obj(it, key)) }
    }
    fun character(character: JsonObject) {
        listOf("sheet", "icon", "atlasSheet", "atlasXml").forEach { field(character, it) }
        arrayField(character, "variations") { variation ->
            field(variation, "sheet"); field(variation, "icon")
        }
        objectField(character, "animationFrames") { frames ->
            frames.entrySet().forEach { (animation, entries) ->
                require(entries.isJsonArray) { "Frames de $animation inválidos." }
                entries.asJsonArray.forEach { frame -> field(obj(frame, "Frame"), "image", required = true) }
            }
        }
    }
    field(root, "icon")
    arrayField(root, "songs") { song ->
        listOf("instrumental", "vocalsPlayer", "vocalsOpponent").forEach { field(song, it) }
    }
    objectField(root, "character", ::character) // pre-library projects
    arrayField(root, "characterLibrary") { entry ->
        val draft = entry.get("draft")
        require(draft != null && !draft.isJsonNull) { "Falta el personaje de la biblioteca." }
        character(obj(draft, "Personaje"))
    }
    objectField(root, "stageDraft") { stage ->
        field(stage, "backgroundImage")
        arrayField(stage, "sprites") { field(it, "image") }
    }
    arrayField(root, "importedModResources") { field(it, "asset") }
    // Asset Library 1.0: cada recurso conserva su archivo mediante la referencia tipada "file".
    arrayField(root, "assetPacks") { pack ->
        arrayField(pack, "assets") { asset -> field(asset, "file", required = true) }
    }
}

/** Visit media references inside v2 independentCharts variants. Typed references only. */
internal fun visitIndependentMedia(root: JsonObject, action: (JsonObject) -> Unit) {
    fun obj(node: JsonElement, label: String): JsonObject {
        require(node.isJsonObject) { "$label debe ser un objeto." }
        return node.asJsonObject
    }
    fun reference(asset: JsonObject, label: String) {
        val path = asset.get("path"); val name = asset.get("displayName"); val uri = asset.get("uri")
        require(path?.isJsonPrimitive == true && path.asJsonPrimitive.isString && path.asString.isNotEmpty() &&
            name?.isJsonPrimitive == true && name.asJsonPrimitive.isString &&
            (uri == null || uri.isJsonNull || (uri.isJsonPrimitive && uri.asJsonPrimitive.isString))) {
            "Referencia de recurso $label inválida."
        }
        action(asset)
    }
    fun field(parent: JsonObject, key: String) {
        val value = parent.get(key)
        if (value == null || value.isJsonNull) return
        val asset = obj(value, "Recurso $key")
        reference(asset, key)
    }
    val independent = root.get("independentCharts") ?: return
    // A legacy document serialized with serializeNulls carries the absent collection
    // as an explicit null; that is the legal legacy shape, not malformed v2 data.
    if (independent.isJsonNull) return
    require(independent.isJsonObject) { "independentCharts debe ser un objeto." }
    independent.asJsonObject.entrySet().forEach songLoop@{ (songId, songNode) ->
        if (songId == "legacyDocumentJson" || !songNode.isJsonObject) return@songLoop
        val songObj = songNode.asJsonObject
        listOf("psych", "vslice").forEach engineLoop@{ engineKey ->
            val variantNode = songObj.get(engineKey) ?: return@engineLoop
            if (!variantNode.isJsonObject) return@engineLoop
            val variant = variantNode.asJsonObject
            // metadata may contain audio references.
            variant.get("metadata")?.let { metadata ->
                if (metadata.isJsonObject) {
                    listOf("instrumental", "vocalsPlayer", "vocalsOpponent").forEach { field(metadata.asJsonObject, it) }
                }
            }
            // originals: either an "asset" media reference (nested under "asset") or opaque "contentJson".
            variant.get("originals")?.let { originals ->
                if (originals.isJsonArray) {
                    originals.asJsonArray.forEach originalLoop@{ ref ->
                        if (!ref.isJsonObject) return@originalLoop
                        val original = ref.asJsonObject
                        val assetNode = original.get("asset")
                        if (assetNode != null && assetNode.isJsonObject) {
                            val asset = assetNode.asJsonObject
                            val kind = asset.get("kind")?.asString
                            if (kind in setOf("image", "audio")) reference(asset, "original de $engineKey/$songId")
                        } else {
                            original.get("contentJson")?.let { require(it.isJsonPrimitive && it.asJsonPrimitive.isString) {
                                "Original sin medio ni documento opaco en $engineKey/$songId."
                            } }
                        }
                    }
                }
            }
        }
    }
}