package com.fnfmodmaker.model

/** Preview-only settings. Positive offset delays a track relative to the common song timeline. */
data class TrackMix(val muted: Boolean = false, val volume: Float = 1f, val offsetMs: Int = 0)
data class ChartAudioMix(val instrumental: TrackMix = TrackMix(), val player: TrackMix = TrackMix(), val opponent: TrackMix = TrackMix())
enum class PreviewTrack { INSTRUMENTAL, PLAYER, OPPONENT }
fun ChartAudioMix.track(track: PreviewTrack): TrackMix = when (track) {
    PreviewTrack.INSTRUMENTAL -> instrumental; PreviewTrack.PLAYER -> player; PreviewTrack.OPPONENT -> opponent
}
fun ChartAudioMix.withTrack(track: PreviewTrack, value: TrackMix): ChartAudioMix = when (track) {
    PreviewTrack.INSTRUMENTAL -> copy(instrumental = value); PreviewTrack.PLAYER -> copy(player = value); PreviewTrack.OPPONENT -> copy(opponent = value)
}
/** The instrumental position is the master clock; offsets are relative to it. Null means silence. */
fun previewTrackPosition(masterPositionMs: Long, masterOffsetMs: Int, trackOffsetMs: Int, trackDurationMs: Long): Long? {
    val time = masterPositionMs + masterOffsetMs.toLong() - trackOffsetMs.toLong()
    return time.takeIf { it >= 0L && (trackDurationMs <= 0L || it < trackDurationMs) }
}

sealed interface PreviewSyncAction {
    data object Pause : PreviewSyncAction
    data object Wait : PreviewSyncAction
    data class Seek(val positionMs: Long) : PreviewSyncAction
}
/** Pure scheduling decision; actual decoder timing and audio output remain Android responsibilities. */
fun previewSyncAction(masterRunning: Boolean, target: Long?, voicePlaying: Boolean, voiceSeeking: Boolean,
    voicePosition: Long, force: Boolean = false): PreviewSyncAction {
    if (!masterRunning || target == null) return if (voicePlaying) PreviewSyncAction.Pause else PreviewSyncAction.Wait
    if (voiceSeeking) return PreviewSyncAction.Wait
    return if (force || !voicePlaying || kotlin.math.abs(voicePosition - target) > 150L) PreviewSyncAction.Seek(target) else PreviewSyncAction.Wait
}
