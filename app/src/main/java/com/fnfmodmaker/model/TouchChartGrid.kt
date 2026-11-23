package com.fnfmodmaker.model

import kotlin.math.ceil
import kotlin.math.roundToLong

/** A four-beat page. Visual columns are opponent first; the model stores player first. */
data class TouchGrid(val bpm: Int, val divisionsPerBeat: Int, val durationMs: Long) {
    init { require(bpm in 1..400 && divisionsPerBeat in setOf(1, 2, 4, 8) && durationMs > 0) }
    val rows: Int get() = 4 * divisionsPerBeat
    val stepMs: Double get() = 60_000.0 / bpm / divisionsPerBeat
    val pageCount: Int get() = ceil(durationMs / (stepMs * rows)).toInt().coerceAtLeast(1)
    fun timeAt(page: Int, row: Int): Long? {
        if (page !in 0 until pageCount || row !in 0 until rows) return null
        val time = ((page.toLong() * rows + row) * stepMs).roundToLong()
        return time.takeIf { it in 0 until durationMs }
    }
    fun pageStart(page: Int): Long = timeAt(page.coerceIn(0, pageCount - 1), 0) ?: 0
    fun pageForTime(ms: Long): Int = (ms.coerceAtLeast(0) / (stepMs * rows)).toInt().coerceIn(0, pageCount - 1)
    fun laneForColumn(column: Int): Int? = when (column) { in 0..3 -> column + 4; in 4..7 -> column - 4; else -> null }
    fun columnForLane(lane: Int): Int? = when (lane) { in 0..3 -> lane + 4; in 4..7 -> lane - 4; else -> null }
}

/** Atomic replacement: preserve all other notes and reject collision/invalid duration. */
fun replaceGridNote(notes: List<ChartNote>, original: ChartNote?, replacement: ChartNote?, durationMs: Long): List<ChartNote>? {
    if (durationMs <= 0) return null
    val remaining = if (original == null) notes else removeRecordedNote(notes, original) ?: return null
    if (replacement == null) return if (original != null) remaining else null
    if (replacement.timeMs < 0 || replacement.timeMs >= durationMs || replacement.lengthMs < 0 ||
        replacement.lengthMs > durationMs - replacement.timeMs) return null
    return appendRecordedNote(remaining, replacement)
}

/** Bounded session history preserves a missing custom chart as a distinct state. */
class GridUndoHistory(private val limit: Int = 50) {
    private val previous = mutableListOf<ChartDraft?>()
    private val next = mutableListOf<ChartDraft?>()
    init { require(limit > 0) }
    val size: Int get() = previous.size
    val redoSize: Int get() = next.size
    private fun addBounded(stack: MutableList<ChartDraft?>, chart: ChartDraft?) {
        if (stack.size == limit) stack.removeAt(0)
        stack.add(chart)
    }
    fun push(before: ChartDraft?) { addBounded(previous, before); next.clear() }
    fun undo(current: ChartDraft?): GridUndoEntry? {
        if (previous.isEmpty()) return null
        addBounded(next, current)
        return GridUndoEntry(previous.removeAt(previous.lastIndex))
    }
    fun redo(current: ChartDraft?): GridUndoEntry? {
        if (next.isEmpty()) return null
        addBounded(previous, current)
        return GridUndoEntry(next.removeAt(next.lastIndex))
    }
    fun clear() { previous.clear(); next.clear() }
}
data class GridUndoEntry(val chart: ChartDraft?)
