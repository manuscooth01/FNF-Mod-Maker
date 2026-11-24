package com.fnfmodmaker.modimport

import org.apache.commons.compress.archivers.zip.ZipArchiveEntry
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.util.concurrent.CancellationException

class ModZipInspectorTest {
    @get:Rule val temp = TemporaryFolder()
    private fun zip(vararg files: Pair<String, ByteArray>, mode: Int = 0): ByteArray {
        val out = ByteArrayOutputStream()
        ZipArchiveOutputStream(out).use { zip -> files.forEach { (name, data) ->
            val entry = ZipArchiveEntry(name); if (mode != 0) entry.unixMode = mode
            zip.putArchiveEntry(entry); zip.write(data); zip.closeArchiveEntry()
        } }
        return out.toByteArray()
    }
    private fun file(name: String, value: String = "{}") = name to value.toByteArray()
    private fun inspect(bytes: ByteArray, limits: ModZipLimits = ModZipLimits()): ModZipReview =
        ModZipInspector(temp.root, limits).inspect(bytes.inputStream(), "mod.zip")
    private fun emptyTemp() = assertTrue(temp.root.listFiles().orEmpty().isEmpty())
    @Test fun inventoriesPsychAndAdditionalResourcesWithoutClaimingImport() {
        val review = inspect(zip(file("wrapper/pack.json"), file("wrapper/data/test/test.json"),
            file("wrapper/data/test/test-hard.json"), file("wrapper/songs/test/Inst.ogg"),
            file("wrapper/characters/dad.json"), file("wrapper/images/dad.xml"),
            file("wrapper/stages/stage.json"), file("wrapper/scripts/main.lua"), file("wrapper/custom/unknown.json")))
        assertEquals(listOf("Psych Engine (indicios)"), review.engines)
        assertEquals(listOf("test"), review.songs); assertEquals(2, review.difficulties.size)
        assertEquals(1, review.characters); assertEquals(1, review.stages); assertEquals(1, review.audio); assertEquals(1, review.scripts)
        assertTrue(review.files.all { it.status == "Requiere revisión" }); assertTrue(review.warnings.any { it.contains("no se crea") })
        emptyTemp()
    }
    @Test fun inventoriesVsliceAndBothWithoutCombiningCharts() {
        val review = inspect(zip(file("_polymod_meta.json"), file("data/songs/test/test-metadata.json"), file("data/songs/test/test-chart.json"),
            file("pack.json"), file("data/test/test.json"), file("data/characters/dad.json"), file("data/stages/mainStage.json")))
        assertEquals(2, review.engines.size); assertEquals(listOf("test"), review.songs)
        assertEquals(1, review.characters); assertEquals(1, review.stages)
        assertTrue(review.warnings.any { it.contains("no se comparan") }); emptyTemp()
    }
    @Test fun unknownFormatDoesNotGuessFromZipAlone() {
        val review = inspect(zip(file("unknown.json")))
        assertEquals(listOf("Desconocido o incompleto"), review.engines); emptyTemp()
    }
    @Test fun rejectsTraversalAbsoluteAndDangerousPaths() {
        for (name in listOf("../escape", "/absolute", "C:/x", "foo/../x", "foo\\x", "a//b", "a/./b", "a/．．/b", "a/name?.txt")) {
            assertThrows(Exception::class.java) { inspect(zip(file(name))) }; emptyTemp()
        }
    }
    @Test fun rejectsDuplicatesCaseUnicodeAndFileFolderConflicts() {
        for ((a, b) in listOf("a.json" to "a.json", "a.txt" to "A.TXT", "é.txt" to "e\u0301.txt", "a" to "a/b", "One/a" to "one/b")) {
            assertThrows(Exception::class.java) { inspect(zip(file(a), file(b))) }; emptyTemp()
        }
    }
    @Test fun rejectsUnixSymlinkAndSpecialFile() {
        for (mode in listOf(0xA000 or 0x1FF, 0x6000 or 0x1FF)) {
            assertThrows(Exception::class.java) { inspect(zip(file("link", "../outside"), mode = mode)) }; emptyTemp()
        }
    }
    @Test fun rejectsCorruptionAndCrcMismatch() {
        assertThrows(Exception::class.java) { inspect("not a zip".toByteArray()) }; emptyTemp()
        val bytes = zip(file("a.txt", "abc"))
        // Corrupt CRC in central directory without changing compressed data.
        val sig = byteArrayOf(0x50, 0x4b, 0x01, 0x02)
        val start = (0..bytes.size - sig.size).first { i -> sig.indices.all { bytes[i + it] == sig[it] } }
        bytes[start + 16] = (bytes[start + 16].toInt() xor 1).toByte()
        assertThrows(Exception::class.java) { inspect(bytes) }; emptyTemp()
    }
    @Test fun enforcesCompressedExpandedFileAndCountLimits() {
        val bytes = zip(file("a", "123456"), file("b", "abcdef"))
        for (limits in listOf(ModZipLimits(archiveBytes = (bytes.size - 1).toLong()), ModZipLimits(fileBytes = 5), ModZipLimits(totalBytes = 10), ModZipLimits(entries = 1))) {
            assertThrows(Exception::class.java) { inspect(bytes, limits) }; emptyTemp()
        }
    }
    @Test fun rejectsCompressionBombByActualBytes() {
        val bytes = zip("bomb" to ByteArray(100_000))
        assertThrows(Exception::class.java) { inspect(bytes, ModZipLimits(maxRatio = 5, ratioThreshold = 1024)) }; emptyTemp()
    }
    @Test fun cancellationCleansStageWithoutPublishing() {
        val data = zip(file("data/test/test.json"), file("songs/test/Inst.ogg", "audio"))
        var calls = 0
        assertThrows(CancellationException::class.java) {
            ModZipInspector(temp.root).inspect(data.inputStream(), "test.zip") { if (++calls == 5) throw CancellationException() }
        }
        emptyTemp()
    }
}
