package com.fnfmodmaker.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import com.fnfmodmaker.model.TargetEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ModZipConfirmationTest {
    private val scheduler = TestCoroutineScheduler()
    private val dispatcher = StandardTestDispatcher(scheduler)
    private lateinit var app: Application
    private lateinit var store: ViewModelStore
    private lateinit var vm: WizardViewModel

    @Before fun setup() {
        Dispatchers.setMain(dispatcher)
        app = RuntimeEnvironment.getApplication()
        val projects = File(app.filesDir, "projects")
        if (java.nio.file.Files.isSymbolicLink(projects.toPath())) java.nio.file.Files.delete(projects.toPath())
        else projects.deleteRecursively()
        val realProjects = File(app.filesDir, "actual-projects")
        realProjects.deleteRecursively(); check(realProjects.mkdirs())
        java.nio.file.Files.createSymbolicLink(projects.toPath(), realProjects.toPath())
        store = ViewModelStore()
        val factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = WizardViewModel(app) as T
        }
        vm = ViewModelProvider(store, factory)[WizardViewModel::class.java]
        await { !vm.busy && vm.currentProjectId != null }
    }
    @After fun teardown() {
        store.clear()
        scheduler.runCurrent()
        Dispatchers.resetMain()
    }
    private fun await(condition: () -> Boolean) {
        val end = System.nanoTime() + 20_000_000_000L
        while (System.nanoTime() < end) {
            scheduler.runCurrent()
            if (condition()) return
            Thread.sleep(10)
        }
        fail("Timeout: busy=${vm.busy}, message=${vm.portableMessage}, error=${vm.modZipImportError}")
    }
    private fun zip(withCharacter: Boolean = false, mixed: Boolean = false): Uri {
        val file = File(app.cacheDir, "tarde-test.zip")
        val opponent = if (withCharacter) "Hero_Day" else "dad"
        val metadata = """{"version":"2.2.4","songName":"Tarde","artist":"Example","playData":{"difficulties":["easy","normal","hard"],"characters":{"player":"bf","opponent":"$opponent"},"stage":"mainStage"},"timeChanges":[{"t":0,"bpm":120}]}"""
        val chart = """{"version":"2.0.0","scrollSpeed":{"default":1},"notes":{"easy":[{"t":100,"d":0}],"normal":[{"t":200,"d":1}],"hard":[{"t":300,"d":2}]},"events":[]}"""
        ZipOutputStream(file.outputStream()).use { out ->
            fun entry(name: String, bytes: ByteArray) { out.putNextEntry(ZipEntry(name)); out.write(bytes); out.closeEntry() }
            entry("data/songs/tarde/tarde-metadata.json", metadata.toByteArray())
            entry("data/songs/tarde/tarde-chart.json", chart.toByteArray())
            entry("songs/tarde/Inst.ogg", javaClass.getResourceAsStream("/psych-fixture/Inst.ogg")!!.use { it.readBytes() })
            entry("scripts/original.hx", "trace('original');".toByteArray())
            if (mixed) entry("data/tarde/tarde-hard.json", """{"song":{"song":"tarde","bpm":120.5,"notes":[{"sectionNotes":[[500,7,0,99]]}]}}""".toByteArray())
            if (withCharacter) {
                entry("data/characters/Hero_Day.json", """{"version":"1.0.2","name":"Hero","assetPath":"characters/Hero_Day","renderType":"sparrow","scale":1,"unknown":"kept","animations":[{"name":"idle","prefix":"idle","frameRate":24,"looped":false,"offsets":[0,0]}]}""".toByteArray())
                entry("images/characters/Hero_Day.xml", """<TextureAtlas imagePath="Hero_Day.png"><SubTexture name="idle0000" x="0" y="0" width="1" height="1"/></TextureAtlas>""".toByteArray())
                entry("images/characters/Hero_Day.png", javaClass.getResourceAsStream("/psych-fixture/dad.png")!!.use { it.readBytes() })
                entry("data/levels/existing.json", "{}".toByteArray())
            }
        }
        return Uri.fromFile(file)
    }
    private fun psychZip(advanced: Boolean = false, numericType: Boolean = false): Uri {
        val file = File(app.cacheDir, "psych-events-test.zip")
        val chart = if (!advanced) """{"song":{"song":"test","bpm":120,"speed":1,"player1":"bf","player2":"dad","stage":"stage","events":[[100,[["Hey!","0",""]]]],"notes":[{"sectionBeats":4,"mustHitSection":true,"sectionNotes":[[100,0,0]]}]}}""" else """{"song":{"song":"test","bpm":120.5,"speed":1,"player1":"bf","player2":"dad","stage":"stage","customSong":{"huge":123456789012345678901234567890},"events":[[100,[["Hey!","0",""]]]],"notes":[{"lengthInSteps":16,"mustHitSection":true,"sectionNotes":[[100,0,500,"Hurt Note"]],"customSection":{"keep":true}},{"sectionBeats":3,"lengthInSteps":12,"mustHitSection":false,"altAnim":true,"gfSection":true,"changeBPM":true,"bpm":180,"sectionNotes":[[2200,0,300]]},{"sectionBeats":4,"mustHitSection":true,"changeBPM":true,"bpm":90,"sectionNotes":[[3100,5,100]]}]}}"""
        ZipOutputStream(file.outputStream()).use { out ->
            fun entry(name: String, bytes: ByteArray) { out.putNextEntry(ZipEntry(name)); out.write(bytes); out.closeEntry() }
            entry("pack.json", """{"name":"Test","description":"Test"}""".toByteArray())
            entry("data/test/test.json", (if (numericType) chart.replace("[100,0,0]", "[100,0,0,0]") else chart).toByteArray())
            entry("songs/test/Inst.ogg", javaClass.getResourceAsStream("/psych-fixture/Inst.ogg")!!.use { it.readBytes() })
        }
        return Uri.fromFile(file)
    }
    @Test fun explicitV1SurvivesAutosaveAndReopening() {
        val projects = com.fnfmodmaker.project.ProjectStore(File(app.filesDir, "projects"), File(app.applicationInfo.dataDir))
        val old = vm.currentProjectId!!
        val versioned = projects.newId()
        projects.save(versioned, projects.load(old).config.deepCopy().apply { addProperty("chartSchemaVersion", 1) })
        vm.switchProject(versioned); await { !vm.busy }
        assertEquals(versioned, vm.currentProjectId)
        assertEquals(1, vm.config.chartSchemaVersion)
        vm.setModTitle("Version One")
        vm.forceAutoSave()
        await { projects.load(versioned).config.get("modTitle").asString == "Version One" }
        assertEquals(1, projects.load(versioned).config.get("chartSchemaVersion").asInt)
        vm.switchProject(old); await { !vm.busy }
        vm.switchProject(versioned); await { !vm.busy }
        assertEquals("Version One", vm.config.modTitle)
        assertEquals(1, vm.config.chartSchemaVersion)
    }

    // Fixtures emulate a project written by a newer app, not this version's guarded writer.
    private fun seedFuture(id: String, config: com.google.gson.JsonObject) {
        val file = File(app.filesDir, "projects/$id/project.json")
        check(file.parentFile.isDirectory || file.parentFile.mkdirs())
        file.writeText(com.google.gson.JsonObject().apply {
            addProperty("version", 1); addProperty("id", id); addProperty("updatedAt", 1L); add("config", config)
        }.toString())
    }
    private fun seedActive(id: String) { File(app.filesDir, "projects/active.txt").writeText(id) }
    private fun futureConfig(id: String) = com.google.gson.JsonParser.parseString(
        File(app.filesDir, "projects/$id/project.json").readText()).asJsonObject.getAsJsonObject("config")

    private fun validIndependentV2(base: com.google.gson.JsonObject) = base.deepCopy().apply {
        addProperty("chartSchemaVersion", 2)
        add("independentCharts", com.google.gson.JsonParser.parseString("""{"stable":{"id":"stable"}}"""))
        add("charts", com.google.gson.JsonObject())
        add("chartHistories", com.google.gson.JsonObject())
        com.fnfmodmaker.project.requireIndependentChartSchema(this, com.google.gson.Gson())
        com.fnfmodmaker.project.ProjectConfigValidator(com.google.gson.Gson()).validate(this)
    }

    private fun projectFiles(): Map<String, List<Byte>> {
        val root = File(app.filesDir, "projects")
        return root.walkTopDown().filter { it.isFile }.associate { it.relativeTo(root).path to it.readBytes().toList() }
    }

    @Test fun validV2SwitchIsRejectedWithoutChangingActiveProjectOrDisk() {
        val projects = com.fnfmodmaker.project.ProjectStore(File(app.filesDir, "projects"), File(app.applicationInfo.dataDir))
        val originalId = vm.currentProjectId!!
        vm.forceAutoSave(); await { vm.projectSaveStatus == "Guardado en este dispositivo" }
        val target = projects.newId()
        seedFuture(target, validIndependentV2(projects.load(originalId).config))
        val before = projectFiles(); val config = vm.config; val editor = vm.chartEditorState
        vm.switchProject(target); await { !vm.busy }
        assertEquals(originalId, vm.currentProjectId); assertEquals(originalId, projects.activeId())
        assertEquals(config, vm.config); assertEquals(editor, vm.chartEditorState)
        assertTrue(vm.projectMessage.orEmpty().contains("no es compatible", true))
        vm.forceAutoSave(); await { vm.projectSaveStatus == "Guardado en este dispositivo" }
        assertEquals(before, projectFiles())
    }

    @Test fun validV2ActiveProjectDoesNotOpenOrAutosaveAndCompatibleProjectStillOpens() {
        val projects = com.fnfmodmaker.project.ProjectStore(File(app.filesDir, "projects"), File(app.applicationInfo.dataDir))
        val compatible = vm.currentProjectId!!
        vm.forceAutoSave(); await { vm.projectSaveStatus == "Guardado en este dispositivo" }
        val target = projects.newId(); seedFuture(target, validIndependentV2(projects.load(compatible).config))
        store.clear(); scheduler.runCurrent(); seedActive(target)
        val before = projectFiles()
        store = ViewModelStore()
        val factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST") override fun <T : ViewModel> create(modelClass: Class<T>): T = WizardViewModel(app) as T
        }
        vm = ViewModelProvider(store, factory)[WizardViewModel::class.java]
        await { !vm.busy }
        assertNull(vm.currentProjectId); assertThrows(IllegalArgumentException::class.java) { projects.activeId() }; assertEquals(target, File(app.filesDir, "projects/active.txt").readText())
        val autoSave = WizardViewModel::class.java.getDeclaredField("autoSaveJob").apply { isAccessible = true }.get(vm)
        assertNull(autoSave)
        assertTrue(vm.projectMessage.orEmpty().contains("no es compatible", true))
        vm.forceAutoSave(); scheduler.runCurrent()
        assertEquals(before, projectFiles())
        vm.switchProject(compatible); await { !vm.busy }
        assertEquals(compatible, vm.currentProjectId); assertEquals(compatible, projects.activeId())
        assertEquals(before.filterKeys { it.startsWith("$target/") }, projectFiles().filterKeys { it.startsWith("$target/") })
    }

    @Test fun v2BackupIsNotRestoredOverOriginalDuringRejectedSwitch() {
        val projects = com.fnfmodmaker.project.ProjectStore(File(app.filesDir, "projects"), File(app.applicationInfo.dataDir))
        val originalId = vm.currentProjectId!!
        vm.forceAutoSave(); await { vm.projectSaveStatus == "Guardado en este dispositivo" }
        val target = projects.newId(); seedFuture(target, validIndependentV2(projects.load(originalId).config))
        val primary = File(app.filesDir, "projects/$target/project.json")
        val backup = File(app.filesDir, "projects/$target/project.json.bak")
        check(primary.renameTo(backup)); val before = projectFiles(); val config = vm.config
        vm.switchProject(target); await { !vm.busy }
        assertEquals(originalId, vm.currentProjectId); assertEquals(originalId, projects.activeId())
        assertEquals(config, vm.config); assertFalse(primary.exists())
        vm.forceAutoSave(); await { vm.projectSaveStatus == "Guardado en este dispositivo" }
        assertEquals(before, projectFiles())
    }

    @Test fun startupWithCorruptPrimaryAndV2BackupDoesNotRestoreOrActivateSession() {
        val projects = com.fnfmodmaker.project.ProjectStore(File(app.filesDir, "projects"), File(app.applicationInfo.dataDir))
        val compatible = vm.currentProjectId!!; val target = projects.newId()
        seedFuture(target, validIndependentV2(projects.load(compatible).config))
        val primary = File(app.filesDir, "projects/$target/project.json")
        val backup = File(app.filesDir, "projects/$target/project.json.bak")
        check(primary.renameTo(backup)); primary.writeText("broken")
        File(app.filesDir, "projects/$target/project.json.tmp").writeText("interrupted evidence")
        store.clear(); scheduler.runCurrent(); seedActive(target)
        val before = projectFiles(); store = ViewModelStore()
        val factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST") override fun <T : ViewModel> create(modelClass: Class<T>): T = WizardViewModel(app) as T
        }
        vm = ViewModelProvider(store, factory)[WizardViewModel::class.java]; await { !vm.busy }
        assertNull(vm.currentProjectId); assertThrows(IllegalArgumentException::class.java) { projects.activeId() }; assertEquals(target, File(app.filesDir, "projects/active.txt").readText())
        assertTrue(vm.projectMessage.orEmpty().contains("no es compatible", true))
        vm.forceAutoSave(); scheduler.runCurrent(); assertEquals(before, projectFiles())
        vm.switchProject(compatible); await { !vm.busy }
        assertEquals(compatible, vm.currentProjectId)
        assertEquals(before.filterKeys { it.startsWith("$target/") }, projectFiles().filterKeys { it.startsWith("$target/") })
    }

    @Test fun rejectionKeepsRealUndoHistoryAndPlaybackState() {
        val projects = com.fnfmodmaker.project.ProjectStore(File(app.filesDir, "projects"), File(app.applicationInfo.dataDir))
        val previous = vm.currentProjectId!!
        vm.initChartHistory(); vm.addChartNote(com.fnfmodmaker.model.ChartNote(timeMs = 100, lane = 1))
        assertTrue(vm.chartEditorState.canUndo)
        vm.forceAutoSave(); await { vm.projectSaveStatus == "Guardado en este dispositivo" }
        val target = projects.newId(); seedFuture(target, validIndependentV2(projects.load(previous).config))
        val config = vm.config; val editor = vm.chartEditorState; val playing = vm.livePlaying; val loading = vm.liveLoading
        val before = projectFiles(); vm.switchProject(target); await { !vm.busy }
        assertEquals(previous, vm.currentProjectId); assertEquals(previous, projects.activeId())
        assertEquals(config, vm.config); assertEquals(editor, vm.chartEditorState)
        assertEquals(playing, vm.livePlaying); assertEquals(loading, vm.liveLoading)
        assertEquals(before, projectFiles()); assertTrue(vm.chartEditorState.canUndo)
        vm.undo(); assertTrue(vm.chartEditorState.canRedo)
        vm.redo(); assertEquals(config, vm.config)
    }

    @Test fun characterUndoAndRedoFromProjectACannotChangeNewProjectB() {
        val original = vm.currentProjectId!!
        vm.setCharName("A primero"); vm.setCharName("A segundo"); vm.undoCharacterEdit()
        assertEquals("A primero", vm.config.character.displayName)
        assertTrue(vm.characterUndoAvailable); assertTrue(vm.characterRedoAvailable)
        vm.switchProject(); await { !vm.busy }
        assertNotNull(vm.currentProjectId); assertNotEquals(original, vm.currentProjectId)
        val b = vm.config; val character = b.character
        assertFalse(vm.characterUndoAvailable); assertFalse(vm.characterRedoAvailable)
        vm.undoCharacterEdit(); vm.redoCharacterEdit()
        assertEquals(character, vm.config.character); assertEquals(b, vm.config)
        assertFalse(vm.characterUndoAvailable); assertFalse(vm.characterRedoAvailable)
    }

    @Test fun reopenedProjectStartsWithEmptyCharacterHistory() {
        val a = vm.currentProjectId!!
        vm.setCharName("Edición guardada de A"); assertTrue(vm.characterUndoAvailable)
        vm.switchProject(); await { !vm.busy }
        assertNotEquals(a, vm.currentProjectId)
        vm.switchProject(a); await { !vm.busy }
        assertEquals(a, vm.currentProjectId); assertEquals("Edición guardada de A", vm.config.character.displayName)
        val before = vm.config
        assertFalse(vm.characterUndoAvailable); assertFalse(vm.characterRedoAvailable)
        vm.undoCharacterEdit(); vm.redoCharacterEdit(); assertEquals(before, vm.config)
        assertFalse(vm.characterUndoAvailable); assertFalse(vm.characterRedoAvailable)
    }

    @Test fun rejectedProjectChangeKeepsCharacterUndoAndRedoUsable() {
        val projects = com.fnfmodmaker.project.ProjectStore(File(app.filesDir, "projects"), File(app.applicationInfo.dataDir))
        val a = vm.currentProjectId!!; val first = vm.config.character
        vm.setCharName("Primero"); val edited = vm.config.character
        vm.setCharName("Segundo"); vm.undoCharacterEdit()
        assertTrue(vm.characterUndoAvailable); assertTrue(vm.characterRedoAvailable)
        val session = vm.projectSession; val before = vm.config
        val incompatible = projects.newId(); seedFuture(incompatible, validIndependentV2(projects.load(a).config))
        vm.switchProject(incompatible); await { !vm.busy }
        assertEquals(a, vm.currentProjectId); assertEquals(session, vm.projectSession); assertEquals(before, vm.config)
        assertTrue(vm.projectMessage.orEmpty().contains("compatible"))
        assertTrue(vm.characterUndoAvailable); assertTrue(vm.characterRedoAvailable)
        vm.redoCharacterEdit(); assertEquals("Segundo", vm.config.character.displayName)
        vm.undoCharacterEdit(); assertEquals(edited, vm.config.character)
        vm.undoCharacterEdit(); assertEquals(first, vm.config.character)
    }

    @Test fun characterUndoRedoRemainsOrderedAndBoundedWithinSession() {
        vm.setCharName("Primero"); val first = vm.config.character
        vm.setCharName("Segundo"); val second = vm.config.character
        vm.setCharName("Tercero"); val third = vm.config.character
        assertTrue(vm.characterUndoAvailable); assertFalse(vm.characterRedoAvailable)
        vm.undoCharacterEdit(); assertEquals(second, vm.config.character); assertTrue(vm.characterRedoAvailable)
        vm.undoCharacterEdit(); assertEquals(first, vm.config.character)
        vm.redoCharacterEdit(); assertEquals(second, vm.config.character)
        vm.redoCharacterEdit(); assertEquals(third, vm.config.character); assertFalse(vm.characterRedoAvailable)
        // A real identity change clears the existing stack before testing the 50-edit bound.
        val selected = vm.config.selectedCharacterId; vm.selectLibraryCharacter(selected)
        assertFalse(vm.characterUndoAvailable); assertFalse(vm.characterRedoAvailable)
        repeat(55) { vm.setCharName("Cambio $it") }
        repeat(50) { assertTrue(vm.characterUndoAvailable); vm.undoCharacterEdit() }
        assertFalse(vm.characterUndoAvailable); assertTrue(vm.characterRedoAvailable)
        assertEquals("Cambio 4", vm.config.character.displayName)
        repeat(50) { assertTrue(vm.characterRedoAvailable); vm.redoCharacterEdit() }
        assertEquals("Cambio 54", vm.config.character.displayName); assertFalse(vm.characterRedoAvailable)
        vm.undoCharacterEdit(); assertTrue(vm.characterRedoAvailable)
        vm.setCharName("Nueva rama"); assertFalse(vm.characterRedoAvailable)
    }

    @Test fun failedActivePreparationAndInstallationKeepCharacterHistory() {
        val root = File(app.filesDir, "projects"); val assetRoot = File(app.applicationInfo.dataDir)
        val projects = com.fnfmodmaker.project.ProjectStore(root, assetRoot)
        val a = vm.currentProjectId!!; val target = projects.newId(); projects.save(target, projects.load(a).config)
        vm.setCharName("Primero"); vm.setCharName("Segundo"); vm.undoCharacterEdit()
        val before = vm.config; val session = vm.projectSession
        val failures = listOf(
            com.fnfmodmaker.project.ProjectStore(root, assetRoot, activeTempWrite = { _, _ -> throw java.io.IOException("prepare failure") }),
            com.fnfmodmaker.project.ProjectStore(root, assetRoot, activeReplace = { _, _ -> throw java.io.IOException("install failure") })
        )
        failures.forEachIndexed { index, failing ->
            WizardViewModel::class.java.getDeclaredField("projectStore").apply { isAccessible = true }.set(vm, failing)
            vm.switchProject(target); await { !vm.busy }
            assertTrue(vm.projectMessage.orEmpty().contains(if (index == 0) "prepare failure" else "install failure"))
            assertEquals(a, vm.currentProjectId); assertEquals(session, vm.projectSession); assertEquals(before, vm.config)
            assertTrue(vm.characterUndoAvailable); assertTrue(vm.characterRedoAvailable)
        }
        vm.redoCharacterEdit(); assertEquals("Segundo", vm.config.character.displayName)
        vm.undoCharacterEdit(); assertEquals("Primero", vm.config.character.displayName)
    }

    @Test fun staleAutosaveMustNotOverwriteChangesFromAnotherStore() {
        val root = File(app.filesDir, "projects")
        val other = com.fnfmodmaker.project.ProjectStore(root, File(app.applicationInfo.dataDir))
        val id = vm.currentProjectId!!; val session = vm.projectSession
        vm.setModTitle("Edición local pendiente")
        val external = other.load(id).config.deepCopy().apply { addProperty("modTitle", "Edición externa") }
        other.save(id, external)
        val primary = File(root, "$id/project.json"); val backup = File(root, "$id/project.json.bak")
        val tmp = File(root, "$id/project.json.tmp"); val primaryBefore = primary.readBytes(); val backupBefore = backup.readBytes()
        vm.forceAutoSave(); await { vm.projectSaveStatus != "Guardando…" }
        assertEquals("Error al guardar", vm.projectSaveStatus)
        assertTrue(vm.projectMessage.orEmpty().contains("conflicto", ignoreCase = true))
        assertEquals(id, vm.currentProjectId); assertEquals(session, vm.projectSession)
        assertEquals("Edición local pendiente", vm.config.modTitle)
        assertArrayEquals(primaryBefore, primary.readBytes()); assertArrayEquals(backupBefore, backup.readBytes()); assertFalse(tmp.exists())
        assertEquals("Edición externa", other.load(id).config.get("modTitle").asString)
    }

    @Test fun staleSnapshotMustBlockProjectSwitchAndPreserveSession() {
        val root = File(app.filesDir, "projects")
        val other = com.fnfmodmaker.project.ProjectStore(root, File(app.applicationInfo.dataDir))
        val id = vm.currentProjectId!!; val session = vm.projectSession
        val target = other.newId(); other.save(target, other.load(id).config)
        vm.setModTitle("Pendiente local")
        other.save(id, other.load(id).config.deepCopy().apply { addProperty("modTitle", "Cambio de otra sesión") })
        val primary = File(root, "$id/project.json"); val backup = File(root, "$id/project.json.bak")
        val active = File(root, "active.txt"); val p = primary.readBytes(); val b = backup.readBytes(); val a = active.readBytes()
        vm.switchProject(target); await { !vm.busy }
        assertTrue(vm.projectMessage.orEmpty().contains("conflicto", ignoreCase = true))
        assertEquals(id, vm.currentProjectId); assertEquals(session, vm.projectSession); assertEquals("Pendiente local", vm.config.modTitle)
        assertArrayEquals(p, primary.readBytes()); assertArrayEquals(b, backup.readBytes()); assertArrayEquals(a, active.readBytes())
        assertEquals("Cambio de otra sesión", other.load(id).config.get("modTitle").asString)
    }

    @Test fun autosaveSessionStartsOnlyForOpenedProjectsAndCancelsOnSwitch() {
        fun job() = WizardViewModel::class.java.getDeclaredField("autoSaveJob").apply { isAccessible = true }.get(vm) as kotlinx.coroutines.Job?
        val projects = com.fnfmodmaker.project.ProjectStore(File(app.filesDir, "projects"), File(app.applicationInfo.dataDir))
        val first = vm.currentProjectId!!; val originalJob = job()!!; assertTrue(originalJob.isActive)
        val future = projects.newId(); seedFuture(future, validIndependentV2(projects.load(first).config))
        val before = projectFiles(); vm.switchProject(future); await { !vm.busy }
        assertSame(originalJob, job()); assertTrue(originalJob.isActive)
        assertEquals(first, vm.currentProjectId); assertEquals(before, projectFiles())
        val compatible = projects.newId(); projects.save(compatible, projects.load(first).config)
        vm.switchProject(compatible); await { !vm.busy }
        assertEquals(compatible, vm.currentProjectId); assertTrue(originalJob.isCancelled)
        val next = job()!!; assertNotSame(originalJob, next); assertTrue(next.isActive)
        store.clear(); scheduler.runCurrent(); assertTrue(next.isCancelled)
    }

    @Test fun stalePlaybackCallbackCannotChangeNewSessionAndRejectedSwitchKeepsOldSession() {
        val projects = com.fnfmodmaker.project.ProjectStore(File(app.filesDir, "projects"), File(app.applicationInfo.dataDir))
        val current = vm.currentProjectId!!; val session = vm.projectSession
        val future = projects.newId(); seedFuture(future, validIndependentV2(projects.load(current).config))
        val before = vm.chartEditorState
        vm.switchProject(future); await { !vm.busy }
        assertEquals(current, vm.currentProjectId); assertEquals(session, vm.projectSession); assertEquals(before, vm.chartEditorState)
        val compatible = projects.newId(); projects.save(compatible, projects.load(current).config)
        vm.switchProject(compatible); await { !vm.busy }; assertEquals(compatible, vm.currentProjectId)
        assertNotEquals(session, vm.projectSession)
        vm.seekTo(1234); val newState = vm.chartEditorState; val newConfig = vm.config
        val oldPlayer = android.media.MediaPlayer()
        try { vm.onPlaybackComplete(oldPlayer, session) } finally { oldPlayer.release() }
        assertEquals(newState, vm.chartEditorState); assertEquals(newConfig, vm.config); assertEquals(compatible, vm.currentProjectId)
    }

    @Test fun manualRecoveryRequiresConfirmationThenOpensWithResourceAndUndo() {
        val projects = com.fnfmodmaker.project.ProjectStore(File(app.filesDir, "projects"), File(app.applicationInfo.dataDir))
        val original = vm.currentProjectId!!; val target = projects.newId()
        val config = projects.load(original).config.deepCopy().apply { addProperty("chartSchemaVersion", 1) }
        val audio = File(projects.assets(target), "recovered.ogg").apply { writeText("audio original") }
        config.getAsJsonArray("songs")[0].asJsonObject.add("instrumental", com.google.gson.JsonParser.parseString(
            """{"path":${com.google.gson.Gson().toJson(audio.absolutePath)},"displayName":"recovered.ogg","durationMs":1000}"""))
        projects.save(target, config); projects.save(target, config)
        val primary = File(app.filesDir, "projects/$target/project.json"); val backup = File(app.filesDir, "projects/$target/project.json.bak")
        assertTrue(primary.delete()); val backupBytes = backup.readBytes()
        store.clear(); scheduler.runCurrent(); store = ViewModelStore()
        val factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST") override fun <T : ViewModel> create(modelClass: Class<T>): T = WizardViewModel(app) as T
        }
        vm = ViewModelProvider(store, factory)[WizardViewModel::class.java]; await { !vm.busy }
        assertEquals(original, vm.currentProjectId); val before = projectFiles()
        vm.requestProjectRecovery(target); assertEquals(target, vm.recoveryConfirmationId)
        vm.cancelProjectRecovery(); assertNull(vm.recoveryConfirmationId); assertEquals(before, projectFiles())
        vm.confirmProjectRecovery(); scheduler.runCurrent(); assertEquals(before, projectFiles())
        vm.requestProjectRecovery(target); vm.confirmProjectRecovery(); await { !vm.busy }
        assertTrue(vm.projectMessage.orEmpty().contains("recuperada", true)); assertArrayEquals(backupBytes, backup.readBytes())
        assertArrayEquals(backupBytes, primary.readBytes()); assertEquals(original, vm.currentProjectId)
        vm.switchProject(target); await { !vm.busy }; assertEquals(target, vm.currentProjectId)
        assertEquals("audio original", File(vm.config.songs[0].instrumental!!.path).readText())
        vm.initChartHistory(); vm.addChartNote(com.fnfmodmaker.model.ChartNote(timeMs = 345, lane = 2))
        assertTrue(vm.chartEditorState.canUndo); vm.undo(); assertTrue(vm.chartEditorState.canRedo); vm.redo()
        assertTrue(vm.activeChartDraft.notes.any { it.timeMs == 345L && it.lane == 2 })
        assertArrayEquals(backupBytes, backup.readBytes())
        vm.setModTitle("Recuperado y guardado"); vm.forceAutoSave()
        await { projects.load(target).config.get("modTitle").asString == "Recuperado y guardado" }
        assertEquals("Recuperado y guardado", projects.load(target).config.get("modTitle").asString)
        assertArrayEquals(backupBytes, backup.readBytes())
    }

    @Test fun incompatibleActiveProjectAllowsSafeRecovery() {
        val projects = com.fnfmodmaker.project.ProjectStore(File(app.filesDir, "projects"), File(app.applicationInfo.dataDir))
        val compatible = vm.currentProjectId!!
        val futureId = projects.newId()
        val future = projects.load(compatible).config.deepCopy().apply {
            addProperty("chartSchemaVersion", 2)
            add("independentCharts", com.google.gson.JsonParser.parseString("""{"psych":{"normal":[1]}}"""))
        }
        seedFuture(futureId, future)
        seedActive(futureId)
        val file = File(app.filesDir, "projects/$futureId/project.json")
        val bytes = file.readBytes()
        store.clear(); scheduler.runCurrent()
        store = ViewModelStore()
        val factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = WizardViewModel(app) as T
        }
        vm = ViewModelProvider(store, factory)[WizardViewModel::class.java]
        await { !vm.busy }
        assertNull(vm.currentProjectId)
        assertThrows(IllegalArgumentException::class.java) { projects.activeId() }; assertEquals(futureId, File(app.filesDir, "projects/active.txt").readText())
        assertTrue(vm.projects.any { it.id == compatible })
        vm.forceAutoSave(); scheduler.runCurrent()
        assertArrayEquals(bytes, file.readBytes())
        vm.switchProject(futureId); await { !vm.busy }
        assertNull(vm.currentProjectId)
        assertThrows(IllegalArgumentException::class.java) { projects.activeId() }; assertEquals(futureId, File(app.filesDir, "projects/active.txt").readText())
        assertArrayEquals(bytes, file.readBytes())
        vm.switchProject(compatible); await { !vm.busy }
        assertEquals(compatible, vm.currentProjectId)
        assertEquals(compatible, projects.activeId())
        assertArrayEquals(bytes, file.readBytes())
    }

    @Test fun recoveryListsIncompatibleAndAllowsPortableOperationsWithoutActiveProject() {
        val projects = com.fnfmodmaker.project.ProjectStore(File(app.filesDir, "projects"), File(app.applicationInfo.dataDir))
        val compatible = vm.currentProjectId!!
        val compatibleConfig = projects.load(compatible).config
        val futureId = projects.newId()
        val future = compatibleConfig.deepCopy().apply {
            addProperty("modTitle", "Proyecto futuro")
            addProperty("chartSchemaVersion", 99)
            add("independentCharts", com.google.gson.JsonParser.parseString("""{"psych":{"normal":[1]}}"""))
        }
        seedFuture(futureId, future); seedActive(futureId)
        val activeFile = File(app.filesDir, "projects/active.txt")
        val activeBefore = activeFile.readBytes()
        val futureDir = File(app.filesDir, "projects/$futureId")
        fun files() = futureDir.walkTopDown().filter { it.isFile }.associate { it.relativeTo(futureDir).path to it.readBytes().toList() }
        val before = files()
        val input = File(app.cacheDir, "recovery-source.fnfproject")
        com.fnfmodmaker.project.PortableProject(File(app.applicationInfo.dataDir)).export(compatibleConfig, projects.assets(compatible), input)
        store.clear(); scheduler.runCurrent(); store = ViewModelStore()
        val factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = WizardViewModel(app) as T
        }
        vm = ViewModelProvider(store, factory)[WizardViewModel::class.java]
        await { !vm.busy }
        assertNull(vm.currentProjectId)
        assertTrue(vm.projects.single { it.id == futureId }.problem!!.contains("Charts incompatibles"))
        assertNull(vm.projects.single { it.id == compatible }.problem)
        assertThrows(IllegalArgumentException::class.java) { projects.activeId() }; assertEquals(futureId, File(app.filesDir, "projects/active.txt").readText())

        vm.preparePortableExport(futureId); await { !vm.busy }
        assertNull(vm.portableExportName)
        assertTrue(vm.portableMessage.orEmpty().contains("No se pudo exportar"))
        assertEquals(before, files()); assertThrows(IllegalArgumentException::class.java) { projects.activeId() }; assertEquals(futureId, File(app.filesDir, "projects/active.txt").readText())
        assertArrayEquals(activeBefore, activeFile.readBytes())
        vm.preparePortableExport(compatible); await { !vm.busy }
        assertNotNull(vm.portableMessage, vm.portableExportName)
        val output = File(app.cacheDir, "recovery-copy.fnfproject")
        vm.savePortableExport(Uri.fromFile(output)); await { !vm.busy }
        assertTrue(output.isFile)
        val portable = com.fnfmodmaker.project.PortableProject(File(app.applicationInfo.dataDir))
        val prepared = portable.prepare(output, projects.newImportStage())
        assertEquals(compatibleConfig.get("charts"), prepared.config.get("charts"))
        projects.discardImportStage(prepared.directory)
        assertThrows(IllegalArgumentException::class.java) { projects.activeId() }; assertEquals(futureId, File(app.filesDir, "projects/active.txt").readText())
        assertArrayEquals(activeBefore, activeFile.readBytes())

        val known = projects.list().map { it.id }.toSet()
        vm.preparePortableImport(Uri.fromFile(input)); await { !vm.busy }
        assertNotNull(vm.portableMessage, vm.portableImportSummary)
        vm.confirmPortableImport(); await { !vm.busy }
        assertEquals(1, projects.list().count { it.id !in known })
        assertNull(vm.currentProjectId); assertThrows(IllegalArgumentException::class.java) { projects.activeId() }; assertEquals(futureId, File(app.filesDir, "projects/active.txt").readText())
        assertArrayEquals(activeBefore, activeFile.readBytes())
        assertEquals(before, files())
        vm.switchProject(compatible); await { !vm.busy }
        assertEquals(compatible, vm.currentProjectId); assertEquals(compatible, projects.activeId())
        assertEquals(before, files())
    }

    @Test fun recoveryCanCreateNewProjectWithoutChangingIncompatibleProject() {
        val projects = com.fnfmodmaker.project.ProjectStore(File(app.filesDir, "projects"), File(app.applicationInfo.dataDir))
        val futureId = projects.newId()
        val future = projects.load(vm.currentProjectId!!).config.deepCopy().apply { addProperty("chartSchemaVersion", 99) }
        seedFuture(futureId, future); seedActive(futureId)
        val file = File(app.filesDir, "projects/$futureId/project.json")
        val bytes = file.readBytes()
        store.clear(); scheduler.runCurrent(); store = ViewModelStore()
        val factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = WizardViewModel(app) as T
        }
        vm = ViewModelProvider(store, factory)[WizardViewModel::class.java]
        await { !vm.busy }; assertNull(vm.currentProjectId)
        assertThrows(IllegalArgumentException::class.java) { projects.activeId() }; assertEquals(futureId, File(app.filesDir, "projects/active.txt").readText())
        vm.switchProject(); await { !vm.busy }
        assertNotNull(vm.currentProjectId); assertNotEquals(futureId, vm.currentProjectId)
        assertEquals(vm.currentProjectId, projects.activeId())
        assertArrayEquals(bytes, file.readBytes())
    }

    @Test fun failedExportOrSwitchDoesNotSavePendingEditsOrChangeActive() {
        val projects = com.fnfmodmaker.project.ProjectStore(File(app.filesDir, "projects"), File(app.applicationInfo.dataDir))
        val current = vm.currentProjectId!!
        val incompatible = projects.newId()
        seedFuture(incompatible, projects.load(current).config.deepCopy().apply { addProperty("chartSchemaVersion", 99) })
        val currentFile = File(app.filesDir, "projects/$current/project.json")
        val activeFile = File(app.filesDir, "projects/active.txt")
        val oldCurrent = currentFile.readBytes(); val oldActive = activeFile.readBytes()
        vm.setModTitle("Pendiente de guardar")
        vm.preparePortableExport(incompatible); await { !vm.busy }
        assertTrue(vm.portableMessage.orEmpty().contains("No se pudo exportar"))
        assertArrayEquals(oldCurrent, currentFile.readBytes()); assertArrayEquals(oldActive, activeFile.readBytes())
        vm.switchProject(incompatible); await { !vm.busy }
        assertTrue(vm.projectMessage.orEmpty().contains("No se pudo abrir"))
        assertArrayEquals(oldCurrent, currentFile.readBytes()); assertArrayEquals(oldActive, activeFile.readBytes())
        assertEquals(current, vm.currentProjectId)
        assertEquals("Pendiente de guardar", vm.config.modTitle)
    }

    @Test fun activePointerPreparationFailureDoesNotSavePreviousPendingEdits() {
        val root = File(app.filesDir, "projects")
        val projects = com.fnfmodmaker.project.ProjectStore(root, File(app.applicationInfo.dataDir))
        val current = vm.currentProjectId!!; val target = projects.newId()
        projects.save(target, projects.load(current).config)
        val primary = File(root, "active.txt"); val currentFile = File(root, "$current/project.json")
        val oldActive = primary.readBytes(); val oldProject = currentFile.readBytes()
        // An unreadable candidate forces selection to fail before atomic installation.
        val backup = File(root, "active.txt.bak"); assertFalse(backup.exists()); assertTrue(backup.mkdir())
        vm.setModTitle("Edición pendiente que no debe guardarse")
        vm.switchProject(target); await { !vm.busy }
        assertTrue(vm.projectMessage.orEmpty().contains("No se pudo abrir"))
        assertEquals(current, vm.currentProjectId)
        assertEquals("Edición pendiente que no debe guardarse", vm.config.modTitle)
        assertArrayEquals(oldActive, primary.readBytes()); assertArrayEquals(oldProject, currentFile.readBytes())
    }

    @Test fun installFailureAfterPreparationKeepsPreviousSessionAndPendingEdits() {
        val root = File(app.filesDir, "projects"); val assetRoot = File(app.applicationInfo.dataDir)
        val projects = com.fnfmodmaker.project.ProjectStore(root, assetRoot)
        val current = vm.currentProjectId!!; val target = projects.newId(); projects.save(target, projects.load(current).config)
        val primary = File(root, "active.txt"); val backup = File(root, "active.txt.bak").apply { writeText("backup evidence") }
        val temp = File(root, "active.txt.tmp").apply { writeText("temp evidence") }
        val before = listOf(primary, backup, temp).map { it.readBytes().toList() }
        var prepared = false
        val failing = com.fnfmodmaker.project.ProjectStore(root, assetRoot,
            afterActivePrepared = { prepared = true }, activeReplace = { _, _ -> throw java.io.IOException("install failure") })
        WizardViewModel::class.java.getDeclaredField("projectStore").apply { isAccessible = true }.set(vm, failing)
        vm.setModTitle("Cambios pendientes"); val session = vm.projectSession; val editor = vm.chartEditorState
        vm.switchProject(target); await { !vm.busy }
        assertTrue(prepared); assertTrue(vm.projectMessage.orEmpty().contains("install failure"))
        assertEquals(current, vm.currentProjectId); assertEquals(session, vm.projectSession)
        assertEquals("Cambios pendientes", vm.config.modTitle); assertEquals(editor, vm.chartEditorState)
        assertEquals(before, listOf(primary, backup, temp).map { it.readBytes().toList() })
        val saved = projects.load(current)
        assertEquals("Cambios pendientes", saved.config.get("modTitle").asString)
        // The old session must keep the version from that successful save even though
        // installing the new active pointer failed. A further edit must save normally.
        vm.setModTitle("Guardado posterior")
        vm.forceAutoSave(); await { vm.projectSaveStatus != "Guardando…" }
        assertEquals("Guardado en este dispositivo", vm.projectSaveStatus)
        val after = projects.load(current)
        assertEquals("Guardado posterior", after.config.get("modTitle").asString)
        assertNotEquals(saved.version, after.version)
        assertEquals(current, projects.activeId())
        assertTrue(root.listFiles().orEmpty().none { it.name.startsWith("active-selection-") })
    }

    @Test fun previousSaveFailureAfterPreparationKeepsPreviousSessionAndPointer() {
        val root = File(app.filesDir, "projects"); val assetRoot = File(app.applicationInfo.dataDir)
        val projects = com.fnfmodmaker.project.ProjectStore(root, assetRoot)
        val current = vm.currentProjectId!!; val target = projects.newId(); projects.save(target, projects.load(current).config)
        val primary = File(root, "active.txt"); val oldActive = primary.readBytes()
        val oldProject = File(root, "$current/project.json"); val beforeProject = oldProject.readBytes()
        val backup = File(root, "active.txt.bak").apply { writeText("backup evidence") }
        val temp = File(root, "active.txt.tmp").apply { writeText("temp evidence") }
        val b = backup.readBytes(); val t = temp.readBytes(); var prepared = false
        val failing = com.fnfmodmaker.project.ProjectStore(root, assetRoot, afterActivePrepared = { prepared = true },
            beforeSaveWrite = { id -> if (id == current) throw java.io.IOException("previous save failure") })
        WizardViewModel::class.java.getDeclaredField("projectStore").apply { isAccessible = true }.set(vm, failing)
        vm.setModTitle("Pendiente de guardar"); val session = vm.projectSession; val editor = vm.chartEditorState
        vm.switchProject(target); await { !vm.busy }
        assertTrue(prepared); assertTrue(vm.projectMessage.orEmpty().contains("previous save failure"))
        assertEquals(current, vm.currentProjectId); assertEquals(session, vm.projectSession)
        assertEquals("Pendiente de guardar", vm.config.modTitle); assertEquals(editor, vm.chartEditorState)
        assertArrayEquals(oldActive, primary.readBytes()); assertArrayEquals(beforeProject, oldProject.readBytes())
        assertArrayEquals(b, backup.readBytes()); assertArrayEquals(t, temp.readBytes())
        assertTrue(root.listFiles().orEmpty().none { it.name.startsWith("active-selection-") })
    }

    @Test fun unknownJsonSurvivesAutosaveDuplicationAndPortableExport() {
        val projects = com.fnfmodmaker.project.ProjectStore(File(app.filesDir, "projects"), File(app.applicationInfo.dataDir))
        val first = vm.currentProjectId!!
        val sourceId = projects.newId()
        val fake = File(app.filesDir, "not-an-asset.ogg").apply { writeText("metadata only") }
        val custom = com.google.gson.JsonParser.parseString("""{"path":"relative/file","displayName":"custom","uri":"not-an-asset","nested":[{"path":${com.google.gson.Gson().toJson(fake.absolutePath)},"displayName":"audio","uri":"content://x"}],"psych":{"path":"charts/file.json"},"vs":{"events":[{"v":{"path":"x"}}]}}""").asJsonObject
        val source = projects.load(first).config.deepCopy().apply {
            add("unknownDocument", custom.deepCopy())
            getAsJsonArray("songs")[0].asJsonObject.add("unknownDocument", custom.deepCopy())
        }
        projects.save(sourceId, source)
        vm.switchProject(sourceId); await { !vm.busy }
        assertEquals(sourceId, vm.currentProjectId)
        vm.setModTitle("Conservado")
        vm.forceAutoSave(); await { projects.load(sourceId).config.get("modTitle").asString == "Conservado" }
        assertEquals(custom, projects.load(sourceId).config.getAsJsonObject("unknownDocument"))
        assertEquals(custom, projects.load(sourceId).config.getAsJsonArray("songs")[0].asJsonObject.getAsJsonObject("unknownDocument"))
        vm.switchProject(duplicate = true); await { !vm.busy }
        assertNotEquals(sourceId, vm.currentProjectId)
        val duplicate = projects.load(vm.currentProjectId!!).config
        assertEquals(custom, duplicate.getAsJsonObject("unknownDocument"))
        assertEquals(custom, duplicate.getAsJsonArray("songs")[0].asJsonObject.getAsJsonObject("unknownDocument"))
        vm.preparePortableExport(vm.currentProjectId!!); await { !vm.busy }
        assertNotNull(vm.portableMessage, vm.portableExportName)
        val output = File(app.cacheDir, "unknown-duplicate.fnfproject")
        vm.savePortableExport(Uri.fromFile(output)); await { !vm.busy }
        java.util.zip.ZipFile(output).use { z ->
            assertEquals(setOf("project.json", "manifest.json"), z.entries().asSequence().map { it.name }.toSet())
            val result = com.google.gson.JsonParser.parseString(z.getInputStream(z.getEntry("project.json")).reader().readText()).asJsonObject
            assertEquals(custom, result.getAsJsonObject("unknownDocument"))
        }
        val known = projects.list().map { it.id }.toSet()
        vm.preparePortableImport(Uri.fromFile(output)); await { !vm.busy }
        assertNotNull(vm.portableMessage, vm.portableImportSummary)
        vm.confirmPortableImport(); await { !vm.busy }
        val imported = projects.list().single { it.id !in known }.id
        assertEquals(custom, projects.load(imported).config.getAsJsonObject("unknownDocument"))
        assertEquals(custom, projects.load(imported).config.getAsJsonArray("songs")[0].asJsonObject.getAsJsonObject("unknownDocument"))
    }

    @Test fun futureChartSchemaIsNotOpenedOrOverwrittenByAutosave() {
        val previous = vm.currentProjectId!!
        val before = vm.config
        val projects = com.fnfmodmaker.project.ProjectStore(File(app.filesDir, "projects"), File(app.applicationInfo.dataDir))
        val futureId = projects.newId()
        val future = projects.load(previous).config.deepCopy().apply {
            addProperty("chartSchemaVersion", 2)
            add("independentCharts", com.google.gson.JsonParser.parseString("""{"psych":{"normal":{"notes":[1]}},"vslice":{"normal":{"notes":[2]}}}"""))
        }
        seedFuture(futureId, future)
        vm.switchProject(futureId); await { !vm.busy }
        assertEquals(previous, vm.currentProjectId)
        assertEquals(before, vm.config)
        assertTrue(vm.projectMessage.orEmpty().contains("versión de charts"))
        assertEquals(previous, projects.activeId())
        assertEquals(future, futureConfig(futureId))
        vm.forceAutoSave(); scheduler.runCurrent()
        assertEquals(future, futureConfig(futureId))
    }

    @Test fun mixedZipWithoutPackDoesNotChangeOrPublishProject() {
        val previous = vm.currentProjectId!!
        val before = vm.config
        val ids = vm.projects.map { it.id }.toSet()
        vm.inspectModZip(zip(mixed = true)); await { !vm.busy }
        assertNull(vm.modZipReview)
        assertTrue(vm.portableMessage.orEmpty().contains("BOTH"))
        assertEquals(previous, vm.currentProjectId)
        assertEquals(before, vm.config)
        assertEquals(ids, vm.projects.map { it.id }.toSet())
        assertTrue(File(app.filesDir, "projects").listFiles().orEmpty().none { it.name.startsWith(".pending-") })
        vm.confirmModZipImport()
        assertEquals(previous, vm.currentProjectId)
        assertEquals(ids, vm.projects.map { it.id }.toSet())
    }

    @Test fun psychEventEditingUndoReopenAndExport() {
        val previous = vm.currentProjectId!!
        vm.inspectModZip(psychZip()); await { !vm.busy }; assertNotNull(vm.modZipImportError, vm.modZipReview)
        vm.confirmModZipImport(); await { !vm.busy }; assertNull(vm.modZipImportError)
        val imported = vm.currentProjectId!!
        vm.openLiveChart()
        assertEquals("""[[100,[["Hey!","0",""]]]]""", vm.livePsychEvents())
        val selected = com.fnfmodmaker.mod.psychEventRefs(vm.livePsychEvents()).single()
        val edited = com.fnfmodmaker.mod.changePsychEvent(vm.livePsychEvents(), selected, "200", "Hey!", "1", "")
        vm.saveLivePsychEvents(edited)
        val duplicated = com.fnfmodmaker.mod.duplicatePsychEvent(edited, com.fnfmodmaker.mod.psychEventRefs(edited).single())
        assertEquals(2, com.fnfmodmaker.mod.psychEventRefs(duplicated).size)
        val moved = com.fnfmodmaker.mod.changePsychEvent(edited, com.fnfmodmaker.mod.psychEventRefs(edited).single(), "300", "Hey!", "0", "")
        vm.saveLivePsychEvents(moved)
        vm.closeLiveChart(); vm.goHome(); vm.switchProject(previous); await { !vm.busy }; vm.switchProject(imported); await { !vm.busy }
        vm.openLiveChart(); vm.undoLiveChartEdit()
        assertEquals("""[[200,[["Hey!","1",""]]]]""", vm.livePsychEvents())
        vm.redoLiveChartEdit()
        assertEquals("""[[300,[["Hey!","0",""]]]]""", vm.livePsychEvents())
        vm.undoLiveChartEdit()
        vm.closeLiveChart()
        val output = File(app.cacheDir, "psych-events-export.zip")
        vm.export(Uri.fromFile(output)); await { !vm.busy }; assertNull(vm.error)
        val files = mutableMapOf<String, ByteArray>()
        java.util.zip.ZipInputStream(output.inputStream()).use { z -> while (true) { val e = z.nextEntry ?: break; files[e.name] = z.readBytes() } }
        val chart = org.json.JSONObject(files.getValue(vm.config.modFolder + "/data/test/test.json").toString(Charsets.UTF_8))
        assertEquals(200, chart.getJSONArray("events").getJSONArray(0).getInt(0))
    }
    @Test fun psychNumericNoteTypesEditUndoPortableAndZipRoundTrip() {
        vm.inspectModZip(psychZip(numericType = true)); await { !vm.busy }; assertNotNull(vm.modZipImportError, vm.modZipReview)
        vm.confirmModZipImport(); await { !vm.busy }; assertNull(vm.modZipImportError)
        val imported = vm.currentProjectId!!
        vm.openLiveChart()
        val original = vm.liveChart!!.notes.single()
        assertEquals("0", original.psychTypeJson)
        assertTrue(vm.editLiveNoteProperties(original, 250, "", "99"))
        vm.undoLiveChartEdit(); assertEquals("0", vm.liveChart!!.notes.single().psychTypeJson)
        vm.redoLiveChartEdit(); assertEquals("99", vm.liveChart!!.notes.single().psychTypeJson)
        vm.closeLiveChart(); vm.switchProject(); await { !vm.busy }; vm.switchProject(imported); await { !vm.busy }
        vm.openLiveChart(); assertEquals("99", vm.liveChart!!.notes.single().psychTypeJson); vm.closeLiveChart()
        vm.preparePortableExport(imported); await { !vm.busy }; assertNotNull(vm.portableMessage, vm.portableExportName)
        val portable = File(app.cacheDir, "numeric-types.fnfproject")
        vm.savePortableExport(Uri.fromFile(portable)); await { !vm.busy }; assertTrue(portable.isFile)
        val known = vm.projects.map { it.id }.toSet()
        vm.preparePortableImport(Uri.fromFile(portable)); await { !vm.busy }; assertNotNull(vm.portableMessage, vm.portableImportSummary)
        vm.confirmPortableImport(); await { !vm.busy }
        val restored = vm.projects.single { it.id !in known }.id
        vm.switchProject(restored); await { !vm.busy }; vm.openLiveChart()
        vm.undoLiveChartEdit(); assertEquals("0", vm.liveChart!!.notes.single().psychTypeJson)
        vm.redoLiveChartEdit(); assertEquals("99", vm.liveChart!!.notes.single().psychTypeJson)
        for (format in com.fnfmodmaker.model.PsychChartFormat.entries) {
            vm.setPsychChartFormat(format); vm.closeLiveChart()
            val output = File(app.cacheDir, "numeric-${format.name}.zip")
            vm.export(Uri.fromFile(output)); await { !vm.busy }; assertNull(vm.error)
            vm.inspectModZip(Uri.fromFile(output)); await { !vm.busy }; assertNotNull(vm.portableMessage, vm.modZipReview)
            vm.confirmModZipImport(); await { !vm.busy }; assertNull(vm.modZipImportError)
            vm.openLiveChart(); assertEquals("99", vm.liveChart!!.notes.single().psychTypeJson)
            assertEquals(250L, vm.liveChart!!.notes.single().lengthMs)
            vm.closeLiveChart(); vm.switchProject(restored); await { !vm.busy }; vm.openLiveChart()
        }
    }
    @Test fun psychSectionsEditPersistHistoryExportAndReimportBothFormats() {
        val previous = vm.currentProjectId!!
        vm.inspectModZip(psychZip(advanced = true)); await { !vm.busy }; assertNotNull(vm.portableMessage, vm.modZipReview)
        vm.confirmModZipImport(); await { !vm.busy }; assertNull(vm.modZipImportError)
        val imported = vm.currentProjectId!!
        assertTrue(vm.duplicateActiveDifficulty("normal", "hard"))
        vm.openLiveChart()
        val original = vm.liveChart!!
        val hard = vm.config.getChart("test", "hard")!!
        assertEquals(3, original.psychLayout.size)
        assertTrue(original.psychLayout[1].altAnim && original.psychLayout[1].gfSection)
        assertEquals(4, original.notes[1].lane) // 0.7.3 relative to opponent section
        val spans = com.fnfmodmaker.model.psychSectionSpans(original.psychLayout, 120.5)
        assertEquals(4 * 60000.0 / 120.5, spans[1].startMs, 0.0001)
        assertEquals(spans[1].startMs + 1000.0, spans[2].startMs, 0.0001)
        assertEquals(120.5, original.bpm, 0.0)
        val sections = original.psychLayout.toMutableList().apply { this[1] = this[1].copy(beats = 2.5, bpm = 240.5, mustHit = true, altAnim = false) }
        val editedNotes = original.notes.toMutableList().apply { this[1] = this[1].copy(timeMs = 2300, lengthMs = 400, kind = "Hurt Note") }
        vm.saveLivePsychSections(sections, editedNotes)
        assertEquals(editedNotes, vm.liveChart!!.notes)
        assertEquals(original.notes[0], vm.liveChart!!.notes[0])
        assertEquals(hard, vm.config.getChart("test", "hard"))
        vm.closeLiveChart(); vm.switchProject(previous); await { !vm.busy }; vm.switchProject(imported); await { !vm.busy }
        vm.openLiveChart(); assertEquals(sections, vm.liveChart!!.psychLayout)
        vm.undoLiveChartEdit(); assertEquals(original.psychLayout, vm.liveChart!!.psychLayout)
        vm.redoLiveChartEdit(); assertEquals(sections, vm.liveChart!!.psychLayout)
        vm.duplicateLivePsychSection(0)
        assertEquals(4, vm.liveChart!!.psychLayout.size)
        assertEquals(4, vm.liveChart!!.notes.size)
        vm.undoLiveChartEdit(); assertEquals(editedNotes.sortedBy { it.timeMs }, vm.liveChart!!.notes.sortedBy { it.timeMs })
        val extra = sections + com.fnfmodmaker.model.PsychSectionData(bpm = 90.0)
        vm.saveLivePsychSections(extra); assertEquals(4, vm.liveChart!!.psychLayout.size)
        vm.saveLivePsychSections(sections); assertEquals(3, vm.liveChart!!.psychLayout.size)
        vm.closeLiveChart()
        vm.preparePortableExport(imported); await { !vm.busy }; assertNotNull(vm.portableMessage, vm.portableExportName)
        val portable = File(app.cacheDir, "sections.fnfproject")
        vm.savePortableExport(Uri.fromFile(portable)); await { !vm.busy }; assertTrue(portable.isFile)
        val knownIds = vm.projects.map { it.id }.toSet()
        vm.preparePortableImport(Uri.fromFile(portable)); await { !vm.busy }; assertNotNull(vm.portableMessage, vm.portableImportSummary)
        vm.confirmPortableImport(); await { !vm.busy }
        val restored = vm.projects.single { it.id !in knownIds }.id
        vm.switchProject(restored); await { !vm.busy }; vm.openLiveChart()
        assertEquals(sections, vm.liveChart!!.psychLayout)
        assertEquals(120.5, vm.liveChart!!.bpm, 0.0)
        vm.undoLiveChartEdit(); assertEquals(4, vm.liveChart!!.psychLayout.size)
        vm.redoLiveChartEdit(); assertEquals(sections, vm.liveChart!!.psychLayout)
        assertEquals(hard, vm.config.getChart("test", "hard"))
        vm.closeLiveChart(); vm.switchProject(imported); await { !vm.busy }; vm.openLiveChart()
        for (format in com.fnfmodmaker.model.PsychChartFormat.entries) {
            vm.setPsychChartFormat(format); vm.closeLiveChart()
            val out = File(app.cacheDir, "sections-${format.name}.zip")
            vm.export(Uri.fromFile(out)); await { !vm.busy }; assertNull(vm.error)
            vm.inspectModZip(Uri.fromFile(out)); await { !vm.busy }; assertNotNull(vm.portableMessage, vm.modZipReview)
            vm.confirmModZipImport(); await { !vm.busy }; assertNull(vm.modZipImportError)
            vm.openLiveChart()
            val roundTrip = vm.liveChart!!
            assertEquals(editedNotes.sortedBy { it.timeMs }, roundTrip.notes.sortedBy { it.timeMs })
            assertEquals(original.psychEventsJson, roundTrip.psychEventsJson)
            assertEquals(240.5, roundTrip.psychLayout[1].bpm, 0.0)
            assertEquals(120.5, roundTrip.bpm, 0.0)
            assertEquals(2.5, roundTrip.psychLayout[1].beats, 0.0)
            assertEquals(10, com.google.gson.JsonParser.parseString(roundTrip.psychLayout[1].sourceJson).asJsonObject.get("lengthInSteps").asInt)
            assertTrue(roundTrip.psychLayout[1].mustHit && roundTrip.psychLayout[1].gfSection)
            assertTrue(roundTrip.psychLayout[0].sourceJson.contains("customSection"))
            assertTrue(roundTrip.psychSongExtras.contains("123456789012345678901234567890"))
            vm.closeLiveChart(); vm.switchProject(imported); await { !vm.busy }; vm.openLiveChart()
        }
    }
    private fun inspect() {
        vm.inspectModZip(zip())
        await { !vm.busy }
        assertNotNull(vm.portableMessage, vm.modZipReview)
        assertEquals(listOf("Tarde"), vm.modZipReview!!.songs)
        val stage = File(app.filesDir, "projects").listFiles()!!.single { it.name.startsWith(".pending-") }
        val asset = File(stage, "assets/imported").listFiles()!!.first()
        assertNotEquals(asset.absoluteFile, asset.canonicalFile)
        assertTrue(asset.isFile)
    }
    @Test fun confirmationOpensVSliceProject() {
        val previous = vm.currentProjectId
        inspect()
        vm.confirmModZipImport()
        assertTrue(vm.modZipImporting)
        assertNotNull(vm.modZipReview)
        await { !vm.busy }
        assertNull(vm.modZipImportError)
        assertNull(vm.modZipReview)
        assertNotEquals(previous, vm.currentProjectId)
        assertEquals(Tool.MOD, vm.tool)
        assertEquals(TargetEngine.VSLICE, vm.config.engine)
        assertEquals("Tarde", vm.config.firstSong.songName)
        assertEquals(200L, vm.config.getChart("tarde", "normal")!!.notes.single().timeMs)
        assertTrue(File(vm.config.firstSong.instrumental!!.path).isFile)
        assertEquals(vm.currentProjectId, File(app.filesDir, "projects/active.txt").readText())
    }
    @Test fun vSliceTempoChangesPersistUndoRedoAndExport() {
        val previous = vm.currentProjectId!!
        vm.inspectModZip(zip()); await { !vm.busy }; vm.confirmModZipImport(); await { !vm.busy }
        val imported = vm.currentProjectId!!
        vm.openLiveChart()
        val first = vm.liveVSliceTempo()
        val edited = com.fnfmodmaker.mod.changeVSliceTempo(first, null, "1000", "180", "3", "8")
        vm.saveLiveVSliceTempo(edited)
        vm.closeLiveChart(); vm.goHome(); vm.switchProject(previous); await { !vm.busy }; vm.switchProject(imported); await { !vm.busy }
        vm.openLiveChart(); assertEquals(edited, vm.liveVSliceTempo())
        val grid = com.fnfmodmaker.model.TouchGrid(vm.liveSong!!.bpmValue, 4, 10_000, com.fnfmodmaker.mod.vSliceGridTempos(vm.liveVSliceTempo()))
        assertEquals(1167L, grid.timeAt(0, 12))
        vm.selectLiveSnap(com.fnfmodmaker.model.RecordingSnap.HALF_BEAT)
        val recordingGrid = vm.recordingTempoGrid()!!
        assertEquals(1167L, com.fnfmodmaker.model.recordedNoteTime(1150, 0, vm.liveSong!!.bpmValue,
            com.fnfmodmaker.model.RecordingSnap.HALF_BEAT, 10_000, recordingGrid))
        vm.selectLiveSnap(com.fnfmodmaker.model.RecordingSnap.FREE)
        assertNull(vm.recordingTempoGrid())
        vm.restoreLiveVSliceTempo(false); assertEquals(first, vm.liveVSliceTempo())
        vm.restoreLiveVSliceTempo(true); assertEquals(edited, vm.liveVSliceTempo())
        vm.closeLiveChart()
        val output = File(app.cacheDir, "tempo-export.zip")
        vm.export(Uri.fromFile(output)); await { !vm.busy }; assertNull(vm.error)
        val files = mutableMapOf<String, ByteArray>()
        java.util.zip.ZipInputStream(output.inputStream()).use { z -> while (true) { val e = z.nextEntry ?: break; files[e.name] = z.readBytes() } }
        val meta = com.google.gson.JsonParser.parseString(files.getValue(vm.config.modFolder + "/data/songs/tarde/tarde-metadata.json").toString(Charsets.UTF_8)).asJsonObject
        assertEquals(com.google.gson.JsonParser.parseString(edited), meta.get("timeChanges"))
    }
    @Test fun bothTempoPersistsAndExportsWithoutChangingPsychChart() {
        val project = vm.currentProjectId!!
        vm.setEngine(TargetEngine.BOTH)
        vm.setModTitle("Both Tempo")
        assertTrue(vm.setActiveSongName("Shared"))
        vm.setActiveSongBpm("120")
        val source = File(app.cacheDir, "both-inst.ogg")
        javaClass.getResourceAsStream("/psych-fixture/Inst.ogg")!!.use { source.writeBytes(it.readBytes()) }
        vm.pickActiveSongAudio(Uri.fromFile(source), com.fnfmodmaker.model.AudioSlot.INSTRUMENTAL)
        await { !vm.busy }; assertNull(vm.error)
        vm.openLiveChart()
        val duration = vm.liveSong!!.instrumental!!.durationMs
        val before = com.google.gson.JsonParser.parseString(com.fnfmodmaker.mod.JsonBuilders.psychChart(vm.config, vm.liveSong!!, duration, "normal"))
        val initial = vm.liveVSliceTempo()
        var changed = com.fnfmodmaker.mod.changeVSliceTempo(initial, 0, "0", "150", "3", "4", true)
        changed = com.fnfmodmaker.mod.changeVSliceTempo(changed, null, "1000", "180", "3", "8")
        vm.saveLiveVSliceTempo(changed)
        assertEquals("120", vm.liveSong!!.bpm)
        assertEquals(before, com.google.gson.JsonParser.parseString(com.fnfmodmaker.mod.JsonBuilders.psychChart(vm.config, vm.liveSong!!, duration, "normal")))
        vm.selectLiveSnap(com.fnfmodmaker.model.RecordingSnap.HALF_BEAT)
        val grid = vm.recordingTempoGrid()!!
        assertEquals(3, grid.tempoChanges.first().numerator)
        assertEquals(2.5, grid.beatAt(1000.0), 0.0001)
        assertEquals(1167L, com.fnfmodmaker.model.recordedNoteTime(1150, 0, 120, com.fnfmodmaker.model.RecordingSnap.HALF_BEAT, 5000, grid))
        val held = com.fnfmodmaker.model.ChartNote(800, 0)
        assertEquals(367L, com.fnfmodmaker.model.recordedHoldLength(held, 810, 1150, 0, 120,
            com.fnfmodmaker.model.RecordingSnap.HALF_BEAT, 5000, listOf(held), grid))
        val practice = org.json.JSONObject(com.fnfmodmaker.mod.JsonBuilders.vsliceChart(vm.config, vm.liveSong!!, 5000)).getJSONObject("notes").getJSONArray("normal")
        assertEquals(400L, practice.getJSONObject(1).getLong("t"))
        assertEquals(1083L, practice.getJSONObject(3).getLong("t"))
        vm.closeLiveChart(); vm.switchProject(); await { !vm.busy }; vm.switchProject(project); await { !vm.busy }
        vm.openLiveChart(); assertEquals(changed, vm.liveVSliceTempo())
        vm.restoreLiveVSliceTempo(false); assertEquals(initial, vm.liveVSliceTempo()); assertEquals("120", vm.liveSong!!.bpm)
        vm.restoreLiveVSliceTempo(true); assertEquals(changed, vm.liveVSliceTempo()); assertEquals("120", vm.liveSong!!.bpm)
        vm.closeLiveChart()
        val output = File(app.cacheDir, "both-tempo.zip")
        vm.export(Uri.fromFile(output)); await { !vm.busy }; assertNull(vm.error)
        val files = mutableMapOf<String, ByteArray>()
        java.util.zip.ZipInputStream(output.inputStream()).use { z -> while (true) { val e = z.nextEntry ?: break; files[e.name] = z.readBytes() } }
        val metadata = com.google.gson.JsonParser.parseString(files.getValue(vm.config.modFolder + "/data/songs/shared/shared-metadata.json").toString(Charsets.UTF_8)).asJsonObject
        assertEquals(com.google.gson.JsonParser.parseString(changed), metadata.get("timeChanges"))
        val psych = com.google.gson.JsonParser.parseString(files.getValue(vm.config.modFolder + "/data/shared/shared.json").toString(Charsets.UTF_8))
        assertEquals(before, psych)
    }
    @Test fun bothPsychSectionsDoNotChangeVSliceTempoOrChart() {
        vm.setEngine(TargetEngine.BOTH); vm.setModTitle("Both Sections")
        assertTrue(vm.setActiveSongName("Shared")); vm.setActiveSongBpm("120.5")
        val source = File(app.cacheDir, "both-sections.ogg")
        javaClass.getResourceAsStream("/psych-fixture/Inst.ogg")!!.use { source.writeBytes(it.readBytes()) }
        vm.pickActiveSongAudio(Uri.fromFile(source), com.fnfmodmaker.model.AudioSlot.INSTRUMENTAL)
        await { !vm.busy }; assertNull(vm.error)
        vm.openLiveChart()
        val initial = vm.liveVSliceTempo()
        var tempo = com.fnfmodmaker.mod.changeVSliceTempo(initial, 0, "0", "150.25", "4", "4")
        tempo = com.fnfmodmaker.mod.changeVSliceTempo(tempo, null, "1000", "180.25", "3", "8")
        vm.saveLiveVSliceTempo(tempo)
        vm.setActiveSongBpm("121.75")
        assertEquals(tempo, vm.liveVSliceTempo())
        vm.restoreLiveVSliceTempo(false); assertEquals(initial, vm.liveVSliceTempo())
        vm.restoreLiveVSliceTempo(true); assertEquals(tempo, vm.liveVSliceTempo())
        vm.setActiveSongBpm("120.5")
        vm.selectLiveSnap(com.fnfmodmaker.model.RecordingSnap.HALF_BEAT)
        val recordingGrid = vm.recordingTempoGrid()!!
        assertEquals(2.5042, recordingGrid.beatAt(1000.0), 1e-9)
        vm.createLivePsychChart()
        val song = vm.liveSong!!
        val metaBefore = com.google.gson.JsonParser.parseString(com.fnfmodmaker.mod.JsonBuilders.vsliceMetadata(song, 0))
        val chartBefore = com.google.gson.JsonParser.parseString(com.fnfmodmaker.mod.JsonBuilders.vsliceChart(vm.config, song, 0))
        val changed = vm.liveChart!!.psychLayout + com.fnfmodmaker.model.PsychSectionData(beats = 3.0, bpm = 240.5, changeBpm = true, gfSection = true)
        vm.saveLivePsychSections(changed)
        vm.setLivePsychSnapReference(true)
        assertFalse(vm.liveUsesVSliceSnap)
        assertEquals(120.5 / 60.0, vm.recordingTempoGrid()!!.beatAt(1000.0), 1e-9)
        vm.setLivePsychSnapReference(false)
        assertEquals(2.5042, vm.recordingTempoGrid()!!.beatAt(1000.0), 1e-9)
        vm.setLivePsychSnapReference(true)
        vm.saveLivePsychEvents("""[[123,[["Hey!","1",""]]]]""")
        assertEquals(tempo, vm.liveVSliceTempo())
        assertEquals(metaBefore, com.google.gson.JsonParser.parseString(com.fnfmodmaker.mod.JsonBuilders.vsliceMetadata(vm.liveSong!!, 0)))
        assertEquals(chartBefore, com.google.gson.JsonParser.parseString(com.fnfmodmaker.mod.JsonBuilders.vsliceChart(vm.config, vm.liveSong!!, 0)))
        vm.closeLiveChart()
        val originalId = vm.currentProjectId!!
        vm.preparePortableExport(originalId); await { !vm.busy }; assertNotNull(vm.portableMessage, vm.portableExportName)
        val portable = File(app.cacheDir, "both-snap-reference.fnfproject")
        vm.savePortableExport(Uri.fromFile(portable)); await { !vm.busy }; assertTrue(portable.isFile)
        val knownIds = vm.projects.map { it.id }.toSet()
        vm.preparePortableImport(Uri.fromFile(portable)); await { !vm.busy }; assertNotNull(vm.portableMessage, vm.portableImportSummary)
        vm.confirmPortableImport(); await { !vm.busy }
        val restored = vm.projects.single { it.id !in knownIds }.id
        vm.switchProject(restored); await { !vm.busy }; vm.openLiveChart()
        assertFalse(vm.liveUsesVSliceSnap)
        assertEquals(tempo, vm.liveVSliceTempo())
        assertEquals(120.5, vm.liveSong!!.bpmValue, 0.0)
        vm.closeLiveChart()
        val output = File(app.cacheDir, "both-psych-sections.zip")
        vm.export(Uri.fromFile(output)); await { !vm.busy }; assertNull(vm.error)
        val files = mutableMapOf<String, ByteArray>()
        java.util.zip.ZipInputStream(output.inputStream()).use { z -> while (true) { val e = z.nextEntry ?: break; files[e.name] = z.readBytes() } }
        val psych = com.google.gson.JsonParser.parseString(files.getValue(vm.config.modFolder + "/data/shared/shared.json").toString(Charsets.UTF_8)).asJsonObject
        assertEquals(240.5, psych.getAsJsonArray("notes")[1].asJsonObject.get("bpm").asDouble, 0.0)
        assertEquals(120.5, psych.get("bpm").asDouble, 0.0)
        assertEquals("""[[123,[["Hey!","1",""]]]]""", psych.get("events").toString())
        val metadata = com.google.gson.JsonParser.parseString(files.getValue(vm.config.modFolder + "/data/songs/shared/shared-metadata.json").toString(Charsets.UTF_8)).asJsonObject
        assertEquals(com.google.gson.JsonParser.parseString(tempo), metadata.get("timeChanges"))
    }
    @Test fun vSliceEventEditsPersistUndoAndExport() {
        val previous = vm.currentProjectId!!
        vm.inspectModZip(zip()); await { !vm.busy }; vm.confirmModZipImport(); await { !vm.busy }
        val imported = vm.currentProjectId!!
        assertTrue(vm.duplicateActiveDifficulty("normal", "insane"))
        assertFalse(vm.duplicateActiveDifficulty("normal", "insane"))
        vm.openLiveChart()
        assertEquals("[]", vm.liveVSliceEvents())
        var visual = com.fnfmodmaker.mod.changeVSliceEvent(vm.liveVSliceEvents(), null, "100", "FocusCamera", "1")
        visual = com.fnfmodmaker.mod.duplicateVSliceEvent(visual, 0)
        visual = com.fnfmodmaker.mod.removeVSliceEvent(visual, 1)
        vm.saveLiveVSliceEvents(visual)
        visual = com.fnfmodmaker.mod.changeVSliceEvent(visual, 0, "200", "FocusCamera", "0")
        vm.saveLiveVSliceEvents(visual)
        vm.closeLiveChart(); vm.goHome(); vm.switchProject(previous); await { !vm.busy }; vm.switchProject(imported); await { !vm.busy }
        assertEquals(200L, vm.config.getChart("tarde", "insane")!!.notes.single().timeMs)
        vm.openLiveChart(); vm.undoLiveVSliceEvents()
        assertEquals("""[{"t":100,"e":"FocusCamera","v":1}]""", vm.liveVSliceEvents())
        vm.redoLiveVSliceEvents()
        assertEquals("""[{"t":200,"e":"FocusCamera","v":0}]""", vm.liveVSliceEvents())
        vm.undoLiveVSliceEvents()
        vm.closeLiveChart()
        val output = File(app.cacheDir, "events-export.zip")
        vm.export(Uri.fromFile(output)); await { !vm.busy }; assertNull(vm.error)
        val files = mutableMapOf<String, ByteArray>()
        java.util.zip.ZipInputStream(output.inputStream()).use { z -> while (true) { val e = z.nextEntry ?: break; files[e.name] = z.readBytes() } }
        val chart = org.json.JSONObject(files.getValue(vm.config.modFolder + "/data/songs/tarde/tarde-chart.json").toString(Charsets.UTF_8))
        assertEquals(100, chart.getJSONArray("events").getJSONObject(0).getInt("t"))
        assertEquals(200, chart.getJSONObject("notes").getJSONArray("insane").getJSONObject(0).getInt("t"))
    }
    @Test fun androidViewModelReopensAndExportsImportedCharacterWithoutChangingMetadata() {
        val previous = vm.currentProjectId!!
        vm.inspectModZip(zip(withCharacter = true))
        await { !vm.busy }
        assertNotNull(vm.portableMessage, vm.modZipReview)
        assertEquals(1, vm.modZipReview!!.characters)
        vm.confirmModZipImport(); await { !vm.busy }
        val imported = vm.currentProjectId!!
        assertEquals("Hero_Day", vm.config.character.charId)
        assertEquals("Hero_Day", vm.config.firstSong.opponent)
        vm.setCharName("Hero editado")
        vm.goHome(); vm.switchProject(previous); await { !vm.busy }
        vm.switchProject(imported); await { !vm.busy }
        assertEquals("Hero editado", vm.config.character.displayName)
        assertEquals("Hero_Day", vm.config.firstSong.opponent)
        assertTrue(vm.validationIssues.joinToString { it.message }, vm.exportReady)
        val output = File(app.cacheDir, "exported-tarde.zip")
        vm.export(Uri.fromFile(output)); await { !vm.busy }
        assertNull(vm.error)
        val files = mutableMapOf<String, ByteArray>()
        java.util.zip.ZipInputStream(output.inputStream()).use { zip ->
            while (true) { val entry = zip.nextEntry ?: break; files[entry.name] = zip.readBytes() }
        }
        val prefix = "${vm.config.modFolder}/"
        val meta = org.json.JSONObject(files.getValue(prefix + "data/songs/tarde/tarde-metadata.json").toString(Charsets.UTF_8))
        assertEquals("Hero_Day", meta.getJSONObject("playData").getJSONObject("characters").getString("opponent"))
        val character = org.json.JSONObject(files.getValue(prefix + "data/characters/Hero_Day.json").toString(Charsets.UTF_8))
        assertEquals("Hero editado", character.getString("name")); assertEquals("kept", character.getString("unknown"))
        assertTrue(prefix + "images/characters/Hero_Day.png" in files)
    }
    @Test fun libraryEditsAndRolesSurviveProjectSwitching() {
        val original = vm.currentProjectId!!
        vm.setCharacterEnabled(true); vm.setCharId("hero"); vm.setCharName("Hero")
        val hero = vm.config.selectedCharacterId
        vm.createLibraryCharacter(); val rival = vm.config.selectedCharacterId
        vm.setCharId("rival"); vm.setCharName("Rival")
        vm.assignLibraryCharacter(com.fnfmodmaker.model.CharacterRole.PLAYER, hero)
        vm.assignLibraryCharacter(com.fnfmodmaker.model.CharacterRole.OPPONENT, rival)
        vm.assignLibraryCharacter(com.fnfmodmaker.model.CharacterRole.GIRLFRIEND, hero)
        vm.selectLibraryCharacter(hero); vm.addCharacterVariation()
        vm.switchProject(); await { !vm.busy }
        vm.switchProject(original); await { !vm.busy }
        assertEquals(listOf("hero", "rival"), vm.config.characterLibrary.map { it.draft.charFolder })
        assertEquals("Hero", vm.config.characterLibrary.first().draft.displayName)
        assertEquals(1, vm.config.characterLibrary.first().draft.variations.size)
        assertEquals(hero, vm.config.resolvedAssignments().player)
        assertEquals(rival, vm.config.resolvedAssignments().opponent)
        assertEquals(hero, vm.config.resolvedAssignments().girlfriend)
    }
    @Test fun duplicateImportedCharacterIsEditablePersistentAndExported() {
        val previous = vm.currentProjectId!!
        vm.inspectModZip(zip(withCharacter = true)); await { !vm.busy }
        vm.confirmModZipImport(); await { !vm.busy }; assertNull(vm.modZipImportError)
        val imported = vm.currentProjectId!!
        vm.createLibraryCharacter(duplicate = true)
        assertNull(vm.error); assertEquals(2, vm.config.characterLibrary.size)
        val duplicate = vm.config.selectedCharacterId
        assertEquals("personaje-1", vm.config.character.charFolder)
        vm.assignLibraryCharacter(com.fnfmodmaker.model.CharacterRole.OPPONENT, duplicate)
        vm.goHome(); vm.switchProject(previous); await { !vm.busy }; vm.switchProject(imported); await { !vm.busy }
        assertEquals(2, vm.config.characterLibrary.size)
        assertEquals("personaje-1", vm.config.effectiveExportConfig().firstSong.opponent)
        assertTrue(vm.validationIssues.joinToString { it.message }, vm.exportReady)
        val output = File(app.cacheDir, "duplicate-character.zip")
        vm.export(Uri.fromFile(output)); await { !vm.busy }; assertNull(vm.error)
        val files = mutableMapOf<String, ByteArray>()
        java.util.zip.ZipInputStream(output.inputStream()).use { z -> while (true) { val e = z.nextEntry ?: break; files[e.name] = z.readBytes() } }
        val prefix = vm.config.modFolder + "/"
        listOf("data/characters/Hero_Day.json", "data/characters/personaje-1.json", "images/characters/personaje-1.png", "images/characters/personaje-1.xml").forEach { assertTrue(it, prefix + it in files) }
        val copy = org.json.JSONObject(files.getValue(prefix + "data/characters/personaje-1.json").toString(Charsets.UTF_8))
        assertEquals("kept", copy.getString("unknown"))
    }

    @Test fun tardeReassignsDadToMetzaAndKeepsOriginalMetadataAfterReopenAndZip() {
        val previous = vm.currentProjectId!!
        vm.inspectModZip(zip()); await { !vm.busy }; vm.confirmModZipImport(); await { !vm.busy }
        val imported = vm.currentProjectId!!
        val originalPath = vm.config.importedModResources.single { it.originalPath == "data/songs/tarde/tarde-metadata.json" }.asset!!.path
        val original = File(originalPath).readText()
        vm.setCharacterEnabled(true); vm.setCharId("metza"); vm.setCharName("Metza")
        vm.setCharFrameWidth("1"); vm.setCharFrameHeight("1"); vm.setCharIdleFrames("1")
        vm.setCharLeftFrames("0"); vm.setCharDownFrames("0"); vm.setCharUpFrames("0"); vm.setCharRightFrames("0")
        val png = File(app.cacheDir, "metza.png"); javaClass.getResourceAsStream("/psych-fixture/dad.png")!!.use { png.writeBytes(it.readBytes()) }
        vm.pickCharacterSheet(Uri.fromFile(png)); await { !vm.busy }; assertNull(vm.error)
        val assignment = """{"summary":"Cambiar rival","changes":[{"type":"assignment","role":"opponent","characterId":"${vm.config.selectedCharacterId}"},{"type":"animation","characterId":"${vm.config.selectedCharacterId}","name":"idle","fps":18,"offsetX":7}]}"""
        vm.acceptAiProposal(vm.config, com.fnfmodmaker.ai.AiProtocol.ProjectResponse(null, assignment), false)
        assertNotNull(vm.aiError, vm.aiProposal); vm.applyAiProposal()
        vm.openLiveChart(); vm.selectLiveDifficulty("easy"); vm.deleteLiveCustomChart(); vm.closeLiveChart()
        assertFalse(vm.config.firstSong.difficulties.contains("easy"))
        assertEquals("metza", vm.config.effectiveExportConfig().firstSong.opponent)
        vm.goHome(); vm.switchProject(previous); await { !vm.busy }; vm.switchProject(imported); await { !vm.busy }
        assertEquals("dad", vm.config.firstSong.opponent)
        assertEquals("metza", vm.config.effectiveExportConfig().firstSong.opponent)
        assertEquals("18", vm.config.character.settingsFor("idle").fps)
        assertTrue(vm.validationIssues.joinToString { it.message }, vm.exportReady)
        val output = File(app.cacheDir, "tarde-metza.zip")
        vm.export(Uri.fromFile(output)); await { !vm.busy }; assertNull(vm.error)
        val files = mutableMapOf<String, ByteArray>()
        java.util.zip.ZipInputStream(output.inputStream()).use { z -> while (true) { val e = z.nextEntry ?: break; files[e.name] = z.readBytes() } }
        val prefix = vm.config.modFolder + "/"
        val exported = org.json.JSONObject(files.getValue(prefix + "data/songs/tarde/tarde-metadata.json").toString(Charsets.UTF_8))
        val chars = exported.getJSONObject("playData").getJSONObject("characters")
        assertEquals("metza", chars.getString("opponent")); chars.put("opponent", "dad")
        val exportedDiffs = exported.getJSONObject("playData").getJSONArray("difficulties")
        assertEquals(listOf("normal", "hard"), (0 until exportedDiffs.length()).map { exportedDiffs.getString(it) })
        val notes = org.json.JSONObject(files.getValue(prefix + "data/songs/tarde/tarde-chart.json").toString(Charsets.UTF_8)).getJSONObject("notes")
        assertFalse(notes.has("easy")); assertTrue(notes.has("normal")); assertTrue(notes.has("hard"))
        val originalTree = com.google.gson.JsonParser.parseString(original).asJsonObject
        originalTree.getAsJsonObject("playData").add("difficulties", com.google.gson.JsonArray().apply { add("normal"); add("hard") })
        assertEquals(originalTree, com.google.gson.JsonParser.parseString(exported.toString())); assertEquals(original, File(originalPath).readText())
        listOf("data/characters/metza.json", "images/characters/metza.png", "images/characters/metza.xml").forEach { assertTrue(it, files.containsKey(prefix + it)) }
        val characterJson = org.json.JSONObject(files.getValue(prefix + "data/characters/metza.json").toString(Charsets.UTF_8))
        val idle = characterJson.getJSONArray("animations").getJSONObject(0)
        assertEquals("idle", idle.getString("name")); assertEquals(18, idle.getInt("frameRate")); assertEquals(7, idle.getJSONArray("offsets").getInt(0))
    }

    @Test fun simulatedAiResponseAppliesSavesReopensAndUndoIsSafe() {
        val id = vm.currentProjectId!!
        vm.setSimpleStageEnabled(true); vm.setStageId("my-stage")
        val base = vm.config
        val args = """{"summary":"Ajustar escena","changes":[{"type":"stage_actor","engine":"vslice","role":"gf","cameraX":23},{"type":"scene","zoom":1.2},{"type":"animation","characterId":"legacy","name":"idle","fps":18,"offsetX":4}]}"""
        vm.acceptAiProposal(base, com.fnfmodmaker.ai.AiProtocol.ProjectResponse(null, args), false)
        assertNotNull(vm.aiError, vm.aiProposal); assertEquals(base, vm.config)
        vm.applyAiProposal(); assertEquals(23f, vm.config.stageDraft.vSliceLayout.gf.cameraX, 0f)
        assertEquals("18", vm.config.character.settingsFor("idle").fps); assertTrue(vm.canUndoAi)
        vm.undoAiBatch(); assertEquals(base, vm.config); assertFalse(vm.canUndoAi)
        vm.acceptAiProposal(base, com.fnfmodmaker.ai.AiProtocol.ProjectResponse(null, args), false); vm.applyAiProposal()
        vm.switchProject(); await { !vm.busy }; vm.switchProject(id); await { !vm.busy }
        assertEquals(23f, vm.config.stageDraft.vSliceLayout.gf.cameraX, 0f)
        assertEquals(1.2f, vm.config.stageDraft.cameraPosition.zoom, 0f)
        assertEquals("18", vm.config.character.settingsFor("idle").fps)
        assertFalse(vm.canUndoAi)
        vm.acceptAiProposal(vm.config, com.fnfmodmaker.ai.AiProtocol.ProjectResponse("Consejo", null), false)
        assertTrue(vm.aiProposalNotice!!.contains("herramientas")); assertNull(vm.aiProposal)
    }

    @Test fun undoNeverOverwritesLaterManualEdits() {
        val base = vm.config
        val args = """{"summary":"posición","changes":[{"type":"character","positionX":12}]}"""
        vm.acceptAiProposal(base, com.fnfmodmaker.ai.AiProtocol.ProjectResponse(null, args), false)
        vm.applyAiProposal(); assertTrue(vm.canUndoAi)
        vm.setCharName("Edición posterior")
        vm.undoAiBatch()
        assertEquals("Edición posterior", vm.config.character.displayName)
        assertEquals("12", vm.config.character.positionX)
        assertTrue(vm.aiError!!.contains("cambió"))
    }

    @Test fun publicationFailureShowsErrorRetainsReviewAndCanRetry() {
        val previous = vm.currentProjectId
        val previousConfig = vm.config
        inspect()
        val stageAssets = File(app.filesDir, "projects").listFiles()!!.single { it.name.startsWith(".pending-") }
        val asset = File(stageAssets, "assets/imported").listFiles()!!.first()
        val bytes = asset.readBytes(); assertTrue(asset.delete())
        vm.confirmModZipImport()
        await { !vm.busy }
        assertNotNull(vm.modZipReview)
        assertNotNull(vm.modZipImportError)
        assertTrue(vm.modZipImportError!!.contains("archivo ausente"))
        assertEquals(previous, vm.currentProjectId)
        assertEquals(previousConfig, vm.config)
        assertEquals(previous, File(app.filesDir, "projects/active.txt").readText())
        asset.writeBytes(bytes)
        vm.confirmModZipImport()
        await { !vm.busy }
        assertNull(vm.modZipImportError)
        assertEquals(Tool.MOD, vm.tool)
        assertNotEquals(previous, vm.currentProjectId)
        val script = vm.config.importedModResources.single { it.originalPath == "scripts/original.hx" }
        assertEquals("trace('original');", File(script.asset!!.path).readText())
    }
}
