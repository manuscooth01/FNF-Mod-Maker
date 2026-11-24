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
