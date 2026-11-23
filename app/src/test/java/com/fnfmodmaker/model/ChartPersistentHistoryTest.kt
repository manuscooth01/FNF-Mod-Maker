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
        assertNull(restoreChartHistory(history, second.copy(bpm = 180), false))
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
}
