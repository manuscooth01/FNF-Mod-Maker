package com.fnfmodmaker.model

/** A removed chart must not be restored from an imported original during export. */
internal fun removeSongDifficulty(config: ModConfig, index: Int, difficulty: String): ModConfig {
    val song = config.songs[index]
    val songs = config.songs.toMutableList().apply { this[index] = song.copy(difficulties = song.difficulties - difficulty) }
    val charts = config.charts[song.songFolder].orEmpty() - difficulty
    val history = config.chartHistories[song.songFolder].orEmpty() - difficulty
    return config.copy(songs = songs,
        charts = if (charts.isEmpty()) config.charts - song.songFolder else config.charts + (song.songFolder to charts),
        chartHistories = if (history.isEmpty()) config.chartHistories - song.songFolder else config.chartHistories + (song.songFolder to history))
}
internal fun addSongDifficulty(config: ModConfig, index: Int, difficulty: String): ModConfig {
    require(isValidDifficulty(difficulty)) { "ID de dificultad inválido." }
    val song = config.songs[index]
    val songs = config.songs.toMutableList().apply { this[index] = song.copy(difficulties = song.difficulties + difficulty) }
    val updated = config.copy(songs = songs)
    if (config.importedModResources.isEmpty() || config.getChart(song.songFolder, difficulty) != null) return updated
    return updated.withChart(song.songFolder, difficulty, ChartDraft(songFolder = song.songFolder, difficulty = difficulty,
        bpm = song.bpmValue, scrollSpeed = song.speedValue, offsetMs = song.offsetValue,
        player1 = song.player, player2 = song.opponent, gfVersion = song.girlfriend, stage = song.stagePsych))
}
internal fun exportDifficulties(config: ModConfig, song: SongConfig): List<String> =
    if (song.vSliceOrigin != null || song.importedPsychFolder.isNotEmpty() ||
        (config.engine == TargetEngine.PSYCH && config.importedModResources.any { it.converted && it.originalPath.startsWith("data/${song.songFolder}/") }))
        song.orderedDifficulties.filter { config.getChart(song.songFolder, it) != null || it in (song.vSliceOrigin?.uneditableDifficulties ?: emptySet()) }
    else song.orderedDifficulties

/** Copy only an editable, explicit chart; never synthesize or overwrite a retained original. */
internal fun duplicateSongDifficulty(config: ModConfig, index: Int, source: String, target: String): ModConfig {
    val song = config.songs.getOrNull(index) ?: error("La canción no existe.")
    require(source in song.difficulties && isValidDifficulty(target)) { "Dificultad de origen o destino inválida." }
    require(target !in song.difficulties && config.getChart(song.songFolder, target) == null) { "El destino ya existe." }
    require(song.difficulties.size < 64) { "La canción ya tiene el máximo de dificultades." }
    val chart = config.getChart(song.songFolder, source) ?: error("La dificultad de origen no tiene un chart editable; no se puede copiar sin pérdida.")
    val copy = chart.copy(difficulty = target)
    // The target did not exist. Keep an empty history: undoing chart creation alone
    // could otherwise resurrect a previously removed, uneditable imported difficulty.
    return addSongDifficulty(config, index, target).withChart(song.songFolder, target, copy)
}
