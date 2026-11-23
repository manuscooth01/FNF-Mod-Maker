package com.fnfmodmaker.model

import org.junit.Assert.*
import org.junit.Test

class WaveformPeaksTest {
    @Test fun binsAreBoundedAndRetainLargestAbsoluteValue() {
        val peaks = WaveformPeaks(1000, 4)
        peaks.add(0, -.5f); peaks.add(200, .2f); peaks.add(250, 4f); peaks.add(999, -.75f)
        peaks.add(-1, 1f); peaks.add(1000, 1f); peaks.add(400, Float.NaN)
        assertArrayEquals(floatArrayOf(.5f, 1f, 0f, .75f), peaks.snapshot(), 0.0001f)
        val copy = peaks.snapshot(); copy[0] = 0f
        assertEquals(.5f, peaks.snapshot()[0], 0.0001f)
    }
}
