package com.fnfmodmaker.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** Character Studio end-to-end flows on the real ViewModel: undo/redo, ZIP import,
 *  animation renaming and per-destination export with independent BOTH packages. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class CharacterStudioTest {
    private val scheduler = TestCoroutineScheduler()
    private val dispatcher = StandardTestDispatcher(scheduler)
    private lateinit var app: Application
    private lateinit var store: ViewModelStore
    private lateinit var vm: WizardViewModel

    @Before fun setup() {
        Dispatchers.setMain(dispatcher)
        app = RuntimeEnvironment.getApplication() as Application
        store = ViewModelStore()
        val factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = WizardViewModel(app) as T
        }
        vm = ViewModelProvider(store, factory)[WizardViewModel::class.java]
        await("setup") { !vm.busy && vm.currentProjectId != null }
    }

    @After fun teardown() {
        store.clear()
        scheduler.runCurrent()
        Dispatchers.resetMain()
    }

    private fun await(label: String, condition: () -> Boolean) {
        val end = System.nanoTime() + 30_000_000_000L
        while (System.nanoTime() < end) {
            scheduler.runCurrent()
            if (condition()) return
            Thread.sleep(10)
        }
        fail("Timeout [$label]: busy=${vm.busy}, error=${vm.error}, library=${vm.config.characterLibrary.size}")
    }

    private fun characterZip(): Uri {
        val file = File(app.cacheDir, "chars.zip")
        val png = javaClass.getResourceAsStream("/psych-fixture/dad.png")!!.use { it.readBytes() }
        ZipOutputStream(file.outputStream()).use { out ->
            fun entry(name: String, bytes: ByteArray) { out.putNextEntry(ZipEntry(name)); out.write(bytes); out.closeEntry() }
            entry("characters/hero.json",
                """{"image":"characters/hero","scale":1.5,"healthicon":"hero","animations":[{"anim":"idle","name":"Hero idle","fps":24,"loop":false,"offsets":[1,-3]}]}""".toByteArray())
            entry("images/characters/hero.png", png)
            entry("images/characters/hero.xml",
                """<TextureAtlas imagePath="hero.png"><SubTexture name="Hero idle0000" x="0" y="0" width="1" height="1"/></TextureAtlas>""".toByteArray())
            entry("images/icons/icon-hero.png", png)
        }
        return Uri.fromFile(file)
    }

    private fun zipNames(file: File): List<String> =
        ZipInputStream(file.inputStream()).use { zip ->
            val names = mutableListOf<String>()
            while (true) { val entry = zip.nextEntry ?: break; names.add(entry.name); zip.closeEntry() }
            names
        }

    private fun readEntry(file: File, name: String): String =
        ZipInputStream(file.inputStream()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (entry.name == name) return zip.readBytes().toString(Charsets.UTF_8)
                zip.closeEntry()
            }
            fail("Falta $name en ${file.name}")
            ""
        }

    @Test fun undoRedoEditsOnlyTheSelectedCharacterDraft() {
        vm.createLibraryCharacter()
        await("crear-personaje") { vm.config.selectedCharacterId != "legacy" && !vm.busy }
        val previousName = vm.config.character.displayName
        vm.setCharName("Nuevo nombre")
        await("setCharName") { vm.config.character.displayName == "Nuevo nombre" }
        assertTrue(vm.characterUndoAvailable)
        assertFalse(vm.characterRedoAvailable)
        vm.undoCharacterEdit()
        await("undo") { vm.config.character.displayName == previousName }
        assertTrue(vm.characterRedoAvailable)
        vm.redoCharacterEdit()
        await("redo") { vm.config.character.displayName == "Nuevo nombre" }
        // Cambiar de identidad invalida las pilas: nunca se restaura otro personaje.
        vm.selectLibraryCharacter("legacy")
        await("seleccion-legacy") { !vm.busy }
        assertFalse(vm.characterUndoAvailable)
        assertFalse(vm.characterRedoAvailable)
    }

    @Test fun zipImportAddsLibraryIdentityAndUndoRevertsIt() {
        assertEquals(1, vm.config.characterLibrary.size)
        vm.prepareCharacterZipImport(characterZip())
        await("analizar-zip") { vm.characterZipAnalysis != null || vm.error != null }
        assertNull(vm.error)
        val analysis = vm.characterZipAnalysis!!
        assertEquals(listOf("hero"), analysis.candidates.map { it.id })
        assertTrue(analysis.importable)
        vm.applyCharacterZipImport(setOf("hero"))
        await("aplicar-zip") { vm.config.characterLibrary.size == 2 && vm.characterZipAnalysis == null }
        val draft = vm.config.character
        assertEquals("hero", draft.charId)
        assertTrue(draft.useAtlas)
        assertTrue(draft.atlasFrames.isNotEmpty())
        assertEquals("Hero idle", draft.prefixFor("idle"))
        assertNotNull(draft.atlasSheet)
        assertNotNull(draft.icon)
        assertEquals(1, draft.scaleValue.toInt())
        // La importación es una operación de biblioteca: no es deshacible con el
        // deshacer de ediciones del draft, y las pilas quedan limpias.
        vm.undoCharacterEdit()
        await("undo-tras-importacion") { !vm.busy }
        assertEquals(2, vm.config.characterLibrary.size)
        assertFalse(vm.characterUndoAvailable)
    }

    @Test fun renameCustomAnimationMovesFramesSettingsAndPrefix() {
        assertTrue(vm.addCustomAnimation("hey"))
        await("add-animacion") { "hey" in vm.config.character.customAnimations }
        vm.updateCharacterAnimation("hey") { it.copy(fps = "12", offsetX = "3") }
        vm.setAtlasPrefix("hey", "Hey ")
        assertTrue(vm.renameCustomAnimation("hey", "wave"))
        await("renombrar") { "wave" in vm.config.character.customAnimations }
        assertEquals("12", vm.config.character.settingsFor("wave").fps)
        assertEquals("3", vm.config.character.settingsFor("wave").offsetX)
        assertEquals("Hey ", vm.config.character.prefixFor("wave"))
        assertFalse("hey" in vm.config.character.animationNames)
        assertFalse(vm.renameCustomAnimation("wave", "idle")) // colisión con una estándar
        assertFalse(vm.renameCustomAnimation("wave", "wave")) // nombre idéntico
    }

    @Test fun exportDestinationWritesIndependentPackagesForBoth() {
        val png = File(app.filesDir, "dad.png")
        javaClass.getResourceAsStream("/psych-fixture/dad.png")!!.use { input -> png.outputStream().use { input.copyTo(it) } }
        vm.setCharacterEnabled(true)
        vm.setCharId("hero"); vm.setCharName("Hero")
        vm.setCharFrameWidth("1"); vm.setCharFrameHeight("1")
        vm.setCharIdleFrames("1"); vm.setCharLeftFrames("1")
        vm.setCharDownFrames("0"); vm.setCharUpFrames("0"); vm.setCharRightFrames("0")
        vm.pickCharacterSheet(Uri.fromFile(png))
        await("pick-sheet") { !vm.busy }
        assertNull(vm.error)
        vm.setCharacterExportTarget(WizardViewModel.CharacterExportTarget.BOTH)
        val psychZip = File(app.filesDir, "out-psych.zip")
        vm.exportCharacter(Uri.fromFile(psychZip))
        await("export-psych") { vm.pendingCharacterPackage != null || vm.error != null }
        assertNull(vm.error)
        val names1 = zipNames(psychZip)
        assertTrue("characters/hero.json" in names1)
        assertFalse(names1.any { it.startsWith("data/characters/") })
        val vsliceZip = File(app.filesDir, "out-vslice.zip")
        vm.exportPendingCharacterPackage(Uri.fromFile(vsliceZip))
        await("export-vslice") { vm.characterSaved != null || vm.error != null }
        assertNull(vm.error)
        assertNull(vm.pendingCharacterPackage)
        val names2 = zipNames(vsliceZip)
        assertTrue("data/characters/hero.json" in names2)
        assertFalse(names2.any { it == "characters/hero.json" })
        val psych = JSONObject(readEntry(psychZip, "characters/hero.json"))
        assertTrue(psych.has("healthbar_colors") && psych.has("sing_duration"))
        assertFalse(psych.has("danceEvery") || psych.has("healthIcon"))
        val vslice = JSONObject(readEntry(vsliceZip, "data/characters/hero.json"))
        assertTrue(vslice.has("danceEvery") && vslice.has("singTime") && vslice.has("startingAnimation"))
        assertFalse(vslice.has("healthbar_colors") || vslice.has("healthicon"))
    }

    @Test fun disabledDraftsBlockExportWithExplicitIssues() {
        val png = File(app.filesDir, "dad-empty.png")
        javaClass.getResourceAsStream("/psych-fixture/dad.png")!!.use { input -> png.outputStream().use { input.copyTo(it) } }
        vm.setCharacterEnabled(true) // sin hoja: no está listo
        assertTrue(vm.characterIssues.isNotEmpty())
        assertFalse(vm.characterOnlyReady)
    }
}
