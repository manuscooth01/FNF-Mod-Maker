package com.fnfmodmaker.model

/** One transactional edit: reject stale selections, overlaps, invalid lanes or song-end overflow. */
fun replaceGridNotes(notes: List<ChartNote>, originals: List<ChartNote>, replacements: List<ChartNote>, durationMs: Long): List<ChartNote>? {
    if (durationMs <= 0 || originals.size != originals.toSet().size || notes.size - originals.size + replacements.size > 10000) return null
    val counts = notes.groupingBy { it }.eachCount()
    // Equal notes are ambiguous and must never be silently removed together.
    if (originals.any { counts[it] != 1 }) return null
    if (replacements.any { it.lane !in 0..7 || it.timeMs !in 0 until durationMs || it.lengthMs < 0 || it.lengthMs > durationMs - it.timeMs }) return null
    data class Item(val note: ChartNote, val changed: Boolean)
    val removed = originals.toSet()
    val items = notes.filterNot { it in removed }.map { Item(it, false) } + replacements.map { Item(it, true) }
    // Existing malformed/overlapping notes are kept untouched. Only conflicts involving an edit block it.
    for (lane in 0..7) {
        val laneItems = items.filter { it.note.lane == lane }.sortedBy { it.note.timeMs }
        var lastAny = Long.MIN_VALUE; var lastChanged = Long.MIN_VALUE
        var endAny = Long.MIN_VALUE; var endChanged = Long.MIN_VALUE
        for ((note, changed) in laneItems) {
            val t = note.timeMs
            if (changed && (lastAny == t || endAny > t) || !changed && (lastChanged == t || endChanged > t)) return null
            val end = if (note.lengthMs > 0 && t > Long.MAX_VALUE - note.lengthMs) Long.MAX_VALUE else t + note.lengthMs.coerceAtLeast(0)
            lastAny = t; endAny = maxOf(endAny, end)
            if (changed) { lastChanged = t; endChanged = maxOf(endChanged, end) }
        }
    }
    return items.map { it.note }.sortedWith(compareBy<ChartNote> { it.timeMs }.thenBy { it.lane })
}

/** Translate visual columns and absolute milliseconds without wrapping sides or overflowing. */
fun translateGridNotes(notes: List<ChartNote>, timeDelta: Long, columnDelta: Int): List<ChartNote>? {
    if (notes.isEmpty()) return null
    return notes.map { note ->
        val column = when (note.lane) { in 0..3 -> note.lane + 4; in 4..7 -> note.lane - 4; else -> return null }
        val target = column.toLong() + columnDelta.toLong()
        if (target !in 0L..7L || timeDelta > 0 && note.timeMs > Long.MAX_VALUE - timeDelta ||
            timeDelta < 0 && note.timeMs < Long.MIN_VALUE - timeDelta) return null
        val lane = if (target < 4) target.toInt() + 4 else target.toInt() - 4
        note.copy(timeMs = note.timeMs + timeDelta, lane = lane)
    }
}
