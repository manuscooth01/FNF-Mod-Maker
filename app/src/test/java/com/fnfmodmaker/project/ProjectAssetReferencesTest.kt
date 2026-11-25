package com.fnfmodmaker.project

import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipFile

class ProjectAssetReferencesTest {
    @get:Rule val temp = TemporaryFolder()
    private fun ref(path: String) = """{"path":${com.google.gson.Gson().toJson(path)},"displayName":"media","uri":"content://media"}"""

    @Test fun portableAndCopyRecognizeOnlyModelLocationsAndKeepUnknownJson() {
        val store = ProjectStore(File(temp.root, "projects"), temp.root)
        val id = store.newId(); val assets = store.assets(id)
        val image = File(assets, "hero.png").apply { writeText("image") }
        val audio = File(assets, "inst.ogg").apply { writeText("audio") }
        val fake = File(temp.root, "not-an-asset.ogg").apply { writeText("not copied") }
        val custom = JsonParser.parseString("""{"a":{"path":"../relative"},"b":{"path":"x","displayName":"a"},"c":${ref(fake.path)},"nested":[{"image":${ref(fake.path)},"instrumental":${ref(fake.path)}}],"psych":{"sectionNotes":[[1,2,3]],"path":"charts/special.json"},"vs":{"events":[{"t":100.125,"v":{"path":"../not-resource"}}]}}""").asJsonObject
        val config = JsonParser.parseString("""{"modTitle":"Prueba","songs":[{"songName":"Test","instrumental":${ref(audio.path)},"custom":${custom}}],"charts":{"test":{"normal":{"unknown":${custom}}}},"character":{"sheet":${ref(image.path)},"variations":[{"sheet":${ref(image.path)}}],"animationFrames":{"idle":[{"image":${ref(image.path)},"width":1,"height":1}]}},"stageDraft":{"sprites":[{"image":${ref(image.path)}}]},"importedModResources":[{"asset":${ref(image.path)}}],"custom":${custom}}""").asJsonObject
        val events = """[{"t":100.125,"e":"FocusCamera","v":{"path":"relative","displayName":"data","uri":"not-a-resource"},"custom":[{"path":"x"}]}]"""
        val psychExtra = """{"custom":{"path":"chart/path","displayName":"not a file","uri":"unknown"}}"""
        config.getAsJsonArray("songs")[0].asJsonObject.addProperty("vSliceEventsJson", events)
        val chart = config.getAsJsonObject("charts").getAsJsonObject("test").getAsJsonObject("normal")
        chart.addProperty("psychSongExtras", psychExtra)
        chart.add("psychLayout", com.google.gson.Gson().toJsonTree(listOf(com.fnfmodmaker.model.PsychSectionData(sourceJson = """{"future":{"path":"section/path"}}"""))))
        val validator = ProjectConfigValidator(com.google.gson.GsonBuilder().registerTypeAdapter(android.net.Uri::class.java,
            object : com.google.gson.TypeAdapter<android.net.Uri>() {
                override fun write(out: com.google.gson.stream.JsonWriter, value: android.net.Uri?) { out.nullValue() }
                override fun read(input: com.google.gson.stream.JsonReader): android.net.Uri? { input.skipValue(); return null }
            }.nullSafe()).create())
        validator.validate(config)
        store.save(id, config); store.setActive(id)
        val original = config.deepCopy()
        val portable = PortableProject(temp.root); val archive = File(temp.root, "test.fnfproject")
        portable.export(config, assets, archive)
        assertEquals(original, config)
        ZipFile(archive).use { zip ->
            val entries = zip.entries().asSequence().map { it.name }.toSet()
            assertEquals(setOf("project.json", "manifest.json", "assets/hero.png", "assets/inst.ogg"), entries)
            val packaged = JsonParser.parseString(zip.getInputStream(zip.getEntry("project.json")).reader().readText()).asJsonObject
            assertEquals(custom.toString(), packaged.getAsJsonObject("custom").toString())
            assertEquals(custom.toString(), packaged.getAsJsonArray("songs")[0].asJsonObject.getAsJsonObject("custom").toString())
            assertEquals(custom.toString(), packaged.getAsJsonObject("charts").getAsJsonObject("test").getAsJsonObject("normal").getAsJsonObject("unknown").toString())
        }
        val prepared = portable.prepare(archive, store.newImportStage())
        val imported = store.publishImport(prepared, portable, validator::validate)
        val restored = store.load(imported).config
        assertEquals(events, restored.getAsJsonArray("songs")[0].asJsonObject.get("vSliceEventsJson").asString)
        assertEquals(psychExtra, restored.getAsJsonObject("charts").getAsJsonObject("test").getAsJsonObject("normal").get("psychSongExtras").asString)
        assertEquals(custom.toString(), restored.getAsJsonObject("custom").toString())
        assertEquals(custom.toString(), restored.getAsJsonArray("songs")[0].asJsonObject.getAsJsonObject("custom").toString())
        val restoredAudio = File(restored.getAsJsonArray("songs")[0].asJsonObject.getAsJsonObject("instrumental").get("path").asString)
        assertEquals("audio", restoredAudio.readText())
        val restoredImage = File(restored.getAsJsonObject("character").getAsJsonObject("sheet").get("path").asString)
        assertEquals("image", restoredImage.readText())
        val second = File(temp.root, "again.fnfproject")
        portable.export(restored, store.assets(imported), second)
        val again = portable.prepare(second, store.newImportStage())
        assertEquals(prepared.config, again.config)
        store.discardImportStage(again.directory)
        val duplicate = store.copyAssets(config, store.newId())
        assertEquals(custom.toString(), duplicate.config.getAsJsonObject("custom").toString())
        assertEquals(custom.toString(), duplicate.config.getAsJsonArray("songs")[0].asJsonObject.getAsJsonObject("custom").toString())
        assertEquals("audio", File(duplicate.config.getAsJsonArray("songs")[0].asJsonObject.getAsJsonObject("instrumental").get("path").asString).readText())
        assertEquals("image", File(duplicate.config.getAsJsonObject("character").getAsJsonObject("sheet").get("path").asString).readText())
        assertEquals(id, store.activeId()); assertEquals(original, store.load(id).config)
    }

    @Test fun malformedOrMissingKnownAssetsDoNotChangeExistingProject() {
        val store = ProjectStore(File(temp.root, "projects"), temp.root); val id = store.newId()
        val original = JsonParser.parseString("""{"modTitle":"Original","songs":[]}""").asJsonObject
        store.save(id, original); store.setActive(id)
        val badValues = listOf("{}", "{\"path\":\"../outside\",\"displayName\":\"x\"}", ref(File(temp.root, "missing").path), "false")
        for (bad in badValues) {
            val config = JsonParser.parseString("""{"songs":[{"instrumental":$bad}]}""").asJsonObject
            val dest = store.newId()
            assertThrows(Exception::class.java) { store.copyAssets(config, dest) }
            assertFalse(File(temp.root, "projects/$dest").exists())
            assertThrows(Exception::class.java) { PortableProject(temp.root).export(config, store.assets(id), File(temp.root, "bad.fnfproject")) }
            assertEquals(id, store.activeId()); assertEquals(original, store.load(id).config)
        }
    }
}
