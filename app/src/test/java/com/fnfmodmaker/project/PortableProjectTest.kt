package com.fnfmodmaker.project

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

class PortableProjectTest {
    @get:Rule val temp = TemporaryFolder()
    private fun fixture(root: File): Pair<ProjectStore, Pair<String, JsonObject>> {
        val store = ProjectStore(File(root, "projects"), root)
        val id = store.newId(); val assets = store.assets(id)
        fun asset(name: String): JsonObject {
            val f = File(assets, name).apply { writeBytes("bytes-$name".toByteArray()) }
            return JsonObject().apply { addProperty("path", f.path); addProperty("uri", "content://original/$name"); addProperty("displayName", name) }
        }
        val config = JsonParser.parseString("""{"modTitle":"Mi mod","songs":[{"songName":"Song","difficulties":["normal","hard"]}],"charts":{"song":{"normal":{"notes":[{"timeMs":125,"lane":2,"lengthMs":500}]},"hard":{"notes":[{"timeMs":777,"lane":7,"lengthMs":0}]}}},"character":{"animations":["idle","singLEFT"],"animationSettings":{"idle":{"fps":"12"}},"animationFrames":{"idle":[{}]}},"stageDraft":{"enabled":true},"psychScripts":{"example":"function onCreate() end"}}""").asJsonObject
        val song = config.getAsJsonArray("songs")[0].asJsonObject
        song.add("instrumental", asset("Inst.ogg")); song.add("vocalsPlayer", asset("Voices.ogg"))
        val character = config.getAsJsonObject("character")
        character.add("sheet", asset("sprite.png")); character.add("atlasXml", asset("sprite.xml")); character.add("icon", asset("icon.png"))
        character.getAsJsonObject("animationFrames").getAsJsonArray("idle")[0].asJsonObject.add("image", asset("frame.png"))
        config.getAsJsonObject("stageDraft").add("backgroundImage", asset("background.png"))
        File(assets, "extra.dat").writeText("unknown resource")
        store.save(id, config); store.setActive(id)
        return store to (id to config)
    }
    private fun files(dir: File) = dir.walkTopDown().filter { it.isFile }.associate { it.relativeTo(dir).path to it.readBytes().toList() }
    private fun normalized(config: JsonObject, dir: File): JsonObject {
        val result = config.deepCopy()
        fun visit(value: com.google.gson.JsonElement) {
            if (value.isJsonArray) value.asJsonArray.forEach(::visit)
            else if (value.isJsonObject) {
                val obj = value.asJsonObject
                if (obj.has("path") && obj.has("displayName")) {
                    obj.addProperty("path", File(obj.get("path").asString).relativeTo(dir).path)
                    obj.addProperty("uri", "")
                } else obj.entrySet().forEach { visit(it.value) }
            }
        }
        visit(result); return result
    }

    @Test fun roundTripAndRepeatedImportsAreIndependent() {
        val (store, pair) = fixture(temp.root); val (id, original) = pair
        val sourceDir = store.assets(id)
        val expectedConfig = normalized(original, sourceDir)
        val originalAssets = files(sourceDir)
        val archive = File(temp.root, "project.fnfproject")
        val portable = PortableProject(temp.root)
        portable.export(original, store.assets(id), archive)
        // Remove only the test source to ensure import is self-contained.
        File(temp.root, "projects/$id").deleteRecursively()
        val stage = store.newImportStage(); val prepared = portable.prepare(archive, stage)
        assertEquals("Mi mod", prepared.summary.title); assertEquals(1, prepared.summary.songs); assertEquals(2, prepared.summary.charts); assertEquals(8, prepared.summary.assets)
        val imported = store.publishImport(prepared, portable) { assertEquals(2, it.getAsJsonObject("charts").getAsJsonObject("song").size()) }
        val importedConfig = store.load(imported).config
        assertEquals(expectedConfig, normalized(importedConfig, store.assets(imported)))
        assertEquals(original.get("charts"), importedConfig.get("charts"))
        assertEquals(original.get("psychScripts"), importedConfig.get("psychScripts"))
        assertEquals(original.getAsJsonObject("character").get("animations"), importedConfig.getAsJsonObject("character").get("animations"))
        assertEquals(original.getAsJsonObject("character").get("animationSettings"), importedConfig.getAsJsonObject("character").get("animationSettings"))
        assertEquals(originalAssets, files(store.assets(imported)))
        val newPath = importedConfig.getAsJsonArray("songs")[0].asJsonObject.getAsJsonObject("instrumental").get("path").asString
        assertEquals("bytes-Inst.ogg", File(newPath).readText())
        assertTrue(newPath.startsWith(store.assets(imported).path))
        assertEquals("", importedConfig.getAsJsonArray("songs")[0].asJsonObject.getAsJsonObject("instrumental").get("uri").asString)
        val again = store.publishImport(portable.prepare(archive, store.newImportStage()), portable) { }
        assertNotEquals(imported, again); assertEquals(originalAssets, files(store.assets(again)))
        assertEquals(2, store.list().size)
    }

    @Test fun projectRoundTripAndRecoveryKeepChartAndHistoryTogether() {
        val (store, pair) = fixture(temp.root); val (id, original) = pair
        val config = original.deepCopy()
        val history = com.fnfmodmaker.model.recordChartHistory(com.fnfmodmaker.model.ChartPersistentHistory(), null,
            com.fnfmodmaker.model.ChartDraft(songFolder = "song", notes = listOf(com.fnfmodmaker.model.ChartNote(125, 2, 500))))
        config.add("chartHistories", com.google.gson.JsonParser.parseString("{\"song\":{\"normal\":${com.google.gson.Gson().toJson(history)}}}"))
        store.save(id, config)
        val portable = PortableProject(temp.root); val archive = File(temp.root, "with-history.fnfproject")
        portable.export(config, store.assets(id), archive)
        val imported = store.publishImport(portable.prepare(archive, store.newImportStage()), portable) { }
        val loaded = store.load(imported).config
        assertEquals(config.get("charts"), loaded.get("charts"))
        assertEquals(config.get("chartHistories"), loaded.get("chartHistories"))
        val file = File(temp.root, "projects/$id/project.json")
        // A completed save became the backup when the next write was interrupted.
        store.save(id, config.deepCopy().apply { addProperty("modTitle", "Next") })
        assertTrue(file.delete()); File(file.parentFile, "project.json.tmp").writeText("incomplete")
        val backup = File(file.parentFile, "project.json.bak")
        val temporary = File(file.parentFile, "project.json.tmp")
        val backupBytes = backup.readBytes(); val temporaryBytes = temporary.readBytes()
        assertThrows(IllegalStateException::class.java) { store.load(id) }
        assertFalse(file.exists()); assertArrayEquals(backupBytes, backup.readBytes()); assertArrayEquals(temporaryBytes, temporary.readBytes())
        val preserved = com.google.gson.JsonParser.parseString(backup.readText()).asJsonObject.getAsJsonObject("config")
        assertEquals(config.get("charts"), preserved.get("charts"))
        assertEquals(config.get("chartHistories"), preserved.get("chartHistories"))
    }

    private fun zip(file: File, contents: List<Pair<String, ByteArray>>) {
        ZipOutputStream(file.outputStream()).use { out -> contents.forEach { (name, bytes) -> out.putNextEntry(ZipEntry(name)); out.write(bytes); out.closeEntry() } }
    }
    @Test fun rejectsTraversalDuplicateOversizeAndMissingEntriesWithoutTouchingActive() {
        val (store, pair) = fixture(temp.root); val id = pair.first; val portable = PortableProject(temp.root)
        val before = store.load(id).config.deepCopy()
        for (bad in listOf("../escape", "/absolute", "assets/../evil", "assets/evil\\foo")) {
            val input = File(temp.root, "bad.zip"); zip(input, listOf(bad to byteArrayOf(1)))
            val stage = store.newImportStage(); assertThrows(Exception::class.java) { portable.prepare(input, stage) }
            assertFalse(stage.exists()); assertEquals(id, store.activeId()); assertEquals(before, store.load(id).config)
        }
        val input = File(temp.root, "duplicate.zip")
        zip(input, listOf("assets/a" to byteArrayOf(1), "assets/b" to byteArrayOf(2)))
        val bytes = input.readBytes(); val find = "assets/b".toByteArray(); val replace = "assets/a".toByteArray()
        for (i in 0..bytes.size - find.size) if (find.indices.all { bytes[i + it] == find[it] }) replace.copyInto(bytes, i)
        input.writeBytes(bytes)
        assertThrows(Exception::class.java) { portable.prepare(input, store.newImportStage()) }
        val huge = File(temp.root, "huge.zip"); zip(huge, listOf("assets/a" to byteArrayOf(1)))
        val data = huge.readBytes(); val marker = byteArrayOf(0x50, 0x4b, 0x01, 0x02)
        val index = (0..data.size - 4).first { i -> marker.indices.all { data[i + it] == marker[it] } }
        // Uncompressed size in central header: 0x10000001, one byte over 256 MiB.
        data[index + 24] = 1; data[index + 25] = 0; data[index + 26] = 0; data[index + 27] = 0x10
        huge.writeBytes(data); assertThrows(Exception::class.java) { portable.prepare(huge, store.newImportStage()) }
        assertEquals(id, store.activeId()); assertEquals(1, store.list().size)
    }

    @Test fun damagedAndMissingResourcesAndInterruptedPublicationDoNotAlterProjects() {
        val (store, pair) = fixture(temp.root); val (id, config) = pair
        val portable = PortableProject(temp.root); val archive = File(temp.root, "good.zip")
        portable.export(config, store.assets(id), archive)
        val entries = mutableListOf<Pair<String, ByteArray>>()
        ZipFile(archive).use { z -> z.entries().asSequence().forEach { entry -> entries.add(entry.name to z.getInputStream(entry).readBytes()) } }
        val target = entries.first { it.first.startsWith("assets/") }.first
        for (missing in listOf(true, false)) {
            val bad = File(temp.root, "bad-$missing.zip")
            val changed = if (missing) entries.filterNot { it.first == target } else entries.map { if (it.first == target) it.first to "corrupt".toByteArray() else it }
            zip(bad, changed)
            val stage = store.newImportStage(); assertThrows(Exception::class.java) { portable.prepare(bad, stage) }
            assertFalse(stage.exists()); assertEquals(id, store.activeId()); assertEquals(config, store.load(id).config)
        }
        val prepared = portable.prepare(archive, store.newImportStage())
        File(prepared.directory, target).writeText("damaged")
        assertThrows(Exception::class.java) { store.publishImport(prepared, portable) { } }
        assertEquals(1, store.list().size); assertEquals(id, store.activeId())
        store.clearAbandonedImports(); assertFalse(prepared.directory.exists())
        val asset = config.getAsJsonArray("songs")[0].asJsonObject.getAsJsonObject("instrumental")
        File(asset.get("path").asString).delete()
        assertThrows(Exception::class.java) { portable.export(config, store.assets(id), File(temp.root, "missing.fnfproject")) }
        assertEquals(id, store.activeId()); assertEquals(config, store.load(id).config)
    }
    @Test fun failedValidationAndAbandonedStageNeverPublishOrReplaceActive() {
        val (store, pair) = fixture(temp.root); val id = pair.first
        val portable = PortableProject(temp.root); val file = File(temp.root, "good.fnfproject")
        portable.export(pair.second, store.assets(id), file)
        val prepared = portable.prepare(file, store.newImportStage())
        assertThrows(IllegalStateException::class.java) { store.publishImport(prepared, portable) { error("invalid model") } }
        assertEquals(id, store.activeId()); assertEquals(1, store.list().size)
        // Simulates process termination prior to the directory rename.
        store.clearAbandonedImports()
        assertFalse(prepared.directory.exists()); assertEquals(1, store.list().size)
    }

    @Test fun malformedReferencesAreRejectedBeforeAnyProjectIsPublished() {
        val (store, pair) = fixture(temp.root); val config = pair.second.deepCopy()
        config.getAsJsonArray("songs")[0].asJsonObject.getAsJsonObject("instrumental").remove("displayName")
        val archive = File(temp.root, "bad.fnfproject")
        assertThrows(Exception::class.java) { PortableProject(temp.root).export(config, store.assets(pair.first), archive) }
        assertEquals(pair.first, store.activeId()); assertEquals(1, store.list().size)
    }

    @Test fun repeatedSuccessfulImportsLeaveTheActiveProjectIntact() {
        val (store, pair) = fixture(temp.root); val (originalId, originalConfig) = pair
        val portable = PortableProject(temp.root); val archive = File(temp.root, "copy.fnfproject")
        portable.export(originalConfig, store.assets(originalId), archive)
        val first = store.publishImport(portable.prepare(archive, store.newImportStage()), portable) { }
        val second = store.publishImport(portable.prepare(archive, store.newImportStage()), portable) { }
        assertNotEquals(first, second); assertNotEquals(first, originalId); assertNotEquals(second, originalId)
        assertEquals(originalId, store.activeId()); assertEquals(originalConfig, store.load(originalId).config)
        assertEquals(3, store.list().size)
    }

}
