package com.fnfmodmaker.appearance

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class AppearanceLimitsTest {
    @Test fun copyingAcceptsExactLimitAndRejectsOversizeOrEmpty() {
        val bytes = ByteArray(100) { it.toByte() }; val out = ByteArrayOutputStream()
        assertEquals(100L, copyAppearanceInput(ByteArrayInputStream(bytes), out, 100))
        assertArrayEquals(bytes, out.toByteArray())
        assertThrows(IllegalArgumentException::class.java) { copyAppearanceInput(ByteArrayInputStream(bytes), ByteArrayOutputStream(), 99) }
        assertThrows(IllegalArgumentException::class.java) { copyAppearanceInput(ByteArrayInputStream(byteArrayOf()), ByteArrayOutputStream(), 100) }
    }
    @Test fun dimensionsAreBoundedAndDownsampled() {
        assertEquals(1, appearanceSampleSize(1200, 900))
        assertEquals(2, appearanceSampleSize(3000, 1600))
        assertEquals(8, appearanceSampleSize(8192, 8192))
        assertThrows(IllegalArgumentException::class.java) { appearanceSampleSize(0, 100) }
        assertThrows(IllegalArgumentException::class.java) { appearanceSampleSize(9000, 100) }
    }
    @Test fun unusualStreamsReturningZeroStillMakeProgress() {
        val input = object : java.io.InputStream() {
            private var index = 0
            override fun read(b: ByteArray, off: Int, len: Int): Int = 0
            override fun read(): Int = if (index++ < 3) 42 else -1
        }
        val out = ByteArrayOutputStream()
        assertEquals(3L, copyAppearanceInput(input, out, 3))
        assertArrayEquals(byteArrayOf(42, 42, 42), out.toByteArray())
    }
}
