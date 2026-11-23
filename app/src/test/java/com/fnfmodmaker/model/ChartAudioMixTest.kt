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
}
