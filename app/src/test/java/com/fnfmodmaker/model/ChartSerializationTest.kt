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
}
