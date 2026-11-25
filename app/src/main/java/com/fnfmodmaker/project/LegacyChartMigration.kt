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

            val psychVariant = buildPsychVariant(songConfig, songObj, charts, histories, gson)
            val vsliceVariant = buildVSliceVariant(songConfig, songObj, charts, histories, gson, targetEngine, both)

            if (psychVariant == null && vsliceVariant == null) {
                legacyPreserved = true
            } else {
                psychVariant?.let { engineAttributedCount++ }
                vsliceVariant?.let { engineAttributedCount++ }
            }

            collectionBuilder[songId] = IndependentSong(songId, psychVariant, vsliceVariant)
        }

        val collection = IndependentChartCollection(songs = collectionBuilder.build())
        return MigrationResult(collection, legacyPreserved, engineAttributedCount)
    }

    /**
     * Apply a migration result to a project config without touching the original.
     * Returns the migrated JsonObject ready for validation and save.
     */
    fun applyMigration(config: JsonObject, gson: Gson): JsonObject {
        val version = config.get("chartSchemaVersion")
        val alreadyV2 = version.isJsonPrimitive && version.asJsonPrimitive.isNumber && version.asString == "2"
        if (alreadyV2) return config.deepCopy()
        val result = migrateToV2(config, gson)
        val updated = config.deepCopy()
        updated.addProperty("chartSchemaVersion", CHART_SCHEMA_V2)
        updated.add("independentCharts", independentChartsToJson(result.collection, gson))
        return updated
    }

    private fun buildPsychVariant(song: SongConfig, songObj: JsonObject, charts: JsonObject?,
        histories: JsonObject?, gson: Gson): IndependentVariant? {
        // Explicit evidence: the song is flagged Psych-originated, or has Psych-specific
        // editable charts/sections that cannot belong to V-Slice.
        val importedPsych = song.importedPsychFolder.isNotEmpty()
        val hasPsychCharts = charts?.get(song.songFolder)?.isJsonObject == true
        val hasPsychLayout = songObj.get("psychLayout")?.isJsonArray == true ||
            songObj.get("psychSongExtras")?.isJsonPrimitive == true
        val evidence = importedPsych || hasPsychCharts || hasPsychLayout
        if (!evidence) return null

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
            charts = chartsMap.build(),
            histories = historiesMap.build(),
            uneditableDifficulties = emptyMap(),
            originals = emptyList(),
            unknownMetadataJson = ""
        )
    }

    private fun buildVSliceVariant(song: SongConfig, songObj: JsonObject, charts: JsonObject?,
        histories: JsonObject?, gson: Gson, targetEngine: TargetEngine, both: Boolean): IndependentVariant? {
        // Explicit evidence: vSliceOrigin present, or project targets V-Slice and
        // the song has V-Slice-specific editable charts/events/tempo.
        // For BOTH projects we cannot attribute shared charts to V-Slice without evidence.
        val hasOrigin = song.vSliceOrigin != null
        val hasVSliceEvents = songObj.get("vSliceEventsJson")?.isJsonPrimitive == true ||
            songObj.get("vSliceTempoJson")?.isJsonPrimitive == true
        val hasVSliceCharts = charts?.get(song.songFolder)?.isJsonObject == true && !both
        val evidence = hasOrigin || hasVSliceEvents || (targetEngine == TargetEngine.VSLICE && hasVSliceCharts)
        if (!evidence) return null

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
            difficulties = difficulties,
            vSliceIndependentInitialBpm = targetEngine == TargetEngine.BOTH
        )
        return IndependentVariant(
            metadata = metadata,
            difficulties = difficulties,
            charts = chartsMap.build(),
            histories = historiesMap.build(),
            uneditableDifficulties = uneditable.build(),
            originals = buildOriginals(song, gson),
            unknownMetadataJson = ""
        )
    }

    private fun buildOriginals(song: SongConfig, gson: Gson): List<IndependentOriginal> {
        val refs = mutableListOf<IndependentOriginal>()
        song.vSliceOrigin?.let { origin ->
            refs.add(IndependentOriginal(
                originalPath = origin.metadataPath,
                asset = ProjectMediaReference(
                    uri = "", displayName = origin.metadataPath, path = origin.metadataPath, kind = "audio"
                ),
                editable = false
            ))
            refs.add(IndependentOriginal(
                originalPath = origin.chartPath,
                asset = ProjectMediaReference(
                    uri = "", displayName = origin.chartPath, path = origin.chartPath, kind = "audio"
                ),
                editable = false
            ))
        }
        return refs.build()
    }
}
