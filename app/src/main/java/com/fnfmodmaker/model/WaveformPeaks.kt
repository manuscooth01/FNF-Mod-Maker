package com.fnfmodmaker.model

/** Fixed-size peak envelope; retains no decoded samples. */
class WaveformPeaks(val durationUs: Long, bins: Int = 1200) {
    init { require(durationUs > 0 && bins in 1..10000) }
    private val peaks = FloatArray(bins)
    fun add(timeUs: Long, amplitude: Float) {
        if (timeUs < 0 || timeUs >= durationUs || !amplitude.isFinite()) return
        val index = (timeUs.toDouble() / durationUs * peaks.size).toInt().coerceIn(0, peaks.lastIndex)
        peaks[index] = maxOf(peaks[index], kotlin.math.abs(amplitude).coerceAtMost(1f))
    }
    fun snapshot(): FloatArray = peaks.copyOf()
}
