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
        val normal = readPsychChart(chart.replace("\"song\":\"test\"", "\"song\":\"Original Song\""), "test", "normal").copy(scrollSpeed = 1.0f, offsetMs = 0, needsVoices = true)
        assertEquals("Original Song", JsonParser.parseString(normal.toPsychJson()).asJsonObject.get("song").asString)
        val hard = normal.copy(difficulty = "hard", scrollSpeed = 1.5f, offsetMs = 12, needsVoices = false)
        val song = SongConfig(songName = "test", bpm = "120", speed = "1.0", offsetMs = "0", importedPsychFolder = "test", difficulties = setOf("normal", "hard"))
        val config = ModConfig(engine = TargetEngine.PSYCH, songs = listOf(song)).withChart("test", "normal", normal).withChart("test", "hard", hard)
        val out = JsonParser.parseString(JsonBuilders.psychChart(config, song, 6000, "hard")).asJsonObject
        assertEquals("Original Song", out.get("song").asString)
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
        assertThrows(IllegalArgumentException::class.java) { draft.copy(psychLayout = listOf(draft.psychLayout[0].copy(sourceJson = """{"sectionBeats":"invalid"}"""))).toPsychJson() }
        assertThrows(IllegalArgumentException::class.java) { draft.copy(psychSongExtras = """{"song":[1]}""").toPsychJson() }
    }
    @Test fun fractionalInitialBpmAndSectionChangesSurviveBothFormats() {
        val source = """{"song":{"song":"decimal","bpm":120.5,"speed":1,"events":[[99,[["Custom","x","y"]]]],"extra":{"preserve":true},"notes":[
          {"sectionBeats":1.25,"lengthInSteps":5,"mustHitSection":true,"sectionNotes":[[622,0,20]]},
          {"sectionBeats":2.5,"lengthInSteps":10,"mustHitSection":false,"changeBPM":true,"bpm":140.25,"altAnim":true,"sectionNotes":[[623,0,100],[1691,7,2]]},
          {"sectionBeats":3.25,"mustHitSection":true,"changeBPM":true,"bpm":99.75,"gfSection":true,"custom":"keep","sectionNotes":[[1692,5,300]]}]}}"""
        val draft = readPsychChart(source, "decimal", "normal")
        val spans = psychSectionSpans(draft.psychLayout, draft.bpm)
        assertEquals(120.5, draft.bpm, 0.0)
        assertEquals(1.25 * 60000.0 / 120.5, spans[1].startMs, 1e-9)
        assertEquals(spans[1].startMs + 2.5 * 60000.0 / 140.25, spans[2].startMs, 1e-9)
        val grid = TouchGrid(120.5, 4, 8000, psychGridTempos(draft.psychLayout, draft.bpm))
        assertEquals(spans[1].startMs, grid.timeAtBeat(1.25), 1e-9)
        assertEquals(spans[2].startMs, grid.timeAtBeat(3.75), 1e-9)
        val restored = com.google.gson.Gson().fromJson(com.google.gson.Gson().toJson(draft), ChartDraft::class.java)
        assertEquals(draft, restored)
        for (encoded in listOf(restored.toPsychJson(), JsonBuilders.psych073Chart(restored.toPsychJson()))) {
            val reimport = readPsychChart(encoded, "decimal", "normal")
            assertEquals(draft.bpm, reimport.bpm, 0.0)
            assertEquals(draft.notes, reimport.notes)
            assertEquals(draft.psychEventsJson, reimport.psychEventsJson)
            assertEquals(140.25, reimport.psychLayout[1].bpm, 0.0)
            assertEquals(99.75, reimport.psychLayout[2].bpm, 0.0)
            assertTrue(reimport.psychLayout[1].altAnim && reimport.psychLayout[2].gfSection)
            assertEquals("keep", JsonParser.parseString(reimport.psychLayout[2].sourceJson).asJsonObject.get("custom").asString)
        }
    }

    @Test fun bpmChangeAtFirstSectionAndLegacyLengthProtection() {
        val draft = readPsychChart(chart, "test", "normal")
        val first = draft.psychLayout[0].copy(beats = 2.25, changeBpm = true, bpm = 125.5)
        val sections = listOf(first) + draft.psychLayout.drop(1)
        val spans = psychSectionSpans(sections, 120.5)
        assertEquals(0.0, spans.first().startMs, 0.0)
        assertEquals(125.5, spans.first().bpm, 0.0)
        assertEquals(2.25 * 60000.0 / 125.5, spans[1].startMs, 1e-9)
        val safe = draft.copy(notes = emptyList(), bpm = 120.5, psychLayout = sections)
        val out = JsonParser.parseString(safe.toPsychJson()).asJsonObject.getAsJsonArray("notes")[0].asJsonObject
        assertEquals(9, out.get("lengthInSteps").asInt)
        assertThrows(IllegalArgumentException::class.java) {
            safe.copy(psychLayout = listOf(first.copy(beats = 2.3)) + sections.drop(1)).toPsychJson()
        }
    }

    @Test fun rejectsInitialBpmWhosePrecisionWouldBeLost() {
        val source = chart.replace("\"bpm\":120,", "\"bpm\":120.1234567890123456789,")
        assertThrows(IllegalArgumentException::class.java) { readPsychChart(source, "test", "normal") }
        val sectionBpm = chart.replace("\"bpm\":180,", "\"bpm\":180.1234567890123456789,")
        assertThrows(IllegalArgumentException::class.java) { readPsychChart(sectionBpm, "test", "normal") }
        val beats = chart.replace("\"sectionBeats\":3,", "\"sectionBeats\":3.1234567890123456789,")
        assertThrows(IllegalArgumentException::class.java) { readPsychChart(beats, "test", "normal") }
        assertEquals(0.0, SongConfig(bpm = "120.1234567890123456789").bpmValue, 0.0)
        assertThrows(IllegalArgumentException::class.java) { ChartDraft(bpm = 0.0).toPsychJson() }
    }

}
