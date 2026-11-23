package com.fnfmodmaker.model

import kotlin.math.roundToLong

enum class RecordingSnap(val label: String, val divisionsPerBeat: Int) {
    FREE("Libre", 0), HALF_BEAT("½ pulso", 2), QUARTER_BEAT("¼ pulso", 4), EIGHTH_BEAT("⅛ pulso", 8)
}

/** Timestamp from the audio player's clock, corrected and optionally quantized. */
fun recordedNoteTime(positionMs: Long, correctionMs: Int, bpm: Int, snap: RecordingSnap, durationMs: Long): Long {
    val maximum = if (durationMs > 0) durationMs - 1 else Long.MAX_VALUE
    val time = (positionMs.coerceAtLeast(0L) + correctionMs.toLong()).coerceIn(0L, maximum)
    if (snap == RecordingSnap.FREE || bpm !in 1..400) return time
    val interval = 60_000.0 / bpm / snap.divisionsPerBeat
    return ((time / interval).roundToLong() * interval).roundToLong().coerceIn(0L, maximum)
}

/** Never replace an existing note, including a hold, on a quantization collision. */
fun appendRecordedNote(notes: List<ChartNote>, note: ChartNote): List<ChartNote>? {
    if (note.lane !in 0..7 || note.timeMs < 0 || notes.any { it.timeMs == note.timeMs && it.lane == note.lane }) return null
    return (notes + note).sortedWith(compareBy<ChartNote> { it.timeMs }.thenBy { it.lane })
}

fun removeRecordedNote(notes: List<ChartNote>, note: ChartNote): List<ChartNote>? {
    val index = notes.indexOf(note)
    if (index < 0) return null
    return notes.toMutableList().apply { removeAt(index) }
}
