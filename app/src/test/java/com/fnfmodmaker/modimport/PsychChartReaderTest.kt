package com.fnfmodmaker.modimport

import com.fnfmodmaker.model.ChartNote
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class PsychChartReaderTest {
    private fun chart(format: String, mustHit: Boolean, extra: String = "") = """{
      "song": {"song":"test","bpm":120,"speed":2,"player1":"bf","player2":"dad", $format
      "events":[],"notes":[{"mustHitSection":$mustHit,"sectionBeats":4,"sectionNotes":[[100,0,0],[500,6,250]$extra]}]}}
    """.trimIndent()
    @Test fun parsesV1AbsoluteLanesIndependentlyOfSection() {
        for (mustHit in listOf(true, false)) {
            val draft = readPsychChart(chart("\"format\":\"psych_v1\",", mustHit), "test", "normal")
            assertEquals(listOf(ChartNote(100, 0), ChartNote(500, 6, 250)), draft.notes)
            assertEquals("bf", draft.player1); assertEquals("dad", draft.player2); assertEquals(120.0, draft.bpm, 0.0)
        }
    }
    @Test fun legacyFlipsLanesWhenOpponentSection() {
        val draft = readPsychChart(chart("", false), "test", "normal")
        assertEquals(listOf(ChartNote(100, 4), ChartNote(500, 2, 250)), draft.notes)
    }
    @Test fun editedLaneSurvivesOutputEvenInOpponentSection() {
        val original = readPsychChart(chart("\"format\":\"psych_v1\",", false), "test", "normal")
        val draft = original.copy(notes = listOf(ChartNote(2100, 1, 200)), psychLayout = original.psychLayout + com.fnfmodmaker.model.PsychSectionData(bpm = 120.0))
        val encoded = JSONObject(draft.toPsychJson())
        val note = encoded.getJSONArray("notes").getJSONObject(1).getJSONArray("sectionNotes").getJSONArray(0)
        assertEquals(1, note.getInt(1)); assertEquals(200, note.getInt(2))
        assertEquals(draft.notes, readPsychChart(encoded.toString(), "test", "normal").notes)
    }
    @Test fun psych073ExportUsesSectionRelativeLanesAndWrappedSong() {
        val song = com.fnfmodmaker.model.SongConfig(songName = "test", bpm = "150", difficulties = setOf("normal"), practiceNotes = false)
        val draft = com.fnfmodmaker.model.ChartDraft(songFolder = "test", difficulty = "normal", bpm = 150.0,
            notes = listOf(ChartNote(0, 0), ChartNote(2000, 0), ChartNote(2100, 4)))
        val config = com.fnfmodmaker.model.ModConfig(engine = com.fnfmodmaker.model.TargetEngine.PSYCH,
            psychChartFormat = com.fnfmodmaker.model.PsychChartFormat.PSYCH_073, songs = listOf(song)).withChart("test", "normal", draft)
        val encoded = JSONObject(com.fnfmodmaker.mod.JsonBuilders.psychChart(config, song, 4000, "normal"))
        val body = encoded.getJSONObject("song")
        val first = body.getJSONArray("notes").getJSONObject(0)
        val second = body.getJSONArray("notes").getJSONObject(1)
        assertTrue(first.getBoolean("mustHitSection")); assertFalse(second.getBoolean("mustHitSection"))
        assertEquals(0, first.getJSONArray("sectionNotes").getJSONArray(0).getInt(1))
        assertEquals(4, second.getJSONArray("sectionNotes").getJSONArray(0).getInt(1))
        assertEquals(0, second.getJSONArray("sectionNotes").getJSONArray(1).getInt(1))
        assertEquals(draft.notes, readPsychChart(encoded.toString(), "test", "normal").notes)
    }

    @Test fun stringNoteTypesRoundTripWithoutBecomingNormalNotes() {
        val source = chart("", true, ",[300,1,0,\"Hurt Note\"]")
        val draft = readPsychChart(source, "test", "normal")
        assertEquals("Hurt Note", draft.notes.last().kind)
        val encoded = draft.toPsychJson()
        assertEquals("Hurt Note", readPsychChart(encoded, "test", "normal").notes.single { it.timeMs == 300L }.kind)
    }

    @Test fun psychEventsRoundTripForBothFormats() {
        val input = chart("", false).replace("\"events\":[]", "\"events\":[[100,[[\"Hey!\",\"0\",\"\"],[\"Custom\",\"one\",\"two\"]]]]")
        val draft = readPsychChart(input, "test", "normal")
        assertEquals("""[[100,[["Hey!","0",""],["Custom","one","two"]]]]""", draft.psychEventsJson)
        val v1 = draft.toPsychJson()
        assertEquals(draft.psychEventsJson, readPsychChart(v1, "test", "normal").psychEventsJson)
        val legacy = com.fnfmodmaker.mod.JsonBuilders.psych073Chart(v1)
        assertEquals(draft.psychEventsJson, readPsychChart(legacy, "test", "normal").psychEventsJson)
        assertThrows(IllegalArgumentException::class.java) { draft.toVSliceNotes() }
    }

    @Test fun eventTimesRetainDecimalTokensInBothPsychFormats() {
        val events = """[[100.1234567890123456789,[["Custom","a","b"]]]]"""
        val draft = readPsychChart(chart("", true), "test", "normal").copy(psychEventsJson = events)
        val v1 = draft.toPsychJson()
        val legacy = com.fnfmodmaker.mod.JsonBuilders.psych073Chart(v1)
        val v1Events = com.google.gson.JsonParser.parseString(v1).asJsonObject.get("events").toString()
        val legacyEvents = com.google.gson.JsonParser.parseString(legacy).asJsonObject.getAsJsonObject("song").get("events").toString()
        assertEquals(events, v1Events)
        assertEquals(events, legacyEvents)
    }

    @Test fun unsupportedGameplayFailsExplicitly() {
        val tempo = chart("", true).replace("\"sectionBeats\":4", "\"sectionBeats\":4,\"changeBPM\":true")
        assertThrows(IllegalArgumentException::class.java) { readPsychChart(tempo, "test", "normal") }
    }
    @Test fun numericNoteTypesAreRejectedUntilTheirOriginalRepresentationCanBePreserved() {
        // Even 0 must not silently become a missing type; mods may also redefine the table.
        for (value in listOf("0", "1", "2", "3", "4", "5", "6", "-1", "1.5")) {
            assertThrows(IllegalStateException::class.java) {
                readPsychChart(chart("", true, ",[300,1,0,$value]"), "test", "normal")
            }
        }
        val text = readPsychChart(chart("", true, ",[300,1,0,\"Hey!\"]"), "test", "normal")
        assertEquals("Hey!", readPsychChart(text.toPsychJson(), "test", "normal").notes.last().kind)
    }

}
