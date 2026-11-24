package com.fnfmodmaker.model

/** Rename the song, every chart and every verified undo/redo chain in one immutable transaction. */
internal fun renameSong(config: ModConfig, index: Int, name: String): ModConfig {
    require(index in config.songs.indices) { "Canción no encontrada." }
    val clean = name.filterNot { it in "/\\|:*?\"<>" }
    require(clean.isNotBlank()) { "Escribe un nombre para la canción." }
    val song = config.songs[index]
    if (song.songName == clean) return config
    val oldFolder = song.songFolder
    val psychImported = config.importedModResources.any { it.converted && it.originalPath.startsWith("data/${song.songFolder}/") }
    val updatedSong = song.copy(songName = clean, importedPsychFolder = if (song.importedPsychFolder.isEmpty() && psychImported) song.songFolder else song.importedPsychFolder)
    val newFolder = updatedSong.songFolder
    require(config.songs.withIndex().none { it.index != index && it.value.songFolder.equals(newFolder, ignoreCase = true) }) {
        "Ya existe una canción con ese nombre o carpeta; elige otro."
    }
    if (newFolder != oldFolder) {
        require(config.charts.keys.none { it != oldFolder && it.equals(oldFolder, ignoreCase = true) } &&
            config.chartHistories.keys.none { it != oldFolder && it.equals(oldFolder, ignoreCase = true) }) {
            "Hay charts o historiales con una carpeta anterior ambigua; no se modificaron."
        }
        require(config.songs.withIndex().none { it.index != index && it.value.songFolder.equals(oldFolder, ignoreCase = true) } ||
            (oldFolder !in config.charts && oldFolder !in config.chartHistories)) {
            "La carpeta anterior está compartida con otra canción; no se pueden mover sus charts con seguridad."
        }
        require(config.charts.keys.none { it.equals(newFolder, ignoreCase = true) } &&
            config.chartHistories.keys.none { it.equals(newFolder, ignoreCase = true) }) {
            "Ya existen charts o historiales con esa carpeta; no se sobrescribieron."
        }
    }
    val songs = config.songs.toMutableList().also { it[index] = updatedSong }
    if (newFolder == oldFolder) return config.copy(songs = songs)
    val oldCharts = config.charts[oldFolder].orEmpty()
    val oldHistories = config.chartHistories[oldFolder].orEmpty()
    val movedCharts = oldCharts.mapValues { (_, chart) ->
        require(chart.songFolder == oldFolder) { "El chart no corresponde a la carpeta de la canción." }
        chart.copy(songFolder = newFolder)
    }
    val movedHistories = oldHistories.mapValues { (difficulty, history) ->
        renameChartHistory(history, oldCharts[difficulty], oldFolder, newFolder)
    }
    return config.copy(songs = songs,
        charts = if (oldFolder in config.charts) config.charts - oldFolder + (newFolder to movedCharts) else config.charts,
        chartHistories = if (oldFolder in config.chartHistories) config.chartHistories - oldFolder + (newFolder to movedHistories) else config.chartHistories)
}

/** Remove a song and remember its source folder, without deleting project assets. */
internal fun removeSong(config: ModConfig, index: Int): ModConfig {
    require(index in config.songs.indices && config.songs.size > 1) { "Debe quedar al menos una canción." }
    val song = config.songs[index]
    val rest = config.songs.filterIndexed { i, _ -> i != index }
    val origin = song.vSliceOrigin?.folder ?: song.importedPsychFolder.ifEmpty {
        if (config.engine == TargetEngine.PSYCH && config.importedModResources.any { it.converted && it.originalPath.startsWith("data/${song.songFolder}/") }) song.songFolder else ""
    }
    require(origin.isEmpty() || origin.matches(Regex("[a-z0-9_-]{1,80}"))) { "Origen de canción inválido." }
    val shared = rest.any { it.songFolder == song.songFolder }
    val originShared = origin.isNotEmpty() && rest.any { (it.vSliceOrigin?.folder ?: it.importedPsychFolder) == origin }
    return config.copy(songs = rest,
        charts = if (shared) config.charts else config.charts - song.songFolder,
        chartHistories = if (shared) config.chartHistories else config.chartHistories - song.songFolder,
        deletedImportedSongFolders = if (origin.isEmpty() || originShared) config.deletedImportedSongFolders else config.deletedImportedSongFolders + origin)
}
