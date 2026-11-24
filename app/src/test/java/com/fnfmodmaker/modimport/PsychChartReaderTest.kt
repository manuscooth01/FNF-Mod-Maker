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
    @Test fun unsupportedGameplayFailsExplicitly() {
        assertThrows(IllegalArgumentException::class.java) { readPsychChart(chart("", true, ",[300,1,0,\"Hurt Note\"]"), "test", "normal") }
        val events = chart("", true).replace("\"events\":[]", "\"events\":[[100,[]]]")
        assertThrows(IllegalArgumentException::class.java) { readPsychChart(events, "test", "normal") }
        val tempo = chart("", true).replace("\"sectionBeats\":4", "\"sectionBeats\":4,\"changeBPM\":true")
        assertThrows(IllegalArgumentException::class.java) { readPsychChart(tempo, "test", "normal") }
    }
}
