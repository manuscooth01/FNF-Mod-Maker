package com.fnfmodmaker.mod

import android.graphics.BitmapFactory
import com.fnfmodmaker.model.AssetCategory
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Real ZIP flows of the Asset Library importer: storage layout, safety limits and cancellation. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class AssetPackImporterTest {
    @get:Rule val temp = TemporaryFolder()

    private val png: ByteArray by lazy { javaClass.getResourceAsStream("/psych-fixture/dad.png")!!.use { it.readBytes() } }

    private fun zip(vararg entries: Pair<String, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { stream ->
            entries.forEach { (name, bytes) ->
                stream.putNextEntry(ZipEntry(name)); stream.write(bytes); stream.closeEntry()
            }
        }
        return out.toByteArray()
    }

    /** Asset pack ZIP without pack.json, mod metadata or charts: a generic resources pack. */
    private val resourcePack = zip(
        "images/characters/hero.png" to png,
        "images/characters/hero.xml" to """<TextureAtlas imagePath="hero.png"><SubTexture name="hero idle0000" x="0" y="0" width="1" height="1"/></TextureAtlas>""".toByteArray(),
        "images/stages/week1bg.png" to png,
        "fonts/vcr.ttf" to "fontdata".toByteArray(),
        "scripts/deep/nested/custom.lua" to "trace('data only')".toByteArray(),
        "images/icons/icon-hero.png" to png)

    private fun importer(limits: AssetPackLimits = AssetPackLimits()) = AssetPackImporter(
        temp.newFolder(), dimensions = { file -> BitmapFactory.decodeFile(file.absolutePath)?.let { it.width to it.height } },
        limits = limits)

    @Test fun publishKeepsHierarchyBytesAndCatalog() {
        val assets = temp.newFolder("project-assets")
        val pack = importer().publish(resourcePack.inputStream(), "paquete-assets.zip", assets)
        assertEquals(6, pack.assets.size)
        assertTrue(pack.sourceName.contains("paquete-assets"))
        assertTrue(pack.importedAt > 0)
        assertTrue(pack.engineHint.contains("Sin indicios"))
        val packDir = File(assets, "library/${pack.id}")
        assertTrue(packDir.isDirectory)
        // Jerarquía original conservada, sin aplanado.
        assertTrue(File(packDir, "images/characters/hero.png").isFile)
        assertTrue(File(packDir, "scripts/deep/nested/custom.lua").isFile)
        assertEquals(png.size.toLong(), File(packDir, "images/characters/hero.png").length())
        val hero = pack.assets.single { it.sourcePath == "images/characters/hero.png" }
        assertEquals(AssetCategory.SPRITE, hero.type)
        assertEquals(2, hero.width); assertEquals(2, hero.height)
        assertEquals(png.size.toLong(), hero.sizeBytes)
        assertTrue(hero.sha256.matches(Regex("[0-9a-f]{64}")))
        val xml = pack.assets.single { it.sourcePath == "images/characters/hero.xml" }
        assertEquals(AssetCategory.ATLAS, xml.type)
        assertEquals(hero.id, xml.relatedId)
        assertEquals(xml.id, hero.relatedId)
        assertTrue(File(hero.file!!.path).isFile)
        assertTrue(hero.file!!.path.contains("library/${pack.id}/"))
        assertEquals(AssetCategory.FONT, pack.assets.single { it.displayName == "vcr.ttf" }.type)
        assertEquals(AssetCategory.SCRIPT, pack.assets.single { it.displayName == "custom.lua" }.type)
        assertEquals(AssetCategory.CHARACTER_ICON, pack.assets.single { it.displayName == "icon-hero.png" }.type)
        // El área temporal del catálogo quedó limpia.
        assertFalse(File(assets, "library").list()!!.any { it.startsWith(".import-") })
    }

    @Test fun analyzeDoesNotTouchTheProjectAndReportsCategories() {
        val assets = temp.newFolder("project-assets")
        val review = importer().analyze(resourcePack.inputStream(), "paquete.zip")
        assertEquals(6, review.entries)
        assertTrue(review.categories.containsKey(AssetCategory.SPRITE.label))
        assertTrue(review.categories.containsKey(AssetCategory.ATLAS.label))
        assertTrue(review.categories.containsKey(AssetCategory.FONT.label))
        assertTrue(review.warnings.any { it.contains("clasificación") })
        assertTrue(review.totalBytes > 0)
        // Sin proyecto modificado: library/ ni siquiera existe.
        assertFalse(File(assets, "library").exists())
    }

    @Test fun twoPublishesNeverCollide() {
        val assets = temp.newFolder("project-assets")
        val importer = importer()
        val first = importer.publish(resourcePack.inputStream(), "a.zip", assets)
        val second = importer.publish(resourcePack.inputStream(), "b.zip", assets)
        assertNotEquals(first.id, second.id)
        assertTrue(File(assets, "library/${first.id}/images/characters/hero.png").isFile)
        assertTrue(File(assets, "library/${second.id}/images/characters/hero.png").isFile)
    }

    @Test fun damagedCrcAndDuplicateEntriesAreRejected() {
        val assets = temp.newFolder("project-assets")
        val importer = importer()
        // CRC dañado: corrupto dentro de los datos comprimidos del primer archivo.
        val damaged = resourcePack.copyOf().also { it[200] = (it[200].toInt() xor 0x51).toByte() }
        assertThrows(Exception::class.java) {
            importer.publish(damaged.inputStream(), "danado.zip", assets)
        }
        // Entradas duplicadas por mayúsculas/minúsculas.
        val duplicated = zip(
            "images/hero.png" to png,
            "images/HERO.PNG" to png)
        assertThrows(IllegalArgumentException::class.java) {
            importer.publish(duplicated.inputStream(), "duplicado.zip", assets)
        }
        assertFalse(File(assets, "library").exists())
    }

    @Test fun traversalAndSymlinkEntriesAreRejected() {
        val assets = temp.newFolder("project-assets")
        val importer = importer()
        val traversal = zip("../escape.png" to png)
        assertThrows(IllegalArgumentException::class.java) {
            importer.publish(traversal.inputStream(), "traversal.zip", assets)
        }
        val absolute = zip("/etc/escape.png" to png)
        assertThrows(IllegalArgumentException::class.java) {
            importer.publish(absolute.inputStream(), "absoluta.zip", assets)
        }
        // Entrada con atributos Unix de enlace simbólico (S_IFLNK).
        val link = ByteArrayOutputStream()
        ZipArchiveOutputStream(link).use { stream ->
            val entry = ZipArchiveEntry("images/link.png").apply { setUnixMode(0xA1FF) }
            stream.putArchiveEntry(entry); stream.write(png); stream.closeArchiveEntry()
        }
        assertThrows(IllegalArgumentException::class.java) {
            importer.publish(link.toByteArray().inputStream(), "symlink.zip", assets)
        }
        assertFalse(File(assets, "library").exists())
    }

    @Test fun sizeAndEntryLimitsAreEnforcedPerPack() {
        val assets = temp.newFolder("project-assets")
        val importer = importer(AssetPackLimits(entries = 4))
        assertThrows(IllegalArgumentException::class.java) {
            importer.publish(resourcePack.inputStream(), "muchos.zip", assets)
        }
        val big = importer(AssetPackLimits(fileBytes = 4))
        assertThrows(IllegalArgumentException::class.java) {
            big.publish(zip("images/hero.png" to png).inputStream(), "grande.zip", assets)
        }
        assertFalse(File(assets, "library").exists())
    }

    @Test fun cancellationLeavesProjectAndStagingUntouched() {
        val assets = temp.newFolder("project-assets")
        val importer = importer()
        assertThrows(IllegalStateException::class.java) {
            importer.publish(resourcePack.inputStream(), "cancelado.zip", assets,
                ensureActive = { throw IllegalStateException("cancelado") })
        }
        val library = File(assets, "library")
        assertTrue(!library.exists() || library.listFiles()!!.none { it.name.startsWith(".import-") })
        assertFalse(library.exists())
    }

    @Test fun emptyAndContentFreeArchivesAreRejected() {
        val assets = temp.newFolder("project-assets")
        val importer = importer()
        assertThrows(IllegalArgumentException::class.java) {
            importer.publish(ByteArray(0).inputStream(), "vacio.zip", assets)
        }
        // ZIP con solo carpetas: nada importable.
        val directories = ByteArrayOutputStream()
        ZipOutputStream(directories).use { stream -> stream.putNextEntry(ZipEntry("solo/carpeta/")) }
        assertThrows(IllegalArgumentException::class.java) {
            importer.publish(directories.toByteArray().inputStream(), "carpetas.zip", assets)
        }
        assertFalse(File(assets, "library").exists())
    }
}
