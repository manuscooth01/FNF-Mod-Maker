package com.fnfmodmaker.project

import com.fnfmodmaker.model.AssetCategory
import com.fnfmodmaker.model.CatalogAsset
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File

/** Pure JVM catalog rules: classification, unambiguous pairing and layout guards. */
class AssetPackCatalogTest {
    private fun asset(id: String, path: String): CatalogAsset = CatalogAsset(
        id = id, packId = "p", sourcePath = path, displayName = path.substringAfterLast('/'),
        category = AssetPackCatalog.classify(path).name, extension = path.substringAfterLast('.', ""),
        sizeBytes = 1, sha256 = "0".repeat(64))

    @Test fun classificationUsesExtensionAndPathHints() {
        assertEquals(AssetCategory.FONT, AssetPackCatalog.classify("fonts/vcr.ttf"))
        assertEquals(AssetCategory.AUDIO, AssetPackCatalog.classify("songs/tarde/Inst.ogg"))
        assertEquals(AssetCategory.SCRIPT, AssetPackCatalog.classify("scripts/custom.lua"))
        assertEquals(AssetCategory.JSON, AssetPackCatalog.classify("data/weeks/1.json"))
        assertEquals(AssetCategory.ATLAS, AssetPackCatalog.classify("images/characters/hero.xml"))
        assertEquals(AssetCategory.SPRITE, AssetPackCatalog.classify("images/characters/hero.png"))
        assertEquals(AssetCategory.CHARACTER_ICON, AssetPackCatalog.classify("images/icons/icon-hero.png"))
        assertEquals(AssetCategory.MENU_ICON, AssetPackCatalog.classify("images/icons/menubuttons/storymode.png"))
        assertEquals(AssetCategory.STAGE, AssetPackCatalog.classify("images/stages/week1bg.png"))
        assertEquals(AssetCategory.PROPS, AssetPackCatalog.classify("images/props/table.png"))
        assertEquals(AssetCategory.SONG_ART, AssetPackCatalog.classify("images/weeks/week1.png"))
        assertEquals(AssetCategory.UI, AssetPackCatalog.classify("images/ui/healthBar.png"))
        assertEquals(AssetCategory.OTHER, AssetPackCatalog.classify("readme.bin"))
        // Una imagen con "stage" solo en el nombre no es un escenario confirmado.
        assertEquals(AssetCategory.UNCLASSIFIED, AssetPackCatalog.classify("images/mystagelike.png"))
        // Evidencia de carpeta characters/ sin extensión de imagen también es pista de sprite.
        assertEquals(AssetCategory.SPRITE, AssetPackCatalog.classify("characters/hero.png"))
    }

    @Test fun pngAndXmlPairOnlyWhenUnambiguous() {
        val a = AssetPackCatalog.pairRelated(listOf(
            asset("xml1", "images/characters/hero.xml"), asset("png1", "images/characters/hero.png")))
        assertEquals("png1", a[0].relatedId)
        assertEquals("xml1", a[1].relatedId)

        // Dos PNG con el mismo nombre en carpetas distintas: emparejado ambiguo, no se inventa.
        val ambiguous = AssetPackCatalog.pairRelated(listOf(
            asset("xml2", "images/characters/hero.xml"),
            asset("png2", "images/characters/hero.png"),
            asset("png3", "images/characters/night/hero.png")))
        assertTrue(ambiguous.all { it.relatedId.isEmpty() })

        // XML sin PNG del mismo nombre queda sin relación.
        val lonely = AssetPackCatalog.pairRelated(listOf(asset("xml3", "images/characters/other.xml")))
        assertTrue(lonely.all { it.relatedId.isEmpty() })
    }

    @Test fun packDirectoryRejectsUnsafePackIds() {
        val assets = File("/tmp/irrelevant-assets")
        assertThrows(IllegalArgumentException::class.java) {
            AssetPackCatalog.packDirectory(assets, "../escape")
        }
        assertThrows(IllegalArgumentException::class.java) {
            AssetPackCatalog.packDirectory(assets, "not-a-uuid")
        }
    }

    @Test fun sha256StreamsAndBoundsLargeInput() {
        val data = "contenido".toByteArray()
        val (size, hash) = ByteArrayInputStream(data).use { AssetPackCatalog.sha256Of(it, 1024) }
        assertEquals(data.size.toLong(), size)
        assertEquals(64, hash.length)
        assertTrue(hash.matches(Regex("[0-9a-f]{64}")))
        assertThrows(IllegalArgumentException::class.java) {
            ByteArrayInputStream(data).use { AssetPackCatalog.sha256Of(it, 4) }
        }
    }
}
