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
    private fun zip(): Uri {
        val file = File(app.cacheDir, "tarde-test.zip")
        val metadata = """{"version":"2.2.4","songName":"Tarde","artist":"Example","playData":{"difficulties":["easy","normal","hard"],"characters":{"player":"bf","opponent":"dad"},"stage":"mainStage"},"timeChanges":[{"t":0,"bpm":120}]}"""
        val chart = """{"version":"2.0.0","scrollSpeed":{"default":1},"notes":{"easy":[{"t":100,"d":0}],"normal":[{"t":200,"d":1}],"hard":[{"t":300,"d":2}]},"events":[]}"""
        ZipOutputStream(file.outputStream()).use { out ->
            fun entry(name: String, bytes: ByteArray) { out.putNextEntry(ZipEntry(name)); out.write(bytes); out.closeEntry() }
            entry("data/songs/tarde/tarde-metadata.json", metadata.toByteArray())
            entry("data/songs/tarde/tarde-chart.json", chart.toByteArray())
            entry("songs/tarde/Inst.ogg", javaClass.getResourceAsStream("/psych-fixture/Inst.ogg")!!.use { it.readBytes() })
            entry("scripts/original.hx", "trace('original');".toByteArray())
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
