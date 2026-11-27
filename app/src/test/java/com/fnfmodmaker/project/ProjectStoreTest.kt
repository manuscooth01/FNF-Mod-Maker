package com.fnfmodmaker.project

import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ProjectStoreTest {
    @get:Rule val temp = TemporaryFolder()

    private fun snapshot(root: File): Map<String, List<Byte>> = root.walkTopDown()
        .filter { it.isFile }.associate { it.relativeTo(root).path to it.readBytes().toList() }
    private fun document(id: String, config: String) = """{"version":1,"id":"$id","updatedAt":1,"config":$config}"""

    @Test fun v2CandidatesAreReadOnlyForListingLoadingSavingAndActivation() {
        val root = File(temp.root, "projects")
        val store = ProjectStore(root, temp.root)
        val active = store.newId(); val legacy = JsonParser.parseString("""{"modTitle":"old","chartSchemaVersion":1}""").asJsonObject
        store.save(active, legacy); store.setActive(active)
        val id = store.newId(); val folder = File(root, id); assertTrue(folder.mkdirs())
        val primary = File(folder, "project.json"); val backup = File(folder, "project.json.bak"); val tmp = File(folder, "project.json.tmp")
        val v2 = document(id, """{"chartSchemaVersion":2,"independentCharts":{"stable":{"id":"stable"}}}""")
        val v1 = document(id, """{"chartSchemaVersion":1,"modTitle":"legacy"}""")
        primary.writeText(v2); backup.writeText(v1); tmp.writeText("temporary evidence")
        fun rejectAndCheck() {
            val before = snapshot(root)
            assertTrue(store.list().single { it.id == id }.problem!!.contains("incompatible"))
            val error = assertThrows(IllegalArgumentException::class.java) { store.load(id) }
            assertTrue(error.message.orEmpty().contains("no es compatible"))
            assertThrows(IllegalArgumentException::class.java) { store.save(id, legacy) }
            assertThrows(IllegalArgumentException::class.java) { store.setActive(id) }
            assertEquals(active, store.activeId()); assertEquals(before, snapshot(root))
        }
        rejectAndCheck() // V2 primary, older backup
        primary.writeText("broken"); backup.writeText(v2)
        rejectAndCheck() // damaged primary, V2 backup
        assertTrue(primary.delete())
        rejectAndCheck() // missing primary, V2 backup
        primary.writeText(v1); backup.writeText(v1); tmp.writeText(v2)
        rejectAndCheck() // a V2 interrupted write is protected too
    }

    @Test fun legacyExplicitNullSchemaFieldsRemainReadableWithoutDiscardingBytes() {
        val root = File(temp.root, "projects"); val store = ProjectStore(root, temp.root); val id = store.newId()
        val config = JsonParser.parseString("""{"chartSchemaVersion":null,"independentCharts":null,"modTitle":"legacy"}""").asJsonObject
        store.save(id, config); val before = snapshot(root)
        assertNull(store.list().single().problem); assertEquals(config, store.load(id).config)
        assertEquals(before, snapshot(root))
        val forged = config.deepCopy().apply { add("independentCharts", JsonParser.parseString("""{"song":{}}""")) }
        assertThrows(IllegalArgumentException::class.java) { store.save(id, forged) }
        assertEquals(before, snapshot(root))
    }

    @Test fun corruptPrimaryWithCompatibleBackupRequiresManualRecovery() {
        val root = File(temp.root, "projects"); val store = ProjectStore(root, temp.root); val id = store.newId()
        val config = JsonParser.parseString("""{"chartSchemaVersion":1,"modTitle":"v1"}""").asJsonObject
        store.save(id, config); store.save(id, config)
        val folder = File(root, id); val primary = File(folder, "project.json"); val backup = File(folder, "project.json.bak")
        val expected = backup.readBytes(); primary.writeText("broken"); val before = snapshot(root)
        assertTrue(store.list().single().problem!!.contains("manual"))
        assertThrows(IllegalStateException::class.java) { store.load(id) }
        assertEquals(before, snapshot(root)); assertArrayEquals(expected, backup.readBytes())
    }

    @Test fun untrustedCandidatesRequireManualRecoveryAndPreserveAllBytes() {
        val root = File(temp.root, "projects"); val store = ProjectStore(root, temp.root)
        val active = store.newId(); val config = JsonParser.parseString("""{"modTitle":"active","chartSchemaVersion":1}""").asJsonObject
        store.save(active, config); store.setActive(active)
        val id = store.newId(); val dir = File(root, id); check(dir.mkdirs())
        val primary = File(dir, "project.json"); val backup = File(dir, "project.json.bak"); val tmp = File(dir, "project.json.tmp")
        val v1 = document(id, """{"modTitle":"v1","chartSchemaVersion":1}""")
        val v2 = document(id, """{"chartSchemaVersion":2,"independentCharts":{"stable":{"id":"stable"}}}""")
        val wrongId = document(store.newId(), """{"modTitle":"v1","chartSchemaVersion":1}""")
        val cases = listOf(
            Triple("{\"version\":1,\"config\":", v1, null), // truncated
            Triple("not-json", v1, null),
            Triple(v1.replace("\"version\":1", "\"version\":99"), v1, null),
            Triple(wrongId, v1, null),
            Triple(document(id, """{"chartSchemaVersion":2,"chartSchemaVersion":1,"modTitle":"fake"}"""), v1, null),
            Triple(document(id, """{"modTitle":{}}"""), v1, null), Triple(v1, v2, null), Triple(v2, v1, null),
            Triple(v1, v1, v2), Triple(document(id, "{}"), v1, null))
        cases.forEach { (main, previous, temporary) ->
            primary.writeText(main); backup.writeText(previous)
            if (temporary == null) { if (tmp.exists()) assertTrue(tmp.delete()) } else tmp.writeText(temporary)
            val before = snapshot(root)
            assertNotNull(store.list().single { it.id == id }.problem)
            assertThrows(Exception::class.java) { store.load(id) }
            assertThrows(Exception::class.java) { store.save(id, config) }
            assertThrows(Exception::class.java) { store.setActive(id) }
            assertEquals(active, store.activeId()); assertEquals(before, snapshot(root))
        }
    }

    @Test fun unreadableCandidateBlocksAllProjectOperations() {
        val root = File(temp.root, "projects"); val store = ProjectStore(root, temp.root); val id = store.newId()
        val config = JsonParser.parseString("""{"chartSchemaVersion":1,"modTitle":"v1"}""").asJsonObject
        store.save(id, config); val folder = File(root, id); val bad = File(folder, "project.json.bak")
        assertTrue(bad.mkdir()); val before = snapshot(root)
        assertTrue(store.list().single().problem!!.contains("READ_ERROR"))
        assertThrows(IllegalStateException::class.java) { store.load(id) }
        assertThrows(IllegalStateException::class.java) { store.save(id, config) }
        assertTrue(bad.isDirectory); assertEquals(before, snapshot(root))
    }

    @Test fun missingPrimaryCanReadValidatedV1BackupWithoutModifyingFiles() {
        val root = File(temp.root, "projects"); val store = ProjectStore(root, temp.root); val id = store.newId()
        val config = com.google.gson.Gson().toJsonTree(com.fnfmodmaker.model.ModConfig()).asJsonObject
        config.addProperty("chartSchemaVersion", 1)
        store.save(id, config); store.save(id, config)
        val primary = File(root, "$id/project.json"); assertTrue(primary.delete()); val before = snapshot(root)
        assertEquals(config, store.load(id).config); assertEquals(before, snapshot(root)); assertFalse(primary.exists())
        assertThrows(IllegalStateException::class.java) { store.save(id, config) }
        assertThrows(IllegalStateException::class.java) { store.setActive(id) }
        assertEquals(before, snapshot(root))
    }

    @Test fun externalChangeAfterInspectionIsDetectedBeforeSave() {
        val root = File(temp.root, "projects"); val original = ProjectStore(root, temp.root); val id = original.newId()
        val config = JsonParser.parseString("""{"chartSchemaVersion":1,"modTitle":"v1"}""").asJsonObject
        original.save(id, config); original.save(id, config)
        val primary = File(root, "$id/project.json"); val backup = File(root, "$id/project.json.bak"); val savedBackup = backup.readBytes()
        val external = document(id, """{"chartSchemaVersion":2,"independentCharts":{"stable":{"id":"stable"}}}""")
        val tested = ProjectStore(root, temp.root) { primary.writeText(external) }
        val failure = assertThrows(IllegalStateException::class.java) { tested.save(id, config) }
        assertTrue(failure.message!!.contains("cambió")); assertEquals(external, primary.readText())
        assertArrayEquals(savedBackup, backup.readBytes()); assertFalse(File(root, "$id/project.json.tmp").exists())
    }

    @Test fun twoStoreInstancesSerializeConcurrentSavesForSameProject() {
        val root = File(temp.root, "projects"); val a = ProjectStore(root, temp.root); val b = ProjectStore(root, temp.root)
        val id = a.newId(); val original = JsonParser.parseString("""{"chartSchemaVersion":1,"modTitle":"initial"}""").asJsonObject
        a.save(id, original)
        val first = original.deepCopy().apply { addProperty("modTitle", "first") }
        val second = original.deepCopy().apply { addProperty("modTitle", "second") }
        val latch = java.util.concurrent.CountDownLatch(1); val pool = java.util.concurrent.Executors.newFixedThreadPool(2)
        try {
            val f1 = pool.submit { latch.await(); a.save(id, first) }
            val f2 = pool.submit { latch.await(); b.save(id, second) }
            latch.countDown(); f1.get(20, java.util.concurrent.TimeUnit.SECONDS); f2.get(20, java.util.concurrent.TimeUnit.SECONDS)
        } finally { pool.shutdownNow() }
        val main = File(root, "$id/project.json"); val backup = File(root, "$id/project.json.bak")
        fun title(file: File) = JsonParser.parseString(file.readText()).asJsonObject.getAsJsonObject("config").get("modTitle").asString
        assertEquals(setOf("first", "second"), setOf(title(main), title(backup)))
        assertFalse(File(root, "$id/project.json.tmp").exists())
    }

    private fun backupFixture(root: File, store: ProjectStore): Pair<String, com.google.gson.JsonObject> {
        val id = store.newId()
        val config = com.google.gson.Gson().toJsonTree(com.fnfmodmaker.model.ModConfig()).asJsonObject
        config.addProperty("chartSchemaVersion", 1)
        val audio = File(temp.root, "recover-$id.ogg").apply { writeText("audio bytes") }
        config.getAsJsonArray("songs")[0].asJsonObject.add("instrumental", JsonParser.parseString(
            """{"path":${com.google.gson.Gson().toJson(audio.absolutePath)},"displayName":"song.ogg","durationMs":1000}"""))
        store.save(id, config); store.save(id, config)
        assertTrue(File(root, "$id/project.json").delete())
        return id to config
    }
    private fun link(source: File, destination: File) { java.nio.file.Files.createLink(destination.toPath(), source.toPath()) }

    @Test fun explicitRecoveryCopiesVerifiedBackupAndLeavesOriginalUntouched() {
        val root = File(temp.root, "projects"); val store = ProjectStore(root, temp.root, recoveryInstall = ::link)
        val (id, config) = backupFixture(root, store); val primary = File(root, "$id/project.json"); val backup = File(root, "$id/project.json.bak")
        val bytes = backup.readBytes(); val before = snapshot(root)
        assertEquals(config, store.load(id).config); assertEquals(before, snapshot(root)); assertFalse(primary.exists())
        store.recoverBackup(id)
        assertArrayEquals(bytes, primary.readBytes()); assertArrayEquals(bytes, backup.readBytes())
        assertFalse(java.nio.file.Files.isSameFile(primary.toPath(), backup.toPath()))
        assertEquals(config, store.load(id).config); store.setActive(id); assertEquals(id, store.activeId())
        assertEquals("audio bytes", File(config.getAsJsonArray("songs")[0].asJsonObject.getAsJsonObject("instrumental").get("path").asString).readText())
        assertArrayEquals(bytes, backup.readBytes())
    }

    @Test fun invalidBackupOrV2IsNotRecoveredAndBytesSurvive() {
        val root = File(temp.root, "projects"); val store = ProjectStore(root, temp.root, recoveryInstall = ::link)
        val (id, _) = backupFixture(root, store); val backup = File(root, "$id/project.json.bak")
        val invalid = document(id, """{"modTitle":"missing data","chartSchemaVersion":1}""")
        val v2 = document(id, """{"chartSchemaVersion":2,"independentCharts":{"stable":{"id":"stable"}}}""")
        for (text in listOf(invalid, v2)) {
            backup.writeText(text); val before = snapshot(root)
            assertThrows(Exception::class.java) { store.recoverBackup(id) }
            assertEquals(before, snapshot(root)); assertFalse(File(root, "$id/project.json").exists())
        }
    }

    @Test fun recoveryDetectsBackupChangeAndNeverOverwritesAConcurrentPrimary() {
        val root = File(temp.root, "projects"); val base = ProjectStore(root, temp.root, recoveryInstall = ::link)
        val (id, _) = backupFixture(root, base); val primary = File(root, "$id/project.json"); val backup = File(root, "$id/project.json.bak")
        val original = backup.readBytes(); val external = original + " ".toByteArray()
        val changed = ProjectStore(root, temp.root, recoveryInstall = ::link, afterInspection = { backup.writeBytes(external) })
        assertThrows(IllegalStateException::class.java) { changed.recoverBackup(id) }
        assertArrayEquals(external, backup.readBytes()); assertFalse(primary.exists())
        backup.writeBytes(original)
        val racing = ProjectStore(root, temp.root, recoveryInstall = { source, destination ->
            destination.writeText("external primary"); link(source, destination)
        })
        assertThrows(java.nio.file.FileAlreadyExistsException::class.java) { racing.recoverBackup(id) }
        assertEquals("external primary", primary.readText()); assertArrayEquals(original, backup.readBytes())
        assertTrue(primary.parentFile.listFiles().orEmpty().any { it.name.startsWith("project.recovery-") })
    }

    @Test fun recoveryWriteFailureKeepsBackupAndVerifiedTemporary() {
        val root = File(temp.root, "projects"); val base = ProjectStore(root, temp.root, recoveryInstall = ::link)
        val (id, _) = backupFixture(root, base); val backup = File(root, "$id/project.json.bak"); val bytes = backup.readBytes()
        val failing = ProjectStore(root, temp.root, recoveryInstall = { _, _ -> throw java.io.IOException("injected install failure") })
        val error = assertThrows(java.io.IOException::class.java) { failing.recoverBackup(id) }
        assertEquals("injected install failure", error.message); assertArrayEquals(bytes, backup.readBytes())
        assertFalse(File(root, "$id/project.json").exists())
        val temporary = backup.parentFile.listFiles().orEmpty().single { it.name.startsWith("project.recovery-") }
        assertArrayEquals(bytes, temporary.readBytes())
    }

    @Test fun twoStoreInstancesOnlyOneCanRecover() {
        val root = File(temp.root, "projects"); val a = ProjectStore(root, temp.root, recoveryInstall = ::link); val b = ProjectStore(root, temp.root, recoveryInstall = ::link)
        val (id, config) = backupFixture(root, a); val backup = File(root, "$id/project.json.bak"); val bytes = backup.readBytes()
        val gate = java.util.concurrent.CountDownLatch(1); val pool = java.util.concurrent.Executors.newFixedThreadPool(2)
        try {
            val f1 = pool.submit<Boolean> { gate.await(); runCatching { a.recoverBackup(id) }.isSuccess }
            val f2 = pool.submit<Boolean> { gate.await(); runCatching { b.recoverBackup(id) }.isSuccess }
            gate.countDown(); assertEquals(setOf(true, false), setOf(f1.get(20, java.util.concurrent.TimeUnit.SECONDS), f2.get(20, java.util.concurrent.TimeUnit.SECONDS)))
        } finally { pool.shutdownNow() }
        assertArrayEquals(bytes, backup.readBytes()); assertEquals(config, a.load(id).config)
    }

    @Test fun fileLockIsVisibleToAnotherProcessDuringSave() {
        val root = File(temp.root, "projects"); val original = ProjectStore(root, temp.root); val id = original.newId()
        val config = JsonParser.parseString("""{"chartSchemaVersion":1,"modTitle":"v1"}""").asJsonObject; original.save(id, config)
        val lock = File(root, ".project-locks/$id.lock")
        var observed = ""
        val second = ProjectStore(root, temp.root, afterInspection = {
            val python = "import fcntl,sys; f=open(sys.argv[1],'r+');\ntry: fcntl.lockf(f,fcntl.LOCK_EX|fcntl.LOCK_NB); print('acquired')\nexcept BlockingIOError: print('blocked')"
            val child = ProcessBuilder("python3", "-c", python, lock.absolutePath).redirectErrorStream(true).start()
            observed = child.inputStream.bufferedReader().readText().trim(); assertEquals(0, child.waitFor())
        })
        second.save(id, config); assertEquals("blocked", observed); assertEquals(config, original.load(id).config)
    }

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
        val root = JsonParser.parseString("""{"character":{"sheet":{"uri":"content://x","displayName":"legacy.png","path":"${input.path}"},"animationFrames":{"idle":[{"image":{"uri":"content://x","displayName":"legacy.png","path":"${input.path}"}}]}},"unknown":{"value":42},"icon":{"uri":"content://y","displayName":"lost.png","path":"${temp.root}/missing.png"}}""").asJsonObject
        assertThrows(IllegalArgumentException::class.java) { store.copyAssets(root, id) }
        root.remove("icon")
        val copy = store.copyAssets(root, id)
        val character = copy.config.getAsJsonObject("character")
        val newPath = character.getAsJsonObject("sheet").get("path").asString
        assertEquals("original", File(newPath).readText())
        assertNotEquals(input.path, newPath)
        assertEquals(newPath, character.getAsJsonObject("animationFrames").getAsJsonArray("idle")[0].asJsonObject.getAsJsonObject("image").get("path").asString)
        assertTrue(copy.missing.isEmpty())
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
        val old = gson.fromJson(migrateCharacterLibrary(store.load(imported).config), com.fnfmodmaker.model.ModConfig::class.java)
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
