package com.fnfmodmaker.project

import com.google.gson.JsonObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files

class ModImportPathTest {
    @get:Rule val temp = TemporaryFolder()

    private fun asset(path: String, name: String) = JsonObject().apply {
        addProperty("path", path); addProperty("displayName", name); addProperty("uri", "content://old")
    }
    private fun document(vararg resources: Pair<String, JsonObject>) = JsonObject().apply {
        val song = JsonObject(); val character = JsonObject()
        resources.forEach { (key, value) ->
            if (key == "audio" || key == "instrumental") song.add("instrumental", value)
            else character.add(key, value)
        }
        add("songs", com.google.gson.JsonArray().apply { add(song) })
        add("character", character)
    }
    private fun assetAt(config: JsonObject, key: String): JsonObject =
        if (key == "audio" || key == "instrumental") config.getAsJsonArray("songs")[0].asJsonObject.getAsJsonObject("instrumental")
        else config.getAsJsonObject("character").getAsJsonObject(key)
    private fun aliasedStore(): ProjectStore {
        val real = File(temp.root, "real").apply { mkdirs() }
        val alias = File(temp.root, "android-data-user-alias")
        Files.createSymbolicLink(alias.toPath(), real.toPath())
        return ProjectStore(File(alias, "projects"), temp.root)
    }
    private fun stage(store: ProjectStore): File = store.newImportStage().also { File(it, "assets/imported").mkdirs() }
    private fun write(stage: File, name: String, content: String) = File(stage, "assets/imported/$name").apply { writeText(content) }

    @Test fun rootSymlinkReproducesOldEqualityFailureAndRebasesAudioImageAndXml() {
        val store = aliasedStore(); val stage = stage(store)
        val inst = write(stage, "Inst.ogg", "audio")
        val png = write(stage, "hero.png", "png")
        val xml = write(stage, "hero.xml", "xml")
        // Pinpoint which condition fails in the Android-like alias scenario.
        assertNotEquals(inst.absoluteFile, inst.canonicalFile)
        assertTrue(inst.canonicalFile.toPath().startsWith(File(stage, "assets").canonicalFile.toPath()))
        assertTrue(inst.canonicalFile.isFile)
        val data = document("instrumental" to asset(inst.absolutePath, "Inst.ogg"),
            "atlasSheet" to asset(png.canonicalPath, "hero.png"), "atlasXml" to asset(xml.absolutePath, "hero.xml"))
        val id = store.publishModImport(stage, data) { }
        assertFalse(stage.exists())
        val loaded = store.load(id).config
        listOf("instrumental" to "audio", "atlasSheet" to "png", "atlasXml" to "xml").forEach { (key, expected) ->
            val obj = assetAt(loaded, key)
            val file = File(obj.get("path").asString)
            assertTrue(file.isFile); assertEquals(expected, file.readText())
            assertTrue(file.canonicalFile.toPath().startsWith(store.assets(id).canonicalFile.toPath()))
            assertFalse(file.path.contains(stage.name)); assertEquals("", obj.get("uri").asString)
        }
        // Original source JSON is not mutated.
        assertEquals(inst.absolutePath, assetAt(data, "instrumental").get("path").asString)
    }

    @Test fun internalSymlinkUsesTheRealTargetAfterStageRename() {
        val store = aliasedStore(); val stage = stage(store)
        val real = write(stage, "real.ogg", "original")
        val link = File(stage, "assets/imported/alias.ogg")
        Files.createSymbolicLink(link.toPath(), real.canonicalFile.toPath())
        val id = store.publishModImport(stage, document("audio" to asset(link.absolutePath, "alias.ogg"))) { }
        val result = File(assetAt(store.load(id).config, "audio").get("path").asString)
        assertEquals("real.ogg", result.name); assertEquals("original", result.readText())
    }

    @Test fun missingExternalTraversalAndEscapingSymlinkFailWithDistinctMessagesAndKeepStage() {
        val store = aliasedStore(); val previous = store.newId()
        val previousConfig = JsonObject().apply { addProperty("modTitle", "Anterior") }
        store.save(previous, previousConfig); store.setActive(previous)
        val stage = stage(store); val legitimate = write(stage, "Inst.ogg", "audio")
        val outside = File(temp.root, "outside.ogg").apply { writeText("outside") }
        val prefixSibling = File(stage, "assets-evil/Inst.ogg").apply { parentFile.mkdirs(); writeText("bad") }
        val escaping = File(stage, "assets/imported/escape.ogg")
        Files.createSymbolicLink(escaping.toPath(), outside.toPath())
        val outsideAlias = File(temp.root, "external-alias.ogg")
        Files.createSymbolicLink(outsideAlias.toPath(), legitimate.canonicalFile.toPath())
        fun rejected(path: String, detail: String) {
            val error = assertThrows(IllegalArgumentException::class.java) {
                store.publishModImport(stage, document("audio" to asset(path, "Inst.ogg"))) { }
            }
            assertTrue(error.message, error.message!!.contains("Inst.ogg"))
            assertTrue(error.message, error.message!!.contains(detail))
            assertEquals(previous, store.activeId()); assertEquals(previousConfig, store.load(previous).config)
            assertTrue(stage.isDirectory); assertEquals("audio", legitimate.readText())
        }
        rejected(File(stage, "assets/imported/missing.ogg").absolutePath, "ausente")
        rejected(outside.absolutePath, "externa")
        rejected(prefixSibling.absolutePath, "externa")
        rejected(outsideAlias.absolutePath, "externa")
        rejected(escaping.absolutePath, "enlace sale")
        rejected(File(stage, "assets/imported/../imported/Inst.ogg").absolutePath, "traversal")
        rejected(File(stage, "assets/../../outside.ogg").absolutePath, "traversal")
        rejected("imported/Inst.ogg", "absoluta")
        val id = store.publishModImport(stage, document("audio" to asset(legitimate.absolutePath, "Inst.ogg"))) { }
        assertEquals(previous, store.activeId()); assertEquals("audio", File(assetAt(store.load(id).config, "audio").get("path").asString).readText())
    }

    @Test fun stageAndAssetsCannotBeSymlinksToOutside() {
        val store = aliasedStore()
        val stage = stage(store)
        File(stage, "assets").deleteRecursively()
        val outside = File(temp.root, "outside").apply { mkdirs() }
        Files.createSymbolicLink(File(stage, "assets").toPath(), outside.toPath())
        val error = assertThrows(IllegalArgumentException::class.java) {
            store.publishModImport(stage, document()) { }
        }
        assertTrue(error.message!!.contains("Carpeta de recursos temporal insegura"))
        assertTrue(outside.isDirectory)
    }
    @Test fun stageItselfCannotLinkOutsideTheProjectsRoot() {
        val store = aliasedStore(); val stage = store.newImportStage()
        assertTrue(stage.parentFile.mkdirs())
        val externalParent = File(temp.root, "external-stage-parent").apply { mkdirs() }
        val target = File(externalParent, stage.name).apply { mkdirs() }
        File(target, "assets").mkdirs()
        Files.createSymbolicLink(stage.toPath(), target.toPath())
        val error = assertThrows(IllegalArgumentException::class.java) { store.publishModImport(stage, document()) { } }
        assertTrue(error.message!!.contains("Carpeta temporal insegura"))
        assertTrue(target.isDirectory)
    }

}
