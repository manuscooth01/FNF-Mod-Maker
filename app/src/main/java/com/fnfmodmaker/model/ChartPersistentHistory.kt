package com.fnfmodmaker.model

import java.security.MessageDigest

/** Note deltas, rather than whole chart snapshots. Missing custom charts remain distinguishable. */
data class ChartHistoryEntry(
    val removed: List<ChartNote> = emptyList(),
    val added: List<ChartNote> = emptyList(),
    val beforeHash: String = "",
    val afterHash: String = "",
    val beforeTemplate: ChartDraft? = null,
    val afterTemplate: ChartDraft? = null
)
data class ChartPersistentHistory(
    val undo: List<ChartHistoryEntry> = emptyList(),
    val redo: List<ChartHistoryEntry> = emptyList()
)

private const val HISTORY_LIMIT = 50
private const val HISTORY_NOTE_BUDGET = 20000
private fun chartFingerprint(chart: ChartDraft?): String {
    // Explicitly includes metadata, so changes made in other editors cannot be overwritten by undo.
    val digest = MessageDigest.getInstance("SHA-256")
    val normalized = chart?.copy(notes = chart.notes.sortedWith(compareBy<ChartNote> { it.timeMs }.thenBy { it.lane }.thenBy { it.lengthMs }.thenBy { it.isSustain }))
    return digest.digest((normalized?.toString() ?: "<absent>").toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
}
private fun difference(a: List<ChartNote>, b: List<ChartNote>): List<ChartNote> {
    val counts = b.groupingBy { it }.eachCount().toMutableMap()
    return a.filter { note -> val count = counts[note] ?: 0; if (count > 0) { counts[note] = count - 1; false } else true }
}
private fun trimHistory(entries: List<ChartHistoryEntry>): List<ChartHistoryEntry> {
    var count = 0
    return entries.asReversed().take(HISTORY_LIMIT).takeWhile {
        count += it.removed.size + it.added.size
        count <= HISTORY_NOTE_BUDGET
    }.asReversed()
}
fun recordChartHistory(history: ChartPersistentHistory, before: ChartDraft?, after: ChartDraft?): ChartPersistentHistory {
    if (before == after) return history
    val entry = ChartHistoryEntry(
        removed = difference(before?.notes.orEmpty(), after?.notes.orEmpty()),
        added = difference(after?.notes.orEmpty(), before?.notes.orEmpty()),
        beforeHash = chartFingerprint(before), afterHash = chartFingerprint(after),
        beforeTemplate = before?.copy(notes = emptyList()), afterTemplate = after?.copy(notes = emptyList())
    )
    // If edits came from another editor, never attach a new action to a stale chain.
    val base = if (history.undo.lastOrNull()?.afterHash == entry.beforeHash || history.undo.isEmpty()) history.undo else emptyList()
    return ChartPersistentHistory(undo = trimHistory(base + entry))
}
data class ChartHistoryResult(val chart: ChartDraft?, val history: ChartPersistentHistory)
fun restoreChartHistory(history: ChartPersistentHistory, current: ChartDraft?, redo: Boolean): ChartHistoryResult? {
    val stack = if (redo) history.redo else history.undo
    val entry = stack.lastOrNull() ?: return null
    if (chartFingerprint(current) != if (redo) entry.beforeHash else entry.afterHash) return null
    val remove = if (redo) entry.removed else entry.added
    val add = if (redo) entry.added else entry.removed
    val targetTemplate = if (redo) entry.afterTemplate else entry.beforeTemplate
    val notes = current?.notes.orEmpty().toMutableList()
    for (note in remove) if (!notes.remove(note)) return null
    notes.addAll(add)
    val target = targetTemplate?.copy(notes = notes.sortedWith(compareBy<ChartNote> { it.timeMs }.thenBy { it.lane }))
    if (target == null && notes.isNotEmpty() || chartFingerprint(target) != if (redo) entry.afterHash else entry.beforeHash) return null
    return ChartHistoryResult(target, if (redo) ChartPersistentHistory(trimHistory(history.undo + entry), history.redo.dropLast(1))
        else ChartPersistentHistory(history.undo.dropLast(1), trimHistory(history.redo + entry)))
}

/** Rebuild hashes from verified states, not from hashes alone. All-or-nothing on a stale chain. */
internal fun renameChartHistory(history: ChartPersistentHistory, current: ChartDraft?, oldFolder: String, newFolder: String): ChartPersistentHistory {
    fun renamed(chart: ChartDraft?): ChartDraft? = chart?.let {
        require(it.songFolder == oldFolder) { "El historial contiene un chart de otra canción." }
        it.copy(songFolder = newFolder)
    }
    renamed(current)
    fun rebase(stack: List<ChartHistoryEntry>, redo: Boolean): List<ChartHistoryEntry> {
        var state = current
        return stack.asReversed().map { entry ->
            // A one-entry stack validates both fingerprints and all note deltas without trimming other entries.
            val single = if (redo) ChartPersistentHistory(redo = listOf(entry)) else ChartPersistentHistory(undo = listOf(entry))
            val restored = restoreChartHistory(single, state, redo)
                ?: throw IllegalArgumentException("El historial no coincide con el chart actual.")
            val next = restored.chart
            val before = if (redo) state else next
            val after = if (redo) next else state
            val mappedBefore = renamed(before); val mappedAfter = renamed(after)
            val updated = entry.copy(beforeHash = chartFingerprint(mappedBefore), afterHash = chartFingerprint(mappedAfter),
                beforeTemplate = renamed(entry.beforeTemplate), afterTemplate = renamed(entry.afterTemplate))
            state = next
            updated
        }.asReversed()
    }
    return ChartPersistentHistory(undo = rebase(history.undo, false), redo = rebase(history.redo, true))
}
