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
    @Test fun undoRestoresAbsentChartAndIsBounded() {
        val history = GridUndoHistory(2)
        val draft = ChartDraft(notes = listOf(ChartNote(10, 0)))
        history.push(null); history.push(draft)
        assertEquals(draft, history.pop()!!.chart)
        assertNull(history.pop()!!.chart)
        assertNull(history.pop())
        history.push(null); history.push(draft); history.push(draft.copy(bpm = 100))
        assertEquals(2, history.size)
        assertEquals(100, history.pop()!!.chart!!.bpm)
        assertEquals(draft, history.pop()!!.chart)
        assertNull(history.pop())
        history.push(draft); history.clear(); assertEquals(0, history.size)
    }
}
