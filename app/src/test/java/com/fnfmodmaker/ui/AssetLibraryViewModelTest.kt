package com.fnfmodmaker.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import com.fnfmodmaker.model.AssetCategory
import kotlinx.coroutines.Dispatchers
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
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Asset Library end-to-end on the real ViewModel: review, confirm, cancel, reuse and removal. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class AssetLibraryViewModelTest {
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
        fail("Timeout [$label]: busy=${vm.busy}, error=${vm.error}")
    }

    /** ZIP sin metadata de mod: solo recursos con jerarquía y un par Sparrow inequívoco. */
    private fun packZip(): Uri {
        val png = javaClass.getResourceAsStream("/psych-fixture/dad.png")!!.use { it.readBytes() }
        val file = File(app.cacheDir, "paquete-juego.zip")
        ZipOutputStream(file.outputStream()).use { out ->
            fun entry(name: String, bytes: ByteArray) { out.putNextEntry(ZipEntry(name)); out.write(bytes); out.closeEntry() }
            entry("images/characters/hero.png", png)
            entry("images/characters/hero.xml", """<TextureAtlas imagePath="hero.png"><SubTexture name="hero idle0000" x="0" y="0" width="1" height="1"/></TextureAtlas>""".toByteArray())
            entry("fonts/vcr.ttf", "fontdata".toByteArray())
            entry("scripts/deep/custom.lua", "trace('data')".toByteArray())
        }
        return Uri.fromFile(file)
    }

    private fun projectAssets(): File = File(app.filesDir, "projects").walkTopDown()
        .firstOrNull { it.isDirectory && it.name == "assets" } ?: error("No hay carpeta de recursos")

    @Test fun importReviewConfirmCancelAndPersistence() {
        assertEquals(0, vm.config.assetPacks.size)
        // Cancelación: la revisión no toca el proyecto.
        vm.prepareAssetPackImport(packZip())
        await("analizar") { vm.assetPackReview != null || vm.error != null }
        assertNull(vm.error)
        assertEquals(4, vm.assetPackReview!!.entries)
        vm.cancelAssetPackImport()
        assertNull(vm.assetPackReview)
        assertTrue(vm.config.assetPacks.isEmpty())
        // Confirmación: publica en el proyecto activo y guarda.
        vm.prepareAssetPackImport(packZip())
        await("re-analizar") { vm.assetPackReview != null }
        vm.confirmAssetPackImport()
        await("confirmar") { vm.config.assetPacks.size == 1 && !vm.busy }
        assertNull(vm.error)
        val pack = vm.config.assetPacks.single()
        val library = File(projectAssets(), "library/${pack.id}")
        assertTrue(File(library, "images/characters/hero.png").isFile)
        assertTrue(File(library, "scripts/deep/custom.lua").isFile)
        assertEquals(AssetCategory.SPRITE, pack.assets.first { it.displayName == "hero.png" }.type)
        assertEquals(AssetCategory.SCRIPT, pack.assets.first { it.displayName == "custom.lua" }.type)
        // Autoguardado: el catálogo quedó en el proyecto del disco.
        await("autoguardado") { !vm.busy }
        vm.forceAutoSave()
        val saved = File(app.filesDir, "projects/${vm.currentProjectId}/project.json").readText()
        assertTrue(saved.contains("\"assetPacks\""))
        assertTrue(saved.contains(pack.id))
    }

    @Test fun reuseAssignmentsAreTypedCompatibleAndRecovered() {
        vm.prepareAssetPackImport(packZip())
        await("analizar") { vm.assetPackReview != null && !vm.busy }
        vm.confirmAssetPackImport()
        await("confirmar") { vm.config.assetPacks.size == 1 && !vm.busy }
        val pack = vm.config.assetPacks.single()
        val png = pack.assets.single { it.displayName == "hero.png" }
        val xml = pack.assets.single { it.displayName == "hero.xml" }
        val font = pack.assets.single { it.displayName == "vcr.ttf" }
        // Incompatibilidad explícita: XML como hoja de sprites.
        vm.useLibraryAssetAsCharacterSheet(xml)
        await("incompatible") { vm.error != null }
        assertTrue(vm.error!!.contains("PNG"))
        // La fuente no es una imagen: rechazo explicado.
        vm.useLibraryAssetAsCharacterIcon(font)
        await("fuente") { vm.error != null }
        // Icono del personaje desde el PNG de la biblioteca (referencia directa, sin copia).
        vm.useLibraryAssetAsCharacterIcon(png)
        await("icono") { vm.config.character.icon != null && !vm.busy }
        assertEquals(png.file!!.path, vm.config.character.icon!!.path)
        assertTrue(File(vm.config.character.icon!!.path).isFile)
        // Atlas desde el XML con su PNG emparejado del paquete.
        vm.useLibraryAssetAsCharacterAtlas(xml)
        await("atlas") { vm.config.character.useAtlas && vm.config.character.atlasFrames.isNotEmpty() && !vm.busy }
        assertEquals(png.file!!.path, vm.config.character.atlasSheet!!.path)
        // Fondo del escenario desde la biblioteca.
        vm.useLibraryAssetAsStageBackground(png)
        await("fondo") { vm.config.stageDraft.backgroundImage != null && !vm.busy }
        // Persistencia y recuperación de las asignaciones.
        vm.forceAutoSave()
        val saved = File(app.filesDir, "projects/${vm.currentProjectId}/project.json").readText()
        assertTrue(saved.contains(png.file!!.path))
    }

    @Test fun packRemovalChecksDependencies() {
        vm.prepareAssetPackImport(packZip())
        await("analizar") { vm.assetPackReview != null && !vm.busy }
        vm.confirmAssetPackImport()
        await("confirmar") { vm.config.assetPacks.size == 1 && !vm.busy }
        val pack = vm.config.assetPacks.single()
        val png = pack.assets.single { it.displayName == "hero.png" }
        // Asignado el icono, la eliminación se rechaza con explicación.
        vm.useLibraryAssetAsCharacterIcon(png)
        await("icono") { vm.config.character.icon != null && !vm.busy }
        vm.removeAssetPack(pack.id)
        await("eliminar-con-uso") { vm.error != null && !vm.busy }
        assertTrue(vm.error!!.contains("asignados"))
        // Sin asignaciones, la eliminación borra archivos y catálogo.
        vm.clearCharacterIcon()
        await("limpiar-icono") { vm.config.character.icon == null && !vm.busy }
        vm.removeAssetPack(pack.id)
        await("eliminar") { vm.config.assetPacks.isEmpty() && !vm.busy }
        assertNull(vm.error)
        assertFalse(File(projectAssets(), "library/${pack.id}").exists())
    }
}

