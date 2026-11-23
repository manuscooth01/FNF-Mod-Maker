package com.fnfmodmaker.model

import org.junit.Assert.*
import org.junit.Test

class ChartGroupEditingTest {
    private val a = ChartNote(1000, 4, 250)
    private val b = ChartNote(1250, 1)
    @Test fun groupMoveAndCopyAreAtomic() {
        val source = listOf(a, b, ChartNote(2500, 7))
        val moved = translateGridNotes(listOf(a, b), 500, 1)!!
        assertEquals(listOf(ChartNote(1500, 5, 250), ChartNote(1750, 2)), moved)
        val result = replaceGridNotes(source, listOf(a, b), moved, 5000)!!
        assertEquals(3, result.size); assertTrue(result.containsAll(moved)); assertTrue(result.contains(source.last()))
        val copied = translateGridNotes(moved, 500, 0)!!
        assertEquals(5, replaceGridNotes(result, emptyList(), copied, 5000)!!.size)
        assertEquals(source, listOf(a, b, ChartNote(2500, 7)))
    }
    @Test fun invalidBatchNeverPartiallyChangesChart() {
        val chart = listOf(a, b, ChartNote(1500, 4))
        assertNull(replaceGridNotes(chart, listOf(a), listOf(a.copy(timeMs = 1400, lengthMs = 200)), 5000))
        assertNull(replaceGridNotes(chart, listOf(a, b), listOf(a.copy(timeMs = 4800, lengthMs = 250), b), 5000))
        assertNull(replaceGridNotes(chart, listOf(ChartNote(4, 0)), emptyList(), 5000))
        assertNull(replaceGridNotes(chart, emptyList(), listOf(a.copy(timeMs = 1200)), 5000))
        assertNull(replaceGridNotes(listOf(a, a), listOf(a), emptyList(), 5000))
        assertNull(translateGridNotes(listOf(ChartNote(2, 3)), 0, 1))
        assertNull(translateGridNotes(listOf(a), Long.MAX_VALUE, 0))
        assertEquals(listOf(b, chart.last()), replaceGridNotes(chart, listOf(a), emptyList(), 5000))
    }
    @Test fun previouslyOverlappingDataIsPreservedButNewCollisionsAreRejected() {
        val old = listOf(ChartNote(100, 0, 600), ChartNote(300, 0))
        val unrelated = ChartNote(300, 1)
        assertEquals(3, replaceGridNotes(old, emptyList(), listOf(unrelated), 2000)!!.size)
        assertNull(replaceGridNotes(old, emptyList(), listOf(ChartNote(500, 0)), 2000))
        assertNotNull(replaceGridNotes(old, emptyList(), listOf(ChartNote(700, 0)), 2000))
    }
    @Test fun groupOperationUndoRedoRestoresWholeSnapshot() {
        val original = ChartDraft(notes = listOf(a, b))
        val moved = original.copy(notes = translateGridNotes(original.notes, 500, 0)!!)
        val history = GridUndoHistory(); history.push(original)
        assertEquals(original, history.undo(moved)!!.chart)
        assertEquals(moved, history.redo(original)!!.chart)
    }
}
