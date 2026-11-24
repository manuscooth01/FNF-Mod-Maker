package com.fnfmodmaker.model

import com.google.gson.Gson
import org.junit.Assert.*
import org.junit.Test

class SongRenameTest {
    @Test fun renameMovesAllChartsAndRebasesUndoAndRedoAcrossSerialization() {
        val first = ChartDraft(songFolder = "old-song", difficulty = "normal", notes = listOf(ChartNote(100, 0)))
        val second = first.copy(notes = first.notes + ChartNote(200, 4, 50))
        val third = second.copy(notes = second.notes + ChartNote(300, 1))
        var normalHistory = recordChartHistory(ChartPersistentHistory(), null, first)
        normalHistory = recordChartHistory(normalHistory, first, second)
        normalHistory = recordChartHistory(normalHistory, second, third)
        val undoThird = restoreChartHistory(normalHistory, third, false)!!
        val hard = ChartDraft(songFolder = "old-song", difficulty = "hard", notes = listOf(ChartNote(50, 6, 300)))
        val hardHistory = recordChartHistory(ChartPersistentHistory(), null, hard)
        val config = ModConfig(songs = listOf(SongConfig(songName = "Old Song")))
            .withChart("old-song", "normal", undoThird.chart!!).withChart("old-song", "hard", hard)
            .copy(chartHistories = mapOf("old-song" to mapOf("normal" to undoThird.history, "hard" to hardHistory)))
        val moved = renameSong(config, 0, "New Song")
        assertEquals("New Song", moved.songs.single().songName)
        assertFalse(moved.charts.containsKey("old-song")); assertFalse(moved.chartHistories.containsKey("old-song"))
        val gson = Gson(); val saved = gson.fromJson(gson.toJson(moved), ModConfig::class.java)
        val normal = saved.getChart("new-song", "normal")!!
        assertEquals(second.copy(songFolder = "new-song"), normal)
        val history = saved.chartHistories.getValue("new-song").getValue("normal")
        val redone = restoreChartHistory(history, normal, true)!!
        assertEquals(third.copy(songFolder = "new-song"), redone.chart)
        val back = restoreChartHistory(redone.history, redone.chart, false)!!
        assertEquals(normal, back.chart)
        val before = restoreChartHistory(back.history, back.chart, false)!!
        assertEquals(first.copy(songFolder = "new-song"), before.chart)
        val absent = restoreChartHistory(before.history, before.chart, false)!!
        assertNull(absent.chart)
        assertEquals(first.copy(songFolder = "new-song"), restoreChartHistory(absent.history, null, true)!!.chart)
        val savedHard = saved.getChart("new-song", "hard")!!
        assertEquals(hard.copy(songFolder = "new-song"), savedHard)
        assertNull(restoreChartHistory(saved.chartHistories.getValue("new-song").getValue("hard"), savedHard, false)!!.chart)
        assertNull(restoreChartHistory(history, normal.copy(bpm = 300.0), false))
    }
    @Test fun historyForAbsentChartAndRedoIsRebased() {
        val c = ChartDraft(songFolder = "old", notes = listOf(ChartNote(10, 0)))
        val h = recordChartHistory(ChartPersistentHistory(), null, c)
        val undone = restoreChartHistory(h, c, false)!!
        val base = ModConfig(songs = listOf(SongConfig(songName = "old")), chartHistories = mapOf("old" to mapOf("normal" to undone.history)))
        val renamed = renameSong(base, 0, "new")
        val redo = restoreChartHistory(renamed.chartHistories.getValue("new").getValue("normal"), null, true)!!
        assertEquals(c.copy(songFolder = "new"), redo.chart)
    }
    @Test fun collisionAndStaleHistoryLeaveOriginalUnchanged() {
        val chart = ChartDraft(songFolder = "old", notes = listOf(ChartNote(1, 0)))
        val history = recordChartHistory(ChartPersistentHistory(), null, chart)
        val base = ModConfig(songs = listOf(SongConfig(songName = "old"), SongConfig(songName = "New Song")))
            .withChart("old", "normal", chart).copy(chartHistories = mapOf("old" to mapOf("normal" to history)))
        assertThrows(IllegalArgumentException::class.java) { renameSong(base, 0, "NEW SONG") }
        assertEquals(chart, base.getChart("old", "normal")); assertEquals(history, base.chartHistories.getValue("old").getValue("normal"))
        val orphan = base.copy(songs = base.songs.take(1), chartHistories = base.chartHistories + ("new-song" to emptyMap()))
        assertThrows(IllegalArgumentException::class.java) { renameSong(orphan, 0, "New Song") }
        val stale = base.copy(songs = base.songs.take(1)).withChart("old", "normal", chart.copy(bpm = 200.0))
        assertThrows(IllegalArgumentException::class.java) { renameSong(stale, 0, "other") }
        assertEquals("old", stale.songs[0].songName)
    }
    @Test fun sharedOldFolderWithChartsIsNeverAssignedToOneSong() {
        val config = ModConfig(songs = listOf(SongConfig(songName = "same"), SongConfig(songName = "same")))
            .withChart("same", "normal", ChartDraft(songFolder = "same"))
        assertThrows(IllegalArgumentException::class.java) { renameSong(config, 0, "different") }
        assertEquals(2, config.songs.size); assertTrue(config.charts.containsKey("same"))
    }
    @Test fun caseAmbiguousLegacyKeysAreRejectedWithoutOrphaningData() {
        val chart = ChartDraft(songFolder = "old", notes = listOf(ChartNote(1, 0)))
        val base = ModConfig(songs = listOf(SongConfig(songName = "old")), charts = mapOf("OLD" to mapOf("normal" to chart)))
        assertThrows(IllegalArgumentException::class.java) { renameSong(base, 0, "new") }
        assertEquals(chart, base.charts.getValue("OLD").getValue("normal"))
    }
    @Test fun cosmeticRenameWithSameFolderKeepsHistoryUnchanged() {
        val config = ModConfig(songs = listOf(SongConfig(songName = "Song Name")))
        assertEquals("SONG NAME", renameSong(config, 0, "SONG NAME").songs.single().songName)
    }
}
