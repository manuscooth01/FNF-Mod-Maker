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
        if (song.songFolder != o.folder || song.songName != o.originalName || song.artist != o.originalArtist ||
            song.bpm != o.originalBpm || song.speed != o.originalSpeed || song.offsetMs != o.originalOffset ||
            song.player != o.originalPlayer || song.opponent != o.originalOpponent || song.girlfriend != o.originalGirlfriend || song.stageVSlice != o.originalStage)
            add("${o.folder}: la metadata V-Slice original es de solo lectura; deshaz los cambios de nombre, artista, BPM, velocidad, offset, personajes o escenario.")
        if (song.difficulties.any { it !in o.editableDifficulties })
            add("${o.folder}: hay dificultades no editables; desactívalas para conservar sus datos originales.")
        if ((song.vocalsPlayer != null) != o.playerVoiceFile.isNotEmpty() || (song.vocalsOpponent != null) != o.opponentVoiceFile.isNotEmpty())
            add("${o.folder}: no se puede añadir ni quitar una pista de voz sin modificar la metadata original; restaura las pistas seleccionadas.")
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
