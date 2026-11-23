package com.fnfmodmaker.model

import org.junit.Assert.*
import org.junit.Test

class ChartAudioMixTest {
    @Test fun positionsUseOneMasterClockWithIndividualOffsets() {
        assertEquals(1000L, previewTrackPosition(1000, 50, 50, 5000))
        assertEquals(900L, previewTrackPosition(1000, 50, 150, 5000))
        assertEquals(1200L, previewTrackPosition(1000, 50, -150, 5000))
        assertNull(previewTrackPosition(0, 0, 100, 5000))
        assertNull(previewTrackPosition(5000, 0, 0, 5000))
        assertEquals(0L, previewTrackPosition(100, 0, 100, 5000))
    }
    @Test fun settingsAreIndependent() {
        val mix = ChartAudioMix().withTrack(PreviewTrack.PLAYER, TrackMix(true, .4f, 20))
        assertEquals(TrackMix(), mix.track(PreviewTrack.INSTRUMENTAL))
        assertEquals(TrackMix(true, .4f, 20), mix.track(PreviewTrack.PLAYER))
        assertEquals(TrackMix(), mix.track(PreviewTrack.OPPONENT))
    }
    @Test fun plannerRespondsToPlayPauseSeekDriftAndMissingVoices() {
        val voices = listOf(PreviewTrack.PLAYER, PreviewTrack.OPPONENT)
        val actions = voices.map { previewSyncAction(true, previewTrackPosition(1000, 0, if (it == PreviewTrack.PLAYER) 100 else -100, 5000), false, false, 0) }
        assertEquals(listOf(PreviewSyncAction.Seek(900), PreviewSyncAction.Seek(1100)), actions)
        assertEquals(PreviewSyncAction.Pause, previewSyncAction(false, 1000, true, false, 1000))
        assertEquals(PreviewSyncAction.Pause, previewSyncAction(true, null, true, false, 1000))
        assertEquals(PreviewSyncAction.Wait, previewSyncAction(true, 1000, true, false, 950))
        assertEquals(PreviewSyncAction.Seek(1000), previewSyncAction(true, 1000, true, false, 700))
        assertEquals(PreviewSyncAction.Wait, previewSyncAction(true, 1000, false, true, 0))
        assertTrue(emptyList<PreviewTrack>().map { previewSyncAction(true, 1000, false, false, 0) }.isEmpty())
    }
    @Test fun mixIsProjectDataAndOldSongsGetDefaults() {
        val gson = com.google.gson.Gson()
        val song = SongConfig(chartAudioMix = ChartAudioMix(player = TrackMix(true, .3f, -75)))
        val restored = gson.fromJson(gson.toJson(song), SongConfig::class.java)
        assertEquals(song.chartAudioMix, restored.chartAudioMix)
        assertEquals(ChartAudioMix(), gson.fromJson("{}", SongConfig::class.java).chartAudioMix)
    }
}
