package com.fnfmodmaker.mod

import com.fnfmodmaker.model.ModConfig
import com.fnfmodmaker.model.SongConfig
import com.fnfmodmaker.modimport.patchVSliceChart
import com.fnfmodmaker.modimport.readVSliceJson
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

internal fun vSliceImportProblems(song: SongConfig): List<String> {
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
        changed("jugador", o.originalPlayer, song.player)
        changed("rival", o.originalOpponent, song.opponent)
        changed("GF", o.originalGirlfriend, song.girlfriend)
        changed("escenario", o.originalStage, song.stageVSlice)
        if (song.difficulties != o.editableDifficulties)
            add("${o.folder}: las dificultades originales son de solo lectura; restaura las dificultades editables importadas.")
        if ((song.vocalsPlayer != null) != o.playerVoiceFile.isNotEmpty() || (song.vocalsOpponent != null) != o.opponentVoiceFile.isNotEmpty())
            add("${o.folder}: no se puede añadir ni quitar una pista de voz sin modificar la metadata original; restaura las pistas seleccionadas.")
    }
}

/** Check both user input and the actual builder projection: neither may hide the other. */
internal fun vSliceExportProblems(raw: SongConfig, effective: SongConfig): List<String> =
    (vSliceImportProblems(raw) + vSliceImportProblems(effective)).distinct()

/** An editable imported character must still target the exact original JSON. */
internal fun vSliceCharacterProblems(config: ModConfig): List<String> {
    val character = config.character
    if (!character.enabled || character.importedSourceFormat != "V-Slice" || config.songs.none { it.vSliceOrigin != null }) return emptyList()
    val paths = config.importedModResources.filter { it.converted && Regex("^data/characters/[A-Za-z0-9_-]{1,64}\\.json$").matches(it.originalPath) }
    if (paths.size != 1) return listOf("Personaje V-Slice importado: no se encontró un único JSON original editable; conserva el proyecto y revisa el personaje.")
    val original = paths.single().originalPath.substringAfterLast('/').removeSuffix(".json")
    val savedId = character.importedVSliceCharacterId
    return buildList {
        if (savedId.isNotEmpty() && savedId != original) add("Personaje V-Slice: ID original incompatible (archivo: ${JSONObject.quote(original)}; guardado: ${JSONObject.quote(savedId)}). Restaura el ID original.")
        if (character.charId != original || character.charFolder != original) add("Personaje V-Slice: el ID es de solo lectura (original: ${JSONObject.quote(original)}; actual: ${JSONObject.quote(character.charId)}). Restaura el ID original.")
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
    require(vSliceImportProblems(song).isEmpty()) { vSliceImportProblems(song).joinToString(" ") }
    require(config.charts[song.songFolder].orEmpty().keys.all { it in origin.editableDifficulties }) {
        "${song.songFolder}: un chart no editable contiene cambios que no se pueden exportar sin pérdida."
    }
    val drafts = song.difficulties.associateWith { diff -> config.getChart(song.songFolder, diff) ?: error("Falta el chart editable $diff de ${song.songFolder}; no se reemplazó el original.") }
    return patchVSliceChart(originalVSliceText(config, origin.chartPath, appRoot, true), drafts, origin.editableDifficulties)
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
