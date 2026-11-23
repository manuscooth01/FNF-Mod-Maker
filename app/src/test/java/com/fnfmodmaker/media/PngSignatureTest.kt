package com.fnfmodmaker.media

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PngSignatureTest {
    @get:Rule val files = TemporaryFolder()

    @Test fun checksContentNotFilename() {
        val renamed = files.newFile("photo.png")
        renamed.writeBytes(byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte()))
        assertFalse(MediaTools.isPng(renamed))
        val png = files.newFile("sheet.bin")
        png.writeBytes(byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a))
        assertTrue(MediaTools.isPng(png))
        assertFalse(MediaTools.isPng(files.root.resolve("missing")))
    }
}
