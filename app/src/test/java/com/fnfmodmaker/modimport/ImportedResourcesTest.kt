package com.fnfmodmaker.modimport

import com.fnfmodmaker.mod.mergeImportedResources
import com.fnfmodmaker.model.ImportedModResource
import com.fnfmodmaker.model.PickedImage
import com.google.gson.Gson
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ImportedResourcesTest {
    @get:Rule val temp = TemporaryFolder()
    private fun resource(path: String, converted: Boolean = false): ImportedModResource {
        val source = File.createTempFile("source", ".dat", temp.root).apply { writeText("original") }
        val asset = Gson().fromJson("""{"path":${Gson().toJson(source.absolutePath)},"displayName":"resource","uri":null}""", PickedImage::class.java)
        return ImportedModResource(path, asset, converted)
    }
    @Test fun rejectsUnknownCollisionsInsteadOfOverwriting() {
        val root = File(temp.root, "out").apply { mkdirs() }
        File(root, "scripts").mkdirs(); File(root, "scripts/test.lua").writeText("generated")
        assertThrows(IllegalArgumentException::class.java) { mergeImportedResources(root, listOf(resource("scripts/test.lua")), temp.root) }
        assertEquals("generated", File(root, "scripts/test.lua").readText())
    }
    @Test fun rejectsCaseAndDirectoryTraversalAndPreservesConvertedSource() {
        val root = File(temp.root, "out").apply { mkdirs() }
        File(root, "data").mkdirs(); File(root, "data/chart.json").writeText("edited")
        assertThrows(IllegalArgumentException::class.java) { mergeImportedResources(root, listOf(resource("DATA/extra.json")), temp.root) }
        assertThrows(IllegalArgumentException::class.java) { mergeImportedResources(root, listOf(resource("../escape")), temp.root) }
        val original = resource("data/chart.json", true)
        mergeImportedResources(root, listOf(original), temp.root)
        assertEquals("edited", File(root, "data/chart.json").readText())
        assertEquals("original", File(original.asset!!.path).readText())
    }
}
