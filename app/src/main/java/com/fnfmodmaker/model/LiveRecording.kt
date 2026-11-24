package com.fnfmodmaker.model

import kotlin.math.roundToLong

enum class RecordingSnap(val label: String, val divisionsPerBeat: Int) {
    FREE("Libre", 0), HALF_BEAT("½ pulso", 2), QUARTER_BEAT("¼ pulso", 4), EIGHTH_BEAT("⅛ pulso", 8)
}

/** Timestamp from the audio player's clock, corrected and optionally quantized. */
fun recordedNoteTime(positionMs: Long, correctionMs: Int, bpm: Int, snap: RecordingSnap, durationMs: Long, tempoGrid: TouchGrid? = null): Long {
    val maximum = if (durationMs > 0) durationMs - 1 else Long.MAX_VALUE
    val time = (positionMs.coerceAtLeast(0L) + correctionMs.toLong()).coerceIn(0L, maximum)
    if (snap == RecordingSnap.FREE || bpm !in 1..400) return time
    if (tempoGrid != null) {
        val beat = tempoGrid.beatAt(time.toDouble())
        val snappedBeat = (beat * snap.divisionsPerBeat).roundToLong().toDouble() / snap.divisionsPerBeat
        return tempoGrid.timeAtBeat(snappedBeat).roundToLong().coerceIn(0L, maximum)
    }
    val interval = 60_000.0 / bpm / snap.divisionsPerBeat
    return ((time / interval).roundToLong() * interval).roundToLong().coerceIn(0L, maximum)
}

/** Avoid same-lane notes inside existing sustains, but permit simultaneous notes in different lanes. */
fun appendRecordedNote(notes: List<ChartNote>, note: ChartNote): List<ChartNote>? {
    if (note.lane !in 0..7 || note.timeMs < 0 || note.lengthMs < 0 || notes.any {
        it.lane == note.lane && (it.timeMs == note.timeMs ||
            (it.lengthMs > 0 && note.timeMs > it.timeMs && note.timeMs < it.endTimeMs) ||
            (note.lengthMs > 0 && it.timeMs > note.timeMs && it.timeMs < note.endTimeMs))
    }) return null
    return (notes + note).sortedWith(compareBy<ChartNote> { it.timeMs }.thenBy { it.lane })
}

fun removeRecordedNote(notes: List<ChartNote>, note: ChartNote): List<ChartNote>? {
    val index = notes.indexOf(note)
    if (index < 0) return null
    return notes.toMutableList().apply { removeAt(index) }
}

/** Audio-clock press/release; brief touches remain taps even when quantized. */
fun recordedHoldLength(note: ChartNote, pressMs: Long, releaseMs: Long, correctionMs: Int,
    bpm: Int, snap: RecordingSnap, durationMs: Long, notes: List<ChartNote>, tempoGrid: TouchGrid? = null): Long {
    if (releaseMs - pressMs < 120L) return 0L
    val end = recordedNoteTime(releaseMs, correctionMs, bpm, snap, durationMs, tempoGrid)
    return safeHoldLength(note, (end - note.timeMs).coerceAtLeast(0L), durationMs, notes)
}

/** A hold may end at the next note's start but never cover another same-lane note. */
fun safeHoldLength(note: ChartNote, desiredMs: Long, durationMs: Long, notes: List<ChartNote>): Long {
    val songLimit = if (durationMs > 0L) (durationMs - note.timeMs).coerceAtLeast(0L) else Long.MAX_VALUE
    val next = notes.asSequence().filter { it.lane == note.lane && it.timeMs > note.timeMs }
        .minOfOrNull { it.timeMs - note.timeMs } ?: Long.MAX_VALUE
    return desiredMs.coerceAtLeast(0L).coerceAtMost(minOf(songLimit, next))
}
