package com.fnfmodmaker.mod

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ModArchiveLibraryTest {
    @get:Rule val folder = TemporaryFolder()
    private fun zip(vararg entries: Pair<String, String>): ByteArray {
        val bytes = ByteArrayOutputStream()
        ZipOutputStream(bytes).use { out -> entries.forEach { (name, content) -> out.putNextEntry(ZipEntry(name)); out.write(content.toByteArray()); out.closeEntry() } }
        return bytes.toByteArray()
    }
    @Test fun importsAndReexportsOriginalWithoutExecutingOrLosingUnknownData() {
        val root = File(folder.root, "library"); val library = ModArchiveLibrary(root)
        val bytes = zip("MyMod/pack.json" to "{}", "MyMod/scripts/evil.lua" to "unknown script", "MyMod/custom.bin" to "opaque bytes")
        val item = library.import(ByteArrayInputStream(bytes), "MyMod.zip")
        assertEquals(listOf("Psych"), item.engines); assertEquals(1, item.scripts)
        assertEquals(item, ModArchiveLibrary(root).list().single())
        val out = ByteArrayOutputStream(); ModArchiveLibrary(root).export(item.id, out)
        assertArrayEquals(bytes, out.toByteArray())
    }
    @Test fun rejectsDangerousPathsDuplicatesAndBadArchivesWithoutPublishing() {
        val root = File(folder.root, "lib"); val library = ModArchiveLibrary(root)
        val good = library.import(zip("pack.json" to "{}").inputStream(), "good.zip")
        val invalid = listOf(zip("../outside" to "x"), zip("/absolute" to "x"), zip("x\\y" to "x"), zip("A.json" to "x", "a.json" to "y"), byteArrayOf(1,2,3))
        invalid.forEach { assertThrows(Exception::class.java) { library.import(it.inputStream(), "bad.zip") } }
        assertEquals(listOf(good), library.list()); assertFalse(File(folder.root, "outside").exists())
        assertTrue(root.listFiles().orEmpty().none { it.name.startsWith(".pending-") })
    }
    @Test fun interruptionAndDamageDoNotOverwritePreviousCopy() {
        val root = File(folder.root, "lib"); val library = ModArchiveLibrary(root)
        val bytes = zip("_polymod_meta.json" to "{}", "data/x" to "notes")
        val saved = library.import(bytes.inputStream(), "v.zip")
        assertThrows(IllegalStateException::class.java) { library.import(bytes.inputStream(), "cancel.zip") { error("interrupted") } }
        assertEquals(listOf(saved), library.list())
        File(root, ".pending-01234567-0123-0123-0123-0123456789ab").mkdirs()
        library.cleanupInterrupted(); assertEquals(1, root.listFiles()!!.size)
        File(root, "${saved.id}/original.zip").writeBytes(ByteArray(bytes.size))
        val out = ByteArrayOutputStream()
        assertThrows(IllegalArgumentException::class.java) { library.export(saved.id, out) }
        assertEquals(0, out.size())
    }
    @Test fun rejectsBoundedArchiveEntryTotalAndCountWithoutPublishing() {
        val bytes = zip("a.txt" to "123456", "b.txt" to "abcdef")
        val configs = listOf(
            ModArchiveLimits(maxArchive = (bytes.size - 1).toLong()),
            ModArchiveLimits(maxEntry = 5),
            ModArchiveLimits(maxUnpacked = 10),
            ModArchiveLimits(maxEntries = 1)
        )
        configs.forEachIndexed { i, limits ->
            val root = File(folder.root, "limit-$i")
            val lib = ModArchiveLibrary(root, limits)
            assertThrows(Exception::class.java) { lib.import(bytes.inputStream(), "too-large.zip") }
            assertTrue(lib.list().isEmpty())
            assertTrue(root.listFiles().orEmpty().none { it.name.startsWith(".pending-") })
        }
    }
}
