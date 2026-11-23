package com.fnfmodmaker.model

import org.junit.Assert.*
import org.junit.Test

class ModPreviewTest {
    @Test fun practiceMatchesExporterTimingAndLane() {
        val notes = previewPracticeNotes(120, 5000)
        assertEquals(10, notes.size)
        assertEquals(ChartNote(0, 0), notes[0])
        assertEquals(ChartNote(500, 5), notes[1])
        assertEquals(ChartNote(1000, 2), notes[2])
        assertEquals(8, previewPracticeNotes(120, 500).size)
        assertTrue(previewPracticeNotes(0, 5000).isEmpty())
    }
    @Test fun practiceMatchesActualVSliceExport() {
        for (bpm in listOf(95, 120, 175)) {
            val song = SongConfig(songName = "Song", bpm = bpm.toString(), practiceNotes = true)
            val exported = org.json.JSONObject(com.fnfmodmaker.mod.JsonBuilders.vsliceChart(song, 8123))
                .getJSONObject("notes").getJSONArray("normal")
            val preview = previewPracticeNotes(bpm, 8123)
            assertEquals(exported.length(), preview.size)
            preview.forEachIndexed { i, note ->
                assertEquals(exported.getJSONObject(i).getLong("t"), note.timeMs)
                assertEquals(exported.getJSONObject(i).getInt("d"), note.lane)
            }
        }
    }
    @Test fun hitsChooseNearestPlayerNoteOnce() {
        val notes = listOf(ChartNote(1000, 0), ChartNote(1050, 0), ChartNote(1050, 4))
        assertEquals(1, previewHitIndex(notes, emptySet(), 0, 1040))
        assertEquals(0, previewHitIndex(notes, setOf(1), 0, 1040))
        assertNull(previewHitIndex(notes, setOf(0, 1), 0, 1040))
        assertNull(previewHitIndex(notes, emptySet(), 0, 2000))
        assertNull(previewHitIndex(notes, emptySet(), 4, 1050))
    }
}
