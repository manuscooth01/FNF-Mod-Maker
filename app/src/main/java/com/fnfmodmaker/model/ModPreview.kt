package com.fnfmodmaker.model

import kotlin.math.roundToLong

/** Match the practice-note generator used by the exporter, where timing is known. */
fun previewPracticeNotes(bpm: Double, durationMs: Long): List<ChartNote> {
    if (bpm <= 0) return emptyList()
    val beatMs = 60_000.0 / bpm
    val count = (if (durationMs > 0) (durationMs / beatMs).toInt() else 64).coerceIn(8, 4000)
    return (0 until count).map { beat -> ChartNote((beat * beatMs).roundToLong(), beat % 4 + if (beat % 2 == 0) 0 else 4) }
}

/** Simple feedback only, not an engine's judgement or scoring rules. */
fun previewHitIndex(notes: List<ChartNote>, alreadyHit: Set<Int>, lane: Int, timeMs: Long, windowMs: Long = 200): Int? {
    if (lane !in 0..3 || windowMs < 0) return null
    return notes.indices.asSequence().filter { it !in alreadyHit && notes[it].lane == lane && notes[it].timeMs >= 0 }
        .filter { kotlin.math.abs(notes[it].timeMs - timeMs) <= windowMs }
        .minByOrNull { kotlin.math.abs(notes[it].timeMs - timeMs) }
}

fun previewPracticeNotes(bpm: Int, durationMs: Long) = previewPracticeNotes(bpm.toDouble(), durationMs)
