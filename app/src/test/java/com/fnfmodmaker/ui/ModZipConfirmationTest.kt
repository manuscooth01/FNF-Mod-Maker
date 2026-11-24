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
    private fun zip(withCharacter: Boolean = false): Uri {
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
