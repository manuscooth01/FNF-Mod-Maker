package com.fnfmodmaker.modimport

import android.net.Uri
import com.fnfmodmaker.mod.*
import com.fnfmodmaker.model.*
import com.fnfmodmaker.project.PortableProject
import com.fnfmodmaker.project.ProjectStore
import com.fnfmodmaker.project.ModImportSession
import com.fnfmodmaker.project.ProjectConfigValidator
import com.google.gson.JsonElement
import com.google.gson.GsonBuilder
import com.google.gson.TypeAdapter
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonWriter
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class VSliceImportCycleTest {
    @get:Rule val temp = TemporaryFolder()
    private val gson = GsonBuilder().serializeNulls().registerTypeAdapter(Uri::class.java, object : TypeAdapter<Uri>() {
        override fun write(out: JsonWriter, value: Uri?) { out.nullValue() }
        override fun read(input: JsonReader): Uri? { input.skipValue(); return null }
    }).create()
    private fun image(f: File, name: String): PickedImage = gson.fromJson("""{"displayName":${gson.toJson(name)},"path":${gson.toJson(f.absolutePath)},"uri":null}""", PickedImage::class.java)
    private fun audio(f: File, name: String, ms: Long): PickedAudio = gson.fromJson("""{"displayName":${gson.toJson(name)},"path":${gson.toJson(f.absolutePath)},"uri":null,"durationMs":$ms}""", PickedAudio::class.java)
    private val metadata = """{"version":"2.2.4","songName":"Test","artist":"Example","playData":{"songVariations":["remix"],"difficulties":["normal","hard"],"characters":{"player":"bf","opponent":"hero","girlfriend":"gf","playerVocals":["bf","gf"],"opponentVocals":["hero"]},"stage":"mainStage","noteStyle":"funkin"},"timeChanges":[{"t":0,"bpm":120},{"t":4000,"bpm":180}],"extraMetadata":{"keep":true,"largeInteger":123456789012345678901234567890,"precise":0.12345678901234567890123456789}}"""
    private val chart = """{"version":"2.0.0","scrollSpeed":{"normal":1.3,"hard":1.5},"events":[{"t":10,"e":"FocusCamera","v":1}],"notes":{"normal":[{"t":100.25,"d":0,"l":0},{"t":500,"d":6,"l":200}],"hard":[{"t":300,"d":1,"k":"mine"}]},"unknownChart":"retained"}"""
    private val character = """{"version":"1.0.2","name":"Hero","assetPath":"characters/hero","renderType":"sparrow","scale":1,"singTime":6,"healthIcon":{"id":"hero","custom":true},"unknownCharacter":123,"animations":[{"name":"idle","prefix":"idle","frameRate":24,"looped":false,"frameIndices":[0],"offsets":[0,0],"extraAnim":"retained"}]}"""
    private fun zip(meta: String = metadata, chartText: String = chart, extra: Map<String, String> = emptyMap(), characterId: String = "hero", side: CharacterSide = CharacterSide.OPPONENT): ByteArray {
        val adjustedMeta = if (side == CharacterSide.OPPONENT) meta.replace("\"opponent\":\"hero\"", "\"opponent\":\"$characterId\"")
            else meta.replace("\"player\":\"bf\",\"opponent\":\"hero\"", "\"player\":\"$characterId\",\"opponent\":\"dad\"")
        val characterJson = character.replace("characters/hero", "characters/$characterId").replace("\"id\":\"hero\"", "\"id\":\"$characterId\"")
        val text = linkedMapOf("data/songs/test/test-metadata.json" to adjustedMeta, "data/songs/test/test-chart.json" to chartText,
            "data/songs/test/test-metadata-remix.json" to "{\"variation\":true}", "data/songs/test/test-chart-remix.json" to "{\"variation\":true}",
            "data/characters/$characterId.json" to characterJson, "images/characters/$characterId.xml" to """<TextureAtlas imagePath="$characterId.png"><SubTexture name="idle0000" x="0" y="0" width="1" height="1"/></TextureAtlas>""",
            "data/stages/custom.json" to "{\"unknown\":true}", "data/levels/old.json" to "{\"level\":true}", "scripts/a.hx" to "trace('safe data');", "other/data.json" to "{\"extra\":true}")
        text.putAll(extra)
        val binaries = mapOf("songs/test/Inst.ogg" to "/psych-fixture/Inst.ogg", "songs/test/Voices-bf.ogg" to "/psych-fixture/Voices.ogg",
            "songs/test/Voices-hero.ogg" to "/psych-fixture/Voices.ogg", "songs/test/Voices-gf.ogg" to "/psych-fixture/Voices.ogg", "images/characters/$characterId.png" to "/psych-fixture/dad.png")
        val result = ByteArrayOutputStream()
        ZipOutputStream(result).use { zip ->
            text.forEach { (p, content) -> zip.putNextEntry(ZipEntry("wrapper/$p")); zip.write(content.toByteArray()); zip.closeEntry() }
            binaries.forEach { (p, resource) -> zip.putNextEntry(ZipEntry("wrapper/$p")); zip.write(javaClass.getResourceAsStream(resource)!!.use { it.readBytes() }); zip.closeEntry() }
        }
        return result.toByteArray()
    }
    private fun importer() = VSliceModImporter({ 2 to 2 }, { 2_000 }, ::audio, ::image)
    private fun prepare(bytes: ByteArray, store: ProjectStore): PreparedVSliceImport =
        ModZipInspector(File(temp.root, "cache")).withExtracted(bytes.inputStream()) { dir, names -> importer().prepare(dir, names, store.newImportStage()) }
    private fun services(): ModBuildServices = ModBuildServices(
        object : AudioEncoder {
            override fun convert(input: File, output: File): String? = error("OGG fixture should be copied")
            override fun mix(first: File, second: File, output: File): String? = error("V-Slice voices should not be mixed")
        },
        { root, config, _, _ ->
            val c = config.character
            if (c.enabled) {
                File(root, "images/characters/${c.charFolder}.png").apply { parentFile.mkdirs(); File(c.atlasSheet!!.path).copyTo(this) }
                File(root, "images/characters/${c.charFolder}.xml").apply { File(c.atlasXml!!.path).copyTo(this) }
                File(root, "data/characters/${c.charFolder}.json").apply { parentFile.mkdirs(); writeText("""{"name":"${c.displayName}","assetPath":"characters/${c.charFolder}","scale":${c.scale},"flipX":false,"offsets":[0,0],"cameraOffsets":[0,0],"animations":[{"name":"idle","prefix":"idle","frameRate":24,"looped":false,"frameIndices":[],"offsets":[0,0]}]}""") }
            }
        }, { _, _, _, _ -> }, { _, _ -> true }, { _, _, _ -> true }
    )
    // JVM Android stubs cannot construct Uri.EMPTY. Model it as the same empty URI string
    // written on Android and omit other nulls, matching the production Gson settings.
    private fun productionJson(config: ModConfig): com.google.gson.JsonObject {
        val tree = gson.toJsonTree(config).asJsonObject
        fun normalize(node: JsonElement) {
            if (node.isJsonArray) { node.asJsonArray.forEach(::normalize); return }
            if (!node.isJsonObject) return
            val obj = node.asJsonObject
            obj.entrySet().toList().forEach { (key, value) ->
                if (value.isJsonNull) {
                    if (key == "uri" && obj.has("path") && obj.has("displayName")) obj.addProperty(key, "")
                    else obj.remove(key)
                } else normalize(value)
            }
        }
        normalize(tree)
        return tree
    }
    private val productionValidator = ProjectConfigValidator(GsonBuilder().registerTypeAdapter(Uri::class.java,
        object : TypeAdapter<Uri>() {
            override fun write(out: JsonWriter, value: Uri?) { out.value("") }
            override fun read(input: JsonReader): Uri? { input.skipValue(); return null }
        }.nullSafe()).create())

    @Test fun confirmationPublishesActivatesAndOpensVSliceWithRealValidator() {
        val real = File(temp.root, "real").apply { mkdirs() }
        val alias = File(temp.root, "android-data-user-alias")
        java.nio.file.Files.createSymbolicLink(alias.toPath(), real.toPath())
        val store = ProjectStore(File(alias, "projects"), temp.root)
        val old = productionJson(ModConfig(modTitle = "Anterior"))
        val active = store.newId(); store.save(active, old); store.setActive(active)
        val prepared = prepare(zip(), store)
        val session = ModImportSession(prepared.stage, productionJson(prepared.config))
        val opened = session.publishAndOpen(store, active, old, productionValidator::validate) { gson.fromJson(it, ModConfig::class.java) }
        assertEquals(opened.id, store.activeId())
        assertEquals(TargetEngine.VSLICE, opened.config.engine)
        assertEquals("Test", opened.config.firstSong.songName)
        assertEquals(2, opened.config.getChart("test", "normal")!!.notes.size)
        assertTrue(File(opened.config.firstSong.instrumental!!.path).isFile)
        assertTrue(File(opened.config.character.atlasSheet!!.path).isFile)
        assertTrue(File(opened.config.character.atlasXml!!.path).isFile)
        assertEquals(File(opened.config.firstSong.instrumental!!.path).canonicalPath, opened.config.firstSong.instrumental!!.path)
        assertTrue(opened.config.importedModResources.all { File(it.asset!!.path).isFile })
        assertEquals(old, store.load(active).config)
    }

    @Test fun confirmationFailuresKeepPreviousProjectAndRetryWithoutLosingOriginals() {
        val store = ProjectStore(File(temp.root, "projects"), temp.root)
        val old = productionJson(ModConfig(modTitle = "Anterior"))
        val active = store.newId(); store.save(active, old); store.setActive(active)
        val prepared = prepare(zip(), store)
        val session = ModImportSession(prepared.stage, productionJson(prepared.config))
        fun parse(value: com.google.gson.JsonObject) = gson.fromJson(value, ModConfig::class.java)
        assertThrows(IllegalStateException::class.java) {
            session.publishAndOpen(store, active, old, { error("publication failure") }, ::parse)
        }
        assertNull(session.publishedId); assertTrue(prepared.stage.isDirectory)
        assertEquals(active, store.activeId()); assertEquals(old, store.load(active).config)
        assertThrows(IllegalStateException::class.java) {
            session.publishAndOpen(store, active, old, productionValidator::validate) { error("opening failure") }
        }
        val published = session.publishedId!!
        assertFalse(prepared.stage.exists()); assertEquals(active, store.activeId())
        assertEquals(old, store.load(active).config)
        val opened = session.publishAndOpen(store, active, old, productionValidator::validate, ::parse)
        assertEquals(published, opened.id); assertEquals(published, store.activeId())
        assertEquals(2, store.list().size)
        val script = opened.config.importedModResources.single { it.originalPath == "scripts/a.hx" }
        assertEquals("trace('safe data');", File(script.asset!!.path).readText())
    }

    @Test fun completeCyclePreservesEventsUnsupportedChartsTempoVariantsResourcesAndNotes() {
        val real = File(temp.root, "real").apply { mkdirs() }
        val alias = File(temp.root, "android-data-user-alias")
        java.nio.file.Files.createSymbolicLink(alias.toPath(), real.toPath())
        val store = ProjectStore(File(alias, "projects"), temp.root)
        val active = store.newId(); store.save(active, gson.toJsonTree(ModConfig(modTitle = "Existing")).asJsonObject); store.setActive(active)
        val prepared = prepare(zip(), store)
        assertEquals(TargetEngine.VSLICE, prepared.config.engine); assertEquals(setOf("normal", "hard"), prepared.config.firstSong.difficulties)
        assertTrue(prepared.config.character.enabled); assertTrue(prepared.warnings.any { it.contains("eventos") }); assertTrue(prepared.warnings.any { it.contains("hard") })
        val id = store.publishModImport(prepared.stage, gson.toJsonTree(prepared.config).asJsonObject) { }
        assertEquals(active, store.activeId())
        var project = gson.fromJson(store.load(id).config, ModConfig::class.java)
        val old = project.getChart("test", "normal")!!
        project = project.withChart("test", "normal", old.copy(notes = listOf(old.notes[0], ChartNote(800, 5, 300))))
        project = project.copy(character = project.character.copy(scale = "1.5"))
        store.save(id, gson.toJsonTree(project).asJsonObject)
        project = gson.fromJson(store.load(id).config, ModConfig::class.java)
        assertEquals(800L, project.getChart("test", "normal")!!.notes[1].timeMs)
        val portable = PortableProject(temp.root); val backup = File(temp.root, "backup.fnfproject")
        portable.export(store.load(id).config, store.assets(id), backup)
        val restoredId = store.publishImport(portable.prepare(backup, store.newImportStage()), portable) { }
        project = gson.fromJson(store.load(restoredId).config, ModConfig::class.java)
        val built = ModBuilder.buildInternal(project, File(temp.root, "build"), temp.root, {}, services())
        val output = ByteArrayOutputStream(); ModBuilder.zip(built.rootDir, output, false)
        val files = mutableMapOf<String, ByteArray>()
        java.util.zip.ZipInputStream(output.toByteArray().inputStream()).use { zip -> while (true) { val e = zip.nextEntry ?: break; files[e.name] = zip.readBytes() } }
        fun text(p: String) = files.getValue(p).toString(Charsets.UTF_8)
        assertEquals("normal", JSONObject(text("data/songs/test/test-metadata.json")).getJSONObject("playData").getJSONArray("difficulties").getString(0))
        val exported = JSONObject(text("data/songs/test/test-chart.json"))
        assertEquals("retained", exported.getString("unknownChart")); assertEquals(1, exported.getJSONArray("events").length())
        assertEquals("mine", exported.getJSONObject("notes").getJSONArray("hard").getJSONObject(0).getString("k"))
        assertEquals(100.25, exported.getJSONObject("notes").getJSONArray("normal").getJSONObject(0).getDouble("t"), 0.0)
        assertEquals(800, exported.getJSONObject("notes").getJSONArray("normal").getJSONObject(1).getInt("t"))
        val char = JSONObject(text("data/characters/hero.json"))
        assertEquals(1.5, char.getDouble("scale"), 0.0); assertEquals(6, char.getInt("singTime")); assertEquals(123, char.getInt("unknownCharacter"))
        assertEquals(0, char.getJSONArray("animations").getJSONObject(0).getJSONArray("frameIndices").getInt(0))
        assertEquals("retained", char.getJSONArray("animations").getJSONObject(0).getString("extraAnim"))
        assertEquals("trace('safe data');", text("scripts/a.hx")); assertEquals("{\"extra\":true}", text("other/data.json"))
        assertEquals("{\"variation\":true}", text("data/songs/test/test-chart-remix.json"))
        for (p in listOf("songs/test/Inst.ogg", "songs/test/Voices-bf.ogg", "songs/test/Voices-hero.ogg", "songs/test/Voices-gf.ogg", "data/stages/custom.json", "data/levels/old.json")) assertTrue(p, p in files)
    }
    @Test fun multipleImportedCharactersPersistAndExportIndependently() {
        val store = ProjectStore(File(temp.root, "multi-vslice"), temp.root)
        val extras = mapOf(
            "data/characters/metza.json" to character.replace("characters/hero", "characters/metza").replace("Hero", "Metza"),
            "images/characters/metza.png" to "png",
            "images/characters/metza.xml" to """<TextureAtlas imagePath="metza.png"><SubTexture name="idle0000" x="0" y="0" width="1" height="1"/></TextureAtlas>"""
        )
        val prepared = prepare(zip(extra = extras), store)
        assertEquals(setOf("hero", "metza"), prepared.config.characterLibrary.map { it.draft.charFolder }.toSet())
        val metza = prepared.config.characterLibrary.single { it.draft.charFolder == "metza" }
        val changed = prepared.config.copy(characterLibrary = prepared.config.characterLibrary.map {
            if (it.id == metza.id) it.copy(draft = it.draft.copy(scale = "1.5")) else it
        }, characterAssignments = CharacterAssignments(opponent = metza.id))
        val id = store.publishModImport(prepared.stage, gson.toJsonTree(changed).asJsonObject) { }
        val reopened = gson.fromJson(store.load(id).config, ModConfig::class.java)
        val built = ModBuilder.buildInternal(reopened, File(temp.root, "multi-output"), temp.root, {}, services())
        val out = ByteArrayOutputStream(); ModBuilder.zip(built.rootDir, out, false)
        val files = mutableMapOf<String, ByteArray>()
        java.util.zip.ZipInputStream(out.toByteArray().inputStream()).use { z -> while (true) { val e = z.nextEntry ?: break; files[e.name] = z.readBytes() } }
        listOf("hero", "metza").forEach { name -> listOf("data/characters/$name.json", "images/characters/$name.png", "images/characters/$name.xml").forEach { assertTrue(it, files.containsKey(it)) } }
        val meta = JSONObject(files.getValue("data/songs/test/test-metadata.json").toString(Charsets.UTF_8))
        assertEquals("metza", meta.getJSONObject("playData").getJSONObject("characters").getString("opponent"))
        val edited = JSONObject(files.getValue("data/characters/metza.json").toString(Charsets.UTF_8))
        assertEquals(1.5, edited.getDouble("scale"), 0.0); assertEquals(123, edited.getInt("unknownCharacter"))
    }

    @Test fun importedCharacterIdentifiersSurvivePersistenceAndExportWithoutChangingMetadata() {
        listOf("Hero_Day" to CharacterSide.OPPONENT, "Tarde-1" to CharacterSide.PLAYER,
            "hero__night" to CharacterSide.OPPONENT, "hero" to CharacterSide.OPPONENT).forEachIndexed { index, (id, side) ->
            val store = ProjectStore(File(temp.root, "projects-$index"), temp.root)
            val prepared = prepare(zip(characterId = id, side = side), store)
            assertTrue("Character $id must be editable", prepared.config.character.enabled)
            assertEquals(id, prepared.config.character.charId)
            assertEquals(id, prepared.config.character.charFolder)
            assertEquals(id, prepared.config.character.importedVSliceCharacterId)
            val old = store.newId(); val oldConfig = gson.toJsonTree(ModConfig(modTitle = "Previous")).asJsonObject
            store.save(old, oldConfig); store.setActive(old)
            val projectId = store.publishModImport(prepared.stage, productionJson(prepared.config), productionValidator::validate)
            var project = gson.fromJson(store.load(projectId).config, ModConfig::class.java)
            val song = project.firstSong
            // Editing the character, including side, must not rewrite a song's imported IDs.
            project = project.copy(character = project.character.copy(displayName = "Editado", scale = "1.5",
                side = if (side == CharacterSide.OPPONENT) CharacterSide.PLAYER else CharacterSide.OPPONENT))
            store.save(projectId, productionJson(project))
            project = gson.fromJson(store.load(projectId).config, ModConfig::class.java)
            assertTrue(validateWizard(project).joinToString { it.message }, validateWizard(project).isEmpty())
            assertEquals(song.player, project.effectiveExportConfig().firstSong.player)
            assertEquals(song.opponent, project.effectiveExportConfig().firstSong.opponent)
            val built = ModBuilder.buildInternal(project, File(temp.root, "case-build-$index"), temp.root, {}, services())
            val original = project.importedModResources.single { it.originalPath == "data/songs/test/test-metadata.json" }
            assertTrue(File(original.asset!!.path).readText().contains("extraMetadata"))
            assertTrue(File(built.rootDir, "data/songs/test/test-metadata.json").readText().contains("extraMetadata"))
            val outputMeta = JSONObject(File(built.rootDir, "data/songs/test/test-metadata.json").readText())
            val chars = outputMeta.getJSONObject("playData").getJSONObject("characters")
            assertEquals(song.player, chars.getString("player")); assertEquals(song.opponent, chars.getString("opponent"))
            assertTrue(File(built.rootDir, "data/characters/$id.json").isFile)
            assertTrue(File(built.rootDir, "images/characters/$id.png").isFile)
            assertEquals("1.5", JSONObject(File(built.rootDir, "data/characters/$id.json").readText()).get("scale").toString())
            assertEquals(old, store.activeId()); assertEquals(oldConfig, store.load(old).config)
        }
    }

    @Test fun anotherCharacterDoesNotSilentlyReplaceTheOriginalRival() {
        val store = ProjectStore(File(temp.root, "projects"), temp.root)
        val p = prepare(zip(), store)
        val draft = p.config.character.copy(charId = "New_Enemy", importedVSliceCharacterId = "",
            importedSourceFormat = "", importedSourceJson = "", side = CharacterSide.OPPONENT)
        val config = p.config.copy(character = draft)
        assertEquals("hero", config.firstSong.opponent)
        assertEquals("new-enemy", draft.charFolder)
        assertEquals("hero", config.effectiveExportConfig().firstSong.opponent)
        assertTrue(validateWizard(config).joinToString { it.message }, validateWizard(config).isEmpty())
        val built = ModBuilder.buildInternal(config, File(temp.root, "new-character-build"), temp.root, {}, services())
        val exported = JSONObject(File(built.rootDir, "data/songs/test/test-metadata.json").readText())
        assertEquals("hero", exported.getJSONObject("playData").getJSONObject("characters").getString("opponent"))
        assertTrue(built.warnings.any { it.contains("no se asigna automáticamente") })
        assertTrue(File(built.rootDir, "data/characters/hero.json").isFile)
        assertTrue(File(built.rootDir, "data/characters/new-enemy.json").isFile)
    }

    @Test fun explicitAssignmentPatchesOnlyRivalAfterSaveAndReopen() {
        val store = ProjectStore(File(temp.root, "projects-roles"), temp.root)
        val p = prepare(zip(), store)
        val metza = p.config.character.copy(charId = "metza", displayName = "Metza", importedVSliceCharacterId = "", importedSourceFormat = "", importedSourceJson = "")
        val config = p.config.copy(characterLibrary = p.config.characterLibrary + CharacterEntry("metza-id", metza),
            characterAssignments = CharacterAssignments(opponent = "metza-id"))
        val id = store.publishModImport(p.stage, productionJson(config), productionValidator::validate)
        val reopened = gson.fromJson(store.load(id).config, ModConfig::class.java)
        assertEquals("hero", reopened.firstSong.opponent)
        assertEquals("metza", reopened.effectiveExportConfig().firstSong.opponent)
        assertTrue(validateWizard(reopened).joinToString { it.message }, validateWizard(reopened).isEmpty())
        val origin = reopened.importedModResources.single { it.originalPath == "data/songs/test/test-metadata.json" }
        val original = File(origin.asset!!.path).readText()
        val built = ModBuilder.buildInternal(reopened, File(temp.root, "roles-build"), temp.root, {}, services())
        val output = ByteArrayOutputStream(); ModBuilder.zip(built.rootDir, output, false)
        val files = mutableMapOf<String, ByteArray>()
        java.util.zip.ZipInputStream(output.toByteArray().inputStream()).use { zip -> while (true) { val e = zip.nextEntry ?: break; files[e.name] = zip.readBytes() } }
        val exported = JSONObject(files.getValue("data/songs/test/test-metadata.json").toString(Charsets.UTF_8))
        assertEquals("metza", exported.getJSONObject("playData").getJSONObject("characters").getString("opponent"))
        val rawExport = files.getValue("data/songs/test/test-metadata.json").toString(Charsets.UTF_8)
        val exportTree = com.google.gson.JsonParser.parseString(rawExport).asJsonObject
        exportTree.getAsJsonObject("playData").getAsJsonObject("characters").addProperty("opponent", "hero")
        val sourceTree = com.google.gson.JsonParser.parseString(original).asJsonObject
        sourceTree.getAsJsonObject("playData").add("difficulties", com.google.gson.JsonArray().apply { add("normal"); add("hard") })
        assertEquals(sourceTree, exportTree)
        assertEquals("123456789012345678901234567890", exportTree.getAsJsonObject("extraMetadata").get("largeInteger").asString)
        assertEquals("0.12345678901234567890123456789", exportTree.getAsJsonObject("extraMetadata").get("precise").asString)
        assertEquals(original, File(origin.asset!!.path).readText())
        listOf("data/characters/metza.json", "images/characters/metza.png", "images/characters/metza.xml").forEach { assertTrue(it, it in files) }
    }

    @Test fun ambiguousDuplicateMetadataKeysAreNotRewritten() {
        val source = """{"playData":{"characters":{"opponent":"dad"}},"unknown":{"key":1,"key":2}}"""
        val origin = VSliceSongOrigin(originalOpponent = "dad")
        val raw = SongConfig(opponent = "dad", vSliceOrigin = origin)
        assertThrows(Exception::class.java) { patchImportedVSliceMetadata(source, raw, raw.copy(opponent = "metza"), setOf("opponent")) }
    }

    @Test fun missingAssignmentOrResourcesAreBlocked() {
        val store = ProjectStore(File(temp.root, "projects-invalid-roles"), temp.root)
        val p = prepare(zip(), store)
        val missing = p.config.copy(characterAssignments = CharacterAssignments(opponent = "missing"))
        assertTrue(validateWizard(missing).any { it.message.contains("no existe") })
        assertThrows(IllegalArgumentException::class.java) { ModBuilder.buildInternal(missing, File(temp.root, "missing"), temp.root, {}, services()) }
        val draft = p.config.character.copy(charId = "metza", importedVSliceCharacterId = "", importedSourceFormat = "", importedSourceJson = "", atlasSheet = null)
        val bad = p.config.copy(characterLibrary = listOf(CharacterEntry("metza", draft)), selectedCharacterId = "metza", characterAssignments = CharacterAssignments(opponent = "metza"))
        assertTrue(validateWizard(bad).any { it.step == 2 })
        assertThrows(IllegalArgumentException::class.java) { ModBuilder.buildInternal(bad, File(temp.root, "bad-assets"), temp.root, {}, services()) }
        val valid = p.config.character.copy(charId = "metza", importedVSliceCharacterId = "", importedSourceFormat = "", importedSourceJson = "")
        val assigned = p.config.copy(characterLibrary = listOf(CharacterEntry("metza", valid)), selectedCharacterId = "metza", characterAssignments = CharacterAssignments(opponent = "metza"))
        val noFiles = services().copy(character = { _, _, _, _ -> })
        val error = assertThrows(IllegalArgumentException::class.java) { ModBuilder.buildInternal(assigned, File(temp.root, "missing-output"), temp.root, {}, noFiles) }
        assertTrue(error.message!!.contains("Falta el archivo generado"))
    }

    @Test fun editedMetadataAndGeneratedCollisionsAreExported() {
        val store = ProjectStore(File(temp.root, "free-metadata"), temp.root)
        val p = prepare(zip(extra = mapOf("LEEME.txt" to "original")), store)
        val changed = p.config.copy(songs = listOf(p.config.firstSong.copy(artist = "Nuevo", bpm = "130", speed = "2.0", offsetMs = "10", stageVSlice = "new-stage")))
        val built = ModBuilder.buildInternal(changed, File(temp.root, "free-build"), temp.root, {}, services())
        val metadata = JSONObject(File(built.rootDir, "data/songs/test/test-metadata.json").readText())
        assertEquals("Nuevo", metadata.getString("artist")); assertEquals(130, metadata.getJSONArray("timeChanges").getJSONObject(0).getInt("bpm"))
        assertEquals(180, metadata.getJSONArray("timeChanges").getJSONObject(1).getInt("bpm"))
        assertEquals(10, metadata.getJSONObject("offsets").getInt("instrumental"))
        assertEquals("new-stage", metadata.getJSONObject("playData").getString("stage"))
        assertEquals(2.0, JSONObject(File(built.rootDir, "data/songs/test/test-chart.json").readText()).getJSONObject("scrollSpeed").getDouble("normal"), 0.0)
        assertNotEquals("original", File(built.rootDir, "LEEME.txt").readText())
    }

    @Test fun preservesPolymodFieldsAndSingleCharacterVoiceFilename() {
        val store = ProjectStore(File(temp.root, "projects"), temp.root)
        val p = prepare(zip(extra = mapOf("_polymod_meta.json" to """{"title":"Source","description":"Original","license":"CUSTOM","vendor":{"keep":1}}""")), store)
        val singleSong = p.config.firstSong.copy(vocalsOpponent = null, vSliceOrigin = p.config.firstSong.vSliceOrigin!!.copy(opponentVoiceFile = ""))
        // A single V-Slice voice must retain its suffix, not turn into Voices.ogg.
        val single = p.config.copy(songs = listOf(singleSong), modTitle = "Edited title")
        val built = ModBuilder.buildInternal(single, File(temp.root, "build"), temp.root, {}, services())
        assertTrue(File(built.rootDir, "songs/test/Voices-bf.ogg").isFile)
        assertFalse(File(built.rootDir, "songs/test/Voices.ogg").exists())
        val meta = JSONObject(File(built.rootDir, "_polymod_meta.json").readText())
        assertEquals("Edited title", meta.getString("title")); assertEquals("CUSTOM", meta.getString("license")); assertEquals(1, meta.getJSONObject("vendor").getInt("keep"))
    }
    @Test fun emptyAndNewDifficultiesExportWithoutRestoringOldCharts() {
        val store = ProjectStore(File(temp.root, "free-difficulties"), temp.root)
        val p = prepare(zip(), store)
        val empty = p.config.copy(songs = listOf(p.config.firstSong.copy(difficulties = emptySet())))
        val built = ModBuilder.buildInternal(empty, File(temp.root, "empty-build"), temp.root, {}, services())
        assertEquals(0, JSONObject(File(built.rootDir, "data/songs/test/test-metadata.json").readText()).getJSONObject("playData").getJSONArray("difficulties").length())
        assertEquals(0, JSONObject(File(built.rootDir, "data/songs/test/test-chart.json").readText()).getJSONObject("notes").length())
        assertTrue(built.warnings.any { it.contains("sin dificultades") })
        val added = com.fnfmodmaker.model.addSongDifficulty(empty, 0, "easy")
        val next = ModBuilder.buildInternal(added, File(temp.root, "added-build"), temp.root, {}, services())
        assertTrue(JSONObject(File(next.rootDir, "data/songs/test/test-chart.json").readText()).getJSONObject("notes").has("easy"))
    }
    @Test fun removingUnsupportedDifficultyIsIntentionalAndPersists() {
        val store = ProjectStore(File(temp.root, "remove-special"), temp.root)
        val prepared = prepare(zip(), store)
        val removed = removeSongDifficulty(prepared.config, 0, "hard")
        val id = store.publishModImport(prepared.stage, gson.toJsonTree(removed).asJsonObject) { }
        val reopened = gson.fromJson(store.load(id).config, ModConfig::class.java)
        val built = ModBuilder.buildInternal(reopened, File(temp.root, "remove-special-output"), temp.root, {}, services())
        val result = ByteArrayOutputStream(); ModBuilder.zip(built.rootDir, result, false)
        val files = mutableMapOf<String, ByteArray>()
        java.util.zip.ZipInputStream(result.toByteArray().inputStream()).use { z -> while (true) { val e = z.nextEntry ?: break; files[e.name] = z.readBytes() } }
        val out = JSONObject(files.getValue("data/songs/test/test-chart.json").toString(Charsets.UTF_8))
        assertFalse(out.getJSONObject("notes").has("hard"))
        assertTrue(out.getJSONObject("notes").has("normal"))
        assertEquals("mine", JSONObject(File(reopened.importedModResources.single { it.originalPath == "data/songs/test/test-chart.json" }.asset!!.path).readText()).getJSONObject("notes").getJSONArray("hard").getJSONObject(0).getString("k"))
    }

    @Test fun fractionalNotesThatBecomeAmbiguousAreKeptUneditable() {
        val source = JSONObject("""{"notes":{"normal":[{"t":100.1,"d":0},{"t":100.2,"d":0}]}}""")
        assertThrows(IllegalArgumentException::class.java) { readVSliceDifficulty(source, "test", "normal", 120, 1f) }
    }
    @Test fun rejectsUnsupportedVersionsAndBothWithoutPublishing() {
        val store = ProjectStore(File(temp.root, "projects"), temp.root)
        assertThrows(IllegalArgumentException::class.java) { prepare(zip(meta = metadata.replace("2.2.4", "3.0.0")), store) }
        assertThrows(IllegalArgumentException::class.java) { prepare(zip(extra = mapOf("pack.json" to "{}", "data/test/test.json" to "{}")), store) }
        assertTrue(store.list().isEmpty())
    }
}
