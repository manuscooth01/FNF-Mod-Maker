package com.fnfmodmaker.model

import com.google.gson.Gson
import org.junit.Assert.*
import org.junit.Test

class ChartPersistentHistoryTest {
    @Test fun deltasSurviveSerializationUndoRedoAndKeepHolds() {
        val a = ChartNote(100, 0, 250); val b = ChartNote(400, 5)
        val first = ChartDraft(songFolder = "song", notes = listOf(a, b))
        val second = first.copy(notes = translateGridNotes(first.notes, 500, 0)!!)
        var history = recordChartHistory(ChartPersistentHistory(), null, first)
        history = recordChartHistory(history, first, second)
        history = Gson().fromJson(Gson().toJson(history), ChartPersistentHistory::class.java)
        val undone = restoreChartHistory(history, second, false)!!
        assertEquals(first, undone.chart)
        val removed = restoreChartHistory(undone.history, first, false)!!
        assertNull(removed.chart)
        val redone = restoreChartHistory(removed.history, null, true)!!
        assertEquals(first, redone.chart)
        assertEquals(second, restoreChartHistory(redone.history, first, true)!!.chart)
        assertNull(restoreChartHistory(history, second.copy(bpm = 180.0), false))
    }
    @Test fun oldUntypedProjectAndHistoryStillRestore() {
        val gson = Gson()
        val old = """{"songFolder":"song","difficulty":"normal","notes":[{"timeMs":100,"lane":0,"lengthMs":0,"isSustain":false}]}"""
        val chart = gson.fromJson(old, ChartDraft::class.java)
        assertEquals("", chart.notes.single().kind)
        val legacyText = "ChartDraft(songFolder=song, difficulty=normal, notes=[ChartNote(timeMs=100, lane=0, lengthMs=0, isSustain=false)], events=[], sections=[], scrollSpeed=2.0, bpm=150, offsetMs=0, needsVoices=false, player1=bf, player2=dad, gfVersion=gf, stage=stage)"
        val legacyHash = java.security.MessageDigest.getInstance("SHA-256").digest(legacyText.toByteArray()).joinToString("") { "%02x".format(it) }
        val absentHash = java.security.MessageDigest.getInstance("SHA-256").digest("<absent>".toByteArray()).joinToString("") { "%02x".format(it) }
        val entry = ChartHistoryEntry(added = chart.notes, beforeHash = absentHash, afterHash = legacyHash, afterTemplate = chart.copy(notes = emptyList()))
        val result = restoreChartHistory(ChartPersistentHistory(undo = listOf(entry)), chart, false)!!
        assertNull(result.chart)
        assertEquals(chart, restoreChartHistory(result.history, null, true)!!.chart)
        assertNull(restoreChartHistory(ChartPersistentHistory(undo = listOf(entry)), chart.copy(notes = chart.notes.map { it.copy(kind = "mine") }), false))
        val config = gson.fromJson("{}", ModConfig::class.java)
        assertEquals(PsychChartFormat.PSYCH_V1, config.psychChartFormat)
        assertNull(config.firstSong.vSliceEventsJson)
        assertTrue(config.firstSong.vSliceEventsUndo.isEmpty())
        assertNull(config.firstSong.vSliceTempoJson)
        assertTrue(config.firstSong.vSliceTempoUndo.isEmpty())
        assertTrue(config.firstSong.vSliceTempoRedo.isEmpty())
    }

    @Test fun historiesAreSeparateAndBounded() {
        var history = ChartPersistentHistory()
        var chart = ChartDraft()
        repeat(60) { i ->
            val next = chart.copy(notes = chart.notes + ChartNote(i.toLong(), 0))
            history = recordChartHistory(history, chart, next); chart = next
        }
        assertEquals(50, history.undo.size)
        val other = recordChartHistory(ChartPersistentHistory(), null, ChartDraft(notes = listOf(ChartNote(7, 2))))
        assertNull(restoreChartHistory(other, chart, false))
        val newEdit = chart.copy(notes = chart.notes + ChartNote(999, 1))
        val undone = restoreChartHistory(history, chart, false)!!
        assertEquals(0, recordChartHistory(undone.history, undone.chart, newEdit).redo.size)
    }
    @Test fun projectSerializationKeepsIndependentDifficultyChains() {
        val normal = ChartDraft(songFolder = "song", difficulty = "normal", notes = listOf(ChartNote(100, 1, 250)))
        val hard = ChartDraft(songFolder = "song", difficulty = "hard", notes = listOf(ChartNote(200, 7, 300)))
        val config = ModConfig().withChart("song", "normal", normal).withChart("song", "hard", hard).copy(
            chartHistories = mapOf("song" to mapOf(
                "normal" to recordChartHistory(ChartPersistentHistory(), null, normal),
                "hard" to recordChartHistory(ChartPersistentHistory(), null, hard))))
        val gson = Gson(); val restored = gson.fromJson(gson.toJson(config), ModConfig::class.java)
        val normalHistory = restored.chartHistories.getValue("song").getValue("normal")
        val hardHistory = restored.chartHistories.getValue("song").getValue("hard")
        assertNull(restoreChartHistory(normalHistory, restored.getChart("song", "normal"), false)!!.chart)
        assertNull(restoreChartHistory(hardHistory, restored.getChart("song", "hard"), false)!!.chart)
        assertNull(restoreChartHistory(normalHistory, hard, false))
        assertEquals(300L, restored.getChart("song", "hard")!!.notes.single().lengthMs)
    }
    @Test fun historyWrittenBeforeDoubleBpmStillRestoresWithPsychLayout() {
        val before = ChartDraft(bpm = 120.0, psychLayout = listOf(PsychSectionData(bpm = 120.0)))
        val after = before.copy(notes = listOf(ChartNote(100, 2)))
        val fresh = recordChartHistory(ChartPersistentHistory(), before, after)
        fun oldHash(c: ChartDraft): String {
            val text = c.toString().replace(", bpm=120.0, offsetMs=", ", bpm=120, offsetMs=")
            return java.security.MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }
        }
        val old = fresh.copy(undo = listOf(fresh.undo.single().copy(beforeHash = oldHash(before), afterHash = oldHash(after))))
        val undo = restoreChartHistory(old, after, false)!!
        assertEquals(before, undo.chart)
        assertEquals(after, restoreChartHistory(undo.history, before, true)!!.chart)
    }

    @Test fun oldTypedNoteHistoryStillWorksAfterOpaquePsychTypes() {
        val before = ChartDraft(bpm = 120.0, psychLayout = listOf(PsychSectionData(bpm = 120.0)))
        val after = before.copy(notes = listOf(ChartNote(100, 2, kind = "Custom")))
        val fresh = recordChartHistory(ChartPersistentHistory(), before, after)
        fun oldHash(c: ChartDraft): String {
            val text = c.toString().replace(", psychTypeJson=)", ")")
            return java.security.MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }
        }
        val entry = fresh.undo.single().copy(beforeHash = oldHash(before), afterHash = oldHash(after))
        val undo = restoreChartHistory(ChartPersistentHistory(undo = listOf(entry)), after, false)!!
        assertEquals(before, undo.chart)
        assertEquals(after, restoreChartHistory(undo.history, before, true)!!.chart)
    }

}
