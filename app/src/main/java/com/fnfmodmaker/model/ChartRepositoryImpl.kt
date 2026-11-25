package com.fnfmodmaker.model

/**
 * Concrete [ChartRepository] backed by [ModConfig.independentCharts].
 *
 * All operations are immutable and operate on the full chart identity
 * (songId, engine, difficulty). The legacy [ModConfig.charts]/[ModConfig.chartHistories]
 * maps are left untouched for v2 projects; they are only used during migration.
 */
internal class ChartRepositoryImpl(private val gson: com.google.gson.Gson) : ChartRepository {

    private var _config: ModConfig = ModConfig()

    override val config: ModConfig
        get() = _config

    override fun setConfig(config: ModConfig) {
        _config = config
    }

    override fun isV2(): Boolean = _config.chartSchemaVersion == com.fnfmodmaker.project.CHART_SCHEMA_V2 &&
        _config.independentCharts != null

    override fun songMetadata(songId: String, engine: ChartEngine): SongConfig? {
        return _config.independentCharts?.songs[songId]?.variant(engine)?.metadata
    }


    override fun songIds(): List<String> = _config.independentCharts?.songs?.keys?.toList() ?: emptyList()

    override fun getChart(key: IndependentChartKey): ChartDraft? {
        require(isV2()) { "El proyecto no usa el esquema de charts independientes v2." }
        return _config.independentCharts?.chart(key)
    }

    override fun editableDifficulties(songId: String, engine: ChartEngine): Set<String> {
        require(isV2()) { "El proyecto no usa el esquema de charts independientes v2." }
        return _config.independentCharts?.songs[songId]?.variant(engine)?.let { variant ->
            variant.difficulties - variant.uneditableDifficulties.keys
        } ?: emptySet()
    }

    override fun createSong(songId: String, metadata: SongConfig): ModConfig {
        require(isV2()) { "El proyecto no usa el esquema de charts independientes v2." }
        require(songId.matches(Regex("[A-Za-z0-9_-]{1,80}"))) { "ID de canción inválido." }
        val collection = _config.independentCharts ?: error("Falta la colección de charts independientes.")
        require(songId !in collection.songs) { "La canción ya existe." }
        val updated = collection.withSong(songId, metadata)
        return _config.copy(independentCharts = updated)
    }

    override fun addVariant(songId: String, engine: ChartEngine, metadata: SongConfig,
        difficulties: Set<String>): ModConfig {
        require(isV2()) { "El proyecto no usa el esquema de charts independientes v2." }
        require(difficulties.all { isValidDifficulty(it) }) { "Dificultad inválida." }
        val collection = _config.independentCharts ?: error("Falta la colección de charts independientes.")
        val song = collection.songs[songId] ?: error("Canción no encontrada.")
        require(song.variant(engine) == null) { "La variante del motor ya existe para esta canción." }
        val variant = IndependentVariant(metadata = metadata, difficulties = difficulties)
        val updated = collection.withVariant(engine, songId, variant)
        return _config.copy(independentCharts = updated)
    }

    override fun replaceVariant(songId: String, engine: ChartEngine, variant: IndependentVariant): ModConfig {
        require(isV2()) { "El proyecto no usa el esquema de charts independientes v2." }
        val collection = _config.independentCharts ?: error("Falta la colección de charts independientes.")
        require(collection.songs.containsKey(songId)) { "Canción no encontrada." }
        val existing = collection.songs[songId]!!.variant(engine)
        require(existing != null) { "La variante del motor no existe para esta canción." }
        require(variant.metadata.songFolder == existing.metadata.songFolder) {
            "El songFolder de la variante no puede cambiar."
        }
        val updated = collection.withVariant(engine, songId, variant)
        return _config.copy(independentCharts = updated)
    }

    override fun editChart(key: IndependentChartKey, next: ChartDraft): ModConfig {
        require(isV2()) { "El proyecto no usa el esquema de charts independientes v2." }
        val collection = _config.independentCharts ?: error("Falta la colección de charts independientes.")
        val updated = collection.edit(key, next)
        return _config.copy(independentCharts = updated)
    }

    override fun removeChart(key: IndependentChartKey): ModConfig {
        require(isV2()) { "El proyecto no usa el esquema de charts independientes v2." }
        val collection = _config.independentCharts ?: error("Falta la colección de charts independientes.")
        val song = collection.songs[key.songId] ?: error("Canción no encontrada.")
        val variant = song.variant(key.engine) ?: error("Variante de motor no encontrada.")
        require(key.difficulty in variant.difficulties && key.difficulty !in variant.uneditableDifficulties) {
            "La dificultad no es editable."
        }
        val revisedVariant = variant.copy(
            charts = variant.charts - key.difficulty,
            histories = variant.histories - key.difficulty
        )
        val updated = collection.withVariant(key.engine, key.songId, revisedVariant)
        return _config.copy(independentCharts = updated)
    }

    override fun undo(key: IndependentChartKey): ModConfig {
        require(isV2()) { "El proyecto no usa el esquema de charts independientes v2." }
        val collection = _config.independentCharts ?: error("Falta la colección de charts independientes.")
        val song = collection.songs[key.songId] ?: error("Canción no encontrada.")
        val variant = song.variant(key.engine) ?: error("Variante de motor no encontrada.")
        val history = variant.histories[key.difficulty] ?: return _config
        val current = variant.charts[key.difficulty]
        val result = restoreChartHistory(history, current, redo = false)
        if (result == null || result.chart == current) return _config
        val revisedVariant = variant.copy(
            charts = if (result.chart == null) variant.charts - key.difficulty
                else variant.charts + (key.difficulty to result.chart),
            histories = variant.histories + (key.difficulty to result.history)
        )
        val updated = collection.withVariant(key.engine, key.songId, revisedVariant)
        return _config.copy(independentCharts = updated)
    }

    override fun redo(key: IndependentChartKey): ModConfig {
        require(isV2()) { "El proyecto no usa el esquema de charts independientes v2." }
        val collection = _config.independentCharts ?: error("Falta la colección de charts independientes.")
        val song = collection.songs[key.songId] ?: error("Canción no encontrada.")
        val variant = song.variant(key.engine) ?: error("Variante de motor no encontrada.")
        val history = variant.histories[key.difficulty] ?: return _config
        val current = variant.charts[key.difficulty]
        val result = restoreChartHistory(history, current, redo = true)
        if (result == null || result.chart == current) return _config
        val revisedVariant = variant.copy(
            charts = if (result.chart == null) variant.charts - key.difficulty
                else variant.charts + (key.difficulty to result.chart),
            histories = variant.histories + (key.difficulty to result.history)
        )
        val updated = collection.withVariant(key.engine, key.songId, revisedVariant)
        return _config.copy(independentCharts = updated)
    }

    override fun history(key: IndependentChartKey): ChartPersistentHistory? {
        require(isV2()) { "El proyecto no usa el esquema de charts independientes v2." }
        return _config.independentCharts?.songs[key.songId]?.variant(key.engine)?.histories?.get(key.difficulty)
    }

    override fun saveHistory(key: IndependentChartKey, history: ChartPersistentHistory): ModConfig {
        require(isV2()) { "El proyecto no usa el esquema de charts independientes v2." }
        val collection = _config.independentCharts ?: error("Falta la colección de charts independientes.")
        val song = collection.songs[key.songId] ?: error("Canción no encontrada.")
        val variant = song.variant(key.engine) ?: error("Variante de motor no encontrada.")
        val revisedVariant = variant.copy(histories = variant.histories + (key.difficulty to history))
        val updated = collection.withVariant(key.engine, key.songId, revisedVariant)
        return _config.copy(independentCharts = updated)
    }
    override fun getVSliceEvents(songId: String, engine: ChartEngine): String? {
        return _config.independentCharts?.songs[songId]?.variant(engine)?.metadata?.vSliceEventsJson
    }

    override fun saveVSliceEvents(songId: String, engine: ChartEngine, events: String): ModConfig {
        val collection = _config.independentCharts ?: error("Falta la colección de charts independientes.")
        val song = collection.songs[songId] ?: error("Canción no encontrada.")
        val variant = song.variant(engine) ?: error("Variante de motor no encontrada.")
        val before = com.fnfmodmaker.mod.validateVSliceEvents(variant.metadata.vSliceEventsJson ?: "[]")
        val edited = com.fnfmodmaker.mod.validateVSliceEvents(events)
        if (before == edited) return _config
        val metadata = variant.metadata.copy(
            vSliceEventsJson = edited,
            vSliceEventsUndo = (variant.metadata.vSliceEventsUndo + before).takeLast(10),
            vSliceEventsRedo = emptyList()
        )
        val revised = variant.copy(metadata = metadata)
        val updated = collection.withVariant(engine, songId, revised)
        return _config.copy(independentCharts = updated)
    }

    override fun undoVSliceEvents(songId: String, engine: ChartEngine): ModConfig {
        val collection = _config.independentCharts ?: error("Falta la colección de charts independientes.")
        val song = collection.songs[songId] ?: error("Canción no encontrada.")
        val variant = song.variant(engine) ?: error("Variante de motor no encontrada.")
        val previous = variant.metadata.vSliceEventsUndo.lastOrNull() ?: error("No hay cambios de eventos para deshacer.")
        com.fnfmodmaker.mod.validateVSliceEvents(previous)
        val metadata = variant.metadata.copy(
            vSliceEventsJson = previous,
            vSliceEventsUndo = variant.metadata.vSliceEventsUndo.dropLast(1),
            vSliceEventsRedo = (variant.metadata.vSliceEventsRedo + (variant.metadata.vSliceEventsJson ?: "[]")).takeLast(10)
        )
        val revised = variant.copy(metadata = metadata)
        val updated = collection.withVariant(engine, songId, revised)
        return _config.copy(independentCharts = updated)
    }

    override fun redoVSliceEvents(songId: String, engine: ChartEngine): ModConfig {
        val collection = _config.independentCharts ?: error("Falta la colección de charts independientes.")
        val song = collection.songs[songId] ?: error("Canción no encontrada.")
        val variant = song.variant(engine) ?: error("Variante de motor no encontrada.")
        val next = variant.metadata.vSliceEventsRedo.lastOrNull() ?: error("No hay cambios de eventos para rehacer.")
        com.fnfmodmaker.mod.validateVSliceEvents(next)
        val metadata = variant.metadata.copy(
            vSliceEventsJson = next,
            vSliceEventsRedo = variant.metadata.vSliceEventsRedo.dropLast(1),
            vSliceEventsUndo = (variant.metadata.vSliceEventsUndo + (variant.metadata.vSliceEventsJson ?: "[]")).takeLast(10)
        )
        val revised = variant.copy(metadata = metadata)
        val updated = collection.withVariant(engine, songId, revised)
        return _config.copy(independentCharts = updated)
    }

    override fun getVSliceTempo(songId: String, engine: ChartEngine): String? {
        return _config.independentCharts?.songs[songId]?.variant(engine)?.metadata?.vSliceTempoJson
    }

    override fun saveVSliceTempo(songId: String, engine: ChartEngine, tempo: String): ModConfig {
        val collection = _config.independentCharts ?: error("Falta la colección de charts independientes.")
        val song = collection.songs[songId] ?: error("Canción no encontrada.")
        val variant = song.variant(engine) ?: error("Variante de motor no encontrada.")
        val before = com.fnfmodmaker.mod.validateVSliceTempo(
            variant.metadata.vSliceTempoJson ?: """[{"t":0,"bpm":${variant.metadata.bpmValue},"n":4,"d":4}]"""
        )
        val edited = com.fnfmodmaker.mod.validateVSliceTempo(tempo)
        if (before == edited) return _config
        val bpm = com.google.gson.JsonParser.parseString(edited).asJsonArray[0].asJsonObject.get("bpm").asString
        val metadata = variant.metadata.copy(
            bpm = bpm.toString(),
            vSliceIndependentInitialBpm = true,
            vSliceTempoJson = edited,
            vSliceTempoUndo = (variant.metadata.vSliceTempoUndo + before).takeLast(10),
            vSliceTempoRedo = emptyList()
        )
        val revised = variant.copy(metadata = metadata)
        val updated = collection.withVariant(engine, songId, revised)
        return _config.copy(independentCharts = updated)
    }

    override fun undoVSliceTempo(songId: String, engine: ChartEngine): ModConfig {
        val collection = _config.independentCharts ?: error("Falta la colección de charts independientes.")
        val song = collection.songs[songId] ?: error("Canción no encontrada.")
        val variant = song.variant(engine) ?: error("Variante de motor no encontrada.")
        val previous = (variant.metadata.vSliceTempoUndo.lastOrNull() ?: error("No hay cambio de tempo que restaurar."))
        val checked = com.fnfmodmaker.mod.validateVSliceTempo(previous)
        val bpm = com.google.gson.JsonParser.parseString(checked).asJsonArray[0].asJsonObject.get("bpm").asString
        val current = variant.metadata.vSliceTempoJson ?: ""
        val metadata = variant.metadata.copy(
            bpm = bpm.toString(),
            vSliceIndependentInitialBpm = true,
            vSliceTempoJson = checked,
            vSliceTempoUndo = variant.metadata.vSliceTempoUndo.dropLast(1),
            vSliceTempoRedo = (variant.metadata.vSliceTempoRedo + current).takeLast(10)
        )
        val revised = variant.copy(metadata = metadata)
        val updated = collection.withVariant(engine, songId, revised)
        return _config.copy(independentCharts = updated)
    }

    override fun redoVSliceTempo(songId: String, engine: ChartEngine): ModConfig {
        val collection = _config.independentCharts ?: error("Falta la colección de charts independientes.")
        val song = collection.songs[songId] ?: error("Canción no encontrada.")
        val variant = song.variant(engine) ?: error("Variante de motor no encontrada.")
        val next = variant.metadata.vSliceTempoRedo.lastOrNull() ?: error("No hay cambio de tempo que restaurar.")
        val checked = com.fnfmodmaker.mod.validateVSliceTempo(next)
        val bpm = com.google.gson.JsonParser.parseString(checked).asJsonArray[0].asJsonObject.get("bpm").asString
        val current = variant.metadata.vSliceTempoJson ?: ""
        val metadata = variant.metadata.copy(
            bpm = bpm.toString(),
            vSliceIndependentInitialBpm = true,
            vSliceTempoJson = checked,
            vSliceTempoRedo = variant.metadata.vSliceTempoRedo.dropLast(1),
            vSliceTempoUndo = (variant.metadata.vSliceTempoUndo + current).takeLast(10)
        )
        val revised = variant.copy(metadata = metadata)
        val updated = collection.withVariant(engine, songId, revised)
        return _config.copy(independentCharts = updated)
    }
}
