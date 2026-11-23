package com.fnfmodmaker.model

import java.io.File

data class WizardIssue(val step: Int, val message: String, val songIndex: Int? = null)

/** Una sola validación para navegación, resumen y exportación. */
fun validateWizard(config: ModConfig, fileExists: (String) -> Boolean = { File(it).isFile }): List<WizardIssue> = buildList {
    if (config.modTitle.isBlank()) add(WizardIssue(0, "Escribe el nombre del mod."))
    if (config.songs.isEmpty()) add(WizardIssue(1, "Añade al menos una canción."))
    val repeated = config.songs.filter { it.songName.isNotBlank() }
        .groupBy { it.songFolder.lowercase() }.filterValues { it.size > 1 }.keys
    config.songs.forEachIndexed { index, song ->
        val label = "Canción ${index + 1}"
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
        config.character.problems.forEach { add(WizardIssue(2, "Personaje: $it")) }
        val draft = config.character
        val images = if (draft.useIndividualFrames) draft.animationFrames.values.flatten().map { it.image }
            else listOfNotNull(draft.sheet)
        if (images.any { !fileExists(it.path) }) add(WizardIssue(2, "Personaje: vuelve a elegir las imágenes que ya no están disponibles."))
        if (draft.icon != null && !fileExists(draft.icon.path)) add(WizardIssue(2, "Personaje: vuelve a elegir el icono de vida."))
    }
    if (config.icon != null && !fileExists(config.icon.path)) add(WizardIssue(3, "Vuelve a elegir el icono del mod."))
}
