package com.fnfmodmaker.model

import org.junit.Assert.*
import org.junit.Test

class TouchChartGridTest {
    @Test fun pagesAndLaneMapping() {
        val grid = TouchGrid(120, 4, 4250)
        assertEquals(16, grid.rows)
        assertEquals(3, grid.pageCount)
        assertEquals(0L, grid.timeAt(0, 0))
        assertEquals(1875L, grid.timeAt(0, 15))
        assertEquals(2000L, grid.pageStart(1))
        assertEquals(2, grid.pageForTime(99999))
        assertEquals(4125L, grid.timeAt(2, 1))
        assertNull(grid.timeAt(2, 2))
        assertNull(grid.timeAt(-1, 0))
        assertEquals((4..7).toList() + (0..3).toList(), (0..7).map { grid.laneForColumn(it) })
        for (lane in 0..7) assertEquals(lane, grid.laneForColumn(grid.columnForLane(lane)!!))
        assertNull(grid.laneForColumn(8)); assertNull(grid.columnForLane(-1))
    }
    @Test fun gridFollowsTempoAndDenominatorChangesWithoutMovingNotes() {
        val grid = TouchGrid(120, 4, 6000, listOf(GridTempo(0.0, 120.0, 4), GridTempo(2000.0, 180.0, 8), GridTempo(3000.0, 90.0, 4)))
        assertEquals(2000L, grid.pageStart(1))
        assertEquals(2167L, grid.timeAt(1, 4))
        assertEquals(4.0, grid.rowPosition(2167, 1), .01)
        assertEquals(2, grid.pageForTime(2667))
        assertEquals(3000L, grid.timeAtBeat(10.0).toLong())
        assertEquals(3667L, grid.shiftTime(3000, 1.0))
        assertEquals(4333L, grid.pageEnd(2))
    }
    @Test fun measuresIntegrateNumeratorAndDenominatorAcrossMidMeasureChange() {
        val grid = TouchGrid(120, 4, 5000, listOf(GridTempo(0.0, 120.0, 4, 3), GridTempo(1000.0, 180.0, 8, 3)))
        assertEquals(2.0 / 3.0, grid.measureAt(1000.0), 0.000001)
        assertEquals(1.0, grid.measureAt(1166.6666666667), 0.000001)
        assertEquals(1666.6666666667, grid.timeAtMeasure(2.0), 0.000001)
        assertEquals(1417L, grid.shiftMeasure(750, 1.0))
        assertEquals(0L, grid.shiftMeasure(100, -1.0))
        assertEquals(4, grid.rows / grid.divisionsPerBeat) // pages, not bars
    }
    @Test fun replaceIsAtomicAndPreservesOtherNotes() {
        val hold = ChartNote(500, 0, 500)
        val chord = ChartNote(500, 4)
        val notes = listOf(hold, chord)
        assertNull(replaceGridNote(notes, chord, ChartNote(750, 0), 2000))
        assertNull(replaceGridNote(notes, hold, ChartNote(1500, 0, 501), 2000))
        assertNull(replaceGridNote(notes, ChartNote(4, 2), null, 2000))
        assertEquals(notes, listOf(hold, chord))
        assertEquals(listOf(chord, ChartNote(1000, 0, 1000)), replaceGridNote(notes, hold, ChartNote(1000, 0, 1000), 2000))
        assertEquals(listOf(hold), replaceGridNote(notes, chord, null, 2000))
        assertNull(replaceGridNote(notes, null, ChartNote(2000, 1), 2000))
    }
    @Test fun undoRedoRestoresAbsentChartAndIsBounded() {
        val history = GridUndoHistory(2)
        val first = ChartDraft(notes = listOf(ChartNote(10, 0)))
        val second = first.copy(notes = first.notes + ChartNote(100, 4))
        history.push(null); history.push(first)
        assertEquals(first, history.undo(second)!!.chart)
        assertNull(history.undo(first)!!.chart)
        assertNull(history.undo(null))
        assertEquals(first, history.redo(null)!!.chart)
        assertEquals(second, history.redo(first)!!.chart)
        assertNull(history.redo(second))
        assertEquals(first, history.undo(second)!!.chart)
        history.push(first) // New edits discard redo.
        assertEquals(0, history.redoSize)
        history.push(second)
        assertEquals(2, history.size)
        assertEquals(second, history.undo(null)!!.chart)
        assertEquals(first, history.undo(second)!!.chart)
        assertNull(history.undo(first))
        history.clear(); assertEquals(0, history.size); assertEquals(0, history.redoSize)
    }
}
