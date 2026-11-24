package com.fnfmodmaker.modimport

import android.net.Uri
import com.fnfmodmaker.mod.JsonBuilders
import com.fnfmodmaker.mod.ModBuilder
import com.fnfmodmaker.mod.AudioEncoder
import com.fnfmodmaker.mod.ModBuildServices
import com.fnfmodmaker.mod.mergeImportedResources
import com.fnfmodmaker.model.*
import com.fnfmodmaker.project.PortableProject
import com.fnfmodmaker.project.ProjectStore
import com.fnfmodmaker.project.ModImportSession
import com.google.gson.GsonBuilder
import com.google.gson.TypeAdapter
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonWriter
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class PsychImportCycleTest {
    @get:Rule val temp = TemporaryFolder()
    private val gson = GsonBuilder().serializeNulls().registerTypeAdapter(Uri::class.java, object : TypeAdapter<Uri>() {
        override fun write(out: JsonWriter, value: Uri?) { out.nullValue() }
        override fun read(input: JsonReader): Uri? { input.skipValue(); return null }
    }).create()
    private fun image(f: File, name: String): PickedImage = gson.fromJson("""{"displayName":${gson.toJson(name)},"path":${gson.toJson(f.absolutePath)},"uri":null}""", PickedImage::class.java)
    private fun audio(f: File, name: String, ms: Long): PickedAudio = gson.fromJson("""{"displayName":${gson.toJson(name)},"path":${gson.toJson(f.absolutePath)},"uri":null,"durationMs":$ms}""", PickedAudio::class.java)
    private fun fixture(allDifficulties: Boolean = false): ByteArray {
        val files = linkedMapOf(
            "pack.json" to """{"name":"Psych Fixture","description":"example"}""",
            "data/test/test.json" to """{"song":{"song":"test","format":"psych_v1","bpm":120,"speed":2,"player1":"bf","player2":"dad","stage":"stage","events":[],"notes":[{"mustHitSection":false,"sectionNotes":[[100,0,0],[500,6,200]]}]}}""",
            "songs/test/Inst.ogg" to "fake-audio-for-unit-test", "songs/test/Voices.ogg" to "fake-voices-for-unit-test",
            "characters/dad.json" to """{"image":"characters/dad","animations":[{"anim":"idle","name":"idle","fps":24,"loop":false,"offsets":[0,0]}]}""",
            "images/characters/dad.png" to "fake-png-for-unit-test", "images/characters/dad.xml" to """<TextureAtlas imagePath="dad.png"><SubTexture name="idle0000" x="0" y="0" width="1" height="1"/></TextureAtlas>""",
            "scripts/custom.lua" to "print('hello')", "unknown/config.json" to "{\"opaque\":true}")
        if (allDifficulties) {
            files["data/test/test-easy.json"] = files.getValue("data/test/test.json")
            files["data/test/test-hard.json"] = files.getValue("data/test/test.json")
        }
        val realFiles = mapOf(
            "songs/test/Inst.ogg" to "/psych-fixture/Inst.ogg",
            "songs/test/Voices.ogg" to "/psych-fixture/Voices.ogg",
            "images/characters/dad.png" to "/psych-fixture/dad.png"
        )
        val bytes = ByteArrayOutputStream()
        ZipOutputStream(bytes).use { zip -> files.forEach { (path, contents) ->
            zip.putNextEntry(ZipEntry("wrapper/$path"))
            val bytesToWrite = realFiles[path]?.let { javaClass.getResourceAsStream(it)!!.use { source -> source.readBytes() } } ?: contents.toByteArray()
            zip.write(bytesToWrite); zip.closeEntry()
        } }
        return bytes.toByteArray()
    }
    @Test fun confirmationAlsoOpensPsychProject() {
        val real = File(temp.root, "real").apply { mkdirs() }
        val alias = File(temp.root, "android-data-user-alias")
        java.nio.file.Files.createSymbolicLink(alias.toPath(), real.toPath())
        val store = ProjectStore(File(alias, "projects"), temp.root)
        val old = gson.toJsonTree(ModConfig(modTitle = "Current")).asJsonObject
        val active = store.newId(); store.save(active, old); store.setActive(active)
        val stage = store.newImportStage()
        val prepared = ModZipInspector(File(temp.root, "cache")).withExtracted(fixture().inputStream()) { dir, names ->
            PsychModImporter({ 1 to 1 }, { 10_000 }, ::audio, ::image).prepare(dir, names, stage)
        }
        val session = ModImportSession(stage, gson.toJsonTree(prepared.config).asJsonObject)
        val audio = File(prepared.config.firstSong.instrumental!!.path)
        val bytes = audio.readBytes(); assertTrue(audio.delete())
        assertThrows(IllegalArgumentException::class.java) {
            session.publishAndOpen(store, active, old, {}) { gson.fromJson(it, ModConfig::class.java) }
        }
        assertEquals(active, store.activeId()); assertEquals("Current", store.load(active).config.get("modTitle").asString)
        assertTrue(stage.isDirectory); audio.writeBytes(bytes)
        val opened = session.publishAndOpen(store, active, old, {}) { gson.fromJson(it, ModConfig::class.java) }
        assertEquals(opened.id, store.activeId())
        assertEquals(TargetEngine.PSYCH, opened.config.engine)
        assertEquals(2, opened.config.getChart("test", "normal")!!.notes.size)
        assertTrue(File(opened.config.firstSong.instrumental!!.path).isFile)
        assertTrue(File(opened.config.character.atlasSheet!!.path).isFile)
        assertTrue(File(opened.config.character.atlasXml!!.path).isFile)
        assertEquals(File(opened.config.firstSong.instrumental!!.path).canonicalPath, opened.config.firstSong.instrumental!!.path)
        assertEquals("Current", store.load(active).config.get("modTitle").asString)
    }
    @Test fun psychCharacterAndStageBindingRemainUnchanged() {
        val store = ProjectStore(File(temp.root, "projects"), temp.root)
        val stage = store.newImportStage()
        val prepared = ModZipInspector(File(temp.root, "cache")).withExtracted(fixture().inputStream()) { dir, names ->
            PsychModImporter({ 1 to 1 }, { 10_000 }, ::audio, ::image).prepare(dir, names, stage)
        }
        val config = prepared.config.copy(character = prepared.config.character.copy(charId = "New_Enemy")).copy(
            stageDraft = StageDraft(enabled = true, stageId = "Test Stage", backgroundImage = prepared.config.character.atlasSheet))
        val effective = config.effectiveExportConfig()
        assertEquals("new-enemy", effective.firstSong.opponent)
        assertEquals("fnfmm-test-stage", effective.firstSong.stagePsych)
        val services = ModBuildServices(object : AudioEncoder {
            override fun convert(input: File, output: File): String? = error("OGG should be copied")
            override fun mix(first: File, second: File, output: File): String? = error("Only one voice")
        }, { root, cfg, _, _ ->
            File(root, "characters/${cfg.character.charFolder}.json").apply { parentFile.mkdirs(); writeText("{}") }
        }, { _, _, _, _ -> }, { _, _ -> true }, { _, _, _ -> true })
        val built = ModBuilder.buildInternal(config, File(temp.root, "build"), temp.root, {}, services)
        val json = org.json.JSONObject(File(built.rootDir, "data/test/test.json").readText())
        assertEquals("new-enemy", json.getString("player2"))
        assertEquals("fnfmm-test-stage", json.getString("stage"))
    }
    @Test fun deletedPsychDifficultyDoesNotReappearInZip() {
        val store = ProjectStore(File(temp.root, "psych-free"), temp.root)
        val prepared = ModZipInspector(File(temp.root, "cache-free")).withExtracted(fixture(true).inputStream()) { dir, names ->
            PsychModImporter({ 1 to 1 }, { 10_000 }, ::audio, ::image).prepare(dir, names, store.newImportStage())
        }
        val modified = com.fnfmodmaker.model.removeSongDifficulty(prepared.config, 0, "easy")
        val id = store.publishModImport(prepared.stage, gson.toJsonTree(modified).asJsonObject) { }
        val reopened = gson.fromJson(store.load(id).config, ModConfig::class.java)
        val services = ModBuildServices(object : AudioEncoder {
            override fun convert(input: File, output: File): String? = error("OGG no requiere conversión")
            override fun mix(first: File, second: File, output: File): String? = error("Una voz")
        }, { _, _, _, _ -> }, { _, _, _, _ -> }, { _, _ -> true }, { _, _, _ -> true })
        val built = ModBuilder.buildInternal(reopened, File(temp.root, "psych-output"), temp.root, {}, services)
        assertFalse(File(built.rootDir, "data/test/test-easy.json").exists())
        assertTrue(File(built.rootDir, "data/test/test.json").isFile)
        assertTrue(File(built.rootDir, "data/test/test-hard.json").isFile)
    }

    @Test fun extractedPsychProjectCanBeSavedReopenedPackagedAndOpaqueAssetsSurvive() {
        val real = File(temp.root, "real").apply { mkdirs() }
        val alias = File(temp.root, "android-data-user-alias")
        java.nio.file.Files.createSymbolicLink(alias.toPath(), real.toPath())
        val store = ProjectStore(File(alias, "projects"), temp.root)
        val active = store.newId(); store.save(active, gson.toJsonTree(ModConfig(modTitle = "Current")).asJsonObject); store.setActive(active)
        val stage = store.newImportStage()
        val importer = PsychModImporter({ 1 to 1 }, { 10_000 }, ::audio, ::image)
        val prepared = ModZipInspector(File(temp.root, "cache")).withExtracted(fixture().inputStream()) { dir, names -> importer.prepare(dir, names, stage) }
        assertEquals(active, store.activeId()); assertEquals(1, prepared.config.songs.size)
        assertTrue(prepared.config.character.enabled); assertEquals(2, prepared.config.getChart("test", "normal")!!.notes.size)
        val id = store.publishModImport(stage, gson.toJsonTree(prepared.config).asJsonObject) { }
        assertEquals(active, store.activeId()); assertEquals("Current", store.load(active).config.get("modTitle").asString)
        var reopened = gson.fromJson(store.load(id).config, ModConfig::class.java)
        assertTrue(File(reopened.firstSong.instrumental!!.path).isFile)
        val edited = reopened.getChart("test", "normal")!!.copy(notes = listOf(ChartNote(1700, 1, 250)))
        reopened = reopened.withChart("test", "normal", edited)
        store.save(id, gson.toJsonTree(reopened).asJsonObject)
        reopened = gson.fromJson(store.load(id).config, ModConfig::class.java)
        assertEquals(edited.notes, reopened.getChart("test", "normal")!!.notes)
        val portable = PortableProject(temp.root)
        val backup = File(temp.root, "backup.fnfproject")
        portable.export(store.load(id).config, store.assets(id), backup)
        val preparedPortable = portable.prepare(backup, store.newImportStage())
        val restoredId = store.publishImport(preparedPortable, portable) { }
        val restored = gson.fromJson(store.load(restoredId).config, ModConfig::class.java)
        assertTrue(File(restored.firstSong.instrumental!!.path).isFile)
        assertEquals(edited.notes, restored.getChart("test", "normal")!!.notes)
        // Exercise the actual merge and ZIP code. Android-only bitmap/FFmpeg builder requires device tests.
        val output = File(temp.root, "built").apply { mkdirs() }
        File(output, "data/test/test.json").apply { parentFile.mkdirs(); writeText(JsonBuilders.psychChart(restored, restored.firstSong, 10_000, "normal")) }
        mergeImportedResources(output, restored.importedModResources, temp.root)
        val zipped = ByteArrayOutputStream(); ModBuilder.zip(output, zipped, false)
        val entries = mutableMapOf<String, ByteArray>()
        java.util.zip.ZipInputStream(zipped.toByteArray().inputStream()).use { zip -> while (true) { val e = zip.nextEntry ?: break; entries[e.name] = zip.readBytes() } }
        assertEquals("print('hello')", entries.getValue("scripts/custom.lua").toString(Charsets.UTF_8))
        assertEquals("{\"opaque\":true}", entries.getValue("unknown/config.json").toString(Charsets.UTF_8))
        assertArrayEquals(javaClass.getResourceAsStream("/psych-fixture/Inst.ogg")!!.use { it.readBytes() }, entries.getValue("songs/test/Inst.ogg"))
        assertArrayEquals(javaClass.getResourceAsStream("/psych-fixture/Voices.ogg")!!.use { it.readBytes() }, entries.getValue("songs/test/Voices.ogg"))
        assertArrayEquals(javaClass.getResourceAsStream("/psych-fixture/dad.png")!!.use { it.readBytes() }, entries.getValue("images/characters/dad.png"))
        assertEquals(edited.notes, readPsychChart(entries.getValue("data/test/test.json").toString(Charsets.UTF_8), "test", "normal").notes)
    }
}
