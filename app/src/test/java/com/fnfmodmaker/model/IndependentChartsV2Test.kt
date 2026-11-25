package com.fnfmodmaker.model

import com.fnfmodmaker.project.CHART_SCHEMA_V2
import com.fnfmodmaker.project.LegacyChartMigration
import com.fnfmodmaker.project.ProjectConfigValidator
import com.fnfmodmaker.project.requireIndependentChartSchema
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Test

class IndependentChartsV2Test {

    private fun psychChart(songFolder: String = "test", difficulty: String = "normal") = ChartDraft(
        songFolder = songFolder, difficulty = difficulty, bpm = 120.0,
        notes = listOf(ChartNote(100, 7, 250, psychTypeJson = "99")),
        psychEventsJson = """[[10,[["Hey!","1",""]]]]""",
        psychLayout = listOf(PsychSectionData(beats = 4.0, bpm = 120.0, sourceJson = "{}"))
    )

    private fun vsliceChart(songFolder: String = "test", difficulty: String = "normal") = ChartDraft(
        songFolder = songFolder, difficulty = difficulty, bpm = 170.0,
        notes = listOf(ChartNote(450, 2, 120, kind = "mine"))
    )

    private fun psychVariant(chart: ChartDraft): IndependentVariant =
        IndependentVariant(
            metadata = SongConfig(songName = "Test", bpm = "120", difficulties = setOf(chart.difficulty)),
            difficulties = setOf(chart.difficulty),
            charts = mapOf(chart.difficulty to chart)
        )

    private fun vsliceVariant(chart: ChartDraft): IndependentVariant =
        IndependentVariant(
            metadata = SongConfig(songName = "Test", bpm = "170", difficulties = setOf(chart.difficulty)),
            difficulties = setOf(chart.difficulty),
            charts = mapOf(chart.difficulty to chart)
        )

    private fun collection(songId: String = "stable-id", psych: ChartDraft? = null,
        vslice: ChartDraft? = null): IndependentChartCollection {
        return IndependentChartCollection(songs = mapOf(songId to IndependentSong(
            songId,
            psych?.let { psychVariant(it) },
            vslice?.let { vsliceVariant(it) }
        )))
    }

    private fun legacyPsychSong(folder: String = "test") = JsonObject().apply {
        addProperty("songName", "Test")
        addProperty("artist", "A")
        addProperty("bpm", "120")
        addProperty("speed", "2.0")
        addProperty("player", "bf")
        addProperty("girlfriend", "gf")
        addProperty("opponent", "dad")
        addProperty("stageVSlice", "mainStage")
        addProperty("stagePsych", "stage")
        addProperty("weekColor", "#C24B99")
        add("difficulties", JsonArray().apply { add("normal"); add("hard") })
        addProperty("practiceNotes", true)
        addProperty("offsetMs", "0")
        addProperty("importedPsychFolder", folder)
    }

    private fun legacyVSliceSong() = JsonObject().apply {
        addProperty("songName", "Test")
        addProperty("artist", "A")
        addProperty("bpm", "170")
        addProperty("speed", "2.0")
        addProperty("player", "bf")
        addProperty("girlfriend", "gf")
        addProperty("opponent", "dad")
        addProperty("stageVSlice", "mainStage")
        addProperty("stagePsych", "stage")
        addProperty("weekColor", "#C24B99")
        add("difficulties", JsonArray().apply { add("normal") })
        addProperty("practiceNotes", true)
        addProperty("offsetMs", "0")
        add("vSliceOrigin", JsonObject().apply {
            addProperty("folder", "test")
            addProperty("metadataPath", "data/songs/test/test-metadata.json")
            addProperty("chartPath", "data/songs/test/test-normal.json")
            add("editableDifficulties", JsonArray().apply { add("normal") })
            addProperty("instrumentalFile", "Inst.ogg")
        })
    }

    private fun legacyBothSong() = JsonObject().apply {
        addProperty("songName", "Test")
        addProperty("artist", "A")
        addProperty("bpm", "120")
        addProperty("speed", "2.0")
        addProperty("player", "bf")
        addProperty("girlfriend", "gf")
        addProperty("opponent", "dad")
        addProperty("stageVSlice", "mainStage")
        addProperty("stagePsych", "stage")
        addProperty("weekColor", "#C24B99")
        add("difficulties", JsonArray().apply { add("normal") })
        addProperty("practiceNotes", true)
        addProperty("offsetMs", "0")
    }

    @Test fun psychAndVsliceCanHaveDifferentChartsForSameSongAndDifficulty() {
        val psych = psychChart(difficulty = "normal")
        val vslice = vsliceChart(difficulty = "normal")
        val col = collection(songId = "song", psych = psych, vslice = vslice)
        val psychKey = IndependentChartKey("song", ChartEngine.PSYCH, "normal")
        val vKey = IndependentChartKey("song", ChartEngine.VSLICE, "normal")
        assertEquals(psych, col.chart(psychKey))
        assertEquals(vslice, col.chart(vKey))
        assertNotEquals(psych.bpm, col.chart(vKey)!!.bpm)
    }

    @Test fun editingPsychDoesNotModifyVslice() {
        val psych = psychChart(difficulty = "normal")
        val vslice = vsliceChart(difficulty = "normal")
        val col = collection(songId = "song", psych = psych, vslice = vslice)
        val key = IndependentChartKey("song", ChartEngine.PSYCH, "normal")
        val edited = psych.copy(notes = psych.notes + ChartNote(900, 1, 100))
        val changed = col.edit(key, edited)
        assertEquals(vslice, changed.songs.getValue("song").vSlice!!.charts.getValue("normal"))
        assertEquals(edited, changed.chart(key))
    }

    @Test fun undoRedoHistoriesAreIndependent() {
        val psych = psychChart(difficulty = "normal")
        val vslice = vsliceChart(difficulty = "normal")
        val col = collection(songId = "song", psych = psych, vslice = vslice)
        val psychKey = IndependentChartKey("song", ChartEngine.PSYCH, "normal")
        val vKey = IndependentChartKey("song", ChartEngine.VSLICE, "normal")

        val editedPsych = psych.copy(notes = listOf(ChartNote(900, 1, 100)))
        val changedPsych = col.edit(psychKey, editedPsych)
        val psychHist = changedPsych.songs.getValue("song").psych!!.histories.getValue("normal")
        val undoResult = restoreChartHistory(psychHist, changedPsych.chart(psychKey), false)
        assertEquals(psych, undoResult!!.chart)

        val vsliceHist = changedPsych.songs.getValue("song").vSlice!!.histories.getValue("normal")
        assertTrue(vsliceHist.undo.isEmpty() && vsliceHist.redo.isEmpty())
    }

    @Test fun difficultiesAreIndependentPerEngine() {
        val psych = psychChart(difficulty = "normal")
        val vsliceEasy = vsliceChart(difficulty = "easy")
        val col = collection(songId = "song", psych = psych, vslice = vsliceEasy)
        val pKey = IndependentChartKey("song", ChartEngine.PSYCH, "normal")
        val vKey = IndependentChartKey("song", ChartEngine.VSLICE, "easy")
        assertEquals("normal", col.chart(pKey)!!.difficulty)
        assertEquals("easy", col.chart(vKey)!!.difficulty)
        assertNull(col.chart(IndependentChartKey("song", ChartEngine.PSYCH, "easy")))
        assertNull(col.chart(IndependentChartKey("song", ChartEngine.VSLICE, "normal")))
    }

    @Test fun metadataAndEventsPreservedSeparately() {
        val psychMetadata = SongConfig(songName = "PsychSong", bpm = "120", vSliceEventsJson = null)
        val vsliceMetadata = SongConfig(songName = "VSong", bpm = "170",
            vSliceEventsJson = """[{"t":12.25,"e":"Camera"}]""",
            vSliceTempoJson = """[{"t":0,"bpm":170}]""")
        val psychChart = psychChart(difficulty = "normal")
        val vsliceChart = vsliceChart(difficulty = "normal")
        val col = IndependentChartCollection(songs = mapOf("song" to IndependentSong(
            "song",
            IndependentVariant(metadata = psychMetadata, difficulties = setOf("normal"), charts = mapOf("normal" to psychChart)),
            IndependentVariant(metadata = vsliceMetadata, difficulties = setOf("normal"), charts = mapOf("normal" to vsliceChart))
        )))
        val pVariant = col.songs["song"]?.variant(ChartEngine.PSYCH)!!
        val vVariant = col.songs["song"]?.variant(ChartEngine.VSLICE)!!
        assertEquals("120", pVariant.metadata.bpm)
        assertEquals("170", vVariant.metadata.bpm)
        assertEquals("""[{"t":12.25,"e":"Camera"}]""", vVariant.metadata.vSliceEventsJson)
        assertNull(pVariant.metadata.vSliceEventsJson)
    }

    @Test fun repositoryKeepsNullVsliceEventSnapshotsAsStrings() {
        val repository = ChartRepositoryImpl(Gson())
        val variant = IndependentVariant(metadata = SongConfig(songName = "Test"))
        repository.setConfig(ModConfig(
            chartSchemaVersion = CHART_SCHEMA_V2,
            independentCharts = IndependentChartCollection(
                songs = mapOf("test" to IndependentSong("test", vSlice = variant))
            )
        ))

        val events = """[{"t":0,"e":"FocusCamera","v":1}]"""
        val saved = repository.saveVSliceEvents("test", ChartEngine.VSLICE, events)
        repository.setConfig(saved)
        assertEquals(listOf("[]"), repository.config.independentCharts!!.songs.getValue("test").vSlice!!.metadata.vSliceEventsUndo)

        val undone = repository.undoVSliceEvents("test", ChartEngine.VSLICE)
        repository.setConfig(undone)
        assertEquals("[]", repository.getVSliceEvents("test", ChartEngine.VSLICE))
        assertEquals(listOf(events), repository.config.independentCharts!!.songs.getValue("test").vSlice!!.metadata.vSliceEventsRedo)

        val redone = repository.redoVSliceEvents("test", ChartEngine.VSLICE)
        assertEquals(events, redone.independentCharts!!.songs.getValue("test").vSlice!!.metadata.vSliceEventsJson)
    }

    @Test fun repositoryUndoRedoRestoresInitialVsliceTempo() {
        val repository = ChartRepositoryImpl(Gson())
        val variant = IndependentVariant(metadata = SongConfig(songName = "Test", bpm = "170"))
        repository.setConfig(ModConfig(
            chartSchemaVersion = CHART_SCHEMA_V2,
            independentCharts = IndependentChartCollection(
                songs = mapOf("test" to IndependentSong("test", vSlice = variant))
            )
        ))

        val editedTempo = """[{"t":0,"bpm":120,"n":4,"d":4}]"""
        val saved = repository.saveVSliceTempo("test", ChartEngine.VSLICE, editedTempo)
        repository.setConfig(saved)
        val initialTempo = repository.config.independentCharts!!.songs.getValue("test").vSlice!!.metadata.vSliceTempoUndo.single()
        assertEquals(170.0, com.google.gson.JsonParser.parseString(initialTempo).asJsonArray[0].asJsonObject.get("bpm").asDouble, 0.0)

        val undone = repository.undoVSliceTempo("test", ChartEngine.VSLICE)
        repository.setConfig(undone)
        assertEquals("170", repository.config.independentCharts!!.songs.getValue("test").vSlice!!.metadata.bpm)
        assertEquals(170.0, com.google.gson.JsonParser.parseString(
            repository.getVSliceTempo("test", ChartEngine.VSLICE)!!
        ).asJsonArray[0].asJsonObject.get("bpm").asDouble, 0.0)

        val redone = repository.redoVSliceTempo("test", ChartEngine.VSLICE)
        assertEquals("120", redone.independentCharts!!.songs.getValue("test").vSlice!!.metadata.bpm)
    }

    @Test fun serializesAndDeserializesRoundTrip() {
        val psych = psychChart(difficulty = "normal")
        val vslice = vsliceChart(difficulty = "normal")
        val col = collection(songId = "song", psych = psych, vslice = vslice)
        val json = Gson().toJsonTree(col).asJsonObject
        val restored = Gson().fromJson(json, IndependentChartCollection::class.java)!!
        assertEquals(psych, restored.chart(IndependentChartKey("song", ChartEngine.PSYCH, "normal")))
        assertEquals(vslice, restored.chart(IndependentChartKey("song", ChartEngine.VSLICE, "normal")))
    }

    @Test fun migrationPsychProjectAttributesChartsToPsych() {
        val gson = Gson()
        val legacy = JsonObject().apply {
            addProperty("engine", TargetEngine.PSYCH.name)
            addProperty("chartSchemaVersion", 1)
            val songs = JsonArray(); songs.add(legacyPsychSong()); add("songs", songs)
            val charts = JsonObject()
            charts.add("test", JsonObject().apply {
                add("normal", Gson().toJsonTree(psychChart("test", "normal")).asJsonObject)
                add("hard", Gson().toJsonTree(psychChart("test", "hard")).asJsonObject)
            })
            add("charts", charts)
            add("chartHistories", JsonObject())
        }
        val result = LegacyChartMigration.migrateToV2(legacy, gson)
        assertTrue(result.engineAttributedCount >= 1)
        val psychSong = result.collection.songs.getValue("test")
        assertNotNull(psychSong.psych)
        assertEquals(2, psychSong.psych!!.difficulties.size)
        assertNull(psychSong.vSlice)
    }

    @Test fun migrationVsliceProjectAttributesChartsToVslice() {
        val gson = Gson()
        val legacy = JsonObject().apply {
            addProperty("engine", TargetEngine.VSLICE.name)
            addProperty("chartSchemaVersion", 1)
            val songs = JsonArray(); songs.add(legacyVSliceSong()); add("songs", songs)
            val charts = JsonObject()
            charts.add("test", JsonObject().apply {
                add("normal", Gson().toJsonTree(vsliceChart("test", "normal")).asJsonObject)
            })
            add("charts", charts)
            add("chartHistories", JsonObject())
        }
        val result = LegacyChartMigration.migrateToV2(legacy, gson)
        val song = result.collection.songs.getValue("test")
        assertNotNull(song.vSlice)
        assertNull(song.psych)
        assertTrue(song.vSlice!!.difficulties.contains("normal"))
    }

    @Test fun ambiguousChartsRemainInLegacyDeposit() {
        val gson = Gson()
        val legacy = JsonObject().apply {
            addProperty("engine", TargetEngine.BOTH.name)
            addProperty("chartSchemaVersion", 1)
            val songs = JsonArray(); songs.add(legacyBothSong()); add("songs", songs)
            val charts = JsonObject()
            charts.add("test", JsonObject().apply {
                add("normal", Gson().toJsonTree(psychChart("test", "normal")).asJsonObject)
            })
            add("charts", charts)
            add("chartHistories", JsonObject())
        }
        val result = LegacyChartMigration.migrateToV2(legacy, gson)
        val song = result.collection.songs.getValue("test")
        assertNull(song.psych)
        assertNull(song.vSlice)
        assertTrue(result.legacyPreserved)
        assertTrue(result.collection.legacyDocumentJson.isNotEmpty())
    }

    @Test fun migrationDoesNotDuplicateSharedChartIntoBothEngines() {
        val gson = Gson()
        // Single song with BOTH engine but no explicit evidence for either engine
        // The shared chart should remain in legacy deposit, not be duplicated
        val legacy = JsonObject().apply {
            addProperty("engine", TargetEngine.BOTH.name)
            addProperty("chartSchemaVersion", 1)
            val songs = JsonArray()
            songs.add(JsonObject().apply {
                addProperty("songName", "Test")
                addProperty("artist", "A")
                addProperty("bpm", "120")
                addProperty("speed", "2.0")
                addProperty("player", "bf")
                addProperty("girlfriend", "gf")
                addProperty("opponent", "dad")
                addProperty("stageVSlice", "mainStage")
                addProperty("stagePsych", "stage")
                addProperty("weekColor", "#C24B99")
                add("difficulties", JsonArray().apply { add("normal") })
                addProperty("practiceNotes", true)
                addProperty("offsetMs", "0")
                // No importedPsychFolder, no vSliceOrigin - no explicit evidence
            })
            add("songs", songs)
            val charts = JsonObject()
            charts.add("test", JsonObject().apply {
                add("normal", Gson().toJsonTree(psychChart("test", "normal")).asJsonObject)
            })
            add("charts", charts)
            add("chartHistories", JsonObject())
        }
        val result = LegacyChartMigration.migrateToV2(legacy, gson)
        val song = result.collection.songs.getValue("test")
        // No explicit evidence for either engine -> both null, chart stays in legacy
        assertNull(song.psych)
        assertNull(song.vSlice)
        assertTrue(result.legacyPreserved)
        // Original chart preserved in legacy deposit
        assertTrue(result.collection.legacyDocumentJson.isNotEmpty())
    }


    @Test fun migrationIsIdempotent() {
        val gson = Gson()
        val legacy = JsonObject().apply {
            addProperty("engine", TargetEngine.PSYCH.name)
            addProperty("chartSchemaVersion", 1)
            val songs = JsonArray(); songs.add(legacyPsychSong()); add("songs", songs)
            val charts = JsonObject()
            charts.add("test", JsonObject().apply {
                add("normal", Gson().toJsonTree(psychChart("test", "normal")).asJsonObject)
            })
            add("charts", charts)
            add("chartHistories", JsonObject())
        }
        val first = LegacyChartMigration.migrateToV2(legacy, gson)
        val v2Config = ModConfig(chartSchemaVersion = CHART_SCHEMA_V2, independentCharts = first.collection)
        val v2Json = gson.toJsonTree(v2Config).asJsonObject
        val second = LegacyChartMigration.migrateToV2(v2Json, gson)
        assertEquals(first.collection.songs.size, second.collection.songs.size)
        val applied = LegacyChartMigration.applyMigration(v2Json, gson)
        assertEquals(v2Json, applied)
    }

    @Test fun unknownFieldsArePreservedInMigration() {
        val gson = Gson()
        val legacy = JsonObject().apply {
            addProperty("engine", TargetEngine.PSYCH.name)
            addProperty("chartSchemaVersion", 1)
            val songs = JsonArray()
            songs.add(JsonObject().apply {
                addProperty("songName", "Test")
                addProperty("artist", "A")
                addProperty("bpm", "120")
                addProperty("speed", "2.0")
                addProperty("player", "bf")
                addProperty("girlfriend", "gf")
                addProperty("opponent", "dad")
                addProperty("stageVSlice", "mainStage")
                addProperty("stagePsych", "stage")
                addProperty("weekColor", "#C24B99")
                add("difficulties", JsonArray().apply { add("normal") })
                addProperty("practiceNotes", true)
                addProperty("offsetMs", "0")
                addProperty("importedPsychFolder", "test")
                add("unknownField", JsonObject().apply { addProperty("path", "not-a-file"); addProperty("custom", 42) })
            })
            add("songs", songs)
            val charts = JsonObject()
            charts.add("test", JsonObject().apply {
                add("normal", Gson().toJsonTree(psychChart("test", "normal")).asJsonObject)
            })
            add("charts", charts)
            add("chartHistories", JsonObject())
        }
        val result = LegacyChartMigration.migrateToV2(legacy, gson)
        assertTrue(result.collection.legacyDocumentJson.isNotEmpty())
        assertTrue(result.collection.legacyDocumentJson.contains("unknownField"))
    }

    @Test fun fullMigrationFlowValidatesAndRoundTrips() {
        val gson = Gson()
        val validator = ProjectConfigValidator(gson)
        val legacy = JsonObject().apply {
            addProperty("engine", TargetEngine.PSYCH.name)
            addProperty("chartSchemaVersion", 1)
            val songs = JsonArray(); songs.add(legacyPsychSong()); add("songs", songs)
            val charts = JsonObject()
            charts.add("test", JsonObject().apply {
                add("normal", Gson().toJsonTree(psychChart("test", "normal")).asJsonObject)
            })
            add("charts", charts)
            add("chartHistories", JsonObject())
        }
        // 1. Apply migration
        val migrated = LegacyChartMigration.applyMigration(legacy, gson)
        // 2. Legacy maps removed from editable storage
        assertFalse(migrated.has("charts"))
        assertFalse(migrated.has("chartHistories"))
        assertTrue(migrated.has("independentCharts"))
        assertEquals("2", migrated.get("chartSchemaVersion").asString)
        // 3. Validate v2
        validator.validate(migrated)
        // 4. Deserialize to ModConfig
        val config = gson.fromJson(migrated, ModConfig::class.java)!!
        assertEquals(CHART_SCHEMA_V2, config.chartSchemaVersion)
        assertNotNull(config.independentCharts)
        // 5. Serialize back (simulates save/close/reopen)
        val roundTrip = gson.fromJson(gson.toJsonTree(config), JsonObject::class.java)!!
        validator.validate(roundTrip)
        val restored = gson.fromJson(roundTrip, ModConfig::class.java)!!
        assertEquals(CHART_SCHEMA_V2, restored.chartSchemaVersion)
        assertNotNull(restored.independentCharts!!.songs.getValue("test").psych)
    }

    @Test fun v2ValidatorAcceptsValidIndependentProject() {
        val gson = Gson()
        val validator = ProjectConfigValidator(gson)
        val psych = psychChart("test", "normal")
        val vslice = vsliceChart("test", "normal")
        val col = collection(songId = "test", psych = psych, vslice = vslice)
        val config = ModConfig(chartSchemaVersion = CHART_SCHEMA_V2, independentCharts = col)
        val json = gson.toJsonTree(config).asJsonObject
        validator.validate(json)
    }

    @Test fun v2ValidatorRejectsMissingIndependentCharts() {
        val gson = Gson()
        val validator = ProjectConfigValidator(gson)
        val config = ModConfig(chartSchemaVersion = CHART_SCHEMA_V2)
        val json = gson.toJsonTree(config).asJsonObject
        assertThrows(IllegalArgumentException::class.java) { validator.validate(json) }
    }

    @Test fun v2ValidatorRejectsUnknownSchemaVersion() {
        val gson = Gson()
        val validator = ProjectConfigValidator(gson)
        val legacy = JsonObject().apply {
            addProperty("engine", TargetEngine.VSLICE.name)
            addProperty("chartSchemaVersion", 99)
        }
        assertThrows(IllegalArgumentException::class.java) { validator.validate(legacy) }
    }

    @Test fun oldReadersRejectV2Schema() {
        val gson = Gson()
        val psych = psychChart("test", "normal")
        val col = collection(songId = "test", psych = psych, vslice = null)
        val config = ModConfig(chartSchemaVersion = CHART_SCHEMA_V2, independentCharts = col)
        val json = gson.toJsonTree(config).asJsonObject
        assertThrows(IllegalArgumentException::class.java) {
            com.fnfmodmaker.project.requireSupportedChartSchema(json)
        }
    }

    @Test fun v1AndUnversionedStillValidate() {
        val gson = Gson()
        val validator = ProjectConfigValidator(gson)
        val legacy = JsonObject().apply {
            addProperty("engine", TargetEngine.BOTH.name)
            val songs = JsonArray(); songs.add(legacyBothSong()); add("songs", songs)
            val charts = JsonObject()
            charts.add("test", JsonObject().apply {
                add("normal", Gson().toJsonTree(psychChart("test", "normal")).asJsonObject)
            })
            add("charts", charts)
            add("chartHistories", JsonObject())
        }
        validator.validate(legacy)
        val explicitV1 = legacy.deepCopy().apply { addProperty("chartSchemaVersion", 1) }
        validator.validate(explicitV1)
    }

    @Test fun mediaReferencesAreTypedNotGuessed() {
        val ref = ProjectMediaReference(uri = "content://x", displayName = "img.png", path = "assets/img.png", kind = "image")
        assertEquals("image", ref.kind)
        val audioRef = ProjectMediaReference(uri = "content://y", displayName = "Inst.ogg", path = "assets/Inst.ogg", kind = "audio")
        assertEquals("audio", audioRef.kind)
        assertThrows(IllegalArgumentException::class.java) {
            ProjectMediaReference(uri = "", displayName = "x", path = "../escape", kind = "image")
        }
    }

    @Test fun failedMigrationDoesNotModifyOriginal() {
        val gson = Gson()
        val legacy = JsonObject().apply {
            addProperty("engine", TargetEngine.VSLICE.name)
            addProperty("chartSchemaVersion", 1)
            add("independentCharts", JsonParser.parseString("""{"x":{"y":1}}"""))
        }
        val before = legacy.deepCopy()
        assertThrows(IllegalArgumentException::class.java) {
            LegacyChartMigration.migrateToV2(legacy, gson)
        }
        assertEquals(before, legacy)
    }

    @Test fun portableRoundTripPreservesBothVariants() {
        val gson = Gson()
        val validator = ProjectConfigValidator(gson)
        val psych = psychChart("test", "normal")
        val vslice = vsliceChart("test", "normal")
        val col = collection(songId = "test", psych = psych, vslice = vslice)
        val config = ModConfig(chartSchemaVersion = CHART_SCHEMA_V2, independentCharts = col)
        val json = gson.toJsonTree(config).asJsonObject
        validator.validate(json)
        // Round-trip through Gson serialization (simulates portable export/import)
        val roundTrip = gson.fromJson(gson.toJson(json), JsonObject::class.java)!!
        validator.validate(roundTrip)
        val restored = gson.fromJson(roundTrip, ModConfig::class.java)!!
        assertEquals(CHART_SCHEMA_V2, restored.chartSchemaVersion)
        val restoredPsych = restored.independentCharts!!.songs.getValue("test").psych!!
        val restoredVslice = restored.independentCharts!!.songs.getValue("test").vSlice!!
        assertEquals(psych.bpm, restoredPsych.charts.getValue("normal").bpm)
        assertEquals(vslice.bpm, restoredVslice.charts.getValue("normal").bpm)
        assertEquals("""[{"t":12.25,"e":"Camera"}]""", restoredVslice.metadata.vSliceEventsJson)
    }
}
