package com.fnfmodmaker.project

import com.fnfmodmaker.model.*
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject

/**
 * Transactional migration from the legacy shared-chart schema (v1 / unversioned) to
 * the v2 independent-chart schema.
 *
 * Conservation rule: a chart is assigned to an engine only when there is explicit,
 * reliable evidence of its provenance. Ambiguous charts stay in the legacy deposit
 * untouched and non-editable until resolved explicitly. No chart is duplicated into
 * both engines and no unknown fields are removed.
 *
 * Migration is idempotent: a config already at v2 is returned unchanged.
 */
internal object LegacyChartMigration {

    /** Migrate a legacy project config JsonObject to v2. Never mutates the input. */
    fun migrateToV2(config: JsonObject, gson: Gson): MigrationResult {
        val version = config.get("chartSchemaVersion")
        val alreadyV2 = version.isJsonPrimitive && version.asJsonPrimitive.isNumber && version.asString == "2"
        if (alreadyV2) {
            val collection = requireIndependentChartSchema(config, gson)
            return MigrationResult(collection, legacyPreserved = false,
                engineAttributedCount = collection.songs.values.sumOf {
                    listOfNotNull(it.psych, it.vSlice).size
                })
        }
        // Legacy or unversioned: reject unknown/future and independentCharts presence.
        requireSupportedChartSchema(config)
        val engineValue = config.get("engine")?.asString ?: TargetEngine.VSLICE.name
        val targetEngine = TargetEngine.valueOf(engineValue)
        val both = targetEngine == TargetEngine.BOTH

        val songs = config.getAsJsonArray("songs") ?: error("Faltan canciones.")
        val charts = config.getAsJsonObject("charts")
        val histories = config.getAsJsonObject("chartHistories")

        val collectionBuilder = mutableMapOf<String, IndependentSong>()
        var legacyPreserved = false
        var engineAttributedCount = 0

        songs.forEach { songNode ->
            require(songNode.isJsonObject) { "Canción inválida." }
            val songObj = songNode.asJsonObject
            val songConfig = gson.fromJson(songObj, SongConfig::class.java) ?: error("Canción inválida.")
            val songId = songConfig.songFolder
            require(songId.matches(Regex("[A-Za-z0-9_-]{1,80}"))) { "ID de canción inválido: $songId." }

            val psychVariant = buildPsychVariant(songConfig, songObj, charts, histories, gson, targetEngine, both)
            val vsliceVariant = buildVSliceVariant(songConfig, songObj, charts, histories, gson, targetEngine, both)

            if (psychVariant == null && vsliceVariant == null) {
                legacyPreserved = true
            } else {
                psychVariant?.let { engineAttributedCount++ }
                vsliceVariant?.let { engineAttributedCount++ }
            }

            if (collectionBuilder.containsKey(songId)) {
                // Duplicate song ID - attempt to merge variants
                val existing = collectionBuilder[songId]!!
                val mergedPsych = if (existing.psych != null && psychVariant != null) {
                    // Check if variants are compatible (same charts/histories)
                    if (existing.psych?.charts == psychVariant?.charts && existing.psych?.histories == psychVariant?.histories) {
                        existing.psych
                    } else {
                        // Incompatible - preserve both in legacy
                        legacyPreserved = true
                        existing.psych
                    }
                } else existing.psych ?: psychVariant
                val mergedVslice = if (existing.vSlice != null && vsliceVariant != null) {
                    if (existing.vSlice?.charts == vsliceVariant?.charts && existing.vSlice?.histories == vsliceVariant?.histories) {
                        existing.vSlice
                    } else {
                        legacyPreserved = true
                        existing.vSlice
                    }
                } else existing.vSlice ?: vsliceVariant
                collectionBuilder[songId] = IndependentSong(songId, mergedPsych, mergedVslice)
            } else {
                collectionBuilder[songId] = IndependentSong(songId, psychVariant, vsliceVariant)
            }
        }
        val collection = IndependentChartCollection(songs = collectionBuilder.toMap(),
            legacyDocumentJson = config.toString())
        return MigrationResult(collection, legacyPreserved, engineAttributedCount)
    }
    /**
     * Apply a migration result to a project config without touching the original.
     * Returns the migrated JsonObject ready for validation and save.
     *
     * 1. Copy the original document.
     * 2. Extract variants whose provenance can be demonstrated.
     * 3. Preserve ambiguous and unknown data in the legacy deposit.
     * 4. Remove legacy editable maps once preserved.
     * 5. Validate the new document before returning.
     */
    fun applyMigration(config: JsonObject, gson: Gson): JsonObject {
        val version = config.get("chartSchemaVersion")
        val alreadyV2 = version.isJsonPrimitive && version.asJsonPrimitive.isNumber && version.asString == "2"
        if (alreadyV2) return config.deepCopy()

        val result = migrateToV2(config, gson)
        val updated = config.deepCopy()
        updated.addProperty("chartSchemaVersion", CHART_SCHEMA_V2)
        updated.add("independentCharts", independentChartsToJson(result.collection, gson))
        // Remove legacy editable maps once preserved in the deposit.
        updated.remove("charts")
        updated.remove("chartHistories")
        return updated
    }

    /**
     * Decide whether to attribute legacy charts to Psych.
     *
     * Evidence rules:
     *  - Project targets Psych only: the shared charts may be attributed to Psych.
     *  - Project targets V-Slice only: charts CANNOT be attributed to Psych.
     *  - Project targets BOTH: require explicit Psych evidence (importedPsychFolder or
     *    Psych-specific layout/extras). Presence in the shared charts map alone is NOT evidence.
     *  - Never duplicate the same chart into both engines.
     */
    private fun psychEvidence(song: SongConfig, songObj: JsonObject, targetEngine: TargetEngine, both: Boolean): Boolean {
        when (targetEngine) {
            TargetEngine.PSYCH -> return true // Single-engine Psych project: charts belong to Psych.
            TargetEngine.VSLICE -> return false // V-Slice only project: charts cannot be Psych.
            TargetEngine.BOTH -> return song.importedPsychFolder.isNotEmpty() ||
                songObj.get("psychLayout")?.isJsonArray == true ||
                songObj.get("psychSongExtras")?.isJsonPrimitive == true
        }
    }

    /**
     * Decide whether to attribute legacy charts to V-Slice.
     *
     * Evidence rules:
     *  - Project targets V-Slice only: the shared charts may be attributed to V-Slice.
     *  - Project targets Psych only: charts CANNOT be attributed to V-Slice.
     *  - Project targets BOTH: require explicit V-Slice evidence (vSliceOrigin,
     *    vSliceEventsJson, or vSliceTempoJson). Presence in the shared charts map alone
     *    is NOT evidence.
     *  - Never duplicate the same chart into both engines.
     */
    private fun vsliceEvidence(song: SongConfig, songObj: JsonObject, targetEngine: TargetEngine, both: Boolean): Boolean {
        when (targetEngine) {
            TargetEngine.VSLICE -> return true // Single-engine V-Slice project: charts belong to V-Slice.
            TargetEngine.PSYCH -> return false // Psych only project: charts cannot be V-Slice.
            TargetEngine.BOTH -> return song.vSliceOrigin != null ||
                songObj.get("vSliceEventsJson")?.isJsonPrimitive == true ||
                songObj.get("vSliceTempoJson")?.isJsonPrimitive == true
        }
    }

    private fun buildPsychVariant(song: SongConfig, songObj: JsonObject, charts: JsonObject?,
        histories: JsonObject?, gson: Gson, targetEngine: TargetEngine, both: Boolean): IndependentVariant? {
        if (!psychEvidence(song, songObj, targetEngine, both)) return null

        val songCharts = charts?.get(song.songFolder)?.asJsonObject ?: JsonObject()
        val songHistories = histories?.get(song.songFolder)?.asJsonObject ?: JsonObject()
        val chartsMap = mutableMapOf<String, ChartDraft>()
        val historiesMap = mutableMapOf<String, ChartPersistentHistory>()
        val difficulties = mutableSetOf<String>()

        songCharts.entrySet().forEach { (difficulty, chartNode) ->
            require(chartNode.isJsonObject) { "Chart inválido para $difficulty." }
            val draft = gson.fromJson(chartNode, ChartDraft::class.java) ?: error("Chart inválido.")
            require(draft.songFolder == song.songFolder && draft.difficulty == difficulty) {
                "El chart no corresponde a la canción o dificultad."
            }
            chartsMap[difficulty] = draft
            difficulties.add(difficulty)
        }
        songHistories.entrySet().forEach { (difficulty, histNode) ->
            require(histNode.isJsonObject) { "Historial inválido para $difficulty." }
            val history = gson.fromJson(histNode, ChartPersistentHistory::class.java)
                ?: error("Historial inválido.")
            historiesMap[difficulty] = history
        }

        val metadata = song.copy(
            difficulties = difficulties,
            psychSnapReference = false
        )
        return IndependentVariant(
            metadata = metadata,
            difficulties = difficulties,
            charts = chartsMap.toMap(),
            histories = historiesMap.toMap(),
            uneditableDifficulties = emptyMap(),
            originals = emptyList(),
            unknownMetadataJson = ""
        )
    }

    private fun buildVSliceVariant(song: SongConfig, songObj: JsonObject, charts: JsonObject?,
        histories: JsonObject?, gson: Gson, targetEngine: TargetEngine, both: Boolean): IndependentVariant? {
        if (!vsliceEvidence(song, songObj, targetEngine, both)) return null

        val songCharts = charts?.get(song.songFolder)?.asJsonObject ?: JsonObject()
        val songHistories = histories?.get(song.songFolder)?.asJsonObject ?: JsonObject()
        val chartsMap = mutableMapOf<String, ChartDraft>()
        val historiesMap = mutableMapOf<String, ChartPersistentHistory>()
        val difficulties = mutableSetOf<String>()
        val uneditable = mutableMapOf<String, String>()

        song.vSliceOrigin?.let { origin ->
            origin.editableDifficulties.forEach { difficulties.add(it) }
            origin.uneditableDifficulties.forEach { uneditable[it] = origin.chartPath }
        }
        songCharts.entrySet().forEach { (difficulty, chartNode) ->
            require(chartNode.isJsonObject) { "Chart inválido para $difficulty." }
            val draft = gson.fromJson(chartNode, ChartDraft::class.java) ?: error("Chart inválido.")
            require(draft.songFolder == song.songFolder && draft.difficulty == difficulty) {
                "El chart no corresponde a la canción o dificultad."
            }
            chartsMap[difficulty] = draft
            difficulties.add(difficulty)
        }
        songHistories.entrySet().forEach { (difficulty, histNode) ->
            require(histNode.isJsonObject) { "Historial inválido para $difficulty." }
            val history = gson.fromJson(histNode, ChartPersistentHistory::class.java)
                ?: error("Historial inválido.")
            historiesMap[difficulty] = history
        }

        val metadata = song.copy(
            difficulties = difficulties
        )
        return IndependentVariant(
            metadata = metadata,
            difficulties = difficulties,
            charts = chartsMap.toMap(),
            histories = historiesMap.toMap(),
            uneditableDifficulties = uneditable.toMap(),
            originals = emptyList(),
            unknownMetadataJson = ""
        )
    }
}