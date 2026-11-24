package com.fnfmodmaker.project

import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ProjectStoreTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun atomicFileRecoversLastValidVersionAfterCorruption() {
        val path = File(temp.root, "data.json")
        val safe = SafeFile(path)
        safe.write("{\"value\":1}")
        safe.write("{\"value\":2}")
        path.writeText("broken")
        val parse: (String) -> Int = { JsonParser.parseString(it).asJsonObject.get("value").asInt }
        assertEquals(1, safe.read(parse))
        safe.write("{\"value\":3}")
        assertEquals(3, safe.read(parse))
        path.writeText("broken again")
        assertEquals(1, safe.read(parse))
    }

    @Test fun projectVersionAndActiveIdAreValidated() {
        val store = ProjectStore(File(temp.root, "projects"), temp.root)
        val id = store.newId()
        val config = JsonParser.parseString("""{"modTitle":"Primero","songs":[]}""").asJsonObject
        store.save(id, config); store.setActive(id)
        assertEquals(id, store.activeId()); assertEquals("Primero", store.load(id).config.get("modTitle").asString)
        assertEquals("Primero", store.list().single().title)
        assertThrows(IllegalArgumentException::class.java) { store.load("../escape") }
        val path = File(temp.root, "projects/$id/project.json")
        path.writeText("""{"version":99,"id":"$id","config":{}}""")
        assertNotNull(store.list().single().problem)
    }

    @Test fun copiesOnlyOwnedAssetsAndPreservesUnknownFields() {
        val input = File(temp.root, "legacy.png").apply { writeText("original") }
        val store = ProjectStore(File(temp.root, "projects"), temp.root)
        val id = store.newId()
        val root = JsonParser.parseString("""{"character":{"sheet":{"uri":"content://x","displayName":"legacy.png","path":"${input.path}"},"frames":[{"image":{"uri":"content://x","displayName":"legacy.png","path":"${input.path}"}}]},"unknown":{"value":42},"icon":{"uri":"content://y","displayName":"lost.png","path":"${temp.root}/missing.png"}}""").asJsonObject
        val copy = store.copyAssets(root, id)
        val character = copy.config.getAsJsonObject("character")
        val newPath = character.getAsJsonObject("sheet").get("path").asString
        assertEquals("original", File(newPath).readText())
        assertNotEquals(input.path, newPath)
        assertEquals(newPath, character.getAsJsonArray("frames")[0].asJsonObject.getAsJsonObject("image").get("path").asString)
        assertEquals(listOf("lost.png"), copy.missing)
        assertEquals(42, copy.config.getAsJsonObject("unknown").get("value").asInt)
        assertEquals(input.path, root.getAsJsonObject("character").getAsJsonObject("sheet").get("path").asString)
    }
    @Test fun interruptedWriteRecoversBackupWhenPrimaryIsMissing() {
        val file = File(temp.root, "interrupted.json")
        val safe = SafeFile(file)
        safe.write("{\"value\":1}"); safe.write("{\"value\":2}")
        assertTrue(file.delete()) // Crash after moving the old primary but before publishing the new file.
        File(temp.root, "interrupted.json.tmp").writeText("incomplete")
        val parse: (String) -> Int = { JsonParser.parseString(it).asJsonObject.get("value").asInt }
        assertEquals(1, safe.read(parse))
        safe.write("{\"value\":3}")
        assertEquals(3, safe.read(parse))
    }

    @Test fun legacyPortableProjectKeepsCharacterVariationsAndNewStageCoordinates() {
        val store = ProjectStore(File(temp.root, "projects"), temp.root)
        val gson = com.google.gson.GsonBuilder().registerTypeAdapter(android.net.Uri::class.java,
            object : com.google.gson.TypeAdapter<android.net.Uri>() {
                override fun write(out: com.google.gson.stream.JsonWriter, value: android.net.Uri?) { out.nullValue() }
                override fun read(input: com.google.gson.stream.JsonReader): android.net.Uri? { input.skipValue(); return null }
            }.nullSafe()).create()
        val validator = ProjectConfigValidator(gson)
        val legacy = JsonParser.parseString("""{"modTitle":"Anterior","songs":[],"charts":{},"character":{"charId":"hero","variations":[{"variationId":"erect","displayName":"Erect"}],"animationSettings":{"idle":{"offsetX":"19"}}},"stageDraft":{"stageId":"test","cameraPosition":{"zoom":1.2}}}""").asJsonObject
        val id = store.newId(); store.save(id, legacy); store.setActive(id)
        val portable = PortableProject(temp.root); val archive = File(temp.root, "legacy.fnfproject")
        portable.export(store.load(id).config, store.assets(id), archive)
        val imported = store.publishImport(portable.prepare(archive, store.newImportStage()), portable, validator::validate)
        val old = gson.fromJson(store.load(imported).config, com.fnfmodmaker.model.ModConfig::class.java)
        assertEquals("erect", old.character.variations.single().variationId)
        assertEquals("19", old.character.settingsFor("idle").offsetX)
        assertEquals(989.5f, old.stageDraft.vSliceLayout.bf.x, 0f)
        val edited = old.copy(stageDraft = old.stageDraft.copy(vSliceLayout = old.stageDraft.vSliceLayout.copy(
            bf = old.stageDraft.vSliceLayout.bf.copy(x = 321f, cameraX = -55f))))
        store.save(imported, gson.toJsonTree(edited).asJsonObject)
        portable.export(store.load(imported).config, store.assets(imported), archive)
        val restored = store.publishImport(portable.prepare(archive, store.newImportStage()), portable, validator::validate)
        val reopened = gson.fromJson(store.load(restored).config, com.fnfmodmaker.model.ModConfig::class.java)
        assertEquals(321f, reopened.stageDraft.vSliceLayout.bf.x, 0f)
        assertEquals(-55f, reopened.stageDraft.vSliceLayout.bf.cameraX, 0f)
        assertEquals("erect", reopened.character.variations.single().variationId)
        assertEquals("19", reopened.character.settingsFor("idle").offsetX)
        assertEquals(id, store.activeId())
    }

}
