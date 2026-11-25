package com.fnfmodmaker.model

/** Individual chart identity: BOTH is a project destination, never a chart engine. */
internal enum class ChartEngine { PSYCH, VSLICE }

internal data class IndependentChartKey(val songId: String, val engine: ChartEngine, val difficulty: String) {
    init {
        require(songId.matches(Regex("[A-Za-z0-9_-]{1,80}")) && isValidDifficulty(difficulty)) { "Identidad de chart inválida." }
    }
}

/** Exact original files are project assets; paths here are archive paths, not local file paths. */
internal data class IndependentOriginal(
    val originalPath: String,
    val asset: PickedImage,
    val editable: Boolean = false
)

/**
 * An explicit engine variant. Song metadata, audio, V-Slice events and tempo belong
 * to this variant; Psych sections and events belong to its individual ChartDrafts.
 * Raw source JSON is opaque and must not be interpreted as local resource paths.
 */
internal data class IndependentVariant(
    val metadata: SongConfig,
    val difficulties: Set<String> = emptySet(),
    val charts: Map<String, ChartDraft> = emptyMap(),
    val histories: Map<String, ChartPersistentHistory> = emptyMap(),
    val uneditableDifficulties: Map<String, String> = emptyMap(),
    val originals: List<IndependentOriginal> = emptyList(),
    val unknownMetadataJson: String = ""
)

internal data class IndependentSong(
    val id: String,
    val psych: IndependentVariant? = null,
    val vSlice: IndependentVariant? = null
) {
    fun variant(engine: ChartEngine): IndependentVariant? = if (engine == ChartEngine.PSYCH) psych else vSlice
    fun withVariant(engine: ChartEngine, value: IndependentVariant): IndependentSong =
        if (engine == ChartEngine.PSYCH) copy(psych = value) else copy(vSlice = value)
}

/**
 * Experimental in-memory model only. No migration, portable publication, existing
 * project loading or visual editor consumes it yet. Legacy JSON is retained as an
 * opaque document until a lossless, validated migration has been implemented.
 */
internal data class IndependentChartCollection(
    val songs: Map<String, IndependentSong> = emptyMap(),
    val legacyDocumentJson: String = ""
) {
    fun chart(key: IndependentChartKey): ChartDraft? = songs[key.songId]?.variant(key.engine)?.charts?.get(key.difficulty)

    /** Edit exactly one variant and record its own history, never synthesize the other. */
    fun edit(key: IndependentChartKey, next: ChartDraft): IndependentChartCollection {
        val song = songs[key.songId] ?: error("Canción no encontrada.")
        require(song.id == key.songId) { "Identidad de canción inconsistente." }
        val variant = song.variant(key.engine) ?: error("Variante de motor no encontrada.")
        require(key.difficulty in variant.difficulties && key.difficulty !in variant.uneditableDifficulties) { "La dificultad no es editable." }
        require(next.songFolder == variant.metadata.songFolder && next.difficulty == key.difficulty) { "El chart pertenece a otra canción o dificultad." }
        val history = recordChartHistory(variant.histories[key.difficulty] ?: ChartPersistentHistory(), variant.charts[key.difficulty], next)
        val revised = variant.copy(charts = variant.charts + (key.difficulty to next), histories = variant.histories + (key.difficulty to history))
        return copy(songs = songs + (key.songId to song.withVariant(key.engine, revised)))
    }
}
