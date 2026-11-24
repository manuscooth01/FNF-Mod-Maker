package com.fnfmodmaker.appearance

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

class AppearanceArchiveTest {
    @get:Rule val folder = TemporaryFolder()
    private fun archive(vararg names: String): File {
        val out = folder.newFile()
        ZipOutputStream(out.outputStream()).use { zip -> names.forEach { name ->
            zip.putNextEntry(ZipEntry(name)); zip.write(byteArrayOf(1, 2, 3)); zip.closeEntry()
        } }
        return out
    }
    @Test fun detectsRepoWithOrWithoutRootAndDoesNotUseOtherContent() {
        val f = archive("funkin.assets-main/preload/images/menuBG.png", "funkin.assets-main/preload/images/icons/icon-bf.png", "songs/Inst.ogg")
        ZipFile(f).use { zip ->
            val found = findAppearanceImages(zip)
            assertEquals(2, found.size)
            assertEquals("funkin.assets-main/preload/images/menuBG.png", found.getValue(AppearanceSlot.BACKGROUND).name)
            assertEquals("funkin.assets-main/preload/images/icons/icon-bf.png", found.getValue(AppearanceSlot.ICON).name)
        }
        val plain = archive("preload/images/menuBG.png")
        ZipFile(plain).use { assertEquals(setOf(AppearanceSlot.BACKGROUND), findAppearanceImages(it).keys) }
    }
    @Test fun ignoresTraversalAbsoluteAndUnrelatedImages() {
        val f = archive("../preload/images/menuBG.png", "/preload/images/icons/icon-bf.png", "root/../preload/images/icons/icon-bf.png", "another/icon-bf.png")
        ZipFile(f).use { assertTrue(findAppearanceImages(it).isEmpty()) }
    }
    @Test fun rejectsAmbiguousPathsBeforeAnyImageIsApplied() {
        val f = archive("one/preload/images/menuBG.png", "two/preload/images/MENUBG.PNG")
        ZipFile(f).use { assertThrows(IllegalArgumentException::class.java) { findAppearanceImages(it) } }
    }
    @Test fun rejectsOversizedEntry() {
        val f = folder.newFile()
        ZipOutputStream(f.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("preload/images/menuBG.png"))
            val block = ByteArray(8192); repeat((MAX_APPEARANCE_BYTES / block.size + 1).toInt()) { zip.write(block) }; zip.closeEntry()
        }
        ZipFile(f).use { assertThrows(IllegalArgumentException::class.java) { findAppearanceImages(it) } }
    }
    @Test fun cacheCopyIsBoundedAndHonorsCancellation() {
        val data = ByteArray(100) { it.toByte() }
        val out = java.io.ByteArrayOutputStream()
        copyAppearanceArchive(data.inputStream(), out, 100)
        assertArrayEquals(data, out.toByteArray())
        assertThrows(IllegalArgumentException::class.java) { copyAppearanceArchive(data.inputStream(), java.io.ByteArrayOutputStream(), 99) }
        assertThrows(IllegalStateException::class.java) { copyAppearanceArchive(data.inputStream(), java.io.ByteArrayOutputStream(), 100) { error("cancelled") } }
    }
}
