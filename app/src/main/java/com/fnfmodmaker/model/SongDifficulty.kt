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
