package com.fnfmodmaker.project

import com.fnfmodmaker.model.ChartDraft
import com.fnfmodmaker.model.ChartNote
import com.fnfmodmaker.model.ChartPersistentHistory
import com.fnfmodmaker.model.ModConfig
import com.fnfmodmaker.model.SongConfig
import com.fnfmodmaker.model.TargetEngine
import com.fnfmodmaker.model.recordChartHistory
import com.google.gson.Gson
import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ChartSchemaTest {
    @get:Rule val temp = TemporaryFolder()
    private val gson = Gson()
    private val validator = ProjectConfigValidator(gson)

    private fun legacy() = gson.toJsonTree(
        ModConfig(engine = TargetEngine.BOTH, songs = listOf(SongConfig(songName = "Test")),
            charts = mapOf("test" to mapOf("normal" to ChartDraft(songFolder = "test", notes = listOf(ChartNote(123, 7, 400, psychTypeJson = "0"))))),
            chartHistories = mapOf("test" to mapOf("normal" to recordChartHistory(ChartPersistentHistory(), null,
                ChartDraft(songFolder = "test", notes = listOf(ChartNote(123, 7, 400, psychTypeJson = "0")))))))
    ).asJsonObject

    @Test fun oldAndExplicitV1ValidateWithoutChangingChartsOrHistory() {
        val source = legacy()
        for (versioned in listOf(false, true)) {
            val config = source.deepCopy()
            if (versioned) config.addProperty("chartSchemaVersion", 1)
            val copy = config.deepCopy()
            validator.validate(config)
            assertEquals(copy, config)
        }
    }

    @Test fun rejectsUnknownAndMalformedVersionsBeforeParsing() {
        for (raw in listOf("2", "0", "-1", "1.0", "1e0", "\"1\"", "null", "true", "{}", "[]", "999999999999999999999999999")) {
            val config = legacy().apply { add("chartSchemaVersion", JsonParser.parseString(raw)) }
            val before = config.deepCopy()
            val error = assertThrows(IllegalArgumentException::class.java) { validator.validate(config) }
            assertTrue(error.message!!.contains("versión de charts"))
            assertEquals(before, config)
        }
    }

    @Test fun portableUnknownVersionDoesNotPublishOrChangeActiveProject() {
        val store = ProjectStore(File(temp.root, "projects"), temp.root)
        val active = store.newId()
        val old = legacy(); store.save(active, old); store.setActive(active)
        val future = old.deepCopy().apply {
            addProperty("chartSchemaVersion", 2)
            add("independentCharts", JsonParser.parseString("""{"psych":{"normal":{"notes":[1]}},"vslice":{"normal":{"notes":[2]}}}"""))
        }
        val archive = File(temp.root, "future.fnfproject")
        val portable = PortableProject(temp.root)
        portable.export(future, store.assets(active), archive)
        val stage = store.newImportStage(); val prepared = portable.prepare(archive, stage)
        assertThrows(IllegalArgumentException::class.java) { store.publishImport(prepared, portable, validator::validate) }
        store.discardImportStage(stage)
        assertFalse(stage.exists())
        assertEquals(active, store.activeId())
        assertEquals(old, store.load(active).config)
        assertEquals(listOf(active), store.list().map { it.id })
        // The source package remains untouched and can be opened by a future version.
        val readAgain = portable.prepare(archive, store.newImportStage())
        assertEquals(future, readAgain.config)
        store.discardImportStage(readAgain.directory)
    }
}
