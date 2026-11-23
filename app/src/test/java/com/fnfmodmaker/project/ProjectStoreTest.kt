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
}
