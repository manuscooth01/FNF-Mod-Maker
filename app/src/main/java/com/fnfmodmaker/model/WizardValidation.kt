package com.fnfmodmaker.model

import java.io.File

data class WizardIssue(val step: Int, val message: String, val songIndex: Int? = null)

fun characterInputProblems(draft: CharacterDraft, fileExists: (String) -> Boolean = { File(it).isFile }): List<String> = buildList {
    addAll(draft.problems)
    val images = when {
        draft.useAtlas -> listOfNotNull(draft.atlasSheet)
        draft.useIndividualFrames -> draft.animationFrames.values.flatten().map { it.image }
        else -> listOfNotNull(draft.sheet)
    }
    if (images.any { !fileExists(it.path) }) add("Vuelve a elegir las imágenes que ya no están disponibles.")
    if (draft.useAtlas && draft.atlasXml != null && !fileExists(draft.atlasXml.path)) add("Vuelve a elegir el XML del atlas.")
    if (draft.icon != null && !fileExists(draft.icon.path)) add("Vuelve a elegir el icono de vida.")
}

/** Una sola validación para navegación, resumen y exportación. */
fun validateWizard(config: ModConfig, fileExists: (String) -> Boolean = { File(it).isFile }): List<WizardIssue> = buildList {
    if (config.stageDraft.enabled) {
        val stage = config.stageDraft
        if (stage.stageId.isBlank()) add(WizardIssue(1, "Escenario: escribe un nombre para el identificador."))
        if (stage.backgroundImage == null || !fileExists(stage.backgroundImage.path)) add(WizardIssue(1, "Escenario: elige una imagen de fondo disponible."))
        if (!stage.cameraPosition.zoom.isFinite() || stage.cameraPosition.zoom !in 0.4f..2f) add(WizardIssue(1, "Escenario: el zoom debe estar entre 0.4 y 2."))
        if (stage.hasUnsupportedFeatures()) add(WizardIssue(1, "Escenario: los objetos avanzados todavía no se exportan. Desactiva el escenario para conservarlos."))
    }
    if (config.psychScripts.isNotEmpty() && !config.engine.includesPsych) add(WizardIssue(0, "Los scripts Lua necesitan un destino que incluya Psych Engine."))
    if (config.psychScripts.any { !isValidPsychScript(it.key, it.value) }) add(WizardIssue(0, "Hay un script Psych inválido; elimínalo antes de exportar."))
    if (config.modTitle.isBlank()) add(WizardIssue(0, "Escribe el nombre del mod."))
    if (config.songs.isEmpty()) add(WizardIssue(1, "Añade al menos una canción."))
    val repeated = config.songs.filter { it.songName.isNotBlank() }
        .groupBy { it.songFolder.lowercase() }.filterValues { it.size > 1 }.keys
    val exportConfig = config.effectiveExportConfig()
    com.fnfmodmaker.mod.vSliceCharacterProblems(exportConfig).forEach { add(WizardIssue(2, it)) }
    config.songs.forEachIndexed { index, song ->
        val label = "Canción ${index + 1}"
        com.fnfmodmaker.mod.vSliceExportProblems(song, exportConfig.songs[index]).forEach { add(WizardIssue(1, it, index)) }
        if (song.songName.isBlank()) add(WizardIssue(1, "$label: escribe un nombre.", index))
        if (song.songName.isNotBlank() && song.songFolder.lowercase() in repeated) {
            add(WizardIssue(1, "$label: usa un nombre de carpeta distinto al de las otras canciones.", index))
        }
        if (song.songFolder.contains('/') || song.songFolder.contains('\\')) {
            add(WizardIssue(1, "$label: el nombre no puede contener barras.", index))
        }
        if (song.bpmValue !in 1..400) add(WizardIssue(1, "$label: el BPM debe estar entre 1 y 400.", index))
        val speed = song.speed.toFloatOrNull()
        if (speed == null || !speed.isFinite() || speed <= 0f) {
            add(WizardIssue(1, "$label: la velocidad debe ser mayor que cero.", index))
        }
        if (song.difficulties.isEmpty()) add(WizardIssue(1, "$label: elige al menos una dificultad.", index))
        song.orderedDifficulties.forEach { difficulty ->
            val problems = config.getChart(song.songFolder, difficulty)?.exportProblems().orEmpty()
            if (problems.isNotEmpty()) add(WizardIssue(1, "$label ($difficulty): no se puede exportar sin perder ${problems.joinToString()}. Conserva el proyecto editable.", index))
        }
        if (song.instrumental == null) {
            add(WizardIssue(3, "$label: elige la instrumental.", index))
        } else if (!fileExists(song.instrumental.path)) {
            add(WizardIssue(3, "$label: vuelve a elegir la instrumental; el archivo ya no está disponible.", index))
        }
        listOf("voces del jugador" to song.vocalsPlayer, "voces del rival" to song.vocalsOpponent).forEach { (name, audio) ->
            if (audio != null && !fileExists(audio.path)) add(WizardIssue(3, "$label: vuelve a elegir las $name.", index))
        }
    }
    if (config.character.enabled) {
        characterInputProblems(config.character, fileExists).forEach { add(WizardIssue(2, "Personaje: $it")) }
    }
    if (config.icon != null && !fileExists(config.icon.path)) add(WizardIssue(3, "Vuelve a elegir el icono del mod."))
}

/** Scripts are confined to mods/<mod>/scripts/<name>.lua on export. */
fun isValidPsychScript(name: String, code: String): Boolean =
    name.matches(Regex("[a-z0-9_-]{1,48}")) && code.isNotBlank() && code.length <= 16000 && !code.contains('\u0000')
