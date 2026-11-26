package com.fnfmodmaker.model

import org.json.JSONArray
import org.json.JSONObject

internal data class CharacterJsonPreview(
    val format: String, val source: String, val id: String, val name: String, val assetPath: String,
    val scale: String, val flip: Boolean, val positionX: String, val positionY: String,
    val cameraX: String, val cameraY: String, val prefixes: Map<String, String>,
    val settings: Map<String, CharacterAnimationSettings>, val warnings: List<String>,
    val danceEvery: String = "1", val singTime: String = "4",
    val startingAnimation: String = "idle", val isPixel: Boolean = false
)

/** Imports editable fields only. The original JSON remains in the project for later recovery. */
internal fun parseCharacterJson(text: String, fileName: String, side: CharacterSide): CharacterJsonPreview {
    require(text.toByteArray(Charsets.UTF_8).size in 1..512_000) { "El JSON debe tener un máximo de 512 KB." }
    var depth = 0; var inString = false; var escaped = false
    for (c in text) {
        if (inString) {
            if (escaped) escaped = false else if (c == '\\') escaped = true else if (c == '"') inString = false
        } else if (c == '"') inString = true
        else if (c == '{' || c == '[') { depth++; require(depth <= 64) { "JSON demasiado profundo." } }
        else if (c == '}' || c == ']') { depth--; require(depth >= 0) { "JSON inválido." } }
    }
    require(!inString && depth == 0) { "JSON incompleto." }
    val json = JSONObject(text)
    val vSlice = json.has("assetPath") && json.has("animations")
    val psych = json.has("image") && json.has("animations")
    require(vSlice.xor(psych)) { "JSON no reconocido: se requiere un personaje Psych o V-Slice." }
    val format = if (vSlice) "V-Slice" else "Psych"
    val asset = json.optString(if (vSlice) "assetPath" else "image").trim()
    require(asset.isNotEmpty() && asset.length <= 512) { "Falta la ruta de la imagen del personaje." }
    val id = slugify(fileName.substringBeforeLast('.')).take(64).ifBlank { "personaje" }
    val display = if (vSlice) json.optString("name", id).take(100) else id
    val scale = json.optDouble("scale", 1.0)
    require(scale.isFinite() && scale > 0 && scale <= 100) { "Escala inválida." }
    fun pair(array: JSONArray?, label: String): Pair<Int, Int> {
        if (array == null) return 0 to 0
        require(array.length() == 2) { "$label debe tener dos números." }
        fun exact(i: Int): Int {
            val n = array.optDouble(i, Double.NaN)
            require(n.isFinite() && n >= -999999 && n <= 999999 && n % 1.0 == 0.0) { "$label necesita valores enteros entre -999999 y 999999." }
            return n.toInt()
        }
        return exact(0) to exact(1)
    }
    val pos = pair(json.optJSONArray(if (vSlice) "offsets" else "position"), "Posición")
    val camera = pair(json.optJSONArray(if (vSlice) "cameraOffsets" else "camera_position"), "Cámara")
    val animationArray = json.optJSONArray("animations") ?: error("Falta la lista de animaciones.")
    require(animationArray.length() in 1..35) { "Se admiten entre 1 y 35 animaciones." }
    val prefixes = linkedMapOf<String, String>(); val settings = linkedMapOf<String, CharacterAnimationSettings>()
    val warnings = mutableListOf<String>()
    if (vSlice && json.optString("renderType", "sparrow").lowercase() != "sparrow") warnings.add("Este personaje usa ${json.optString("renderType")}; el editor solo admite atlas Sparrow, no su renderizador original.")
    for (i in 0 until animationArray.length()) {
        val anim = animationArray.optJSONObject(i) ?: error("Animación inválida.")
        val name = anim.optString(if (vSlice) "name" else "anim")
        val prefix = anim.optString(if (vSlice) "prefix" else "name")
        require(name.matches(Regex("[A-Za-z0-9_-]{1,32}")) && prefix.isNotBlank() && prefix.length <= 128 && name !in prefixes) { "Animación inválida o repetida: $name." }
        val fps = anim.optInt(if (vSlice) "frameRate" else "fps", 24)
        require(fps in 1..60) { "FPS no compatible para $name (1–60)." }
        val offsets = pair(anim.optJSONArray("offsets"), "Offsets de $name")
        if ((anim.optJSONArray(if (vSlice) "frameIndices" else "indices")?.length() ?: 0) > 0) warnings.add("$name usa índices de frames: se conservan en el JSON original, pero no se aplican al generar el personaje.")
        prefixes[name] = prefix
        settings[name] = CharacterAnimationSettings(fps.toString(), offsets.first.toString(), offsets.second.toString(), anim.optBoolean(if (vSlice) "looped" else "loop", false))
    }
    require("idle" in prefixes && prefixes.keys.count { it !in CHARACTER_ANIMATIONS } <= 30) { "Se necesita idle y un máximo de 30 animaciones personalizadas." }
    // Engine-specific properties captured for round-trip fidelity; each is validated
    // against the range its engine schema actually accepts.
    fun boundedNumber(field: String, fallback: Double, max: Double): String {
        val value = if (json.has(field)) json.optDouble(field, fallback) else fallback
        if (json.has(field)) require(value.isFinite() && value >= 0.0 && value <= max) { "$field inválido." }
        return value.toString()
    }
    val danceEvery = if (vSlice) boundedNumber("danceEvery", 1.0, 64.0) else "1"
    val singTime = if (vSlice) boundedNumber("singTime", 4.0, 32.0)
    else boundedNumber("sing_duration", 4.0, 32.0)
    val startingAnimation = if (vSlice) json.optString("startingAnimation", "idle").also {
        if (json.has("startingAnimation")) require(it.matches(Regex("[A-Za-z0-9_-]{1,32}"))) { "startingAnimation inválido." }
    } else "idle"
    val isPixel = if (vSlice) json.optBoolean("isPixel", false) else false
    warnings.add("El PNG y el XML Sparrow deben elegirse por separado; verifica que coincidan con $asset.")
    warnings.add("Se regenerará el JSON al exportar: índices, propiedades no editables y comportamientos específicos no se trasladan. El original se conserva en el proyecto editable.")
    return CharacterJsonPreview(format, text, id, display, asset, scale.toString(), json.optBoolean(if (vSlice) "flipX" else "flip_x", false),
        pos.first.toString(), pos.second.toString(), (if (psych && side == CharacterSide.PLAYER) -camera.first else camera.first).toString(), camera.second.toString(), prefixes, settings, warnings.distinct(),
        danceEvery, singTime, startingAnimation, isPixel)
}

internal fun applyCharacterJson(current: CharacterDraft, data: CharacterJsonPreview): CharacterDraft = current.copy(
    enabled = true, charId = data.id, displayName = data.name, useAtlas = true,
    scale = data.scale, flipX = data.flip, positionX = data.positionX, positionY = data.positionY,
    cameraX = data.cameraX, cameraY = data.cameraY, atlasPrefixes = data.prefixes,
    customAnimations = data.prefixes.keys.filter { it !in CHARACTER_ANIMATIONS },
    animationSettings = data.settings, importedSourceJson = data.source, importedSourceFormat = data.format,
    danceEvery = data.danceEvery, singTime = data.singTime,
    startingAnimation = if (data.startingAnimation in data.prefixes.keys) data.startingAnimation else "idle",
    isPixel = data.isPixel
)
