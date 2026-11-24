package com.fnfmodmaker.model

import kotlin.math.ceil
import kotlin.math.roundToLong

/** Timestamped V-Slice tempo; beat duration follows the engine time signature denominator. */
data class GridTempo(val timeMs: Double, val bpm: Double, val denominator: Int = 4, val numerator: Int = 4)
/** A four-beat page. Visual columns are opponent first; the model stores player first. */
data class TouchGrid(val bpm: Double, val divisionsPerBeat: Int, val durationMs: Long, val tempoChanges: List<GridTempo> = emptyList(), val roundVSliceBeatChanges: Boolean = false) {
    constructor(bpm: Int, divisionsPerBeat: Int, durationMs: Long, tempoChanges: List<GridTempo> = emptyList(), roundVSliceBeatChanges: Boolean = false) : this(bpm.toDouble(), divisionsPerBeat, durationMs, tempoChanges, roundVSliceBeatChanges)
    init {
        require(bpm in 1.0..400.0 && divisionsPerBeat in setOf(1, 2, 4, 8) && durationMs > 0)
        require(tempoChanges.isEmpty() || tempoChanges.first().timeMs == 0.0 && tempoChanges.zipWithNext().all { it.first.timeMs < it.second.timeMs })
        require(tempoChanges.all { it.timeMs.isFinite() && it.timeMs >= 0 && it.bpm.isFinite() && it.bpm > 0 && it.numerator in 1..64 && it.denominator in setOf(1, 2, 4, 8, 16, 32, 64) })
    }
    private val points = (tempoChanges.ifEmpty { listOf(GridTempo(0.0, bpm.toDouble())) }).let { changes ->
        var beats = 0.0
        changes.mapIndexed { index, change ->
            if (index > 0) {
                val prev = changes[index - 1]
                beats += (change.timeMs - prev.timeMs) * prev.bpm / 60_000.0 * prev.denominator / 4.0
                // V-Slice Conductor.mapTimeChanges rounds each accumulated beatTime to four decimals.
                if (roundVSliceBeatChanges) beats = kotlin.math.floor(beats * 10_000.0 + 0.5) / 10_000.0
            }
            change to beats
        }
    }
    // Measure time integrates fractions through every change, including changes mid-measure,
    // matching Conductor.getTimeInMeasures rather than treating each four-beat page as a bar.
    private val measures = MutableList(points.size) { 0.0 }.apply {
        for (i in 1 until points.size) {
            val previous = points[i - 1].first
            this[i] = this[i - 1] + (points[i].first.timeMs - previous.timeMs) * previous.bpm /
                60_000.0 * previous.denominator / 4.0 / previous.numerator
        }
    }
    fun measureAt(ms: Double): Double {
        val index = pointIndex(ms.coerceAtLeast(0.0), false)
        val p = points[index].first
        return measures[index] + (ms.coerceAtLeast(0.0) - p.timeMs) * p.bpm / 60_000.0 * p.denominator / 4.0 / p.numerator
    }
    fun timeAtMeasure(measure: Double): Double {
        require(measure.isFinite() && measure >= 0.0)
        var low = 0; var high = measures.lastIndex
        while (low < high) {
            val mid = (low + high + 1) ushr 1
            if (measures[mid] <= measure) low = mid else high = mid - 1
        }
        val p = points[low].first
        return p.timeMs + (measure - measures[low]) * 60_000.0 / p.bpm * 4.0 / p.denominator * p.numerator
    }
    fun shiftMeasure(ms: Long, amount: Double): Long = timeAtMeasure((measureAt(ms.toDouble()) + amount).coerceAtLeast(0.0)).roundToLong()
    private fun pointIndex(value: Double, byBeat: Boolean): Int {
        var low = 0; var high = points.lastIndex
        while (low < high) {
            val mid = (low + high + 1) ushr 1
            val key = if (byBeat) points[mid].second else points[mid].first.timeMs
            if (key <= value) low = mid else high = mid - 1
        }
        return low
    }
    fun beatAt(ms: Double): Double {
        val p = points[pointIndex(ms, false)]
        return p.second + (ms - p.first.timeMs) * p.first.bpm / 60_000.0 * p.first.denominator / 4.0
    }
    fun timeAtBeat(beat: Double): Double {
        val p = points[pointIndex(beat, true)]
        return p.first.timeMs + (beat - p.second) * 60_000.0 / p.first.bpm * 4.0 / p.first.denominator
    }
    fun shiftTime(ms: Long, beats: Double): Long = timeAtBeat(beatAt(ms.toDouble()) + beats).roundToLong()
    val rows: Int get() = 4 * divisionsPerBeat
    val stepMs: Double get() = 60_000.0 / bpm / divisionsPerBeat
    fun stepMsAt(ms: Long): Double = (timeAtBeat(beatAt(ms.toDouble()) + 1.0 / divisionsPerBeat) - ms).coerceAtLeast(0.0)
    val pageCount: Int get() = ceil(beatAt(durationMs.toDouble()) / 4.0).toInt().coerceAtLeast(1)
    fun timeAt(page: Int, row: Int): Long? {
        if (page !in 0 until pageCount || row !in 0 until rows) return null
        val time = timeAtBeat(page * 4.0 + row.toDouble() / divisionsPerBeat).roundToLong()
        return time.takeIf { it in 0 until durationMs }
    }
    fun pageStart(page: Int): Long = timeAt(page.coerceIn(0, pageCount - 1), 0) ?: 0
    fun pageEnd(page: Int): Long = timeAtBeat((page + 1) * 4.0).roundToLong().coerceIn(0, durationMs)
    fun pageForTime(ms: Long): Int = (beatAt(ms.coerceAtLeast(0).toDouble()) / 4.0).toInt().coerceIn(0, pageCount - 1)
    fun rowPosition(ms: Long, page: Int): Double = beatAt(ms.toDouble()) * divisionsPerBeat - page.toLong() * rows
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
