package com.fnfmodmaker.model

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ChartSerializationTest {
    @Test fun psychIncludesNoteExactlyAtSectionBoundaryAndDurationInMilliseconds() {
        val draft = ChartDraft(bpm = 150, notes = listOf(ChartNote(0, 4), ChartNote(1600, 0, 350)))
        val json = JSONObject(draft.toPsychJson())
        val sections = json.getJSONArray("notes")
        assertEquals(2, sections.length())
        val boundary = sections.getJSONObject(1).getJSONArray("sectionNotes").getJSONArray(0)
        assertEquals(1600, boundary.getInt(0))
        assertEquals(350, boundary.getInt(2))
    }

    @Test fun vslicePreservesLaneAndHoldDuration() {
        val notes = ChartDraft(notes = listOf(ChartNote(500, 7, 200))).toVSliceNotes()
        assertEquals(7, notes.getJSONObject(0).getInt("d"))
        assertEquals(200, notes.getJSONObject(0).getInt("l"))
    }
    @Test fun unsupportedDataFailsBothDirectSerializersInsteadOfBeingDropped() {
        val drafts = listOf(
            ChartDraft(events = listOf(ChartEvent())),
            ChartDraft(sections = listOf(ChartSection())),
            ChartDraft(notes = listOf(ChartNote(10, 0, isSustain = true))),
            ChartDraft(notes = listOf(ChartNote(10, 8)))
        )
        drafts.forEach { draft ->
            assertTrue(draft.exportProblems().isNotEmpty())
            assertThrows(IllegalArgumentException::class.java) { draft.toPsychJson() }
            assertThrows(IllegalArgumentException::class.java) { draft.toVSliceNotes() }
            assertThrows(IllegalArgumentException::class.java) { draft.toVSliceJson() }
        }
    }
    @Test fun editedGroupSurvivesProjectJsonAndBothEngineBuilders() {
        val song = SongConfig(songName = "Group test", bpm = "120", practiceNotes = false)
        val group = listOf(ChartNote(500, 4, 250), ChartNote(750, 1))
        val moved = translateGridNotes(group, 500, 0)!!
        val notes = replaceGridNotes(group, group, moved, 5000)!!
        val config = ModConfig(engine = TargetEngine.BOTH, songs = listOf(song))
            .withChart(song.songFolder, "hard", ChartDraft(songFolder = song.songFolder, difficulty = "hard", notes = notes))
            .withChart(song.songFolder, "normal", ChartDraft(songFolder = song.songFolder, difficulty = "normal", notes = listOf(ChartNote(100, 3))))
        val gson = com.google.gson.Gson()
        val restored = gson.fromJson(gson.toJson(config), ModConfig::class.java)
        assertEquals(notes, restored.getChart(song.songFolder, "hard")!!.notes)
        val v = JSONObject(com.fnfmodmaker.mod.JsonBuilders.vsliceChart(restored, song, 5000)).getJSONObject("notes")
        assertEquals(2, v.getJSONArray("hard").length())
        assertEquals(1000, v.getJSONArray("hard").getJSONObject(0).getInt("t"))
        assertEquals(250, v.getJSONArray("hard").getJSONObject(0).getInt("l"))
        assertEquals(1, v.getJSONArray("normal").length())
        val p = JSONObject(com.fnfmodmaker.mod.JsonBuilders.psychChart(restored, song, 5000, "hard"))
            .getJSONArray("notes").getJSONObject(0).getJSONArray("sectionNotes")
        assertEquals(2, p.length()); assertEquals(1000, p.getJSONArray(0).getInt(0))
        assertEquals(4, p.getJSONArray(0).getInt(1)); assertEquals(250, p.getJSONArray(0).getInt(2))
    }
    @Test fun engineBuildersAndWizardRejectUnsupportedFeatures() {
        val song = SongConfig(songName = "Test")
        val draft = ChartDraft(events = listOf(ChartEvent()))
        for (engine in TargetEngine.entries) {
            val config = ModConfig(engine = engine, songs = listOf(song)).withChart(song.songFolder, "hard", draft)
            assertTrue(validateWizard(config) { true }.any { it.message.contains("eventos") })
            if (engine.includesVSlice) assertThrows(IllegalArgumentException::class.java) { com.fnfmodmaker.mod.JsonBuilders.vsliceChart(config, song, 1000) }
            if (engine.includesPsych) assertThrows(IllegalArgumentException::class.java) { com.fnfmodmaker.mod.JsonBuilders.psychChart(config, song, 1000, "hard") }
        }
    }
}
