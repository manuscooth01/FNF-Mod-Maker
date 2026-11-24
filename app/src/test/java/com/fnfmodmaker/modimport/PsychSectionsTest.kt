package com.fnfmodmaker.modimport

import com.fnfmodmaker.model.*
import com.fnfmodmaker.mod.JsonBuilders
import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Test

class PsychSectionsTest {
    private val chart = """{"song":{"song":"test","bpm":120,"speed":1,"events":[[100,[["Hey!","0",""]]]],"extra":{"value":123456789012345678901234567890},"notes":[{"mustHitSection":true,"lengthInSteps":16,"x":{"keep":true},"sectionNotes":[[100,0,500,"Hurt Note"]]},{"mustHitSection":false,"sectionBeats":3,"lengthInSteps":12,"changeBPM":true,"bpm":180,"altAnim":true,"gfSection":true,"sectionNotes":[[2200,0,300]]},{"mustHitSection":true,"sectionBeats":4,"changeBPM":true,"bpm":90,"sectionNotes":[[3100,5,100]]}]}}"""
    @Test fun sectionTempoAndBothFormatsPreservePropertiesAndLanes() {
        val draft = readPsychChart(chart, "test", "normal")
        assertEquals(listOf(0,4,5), draft.notes.map { it.lane })
        val spans = psychSectionSpans(draft.psychLayout, 120)
        assertEquals(2000.0, spans[1].startMs, .0001)
        assertEquals(3000.0, spans[2].startMs, .0001)
        assertEquals(5666.6666666667, spans[2].endMs, .0001)
        val grid = TouchGrid(120, 4, 6000, psychGridTempos(draft.psychLayout, 120))
        assertEquals(2333L, grid.timeAtBeat(5.0).toLong())
        for (json in listOf(draft.toPsychJson(), JsonBuilders.psych073Chart(draft.toPsychJson()))) {
            val reimport = readPsychChart(json, "test", "normal")
            assertEquals(draft.notes, reimport.notes)
            assertEquals(draft.psychEventsJson, reimport.psychEventsJson)
            assertEquals(180.0, reimport.psychLayout[1].bpm, 0.0)
            assertTrue(reimport.psychLayout[1].gfSection && reimport.psychLayout[1].altAnim)
            val root = JsonParser.parseString(json).asJsonObject
            val body = root.get("song")?.takeIf { it.isJsonObject }?.asJsonObject ?: root
            assertEquals("123456789012345678901234567890", body.getAsJsonObject("extra").get("value").toString())
            assertTrue(body.getAsJsonArray("notes")[0].asJsonObject.getAsJsonObject("x").get("keep").asBoolean)
            assertEquals(12, body.getAsJsonArray("notes")[1].asJsonObject.get("lengthInSteps").asInt)
        }
    }
    @Test fun importedDifficultyMetadataIsNotSilentlyReplacedBySharedSongDefaults() {
        val normal = readPsychChart(chart, "test", "normal").copy(scrollSpeed = 1.0f, offsetMs = 0, needsVoices = true)
        val hard = normal.copy(difficulty = "hard", scrollSpeed = 1.5f, offsetMs = 12, needsVoices = false)
        val song = SongConfig(songName = "test", bpm = "120", speed = "1.0", offsetMs = "0", importedPsychFolder = "test", difficulties = setOf("normal", "hard"))
        val config = ModConfig(engine = TargetEngine.PSYCH, songs = listOf(song)).withChart("test", "normal", normal).withChart("test", "hard", hard)
        val out = JsonParser.parseString(JsonBuilders.psychChart(config, song, 6000, "hard")).asJsonObject
        assertEquals(1.5, out.get("speed").asDouble, 0.0)
        assertEquals(12, out.get("offset").asInt)
        assertFalse(out.get("needsVoices").asBoolean)
        val changed = song.copy(speed = "2", offsetMs = "30")
        val changedOut = JsonParser.parseString(JsonBuilders.psychChart(config, changed, 6000, "hard")).asJsonObject
        assertEquals(2.0, changedOut.get("speed").asDouble, 0.0)
        assertEquals(30, changedOut.get("offset").asInt)
    }
    @Test fun rejectsAnythingThatCannotBeRepresentedInsteadOfLosingIt() {
        assertThrows(IllegalArgumentException::class.java) { readPsychChart(chart.replace("[100,0,500", "[100.25,0,500"), "test", "normal") }
        assertThrows(IllegalArgumentException::class.java) { readPsychChart(chart.replace("[2200,0,300]", "[1200,0,300]"), "test", "normal") }
        assertThrows(IllegalArgumentException::class.java) { readPsychChart(chart.replace("[2200,0,300]", "[2200,0,300,0,{\"extra\":true}]"), "test", "normal") }
        val draft = readPsychChart(chart, "test", "normal")
        assertThrows(IllegalArgumentException::class.java) { draft.copy(psychLayout = draft.psychLayout.dropLast(1)).toPsychJson() }
    }
}
