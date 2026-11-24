package com.fnfmodmaker.mod

import com.fnfmodmaker.model.ModConfig
import com.fnfmodmaker.model.SongConfig
import com.fnfmodmaker.modimport.patchVSliceChart
import com.fnfmodmaker.modimport.readVSliceJson
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

internal fun vSliceImportProblems(song: SongConfig, allowedRoles: Set<String> = emptySet()): List<String> {
    val o = song.vSliceOrigin ?: return emptyList()
    return buildList {
        fun changed(field: String, original: String, current: String) {
            if (original != current) add("${o.folder}: $field de metadata V-Slice es de solo lectura (original: ${JSONObject.quote(original)}; actual: ${JSONObject.quote(current)}). Restaura el valor original.")
        }
        changed("carpeta de canción", o.folder, song.songFolder)
        changed("nombre", o.originalName, song.songName)
        changed("artista", o.originalArtist, song.artist)
        changed("BPM", o.originalBpm, song.bpm)
        changed("velocidad", o.originalSpeed, song.speed)
        changed("offset", o.originalOffset, song.offsetMs)
        if ("player" !in allowedRoles) changed("jugador", o.originalPlayer, song.player)
        if ("opponent" !in allowedRoles) changed("rival", o.originalOpponent, song.opponent)
        if ("girlfriend" !in allowedRoles) changed("GF", o.originalGirlfriend, song.girlfriend)
        changed("escenario", o.originalStage, song.stageVSlice)
        if (song.difficulties != o.editableDifficulties)
            add("${o.folder}: las dificultades originales son de solo lectura; restaura las dificultades editables importadas.")
        if ((song.vocalsPlayer != null) != o.playerVoiceFile.isNotEmpty() || (song.vocalsOpponent != null) != o.opponentVoiceFile.isNotEmpty())
            add("${o.folder}: no se puede añadir ni quitar una pista de voz sin modificar la metadata original; restaura las pistas seleccionadas.")
    }
}

/** Check both user input and the actual builder projection: neither may hide the other. */
internal fun assignedVSliceRoles(config: ModConfig): Set<String> {
    val a = config.characterAssignments ?: return emptySet()
    return listOf("player" to a.player, "opponent" to a.opponent, "girlfriend" to a.girlfriend)
        .filter { (_, id) -> id.isNotEmpty() && config.characterLibrary.singleOrNull { it.id == id }?.draft?.enabled == true }
        .map { it.first }.toSet()
}
internal fun vSliceExportProblems(raw: SongConfig, effective: SongConfig, config: ModConfig): List<String> =
    (vSliceImportProblems(raw) + vSliceImportProblems(effective, assignedVSliceRoles(config))).distinct()

/** Duplicate keys have ambiguous meaning and cannot safely survive reserialization. */
internal fun rejectDuplicateJsonKeys(text: String) {
    val reader = com.google.gson.stream.JsonReader(java.io.StringReader(text))
    fun value() {
        when (reader.peek()) {
            com.google.gson.stream.JsonToken.BEGIN_OBJECT -> {
                reader.beginObject(); val names = mutableSetOf<String>()
                while (reader.hasNext()) {
                    val name = reader.nextName()
                    require(names.add(name)) { "La metadata contiene claves duplicadas; no se puede modificar sin ambigüedad." }
                    value()
                }
                reader.endObject()
            }
            com.google.gson.stream.JsonToken.BEGIN_ARRAY -> { reader.beginArray(); while (reader.hasNext()) value(); reader.endArray() }
            com.google.gson.stream.JsonToken.STRING, com.google.gson.stream.JsonToken.NUMBER -> reader.nextString()
            com.google.gson.stream.JsonToken.BOOLEAN -> reader.nextBoolean()
            com.google.gson.stream.JsonToken.NULL -> reader.nextNull()
            else -> error("Metadata JSON inválida.")
        }
    }
    reader.use { value(); require(it.peek() == com.google.gson.stream.JsonToken.END_DOCUMENT) { "Metadata JSON inválida." } }
}

/** Only explicitly assigned roles are patched; all other JSON values remain from the original. */
internal fun patchImportedVSliceMetadata(original: String, raw: SongConfig, effective: SongConfig, allowed: Set<String>): String {
    val origin = raw.vSliceOrigin ?: error("Falta el origen V-Slice.")
    val changes = listOf(Triple("player", origin.originalPlayer, effective.player), Triple("opponent", origin.originalOpponent, effective.opponent), Triple("girlfriend", origin.originalGirlfriend, effective.girlfriend))
        .filter { it.first in allowed && it.second != it.third }
    if (changes.isEmpty()) return original
    // Validate bounds/depth first, then use Gson's lazy JSON numbers so unknown
    // large integers and high-precision decimals are not rounded by JSONObject.
    readVSliceJson(original, "metadata original")
    rejectDuplicateJsonKeys(original)
    val json = com.google.gson.JsonParser.parseString(original).asJsonObject
    val play = json.get("playData")?.takeIf { it.isJsonObject }?.asJsonObject ?: error("Falta playData en la metadata original.")
    val chars = when {
        !play.has("characters") -> com.google.gson.JsonObject().also { play.add("characters", it) }
        play.get("characters").isJsonObject -> play.getAsJsonObject("characters")
        else -> error("characters no es un objeto.")
    }
    changes.forEach { (role, before, after) ->
        val originalValue = chars.get(role)
        require(originalValue == null || (originalValue.isJsonPrimitive && originalValue.asJsonPrimitive.isString)) { "La referencia original $role no es texto; se conserva sin editar." }
        require((originalValue?.asString ?: "") == before) { "La metadata original de $role no coincide con el proyecto; no se exportó." }
        require(after.matches(Regex("[A-Za-z0-9_-]{1,64}"))) { "ID de personaje inseguro: $after" }
        chars.addProperty(role, after)
    }
    return json.toString()
}

/** An editable imported character must still target the exact original JSON. */
internal fun vSliceCharacterProblems(config: ModConfig): List<String> {
    val character = config.character
    if (!character.enabled || character.importedSourceFormat != "V-Slice" || character.importedVSliceCharacterId.isEmpty() || config.songs.none { it.vSliceOrigin != null }) return emptyList()
    val savedId = character.importedVSliceCharacterId
    val expected = "data/characters/$savedId.json"
    return buildList {
        if (!savedId.matches(Regex("[A-Za-z0-9_-]{1,64}")) || config.importedModResources.count { it.converted && it.originalPath == expected } != 1)
            add("Personaje V-Slice: no se encontró un único JSON original editable para ${JSONObject.quote(savedId)}.")
    }
}

internal fun originalVSliceText(config: ModConfig, path: String, appRoot: File, mustBeConverted: Boolean): String {
    val resource = config.importedModResources.filter { it.originalPath == path }.singleOrNull() ?: error("Falta el original $path.")
    require(!mustBeConverted || resource.converted) { "El original $path no está autorizado para regeneración." }
    val file = File(resource.asset?.path ?: error("Falta el original $path."))
    require(file.canonicalFile == file.absoluteFile && file.canonicalPath.startsWith(appRoot.canonicalPath + File.separator) && file.isFile && file.length() in 1..4_000_000) { "El original $path falta o no es seguro." }
    return file.readText(Charsets.UTF_8)
}

internal fun buildImportedVSliceChart(config: ModConfig, song: SongConfig, appRoot: File): String {
    val origin = song.vSliceOrigin ?: error("No hay origen V-Slice.")
    val difficulties = com.fnfmodmaker.model.exportDifficulties(config, song)
    val drafts = difficulties.mapNotNull { diff -> config.getChart(song.songFolder, diff)?.let { diff to it } }.toMap()
    val retained = difficulties.filter { it !in drafts && it in origin.uneditableDifficulties }.toSet()
    val speed = if (song.speed != origin.originalSpeed) song.speedValue else null
    return patchVSliceChart(originalVSliceText(config, origin.chartPath, appRoot, true), drafts, origin.editableDifficulties, speed, retained)
}

/** Free editing patches known fields while leaving unrelated metadata in the original tree. */
internal fun freeVSliceMetadata(original: String, raw: SongConfig, effective: SongConfig, difficulties: List<String>): String {
    val origin = raw.vSliceOrigin ?: error("Falta origen V-Slice.")
    readVSliceJson(original, "metadata original"); rejectDuplicateJsonKeys(original)
    val json = com.google.gson.JsonParser.parseString(original).asJsonObject
    val play = json.getAsJsonObject("playData") ?: error("Falta playData.")
    val chars = play.getAsJsonObject("characters") ?: com.google.gson.JsonObject().also { play.add("characters", it) }
    fun text(obj: com.google.gson.JsonObject, key: String, value: String, old: String) { if (value != old) obj.addProperty(key, value) }
    text(json, "songName", raw.songName, origin.originalName); text(json, "artist", raw.artist, origin.originalArtist)
    text(chars, "player", effective.player, origin.originalPlayer); text(chars, "opponent", effective.opponent, origin.originalOpponent)
    text(chars, "girlfriend", effective.girlfriend, origin.originalGirlfriend); text(play, "stage", effective.stageVSlice, origin.originalStage)
    fun removeVoiceReference(key: String, originalFile: String, removed: Boolean) {
        if (!removed || !originalFile.startsWith("Voices-") || !originalFile.endsWith(".ogg")) return
        val id = originalFile.removePrefix("Voices-").removeSuffix(".ogg")
        val values = chars.get(key)?.takeIf { it.isJsonArray }?.asJsonArray ?: return
        val remaining = com.google.gson.JsonArray()
        values.forEach { value -> if (!(value.isJsonPrimitive && value.asJsonPrimitive.isString && value.asString == id)) remaining.add(value.deepCopy()) }
        chars.add(key, remaining)
    }
    removeVoiceReference("playerVocals", origin.playerVoiceFile, effective.vocalsPlayer == null)
    removeVoiceReference("opponentVocals", origin.opponentVoiceFile, effective.vocalsOpponent == null)
    if (raw.bpm != origin.originalBpm) {
        val times = json.getAsJsonArray("timeChanges") ?: error("Falta timeChanges.")
        require(times.size() > 0 && times[0].isJsonObject) { "Tempo inicial inválido." }
        times[0].asJsonObject.addProperty("bpm", raw.bpmValue)
    }
    if (raw.offsetMs != origin.originalOffset) {
        val offsets = json.getAsJsonObject("offsets") ?: com.google.gson.JsonObject().also { json.add("offsets", it) }
        offsets.addProperty("instrumental", raw.offsetValue)
    }
    val arr = com.google.gson.JsonArray().apply { difficulties.forEach { add(it) } }
    play.add("difficulties", arr)
    play.get("ratings")?.takeIf { it.isJsonObject }?.asJsonObject?.let { ratings ->
        ratings.keySet().toList().filter { it !in difficulties }.forEach { ratings.remove(it) }
        difficulties.filterNot { ratings.has(it) }.forEach { ratings.addProperty(it, 1) }
    }
    return json.toString()
}

/** Preserve source-only character data and animation fields; replace only editable fields. */
internal fun patchVSliceCharacter(original: String, generated: String, hasIcon: Boolean): String {
    val source = readVSliceJson(original, "personaje original")
    val edit = readVSliceJson(generated, "personaje generado")
    listOf("name", "assetPath", "scale", "flipX", "offsets", "cameraOffsets").forEach { if (edit.has(it)) source.put(it, edit.get(it)) }
    if (hasIcon && edit.optJSONObject("healthIcon") != null) {
        val icon = source.optJSONObject("healthIcon") ?: JSONObject()
        icon.put("id", edit.getJSONObject("healthIcon").optString("id")); source.put("healthIcon", icon)
    }
    val oldAnimations = source.optJSONArray("animations") ?: JSONArray()
    val editedAnimations = edit.getJSONArray("animations")
    val byName = mutableMapOf<String, JSONObject>()
    for (i in 0 until oldAnimations.length()) oldAnimations.optJSONObject(i)?.let { byName[it.optString("name")] = it }
    val result = JSONArray()
    for (i in 0 until editedAnimations.length()) {
        val next = editedAnimations.getJSONObject(i); val name = next.getString("name")
        val originalAnim = byName[name] ?: JSONObject()
        // frameIndices and unrecognized fields are not represented by Character Studio.
        listOf("name", "prefix", "frameRate", "looped", "offsets").forEach { if (next.has(it)) originalAnim.put(it, next.get(it)) }
        result.put(originalAnim)
    }
    source.put("animations", result)
    return source.toString(2)
}

/** Patch Psych fields represented by Character Studio without discarding source-only data. */
internal fun patchPsychCharacter(original: String, generated: String, hasIcon: Boolean): String {
    readVSliceJson(original, "personaje Psych original")
    readVSliceJson(generated, "personaje Psych generado")
    rejectDuplicateJsonKeys(original)
    val source = com.google.gson.JsonParser.parseString(original).asJsonObject
    val edit = com.google.gson.JsonParser.parseString(generated).asJsonObject
    listOf("image", "scale", "flip_x", "position", "camera_position").forEach { key ->
        edit.get(key)?.let { source.add(key, it.deepCopy()) }
    }
    if (hasIcon) edit.get("healthicon")?.let { source.add("healthicon", it.deepCopy()) }
    val edited = edit.get("animations")?.takeIf { it.isJsonArray }?.asJsonArray
    if (edited != null) {
        val old = source.get("animations")?.takeIf { it.isJsonArray }?.asJsonArray
        val byName = old?.filter { it.isJsonObject }?.associateBy { it.asJsonObject.get("anim")?.asString.orEmpty() }.orEmpty()
        val result = com.google.gson.JsonArray()
        edited.forEach { element ->
            require(element.isJsonObject) { "Animación Psych generada inválida." }
            val next = element.asJsonObject
            val name = next.get("anim")?.asString ?: error("Animación Psych sin ID.")
            val merged = byName[name]?.deepCopy()?.asJsonObject ?: com.google.gson.JsonObject()
            listOf("anim", "name", "fps", "loop", "offsets").forEach { key -> next.get(key)?.let { merged.add(key, it.deepCopy()) } }
            result.add(merged)
        }
        source.add("animations", result)
    }
    return source.toString()
}

/** Update documented level/week song arrays without rewriting unknown fields. */
internal fun rewriteSongReferences(original: String, deleted: Set<String>, renamed: Map<String, String>, psych: Boolean): String? {
    if (deleted.isEmpty() && renamed.isEmpty()) return null
    readVSliceJson(original, "nivel o semana original"); rejectDuplicateJsonKeys(original)
    val json = com.google.gson.JsonParser.parseString(original).asJsonObject
    val songs = json.get("songs")?.takeIf { it.isJsonArray }?.asJsonArray ?: return null
    val kept = com.google.gson.JsonArray(); var changed = false
    songs.forEach { entry ->
        val name = if (psych) entry.takeIf { it.isJsonArray }?.asJsonArray?.firstOrNull() else entry
        val raw = name?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
        val key = raw?.let { com.fnfmodmaker.model.psychSongPath(it) }
        when {
            key in deleted -> changed = true
            key != null && key in renamed -> {
                changed = true
                if (psych) {
                    val item = entry.deepCopy().asJsonArray
                    item.set(0, com.google.gson.JsonPrimitive(renamed.getValue(key)))
                    kept.add(item)
                } else kept.add(renamed.getValue(key))
            }
            else -> kept.add(entry.deepCopy())
        }
    }
    if (!changed) return null
    json.add("songs", kept)
    return json.toString()
}
