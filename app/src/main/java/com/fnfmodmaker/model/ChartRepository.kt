package com.fnfmodmaker.model

/**
 * Repository for per-engine chart operations.
 *
 * Works with the full chart identity (songId, engine, difficulty), never only
 * songFolder + difficulty. All edits are immutable: they return a new [ModConfig]
 * and record an independent history entry for the edited variant only.
 */
internal interface ChartRepository {

    /** The current configuration being edited. */
    val config: ModConfig

    /** Replace the entire configuration (used after load/migration/switch). */
    fun setConfig(config: ModConfig)

    /** Returns true when the project is using the v2 independent-chart schema. */
    fun isV2(): Boolean

    /** Song metadata for a given song id, or null when unknown. */
    fun songMetadata(songId: String): SongConfig?

    /** All known song ids. */
    fun songIds(): List<String>

    /**
     * Get an editable chart. Returns null when the variant or difficulty does not exist
     * or is not editable (uneditable imported difficulties are preserved as-is).
     */
    fun getChart(key: IndependentChartKey): ChartDraft?

    /** All editable difficulties for a song variant. */
    fun editableDifficulties(songId: String, engine: ChartEngine): Set<String>

    /** Create a song with no variants yet (v2 only). */
    fun createSong(songId: String, metadata: SongConfig): ModConfig

    /**
     * Set or replace a song variant's metadata and difficulties without touching its charts.
     * Fails when the variant already exists (use updateVariant instead).
     */
    fun addVariant(songId: String, engine: ChartEngine, metadata: SongConfig,
        difficulties: Set<String>): ModConfig

    /**
     * Replace an existing variant entirely (charts, histories, metadata, difficulties).
     * This is a destructive operation: callers must confirm they own all data.
     */
    fun replaceVariant(songId: String, engine: ChartEngine, variant: IndependentVariant): ModConfig

    /**
     * Edit exactly one chart in one variant. Records that variant's own history, never
     * synthesizes or touches the other engine.
     */
    fun editChart(key: IndependentChartKey, next: ChartDraft): ModConfig

    /** Remove a chart from a variant (keeps the difficulty as uneditable if it was imported). */
    fun removeChart(key: IndependentChartKey): ModConfig

    /** Undo the last edit for one (song, engine, difficulty). */
    fun undo(key: IndependentChartKey): ModConfig

    /** Redo the last undone edit for one (song, engine, difficulty). */
    fun redo(key: IndependentChartKey): ModConfig

    /** History for one (song, engine, difficulty). */
    fun history(key: IndependentChartKey): ChartPersistentHistory?

    /** Save and replace the history stack for one (song, engine, difficulty). */
    fun saveHistory(key: IndependentChartKey, history: ChartPersistentHistory): ModConfig
}
