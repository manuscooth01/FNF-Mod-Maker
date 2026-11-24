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
    @Test fun recordingSnapUsesVariableTempoAndTimeSignatureForNotesAndHolds() {
        val grid = TouchGrid(120, 2, 5000, listOf(GridTempo(0.0, 120.0), GridTempo(1000.0, 180.0, 8)))
        assertEquals(750L, recordedNoteTime(770, 0, 120, RecordingSnap.HALF_BEAT, 5000, grid))
        assertEquals(1167L, recordedNoteTime(1150, 0, 120, RecordingSnap.HALF_BEAT, 5000, grid))
        assertEquals(1167L, recordedNoteTime(1140, 10, 120, RecordingSnap.HALF_BEAT, 5000, grid))
        assertEquals(1150L, recordedNoteTime(1150, 0, 120, RecordingSnap.FREE, 5000, grid))
        val note = ChartNote(750, 0)
        assertEquals(583L, recordedHoldLength(note, 770, 1325, 0, 120, RecordingSnap.HALF_BEAT, 5000, listOf(note), grid))
        assertEquals(450L, recordedHoldLength(note, 770, 1325, 0, 120, RecordingSnap.HALF_BEAT, 5000, listOf(note, ChartNote(1200, 0)), grid))
        assertEquals(0L, recordedHoldLength(note, 770, 880, 0, 120, RecordingSnap.HALF_BEAT, 5000, listOf(note), grid))
    }

    @Test fun holdsMeasureAudioClockAndKeepShortTapsShort() {
        val note = ChartNote(1000, 0)
        assertEquals(0L, recordedHoldLength(note, 1000, 1119, 0, 120, RecordingSnap.FREE, 5000, listOf(note)))
        assertEquals(500L, recordedHoldLength(note, 1000, 1500, 0, 120, RecordingSnap.FREE, 5000, listOf(note)))
        // Both start and end are corrected and snapped to the same beat grid.
        val snapped = ChartNote(recordedNoteTime(1010, -10, 120, RecordingSnap.HALF_BEAT, 5000), 1)
        assertEquals(500L, recordedHoldLength(snapped, 1010, 1510, -10, 120, RecordingSnap.HALF_BEAT, 5000, listOf(snapped)))
        assertEquals(0L, recordedHoldLength(snapped, 1010, 1130, -10, 120, RecordingSnap.HALF_BEAT, 5000, listOf(snapped)))
    }

    @Test fun sustainsDoNotOverlapOtherNotesAndRespectSongEnd() {
        val note = ChartNote(1000, 0)
        val next = ChartNote(1500, 0)
        val chord = ChartNote(1100, 1)
        val notes = listOf(note, next, chord)
        assertEquals(500L, recordedHoldLength(note, 1000, 2500, 0, 120, RecordingSnap.FREE, 5000, notes))
        assertEquals(200L, safeHoldLength(note, 5000, 1200, notes))
        assertNull(appendRecordedNote(listOf(ChartNote(1000, 0, 500)), ChartNote(1200, 0)))
        assertNotNull(appendRecordedNote(listOf(ChartNote(1000, 0, 500)), ChartNote(1500, 0)))
        assertNotNull(appendRecordedNote(listOf(ChartNote(1000, 0, 500)), ChartNote(1200, 1)))
    }

    @Test fun heldNotesPersistAndExportAsDurationsNotExtraNotes() {
        val note = ChartNote(300, 7)
        val hold = note.copy(lengthMs = recordedHoldLength(note, 300, 1050, 0, 120, RecordingSnap.FREE, 5000, listOf(note)))
        val chart = ChartDraft(bpm = 120.0, notes = listOf(hold))
        val gson = com.google.gson.Gson()
        val restored = gson.fromJson(gson.toJson(chart), ChartDraft::class.java)
        assertEquals(750L, restored.notes.single().lengthMs)
        val vNote = restored.toVSliceNotes().getJSONObject(0)
        assertEquals(750, vNote.getInt("l")); assertEquals(7, vNote.getInt("d"))
        val psych = org.json.JSONObject(restored.toPsychJson()).getJSONArray("notes").getJSONObject(0).getJSONArray("sectionNotes")
        assertEquals(1, psych.length()); assertEquals(750, psych.getJSONArray(0).getInt(2))
    }
}
