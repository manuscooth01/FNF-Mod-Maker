package com.fnfmodmaker.model

import com.google.gson.Gson
import org.junit.Assert.*
import org.junit.Test

class IndependentChartsTest {
    @Test fun separateVariantsAndHistoriesSurviveBasicSerialization() {
        val psych = ChartDraft(songFolder = "test", difficulty = "normal", bpm = 120.5,
            notes = listOf(ChartNote(100, 7, 250, psychTypeJson = "99")),
            psychEventsJson = """[[10,[["Hey!","1",""]]]]""",
            psychLayout = listOf(PsychSectionData(beats = 3.0, bpm = 120.5, sourceJson = """{"future":{"path":"not-a-file"}}""")))
        val vslice = ChartDraft(songFolder = "test", difficulty = "normal", bpm = 170.25,
            notes = listOf(ChartNote(450, 2, 120, kind = "mine")))
        val p = IndependentVariant(metadata = SongConfig(songName = "Test", bpm = "120.5"),
            difficulties = setOf("normal", "hard"), charts = mapOf("normal" to psych),
            uneditableDifficulties = mapOf("hard" to "data/test/test-hard.json"), unknownMetadataJson = """{"path":"metadata-only"}""")
        val v = IndependentVariant(metadata = SongConfig(songName = "Test", bpm = "170.25",
            vSliceEventsJson = """[{"t":12.25,"e":"Camera","custom":{"path":"x"}}]""",
            vSliceTempoJson = """[{"t":0,"bpm":170.25},{"t":500,"bpm":155.5}]"""),
            difficulties = setOf("normal", "easy"), charts = mapOf("normal" to vslice))
        val legacy = """{"charts":{"test":{"normal":{"notes":[1,2,3]}}},"unknown":{"path":"keep"}}"""
        val original = IndependentChartCollection(songs = mapOf("stable-id" to IndependentSong("stable-id", p, v)), legacyDocumentJson = legacy)
        val key = IndependentChartKey("stable-id", ChartEngine.PSYCH, "normal")
        val editedPsych = psych.copy(notes = psych.notes + ChartNote(900, 1, 100))
        val changed = original.edit(key, editedPsych)
        assertEquals(v, changed.songs.getValue("stable-id").vSlice)
        assertEquals(psych, original.chart(key))
        assertEquals(editedPsych, changed.chart(key))
        val vKey = IndependentChartKey("stable-id", ChartEngine.VSLICE, "normal")
        val bothEdited = changed.edit(vKey, vslice.copy(notes = listOf(ChartNote(600, 4, 200))))
        assertEquals(changed.songs.getValue("stable-id").psych, bothEdited.songs.getValue("stable-id").psych)
        val restored = Gson().fromJson(Gson().toJson(bothEdited), IndependentChartCollection::class.java)
        assertEquals(bothEdited, restored)
        assertEquals(legacy, restored.legacyDocumentJson)
        val undo = restoreChartHistory(restored.songs.getValue("stable-id").psych!!.histories.getValue("normal"), restored.chart(key), false)!!
        assertEquals(psych, undo.chart)
        assertEquals(vslice, restoreChartHistory(restored.songs.getValue("stable-id").vSlice!!.histories.getValue("normal"), restored.chart(vKey), false)!!.chart)
        assertThrows(IllegalArgumentException::class.java) { original.edit(IndependentChartKey("stable-id", ChartEngine.PSYCH, "hard"), psych.copy(difficulty = "hard")) }
    }
}
