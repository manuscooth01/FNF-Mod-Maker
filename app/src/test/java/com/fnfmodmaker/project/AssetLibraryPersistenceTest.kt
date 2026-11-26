package com.fnfmodmaker.project

import com.google.gson.Gson
import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.MessageDigest

/** Asset Library persistence: duplication, portable round-trip, validation and old projects. */
class AssetLibraryPersistenceTest {
    @get:Rule val temp = TemporaryFolder()
    private val gson = Gson()

    private fun sha(data: ByteArray) = MessageDigest.getInstance("SHA-256").digest(data)
        .joinToString("") { "%02x".format(it) }

    /** Builds a config JSON with one imported pack of two files stored under assets/library/. */
    private fun libraryConfig(png: File, xml: File, packId: String): String {
        val pngSha = sha(png.readBytes()); val xmlSha = sha(xml.readBytes())
        return """{"modTitle":"Con biblioteca","songs":[],"charts":{},"stageDraft":{},"characterLibrary":[{"id":"legacy","draft":{"enabled":false}}],
            "assetPacks":[{"id":"$packId","sourceName":"pack-juego.zip","importedAt":1700000000000,
            "engineHint":"Sin indicios de motor",
            "assets":[
            {"id":"asset-1","packId":"$packId","sourcePath":"images/characters/hero.png","displayName":"hero.png",
             "category":"SPRITE","extension":"png","sizeBytes":${png.length()},"sha256":"$pngSha",
             "width":2,"height":2,"relatedId":"asset-2",
             "file":{"path":${gson.toJson(png.path)},"displayName":"hero.png","uri":""}},
            {"id":"asset-2","packId":"$packId","sourcePath":"images/characters/hero.xml","displayName":"hero.xml",
             "category":"ATLAS","extension":"xml","sizeBytes":${xml.length()},"sha256":"$xmlSha",
             "relatedId":"asset-1",
             "file":{"path":${gson.toJson(xml.path)},"displayName":"hero.xml","uri":""}}
            ]}]}"""
    }

    private fun packOf(configJson: String) = gson.fromJson(configJson, com.fnfmodmaker.model.ModConfig::class.java).assetPacks.single()

    @Test fun duplicatingACopyKeepsHierarchyCatalogAndBytes() {
        val store = ProjectStore(File(temp.root, "projects"), temp.root)
        val id = store.newId()
        val assets = store.assets(id)
        val packId = "11111111-2222-3333-4444-555555555555"
        val png = File(assets, "library/$packId/images/characters/hero.png")
        val xml = File(assets, "library/$packId/images/characters/hero.xml")
        png.parentFile.mkdirs()
        png.writeBytes("PNG-DATOS".toByteArray()); xml.writeText("<TextureAtlas/>")
        val originalBytes = png.readBytes()
        val config = JsonParser.parseString(libraryConfig(png, xml, packId)).asJsonObject
        store.save(id, config)

        val copyId = store.newId()
        val copied = store.copyAssets(store.load(id).config, copyId, store.assetsLocation(id))
        assertTrue(copied.missing.isEmpty())
        // La biblioteca conserva su jerarquía dentro del proyecto copiado.
        val copiedPack = packOf(gson.toJson(copied.config))
        assertEquals(2, copiedPack.assets.size)
        val copiedPng = copiedPack.assets.first { it.id == "asset-1" }.file!!.path
        assertTrue(copiedPng.contains("library/$packId/images/characters/hero.png"))
        val copiedFile = File(copiedPng)
        assertTrue(copiedFile.isFile)
        assertEquals(originalBytes.size.toLong(), copiedFile.length())
        // El catálogo conserva metadatos: sha, tamaño, ruta original y asociación.
        assertEquals("PNG-DATOS".toByteArray().size.toLong(), copiedPack.assets.first { it.id == "asset-1" }.sizeBytes)
        assertEquals("images/characters/hero.png", copiedPack.assets.first { it.id == "asset-1" }.sourcePath)
        assertEquals("asset-2", copiedPack.assets.first { it.id == "asset-1" }.relatedId)
        assertTrue(copiedFile.path.startsWith(store.assets(copyId).canonicalPath))
        // Los bytes del proyecto original no cambiaron.
        assertEquals(originalBytes.toList(), png.readBytes().toList())
    }

    @Test fun portableRoundTripPreservesFilesMetadataAndHierarchy() {
        val store = ProjectStore(File(temp.root, "projects"), temp.root)
        val id = store.newId()
        val assets = store.assets(id)
        val packId = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"
        val png = File(assets, "library/$packId/images/stages/bg.png")
        png.parentFile.mkdirs(); png.writeBytes("BG-BYTES-0123456789".toByteArray())
        val config = JsonParser.parseString(libraryConfig(png, File(assets, "library/$packId/x.xml").apply {
            writeText("<TextureAtlas imagePath=\"bg.png\"/>")
        }, packId)).asJsonObject
        val portable = PortableProject(temp.root)
        val zip = File(temp.root, "proyecto.fnfproject")
        portable.export(config, assets, zip)
        val stage = store.newImportStage()
        val prepared = portable.prepare(zip, stage)
        val validator = ProjectConfigValidator(gson)
        val importId = store.publishImport(prepared, portable, validator::validate)
        assertNotEquals(id, importId)
        val reopened = store.load(importId).config
        val pack = packOf(gson.toJson(reopened))
        assertEquals(2, pack.assets.size)
        assertEquals(packId, pack.id)
        assertEquals("pack-juego.zip", pack.sourceName)
        val restored = pack.assets.first { it.id == "asset-1" }
        assertTrue(restored.file!!.path.contains("library/$packId/images/stages/bg.png"))
        val restoredFile = File(restored.file!!.path)
        assertTrue(restoredFile.isFile)
        assertEquals(sha("BG-BYTES-0123456789".toByteArray()), restored.sha256)
        assertEquals(sha(restoredFile.readBytes()), restored.sha256)
        assertTrue(restoredFile.path.startsWith(store.assets(importId).canonicalPath))
    }

    @Test fun validatorRejectsBrokenCatalogs() {
        val validator = ProjectConfigValidator(gson)
        val base = """{"modTitle":"x","songs":[],"assetPacks":[%s]}"""
        val pack = """{"id":"11111111-2222-3333-4444-555555555555","sourceName":"a.zip","importedAt":1,"assets":[]}"""
        validator.validate(JsonParser.parseString(base.format(pack)).asJsonObject)
        assertThrows(IllegalArgumentException::class.java) {
            val duplicated = listOf(pack, pack.replace("11111111", "11111111"))
            validator.validate(JsonParser.parseString(base.format(duplicated.joinToString(","))).asJsonObject)
        }
        assertThrows(IllegalArgumentException::class.java) {
            validator.validate(JsonParser.parseString(base.format(
                """{"id":"11111111-2222-3333-4444-555555555555","assets":[{"id":"a","sha256":"no-hash"}]}""")).asJsonObject)
        }
        assertThrows(IllegalArgumentException::class.java) {
            validator.validate(JsonParser.parseString("""{"assetPacks":{"not":"array"}}""").asJsonObject)
        }
    }

    @Test fun oldProjectsWithoutLibraryOpenWithEmptyCatalog() {
        val validator = ProjectConfigValidator(gson)
        // Proyecto antiguo completo pero sin assetPacks: abre con biblioteca vacía.
        val old = """{"modTitle":"Viejo","songs":[],"charts":{},"stageDraft":{},"characterLibrary":[{"id":"legacy","draft":{"enabled":false}}]}"""
        validator.validate(JsonParser.parseString(old).asJsonObject)
        val config = gson.fromJson(old, com.fnfmodmaker.model.ModConfig::class.java)
        assertTrue(config.assetPacks.isEmpty())
        // Una categoría desconocida de un proyecto futuro no rompe la apertura.
        val legacy = """{"modTitle":"Viejo","assetPacks":[{"id":"x","assets":[{"id":"a","category":"CATEGORIA-FUTURA"}]}]}"""
        val parsed = gson.fromJson(legacy, com.fnfmodmaker.model.ModConfig::class.java)
        assertEquals(com.fnfmodmaker.model.AssetCategory.UNCLASSIFIED, parsed.assetPacks.single().assets.single().type)
    }
}
