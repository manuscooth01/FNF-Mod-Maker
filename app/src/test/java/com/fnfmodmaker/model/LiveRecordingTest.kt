package com.fnfmodmaker.model

import org.junit.Assert.*
import org.junit.Test

class LiveRecordingTest {
    @Test fun freeTimingAndCorrectionAreBounded() {
        assertEquals(1234L, recordedNoteTime(1234, 0, 120, RecordingSnap.FREE, 10000))
        assertEquals(1184L, recordedNoteTime(1234, -50, 120, RecordingSnap.FREE, 10000))
        assertEquals(0L, recordedNoteTime(20, -50, 120, RecordingSnap.FREE, 10000))
        assertEquals(999L, recordedNoteTime(990, 50, 120, RecordingSnap.FREE, 1000))
    }

    @Test fun quantizationUsesBpmAndIsOptional() {
        assertEquals(1250L, recordedNoteTime(1220, 0, 120, RecordingSnap.HALF_BEAT, 5000))
        assertEquals(1125L, recordedNoteTime(1120, 0, 120, RecordingSnap.QUARTER_BEAT, 5000))
        assertEquals(1120L, recordedNoteTime(1120, 0, 0, RecordingSnap.QUARTER_BEAT, 5000))
        assertEquals(1000L, recordedNoteTime(990, 30, 120, RecordingSnap.HALF_BEAT, 5000))
    }

    @Test fun appendingPreservesExistingNotesAndChordsAndNeverOverwritesHolds() {
        val hold = ChartNote(1000, 0, 500)
        val old = listOf(hold)
        assertNull(appendRecordedNote(old, ChartNote(1000, 0)))
        val chord = appendRecordedNote(old, ChartNote(1000, 4))!!
        assertEquals(listOf(hold, ChartNote(1000, 4)), chord)
        assertEquals(old, removeRecordedNote(chord, ChartNote(1000, 4)))
        assertNull(removeRecordedNote(old, ChartNote(1000, 0)))
        assertEquals(listOf(hold), old)
    }
    @Test fun recordedNotesPersistAndExportOnlyInSelectedDifficulty() {
        val song = SongConfig(songName = "Test", practiceNotes = false)
        val first = ChartNote(recordedNoteTime(300, 0, 120, RecordingSnap.FREE, 5000), 0)
        val second = ChartNote(recordedNoteTime(450, 0, 120, RecordingSnap.FREE, 5000), 6)
        val draft = ChartDraft(songFolder = song.songFolder, difficulty = "hard", notes = appendRecordedNote(listOf(first), second)!!)
        val config = ModConfig(songs = listOf(song)).withChart(song.songFolder, "hard", draft)
        val gson = com.google.gson.Gson()
        val restored = gson.fromJson(gson.toJson(config), ModConfig::class.java)
        assertEquals(draft.notes, restored.getChart(song.songFolder, "hard")!!.notes)
        assertNull(restored.getChart(song.songFolder, "normal"))
        val vslice = org.json.JSONObject(com.fnfmodmaker.mod.JsonBuilders.vsliceChart(restored, song, 5000)).getJSONObject("notes")
        assertEquals(2, vslice.getJSONArray("hard").length())
        assertEquals(6, vslice.getJSONArray("hard").getJSONObject(1).getInt("d"))
        assertEquals(0, vslice.getJSONArray("normal").length())
        val hardPsych = org.json.JSONObject(com.fnfmodmaker.mod.JsonBuilders.psychChart(restored, song, 5000, "hard"))
        val hardNotes = hardPsych.getJSONArray("notes").getJSONObject(0).getJSONArray("sectionNotes")
        assertEquals(2, hardNotes.length())
        assertEquals(300, hardNotes.getJSONArray(0).getInt(0))
        assertEquals(6, hardNotes.getJSONArray(1).getInt(1))
        val normalPsych = org.json.JSONObject(com.fnfmodmaker.mod.JsonBuilders.psychChart(restored, song, 5000, "normal"))
        assertEquals(0, normalPsych.getJSONArray("notes").getJSONObject(0).getJSONArray("sectionNotes").length())
    }
}
