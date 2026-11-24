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
            assertEquals("bf", draft.player1); assertEquals("dad", draft.player2); assertEquals(120, draft.bpm)
        }
    }
    @Test fun legacyFlipsLanesWhenOpponentSection() {
        val draft = readPsychChart(chart("", false), "test", "normal")
        assertEquals(listOf(ChartNote(100, 4), ChartNote(500, 2, 250)), draft.notes)
    }
    @Test fun editedLaneSurvivesOutputEvenInOpponentSection() {
        val draft = readPsychChart(chart("\"format\":\"psych_v1\",", false), "test", "normal")
            .copy(notes = listOf(ChartNote(2100, 1, 200)))
        val encoded = JSONObject(draft.toPsychJson())
        val note = encoded.getJSONArray("notes").getJSONObject(1).getJSONArray("sectionNotes").getJSONArray(0)
        assertEquals(1, note.getInt(1)); assertEquals(200, note.getInt(2))
        assertEquals(draft.notes, readPsychChart(encoded.toString(), "test", "normal").notes)
    }
    @Test fun psych073ExportUsesSectionRelativeLanesAndWrappedSong() {
        val song = com.fnfmodmaker.model.SongConfig(songName = "test", bpm = "150", difficulties = setOf("normal"), practiceNotes = false)
        val draft = com.fnfmodmaker.model.ChartDraft(songFolder = "test", difficulty = "normal", bpm = 150,
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

    @Test fun unsupportedGameplayFailsExplicitly() {
        assertThrows(IllegalArgumentException::class.java) { readPsychChart(chart("", true, ",[300,1,0,\"Hurt Note\"]"), "test", "normal") }
        val events = chart("", true).replace("\"events\":[]", "\"events\":[[100,[]]]")
        assertThrows(IllegalArgumentException::class.java) { readPsychChart(events, "test", "normal") }
        val tempo = chart("", true).replace("\"sectionBeats\":4", "\"sectionBeats\":4,\"changeBPM\":true")
        assertThrows(IllegalArgumentException::class.java) { readPsychChart(tempo, "test", "normal") }
    }
}
