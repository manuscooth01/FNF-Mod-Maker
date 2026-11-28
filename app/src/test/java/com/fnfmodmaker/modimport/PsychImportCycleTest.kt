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
    private fun fixture(allDifficulties: Boolean = false, withoutNormal: Boolean = false, multiCharacter: Boolean = false, withWeek: Boolean = false, withInsane: Boolean = false, official073: Boolean = false, withEvents: Boolean = false): ByteArray {
        val files = linkedMapOf(
            "pack.json" to """{"name":"Psych Fixture","description":"example"}""",
            "data/test/test.json" to """{"song":{"song":"test","format":"psych_v1","bpm":120,"speed":2,"player1":"bf","player2":"dad","stage":"stage","events":[],"notes":[{"mustHitSection":false,"sectionNotes":[[100,0,0],[500,6,200]]}]}}""",
            "songs/test/Inst.ogg" to "fake-audio-for-unit-test", "songs/test/Voices.ogg" to "fake-voices-for-unit-test",
            "characters/dad.json" to """{"image":"characters/dad","animations":[{"anim":"idle","name":"idle","fps":24,"loop":false,"offsets":[0,0]}]}""",
            "images/characters/dad.png" to "fake-png-for-unit-test", "images/characters/dad.xml" to """<TextureAtlas imagePath="dad.png"><SubTexture name="idle0000" x="0" y="0" width="1" height="1"/></TextureAtlas>""",
            "scripts/custom.lua" to "print('hello')", "unknown/config.json" to "{\"opaque\":true}")
        if (withEvents) files["data/test/test.json"] = files.getValue("data/test/test.json").replace("\"events\":[]", "\"events\":[[100,[[\"Hey!\",\"0\",\"\"]]]]")
        if (official073) files["data/test/test.json"] = javaClass.getResourceAsStream("/psych-official-073/bopeebo.json")!!.bufferedReader().use { it.readText() }
        if (allDifficulties) {
            files["data/test/test-easy.json"] = files.getValue("data/test/test.json")
            files["data/test/test-hard.json"] = files.getValue("data/test/test.json")
        }
        if (withInsane) files["data/test/test-insane.json"] = files.getValue("data/test/test.json")
        if (withoutNormal) files.remove("data/test/test.json")
        if (withWeek) files["weeks/story.json"] = """{"songs":[["test","dad",[1,2,3]],["fresh","dad",[1,2,3]]],"unknown":true}"""
        if (multiCharacter) {
            files["characters/metza.json"] = """{"image":"characters/metza","scale":1,"unknown":123,"animations":[{"anim":"idle","name":"idle","fps":24,"loop":false,"indices":[0],"extra":true,"offsets":[0,0]}]}"""
            files["images/characters/metza.png"] = "png"
            files["images/characters/metza.xml"] = files.getValue("images/characters/dad.xml")
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
    @Test fun officialPsych073ChartImportsPersistsAndExportsLegacyStructure() {
        val store = ProjectStore(File(temp.root, "official-073"), temp.root)
        val prepared = ModZipInspector(File(temp.root, "official-073-cache")).withExtracted(fixture(official073 = true).inputStream()) { dir, names ->
            PsychModImporter({ 1 to 1 }, { 10_000 }, ::audio, ::image).prepare(dir, names, store.newImportStage())
        }
        val original = prepared.config.getChart("test", "normal")!!
        assertTrue(original.notes.size > 50)
        val changed = prepared.config.copy(psychChartFormat = PsychChartFormat.PSYCH_073)
        val id = store.publishModImport(prepared.stage, gson.toJsonTree(changed).asJsonObject) { }
        val reopened = gson.fromJson(store.load(id).config, ModConfig::class.java)
        assertEquals(PsychChartFormat.PSYCH_073, reopened.psychChartFormat)
        val services = ModBuildServices(object : AudioEncoder {
            override fun convert(input: File, output: File): String? = error("OGG")
            override fun mix(first: File, second: File, output: File): String? = error("Una voz")
        }, { root, cfg, _, _ -> File(root, "characters/${cfg.character.charFolder}.json").apply { parentFile.mkdirs(); writeText("{}") } },
            { _, _, _, _ -> }, { _, _ -> true }, { _, _, _ -> true })
        val built = ModBuilder.buildInternal(reopened, File(temp.root, "official-073-build"), temp.root, {}, services)
        val out = ByteArrayOutputStream(); ModBuilder.zip(built.rootDir, out, false)
        val files = mutableMapOf<String, ByteArray>()
        java.util.zip.ZipInputStream(out.toByteArray().inputStream()).use { z -> while (true) { val e = z.nextEntry ?: break; files[e.name] = z.readBytes() } }
        val json = org.json.JSONObject(files.getValue("data/test/test.json").toString(Charsets.UTF_8))
        val body = json.getJSONObject("song")
        assertFalse(body.has("format")); assertTrue(body.getJSONArray("notes").length() > 1)
        val reimport = readPsychChart(json.toString(), "test", "normal")
        val order = compareBy<ChartNote> { it.timeMs }.thenBy { it.lane }.thenBy { it.lengthMs }
        assertEquals(original.notes.sortedWith(order), reimport.notes.sortedWith(order))
        assertTrue(files.containsKey("songs/test/Inst.ogg"))
    }

    @Test fun psychEventsImportEditPortableRestoreAndExportBothFormats() {
        val store = ProjectStore(File(temp.root, "psych-events"), temp.root)
        val prepared = ModZipInspector(File(temp.root, "psych-events-cache")).withExtracted(fixture(withEvents = true).inputStream()) { dir, names ->
            PsychModImporter({ 1 to 1 }, { 10_000 }, ::audio, ::image).prepare(dir, names, store.newImportStage())
        }
        val chart = prepared.config.getChart("test", "normal")!!
        assertEquals("""[[100,[["Hey!","0",""]]]]""", chart.psychEventsJson)
        val editedEvents = """[[200,[["Hey!","1","0.5"],["custom","v1","v2"]]]]"""
        val edited = prepared.config.withChart("test", "normal", chart.copy(psychEventsJson = editedEvents))
        val id = store.publishModImport(prepared.stage, gson.toJsonTree(edited).asJsonObject) { }
        val portable = PortableProject(temp.root); val backup = File(temp.root, "psych-events.fnfproject")
        portable.export(store.load(id).config, store.assets(id), backup)
        val restored = store.publishImport(portable.prepare(backup, store.newImportStage()), portable) { }
        val reopened = gson.fromJson(store.load(restored).config, ModConfig::class.java)
        assertEquals(editedEvents, reopened.getChart("test", "normal")!!.psychEventsJson)
        val services = ModBuildServices(object : AudioEncoder {
            override fun convert(input: File, output: File): String? = error("OGG")
            override fun mix(first: File, second: File, output: File): String? = error("Una voz")
        }, { root, cfg, _, _ -> File(root, "characters/${cfg.character.charFolder}.json").apply { parentFile.mkdirs(); writeText("{}") } },
            { _, _, _, _ -> }, { _, _ -> true }, { _, _, _ -> true })
        for (format in PsychChartFormat.entries) {
            val config = reopened.copy(psychChartFormat = format)
            val built = ModBuilder.buildInternal(config, File(temp.root, "psych-events-${format.name}"), temp.root, {}, services)
            val output = ByteArrayOutputStream(); ModBuilder.zip(built.rootDir, output, false)
            val files = mutableMapOf<String, ByteArray>()
            java.util.zip.ZipInputStream(output.toByteArray().inputStream()).use { z -> while (true) { val e = z.nextEntry ?: break; files[e.name] = z.readBytes() } }
            val reimport = readPsychChart(files.getValue("data/test/test.json").toString(Charsets.UTF_8), "test", "normal")
            assertEquals(editedEvents, reimport.psychEventsJson)
            assertEquals(chart.notes.sortedBy { it.timeMs }, reimport.notes.sortedBy { it.timeMs })
        }
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
            session.publishAndOpen(store, active, old, requireNotNull(store.load(active).version), {}, {}) { gson.fromJson(it, ModConfig::class.java) }
        }
        assertEquals(active, store.activeId()); assertEquals("Current", store.load(active).config.get("modTitle").asString)
        assertTrue(stage.isDirectory); audio.writeBytes(bytes)
        val opened = session.publishAndOpen(store, active, old, requireNotNull(store.load(active).version), {}, {}) { gson.fromJson(it, ModConfig::class.java) }
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
    @Test fun importsAndExportsMultiplePsychCharactersWithoutLosingUnknownFields() {
        val store = ProjectStore(File(temp.root, "psych-multi"), temp.root)
        val prepared = ModZipInspector(File(temp.root, "cache-psych-multi")).withExtracted(fixture(multiCharacter = true).inputStream()) { dir, names ->
            PsychModImporter({ 1 to 1 }, { 10_000 }, ::audio, ::image).prepare(dir, names, store.newImportStage())
        }
        assertEquals(setOf("dad", "metza"), prepared.config.characterLibrary.map { it.draft.charFolder }.toSet())
        val entry = prepared.config.characterLibrary.single { it.draft.charFolder == "metza" }
        val changed = prepared.config.copy(characterLibrary = prepared.config.characterLibrary.map {
            if (it.id == entry.id) it.copy(draft = it.draft.copy(scale = "1.5")) else it
        }, characterAssignments = CharacterAssignments(opponent = entry.id))
        val id = store.publishModImport(prepared.stage, gson.toJsonTree(changed).asJsonObject) { }
        val reopened = gson.fromJson(store.load(id).config, ModConfig::class.java)
        val services = ModBuildServices(object : AudioEncoder {
            override fun convert(input: File, output: File): String? = error("OGG")
            override fun mix(first: File, second: File, output: File): String? = error("Una voz")
        }, { root, cfg, _, _ ->
            val draft = cfg.character
            File(root, "characters/${draft.charFolder}.json").apply { parentFile.mkdirs(); writeText("""{"image":"characters/${draft.charFolder}","scale":${draft.scale},"animations":[{"anim":"idle","name":"idle","fps":24,"loop":false,"indices":[],"offsets":[0,0]}]}""") }
            File(root, "images/characters/${draft.charFolder}.png").apply { parentFile.mkdirs(); File(draft.atlasSheet!!.path).copyTo(this) }
            File(root, "images/characters/${draft.charFolder}.xml").apply { File(draft.atlasXml!!.path).copyTo(this) }
        }, { _, _, _, _ -> }, { _, _ -> true }, { _, _, _ -> true })
        val built = ModBuilder.buildInternal(reopened, File(temp.root, "psych-multi-output"), temp.root, {}, services)
        val output = ByteArrayOutputStream(); ModBuilder.zip(built.rootDir, output, false)
        val files = mutableMapOf<String, ByteArray>()
        java.util.zip.ZipInputStream(output.toByteArray().inputStream()).use { z -> while (true) { val e = z.nextEntry ?: break; files[e.name] = z.readBytes() } }
        listOf("dad", "metza").forEach { name -> listOf("characters/$name.json", "images/characters/$name.png", "images/characters/$name.xml").forEach { assertTrue(it, files.containsKey(it)) } }
        val chart = org.json.JSONObject(files.getValue("data/test/test.json").toString(Charsets.UTF_8))
        assertEquals("metza", chart.getString("player2"))
        val character = org.json.JSONObject(files.getValue("characters/metza.json").toString(Charsets.UTF_8))
        assertEquals(1.5, character.getDouble("scale"), 0.0); assertEquals(123, character.getInt("unknown"))
        assertEquals(0, character.getJSONArray("animations").getJSONObject(0).getJSONArray("indices").getInt(0))
    }

    @Test fun psychCharacterRenameAndRemovalReachChartAndZip() {
        val store = ProjectStore(File(temp.root, "psych-character-rename"), temp.root)
        val prepared = ModZipInspector(File(temp.root, "psych-character-cache")).withExtracted(fixture().inputStream()) { dir, names ->
            PsychModImporter({ 1 to 1 }, { 10_000 }, ::audio, ::image).prepare(dir, names, store.newImportStage())
        }
        val changed = renameImportedCharacter(prepared.config, prepared.config.selectedCharacterId, "metza")
        val id = store.publishModImport(prepared.stage, gson.toJsonTree(changed).asJsonObject) { }
        var reopened = gson.fromJson(store.load(id).config, ModConfig::class.java)
        val services = ModBuildServices(object : AudioEncoder {
            override fun convert(input: File, output: File): String? = error("OGG")
            override fun mix(first: File, second: File, output: File): String? = error("Una voz")
        }, { root, cfg, _, _ ->
            val c = cfg.character
            File(root, "characters/${c.charFolder}.json").apply { parentFile.mkdirs(); writeText("""{"image":"characters/${c.charFolder}","scale":1,"animations":[{"anim":"idle","name":"idle","fps":24}]}""") }
            File(root, "images/characters/${c.charFolder}.png").apply { parentFile.mkdirs(); File(c.atlasSheet!!.path).copyTo(this) }
            File(root, "images/characters/${c.charFolder}.xml").apply { File(c.atlasXml!!.path).copyTo(this) }
        }, { _, _, _, _ -> }, { _, _ -> true }, { _, _, _ -> true })
        var built = ModBuilder.buildInternal(reopened, File(temp.root, "psych-character-renamed"), temp.root, {}, services)
        assertTrue(File(built.rootDir, "characters/metza.json").isFile)
        assertFalse(File(built.rootDir, "characters/dad.json").exists())
        assertEquals("metza", org.json.JSONObject(File(built.rootDir, "data/test/test.json").readText()).getString("player2"))
        reopened = removeLibraryCharacter(reopened, reopened.selectedCharacterId)
        store.save(id, gson.toJsonTree(reopened).asJsonObject)
        reopened = gson.fromJson(store.load(id).config, ModConfig::class.java)
        built = ModBuilder.buildInternal(reopened, File(temp.root, "psych-character-removed"), temp.root, {}, services)
        assertFalse(File(built.rootDir, "characters/dad.json").exists())
        assertFalse(File(built.rootDir, "characters/metza.json").exists())
        assertEquals("dad", org.json.JSONObject(File(built.rootDir, "data/test/test.json").readText()).getString("player2"))
        assertTrue(File(reopened.importedModResources.single { it.originalPath == "characters/dad.json" }.asset!!.path).isFile)
    }

    @Test fun psychRenameUpdatesChartAudioAndWeekReferences() {
        val store = ProjectStore(File(temp.root, "psych-rename"), temp.root)
        val prepared = ModZipInspector(File(temp.root, "psych-rename-cache")).withExtracted(fixture(withWeek = true).inputStream()) { dir, names ->
            PsychModImporter({ 1 to 1 }, { 10_000 }, ::audio, ::image).prepare(dir, names, store.newImportStage())
        }
        val renamed = renameSong(prepared.config, 0, "Nueva")
        val id = store.publishModImport(prepared.stage, gson.toJsonTree(renamed).asJsonObject) { }
        val reopened = gson.fromJson(store.load(id).config, ModConfig::class.java)
        val services = ModBuildServices(object : AudioEncoder {
            override fun convert(input: File, output: File): String? = error("OGG")
            override fun mix(first: File, second: File, output: File): String? = error("Una voz")
        }, { root, cfg, _, _ -> File(root, "characters/${cfg.character.charFolder}.json").apply { parentFile.mkdirs(); writeText("{}") } },
            { _, _, _, _ -> }, { _, _ -> true }, { _, _, _ -> true })
        val built = ModBuilder.buildInternal(reopened, File(temp.root, "psych-rename-output"), temp.root, {}, services)
        val output = ByteArrayOutputStream(); ModBuilder.zip(built.rootDir, output, false)
        val files = mutableMapOf<String, ByteArray>()
        java.util.zip.ZipInputStream(output.toByteArray().inputStream()).use { z -> while (true) { val e = z.nextEntry ?: break; files[e.name] = z.readBytes() } }
        assertTrue(files.keys.none { it.startsWith("data/test/") || it.startsWith("songs/test/") })
        listOf("data/nueva/nueva.json", "songs/nueva/Inst.ogg", "songs/nueva/Voices.ogg").forEach { assertTrue(it, it in files) }
        val week = org.json.JSONObject(files.getValue("weeks/story.json").toString(Charsets.UTF_8))
        assertEquals("Nueva", week.getJSONArray("songs").getJSONArray(0).getString(0)); assertTrue(week.getBoolean("unknown"))
    }

    @Test fun deletedPsychSongIsRemovedFromWeekAndZipButOriginalRemains() {
        val store = ProjectStore(File(temp.root, "psych-removed-song"), temp.root)
        val prepared = ModZipInspector(File(temp.root, "psych-removed-cache")).withExtracted(fixture(withWeek = true).inputStream()) { dir, names ->
            PsychModImporter({ 1 to 1 }, { 10_000 }, ::audio, ::image).prepare(dir, names, store.newImportStage())
        }
        val other = prepared.config.firstSong.copy(songName = "fresh", importedPsychFolder = "")
        val removed = removeSong(prepared.config.copy(songs = prepared.config.songs + other), 0)
        val id = store.publishModImport(prepared.stage, gson.toJsonTree(removed).asJsonObject) { }
        val reopened = gson.fromJson(store.load(id).config, ModConfig::class.java)
        val services = ModBuildServices(object : AudioEncoder {
            override fun convert(input: File, output: File): String? = error("OGG")
            override fun mix(first: File, second: File, output: File): String? = error("Una voz")
        }, { root, cfg, _, _ -> File(root, "characters/${cfg.character.charFolder}.json").apply { parentFile.mkdirs(); writeText("{}") } },
            { _, _, _, _ -> }, { _, _ -> true }, { _, _, _ -> true })
        val built = ModBuilder.buildInternal(reopened, File(temp.root, "psych-removed-output"), temp.root, {}, services)
        val output = ByteArrayOutputStream(); ModBuilder.zip(built.rootDir, output, false)
        val files = mutableMapOf<String, ByteArray>()
        java.util.zip.ZipInputStream(output.toByteArray().inputStream()).use { z -> while (true) { val e = z.nextEntry ?: break; files[e.name] = z.readBytes() } }
        assertTrue(files.keys.none { it.startsWith("data/test/") || it.startsWith("songs/test/") })
        assertTrue(files.containsKey("data/fresh/fresh.json"))
        val week = org.json.JSONObject(files.getValue("weeks/story.json").toString(Charsets.UTF_8))
        assertEquals(1, week.getJSONArray("songs").length()); assertEquals("fresh", week.getJSONArray("songs").getJSONArray(0).getString(0)); assertTrue(week.getBoolean("unknown"))
        assertTrue(File(reopened.importedModResources.single { it.originalPath == "data/test/test.json" }.asset!!.path).isFile)
    }

    @Test fun psychDuplicatedDifficultyPersistsAndExportsNotesAndEvents() {
        val store = ProjectStore(File(temp.root, "psych-copy"), temp.root)
        val prepared = ModZipInspector(File(temp.root, "psych-copy-cache")).withExtracted(fixture(withEvents = true).inputStream()) { dir, names ->
            PsychModImporter({ 1 to 1 }, { 10_000 }, ::audio, ::image).prepare(dir, names, store.newImportStage())
        }
        val copied = duplicateSongDifficulty(prepared.config, 0, "normal", "insane")
        assertThrows(IllegalArgumentException::class.java) { duplicateSongDifficulty(copied, 0, "normal", "insane") }
        val id = store.publishModImport(prepared.stage, gson.toJsonTree(copied).asJsonObject) { }
        val reopened = gson.fromJson(store.load(id).config, ModConfig::class.java)
        assertEquals(reopened.getChart("test", "normal")!!.notes, reopened.getChart("test", "insane")!!.notes)
        val services = ModBuildServices(object : AudioEncoder {
            override fun convert(input: File, output: File): String? = error("OGG")
            override fun mix(first: File, second: File, output: File): String? = error("Una voz")
        }, { root, cfg, _, _ -> File(root, "characters/${cfg.character.charFolder}.json").apply { parentFile.mkdirs(); writeText("{}") } }, { _, _, _, _ -> }, { _, _ -> true }, { _, _, _ -> true })
        for (format in PsychChartFormat.entries) {
            val built = ModBuilder.buildInternal(reopened.copy(psychChartFormat = format), File(temp.root, "psych-copy-${format.name}"), temp.root, {}, services)
            val out = ByteArrayOutputStream(); ModBuilder.zip(built.rootDir, out, false)
            val files = mutableMapOf<String, ByteArray>()
            java.util.zip.ZipInputStream(out.toByteArray().inputStream()).use { z -> while (true) { val e = z.nextEntry ?: break; files[e.name] = z.readBytes() } }
            val copy = readPsychChart(files.getValue("data/test/test-insane.json").toString(Charsets.UTF_8), "test", "insane")
            assertEquals(reopened.getChart("test", "normal")!!.notes.sortedBy { it.timeMs }, copy.notes.sortedBy { it.timeMs })
            assertEquals(reopened.getChart("test", "normal")!!.psychEventsJson, copy.psychEventsJson)
        }
    }

    @Test fun customPsychDifficultySurvivesSaveAndCanBeRemoved() {
        val store = ProjectStore(File(temp.root, "psych-custom"), temp.root)
        val prepared = ModZipInspector(File(temp.root, "psych-custom-cache")).withExtracted(fixture(withInsane = true).inputStream()) { dir, names ->
            PsychModImporter({ 1 to 1 }, { 10_000 }, ::audio, ::image).prepare(dir, names, store.newImportStage())
        }
        assertTrue("insane" in prepared.config.firstSong.difficulties)
        val id = store.publishModImport(prepared.stage, gson.toJsonTree(prepared.config).asJsonObject) { }
        var reopened = gson.fromJson(store.load(id).config, ModConfig::class.java)
        val services = ModBuildServices(object : AudioEncoder {
            override fun convert(input: File, output: File): String? = error("OGG")
            override fun mix(first: File, second: File, output: File): String? = error("Una voz")
        }, { root, cfg, _, _ -> File(root, "characters/${cfg.character.charFolder}.json").apply { parentFile.mkdirs(); writeText("{}") } }, { _, _, _, _ -> }, { _, _ -> true }, { _, _, _ -> true })
        var built = ModBuilder.buildInternal(reopened, File(temp.root, "psych-custom-output"), temp.root, {}, services)
        assertTrue(File(built.rootDir, "data/test/test-insane.json").isFile)
        reopened = removeSongDifficulty(reopened, 0, "insane"); store.save(id, gson.toJsonTree(reopened).asJsonObject)
        reopened = gson.fromJson(store.load(id).config, ModConfig::class.java)
        built = ModBuilder.buildInternal(reopened, File(temp.root, "psych-custom-removed"), temp.root, {}, services)
        assertFalse(File(built.rootDir, "data/test/test-insane.json").exists())
    }

    @Test fun psychWithoutNormalChartImportsRemainingDifficulties() {
        val store = ProjectStore(File(temp.root, "psych-no-normal"), temp.root)
        val prepared = ModZipInspector(File(temp.root, "cache-no-normal")).withExtracted(fixture(true, true).inputStream()) { dir, names ->
            PsychModImporter({ 1 to 1 }, { 10_000 }, ::audio, ::image).prepare(dir, names, store.newImportStage())
        }
        assertEquals(setOf("easy", "hard"), prepared.config.firstSong.difficulties)
        assertNull(prepared.config.getChart("test", "normal"))
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
        }, { root, cfg, _, _ -> File(root, "characters/${cfg.character.charFolder}.json").apply { parentFile.mkdirs(); writeText("{}") } },
            { _, _, _, _ -> }, { _, _ -> true }, { _, _, _ -> true })
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
