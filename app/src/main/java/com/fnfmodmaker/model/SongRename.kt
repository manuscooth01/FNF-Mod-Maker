package com.fnfmodmaker.model

/** Rename the song, every chart and every verified undo/redo chain in one immutable transaction. */
internal fun renameSong(config: ModConfig, index: Int, name: String): ModConfig {
    require(index in config.songs.indices) { "Canción no encontrada." }
    val clean = name.filterNot { it in "/\\|:*?\"<>" }
    require(clean.isNotBlank()) { "Escribe un nombre para la canción." }
    val song = config.songs[index]
    if (song.songName == clean) return config
    val oldFolder = song.songFolder
    val updatedSong = song.copy(songName = clean)
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
