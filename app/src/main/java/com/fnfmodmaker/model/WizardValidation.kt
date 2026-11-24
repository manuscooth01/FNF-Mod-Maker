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

/** Shared preflight for wizard and builder; file names include variation output IDs. */
fun characterLibraryProblems(config: ModConfig, fileExists: (String) -> Boolean = { File(it).isFile }): List<String> = buildList {
    val entries = config.characterLibrary
    if (entries.size !in 1..64 || entries.any { !it.id.matches(Regex("[A-Za-z0-9_-]{1,64}")) } || entries.map { it.id }.toSet().size != entries.size)
        add("Biblioteca: identificadores internos inválidos o duplicados.")
    if (entries.none { it.id == config.selectedCharacterId }) add("Biblioteca: el personaje seleccionado no existe.")
    val assignments = config.resolvedAssignments()
    listOf("BF" to assignments.player, "DAD" to assignments.opponent, "GF" to assignments.girlfriend).forEach { (role, id) ->
        if (id.isNotEmpty()) {
            val entry = entries.singleOrNull { it.id == id }
            if (entry == null) add("$role: el personaje asignado no existe.")
            else if (!entry.draft.enabled) add("$role: activa el personaje asignado.")
        }
    }
    val names = mutableSetOf<String>()
    config.charactersToExport().forEach { entry ->
        val draft = entry.draft
        val prefix = draft.charFolder
        if (config.engine.includesPsych && draft.cameraX.toIntOrNull() != 0) {
            val needsPlayer = assignments.player == entry.id
            val needsOther = assignments.opponent == entry.id || assignments.girlfriend == entry.id
            if ((needsPlayer && draft.side != CharacterSide.PLAYER) || (needsOther && draft.side == CharacterSide.PLAYER))
                add("Personaje $prefix: Psych invierte la cámara X del jugador. Ajusta el lado de referencia o usa cámara X cero para reutilizarlo en roles opuestos.")
        }
        val importedPath = if (config.engine.includesVSlice) "data/characters/$prefix.json" else "characters/$prefix.json"
        if (config.importedModResources.any { it.converted && it.originalPath == importedPath } &&
            draft.importedSourceFormat != (if (config.engine.includesVSlice) "V-Slice" else "Psych"))
            add("Personaje $prefix: el JSON original pertenece a otro personaje importado; cambia el ID para no sobrescribirlo.")
        if (!prefix.matches(Regex("[A-Za-z0-9_-]{1,64}")) || !names.add(prefix.lowercase(java.util.Locale.ROOT))) add("Personaje: ID de archivo duplicado o inseguro: $prefix.")
        characterInputProblems(draft, fileExists).forEach { add("Personaje $prefix: $it") }
        draft.variations.forEach { variation ->
            if (!variation.variationId.matches(Regex("[a-z0-9_-]{1,40}"))) add("Personaje $prefix: ID de variación inválido.")
            val path = "$prefix-${variation.variationId}"
            if (!names.add(path.lowercase(java.util.Locale.ROOT))) add("Personaje: ID de archivo duplicado: $path.")
            val scale = variation.scale.toFloatOrNull()
            val counts = listOf(variation.idleFrames, variation.leftFrames, variation.downFrames, variation.upFrames, variation.rightFrames).map { it.toIntOrNull() }
            if (scale == null || !scale.isFinite() || scale <= 0f || counts.any { it == null || it < 0 } || (counts.firstOrNull() ?: 0) <= 0)
                add("Personaje $prefix: escala o animaciones inválidas en ${variation.variationId}.")
            if (!variation.isReady || variation.sheet?.let { !fileExists(it.path) } == true || variation.icon?.let { !fileExists(it.path) } == true)
                add("Personaje $prefix: completa los recursos de la variación ${variation.variationId}.")
        }
    }
}

/** Una sola validación para navegación, resumen y exportación. */
fun validateWizard(config: ModConfig, fileExists: (String) -> Boolean = { File(it).isFile }): List<WizardIssue> = buildList {
    if (config.stageDraft.enabled) {
        val stage = config.stageDraft
        stageLayoutProblems(stage, config.engine).forEach { add(WizardIssue(1, it)) }
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
    characterLibraryProblems(config, fileExists).forEach { add(WizardIssue(2, it)) }
    config.charactersToExport().forEach { entry ->
        com.fnfmodmaker.mod.vSliceCharacterProblems(exportConfig.copy(selectedCharacterId = entry.id)).forEach { add(WizardIssue(2, it)) }
    }
    config.songs.forEachIndexed { index, song ->
        val label = "Canción ${index + 1}"
        if (song.songName.isBlank()) add(WizardIssue(1, "$label: escribe un nombre.", index))
        if (song.songName.isNotBlank() && song.songFolder.lowercase() in repeated) {
            add(WizardIssue(1, "$label: usa un nombre de carpeta distinto al de las otras canciones.", index))
        }
        if (!song.songFolder.matches(Regex("[a-z0-9_-]{1,80}"))) {
            add(WizardIssue(1, "$label: el nombre no puede contener barras.", index))
        }
        if (song.bpmValue !in 1.0..400.0) add(WizardIssue(1, "$label: el BPM debe estar entre 1 y 400.", index))
        val speed = song.speed.toFloatOrNull()
        if (speed == null || !speed.isFinite() || speed <= 0f) {
            add(WizardIssue(1, "$label: la velocidad debe ser mayor que cero.", index))
        }
        if (song.difficulties.size > 64 || song.difficulties.any { !isValidDifficulty(it) }) add(WizardIssue(1, "$label: hay IDs de dificultad inválidos o demasiadas dificultades.", index))
        song.orderedDifficulties.forEach { difficulty ->
            val chart = config.getChart(song.songFolder, difficulty)
            val problems = chart?.exportProblems().orEmpty().toMutableList()
            if (config.engine.includesVSlice && chart?.notes?.any { it.psychTypeJson.isNotEmpty() } == true) problems.add("tipos Psych sin traducción reversible a V-Slice")
            if (!config.engine.includesPsych && chart != null && (chart.psychLayout.isNotEmpty() || chart.psychSongExtras.isNotBlank())) problems.add("secciones Psych sin destino Psych")
            if (config.engine.includesPsych && chart != null && chart.psychLayout.isNotEmpty()) {
                runCatching { chart.copy(bpm = song.bpmValue).toPsychJson() }.exceptionOrNull()?.let { problems.add(it.message ?: "secciones Psych inválidas") }
            }
            if (config.engine.includesVSlice && !config.engine.includesPsych && chart != null && chart.psychEventsJson.isNotBlank() && chart.psychEventsJson != "[]") problems.add("eventos Psych sin traducción a V-Slice")
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
    if (config.icon != null && !fileExists(config.icon.path)) add(WizardIssue(3, "Vuelve a elegir el icono del mod."))
}

/** Scripts are confined to mods/<mod>/scripts/<name>.lua on export. */
fun isValidPsychScript(name: String, code: String): Boolean =
    name.matches(Regex("[a-z0-9_-]{1,48}")) && code.isNotBlank() && code.length <= 16000 && !code.contains('\u0000')
