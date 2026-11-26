package com.fnfmodmaker.mod

import android.net.Uri
import com.fnfmodmaker.model.CharacterDraft
import com.fnfmodmaker.model.CharacterSide
import com.fnfmodmaker.model.ModConfig
import com.fnfmodmaker.model.PickedImage
import com.fnfmodmaker.model.TargetEngine
import com.fnfmodmaker.model.applyCharacterJson
import com.fnfmodmaker.model.parseCharacterJson
import com.fnfmodmaker.model.parseSparrowAtlas
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/** Character Studio package export: per-engine independence, real schema fields and
 *  preservation of unknown fields for imported characters. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class CharacterPackageTest {
    @get:Rule val temp = TemporaryFolder()

    private fun fixturePng(): File {
        val target = File(temp.root, "dad.png")
        if (!target.exists()) {
            // javaClass fuera del apply: dentro de apply se ligaría a java.io.File y el
            // classloader de Robolectric no vería los recursos de test.
            javaClass.getResourceAsStream("/psych-fixture/dad.png")!!.use { input ->
                target.outputStream().use { input.copyTo(it) }
            }
        }
        return target
    }

    /** Grid draft over the 2×2 fixture; only idle+singLEFT so every frame fits. */
    private fun gridDraft(id: String = "hero") = CharacterDraft(
        enabled = true, charId = id, displayName = "Héroe", side = CharacterSide.PLAYER,
        sheet = PickedImage(Uri.fromFile(fixturePng()), "dad.png", fixturePng().absolutePath),
        sheetWidth = 2, sheetHeight = 2, frameWidth = "1", frameHeight = "1",
        idleFrames = "1", leftFrames = "1", downFrames = "0", upFrames = "0", rightFrames = "0",
        scale = "1.0", danceEvery = "2.5", singTime = "6.5", startingAnimation = "idle", isPixel = true)

    private fun configOf(draft: CharacterDraft): ModConfig = ModConfig(engine = TargetEngine.BOTH).copy(character = draft)

    private fun build(engine: TargetEngine, draft: CharacterDraft, rootName: String): List<String> =
        CharacterPackage.build(File(temp.root, rootName), configOf(draft), engine, temp.root, {}, mutableListOf())

    @Test fun psychOnlyPackageContainsOnlyPsychFilesWithRealFields() {
        val files = build(TargetEngine.PSYCH, gridDraft(), "psych")
        assertTrue("images/characters/hero.png" in files)
        assertTrue("images/characters/hero.xml" in files)
        assertTrue("characters/hero.json" in files)
        assertTrue("LEEME.txt" in files)
        assertFalse(files.any { it.startsWith("data/characters/") })
        val json = JSONObject(File(temp.root, "psych/characters/hero.json").readText())
        assertEquals(6.5, json.getDouble("sing_duration"), 0.0)
        assertTrue(json.getBoolean("no_antialiasing"))
        assertEquals(0, json.getJSONArray("camera_position").getInt(0)) // cámara 0 con PLAYER sigue siendo 0 tras el giro de Psych
        val anim = json.getJSONArray("animations").getJSONObject(0)
        assertEquals(listOf("anim", "fps", "indices", "loop", "name", "offsets"),
            anim.keys().asSequence().toList().sorted())
        assertEquals("idle", anim.getString("anim"))
        assertEquals("hero idle0000", anim.getString("name"))
        assertTrue(File(temp.root, "psych/LEEME.txt").readText().contains("Psych"))
    }

    @Test fun vsliceOnlyPackageContainsOnlyVsiceFilesWithRealFields() {
        val files = build(TargetEngine.VSLICE, gridDraft(), "vslice")
        assertTrue("data/characters/hero.json" in files)
        assertFalse(files.any { it == "characters/hero.json" })
        val json = JSONObject(File(temp.root, "vslice/data/characters/hero.json").readText())
        assertEquals("1.0.0", json.getString("version"))
        assertEquals("Héroe", json.getString("name"))
        assertEquals("sparrow", json.getString("renderType"))
        assertEquals("characters/hero", json.getString("assetPath"))
        assertTrue(json.getBoolean("isPixel"))
        assertEquals(2.5, json.getDouble("danceEvery"), 0.0)
        assertEquals(6.5, json.getDouble("singTime"), 0.0)
        assertEquals("idle", json.getString("startingAnimation"))
        val anim = json.getJSONArray("animations").getJSONObject(0)
        assertEquals("idle", anim.getString("name"))
        assertEquals("hero idle0000", anim.getString("prefix"))
        assertEquals("V-Slice", File(temp.root, "vslice/LEEME.txt").readText().substringAfter("destino ").trim().take(7))
    }

    @Test fun bothDestinationsBuildIndependentPackagesWithoutMixingJson() {
        val draft = gridDraft()
        val config = configOf(draft)
        val psych = CharacterPackage.build(File(temp.root, "b-psych"), config, TargetEngine.PSYCH, temp.root, {}, mutableListOf())
        assertEquals(TargetEngine.BOTH, config.engine) // el proyecto editable no se muta
        val vslice = CharacterPackage.build(File(temp.root, "b-vslice"), config, TargetEngine.VSLICE, temp.root, {}, mutableListOf())
        assertTrue("characters/hero.json" in psych && "data/characters/hero.json" !in psych)
        assertTrue("data/characters/hero.json" in vslice && "characters/hero.json" !in vslice)
        val p = JSONObject(File(temp.root, "b-psych/characters/hero.json").readText())
        assertFalse(p.has("danceEvery") || p.has("startingAnimation") || p.has("healthIcon"))
        val v = JSONObject(File(temp.root, "b-vslice/data/characters/hero.json").readText())
        assertFalse(v.has("sing_duration") || v.has("healthbar_colors") || v.has("healthicon"))
        // Cada paquete lleva sus propias copias de imagen y XML.
        assertEquals(psych.filter { it != "LEEME.txt" }, vslice.filter { it != "LEEME.txt" })
        assertEquals(draft, config.character)
    }

    @Test fun importedVsiceCharacterKeepsUnknownFieldsAndAppliesEdits() {
        val png = fixturePng()
        val xml = File(temp.root, "dad.xml")
        xml.writeText("""<?xml version="1.0"?><TextureAtlas imagePath="dad.png">
            <SubTexture name="Dad idle0000" x="0" y="0" width="1" height="1" frameX="-1" frameY="-2" frameWidth="2" frameHeight="2"/>
        </TextureAtlas>""")
        val sourceJson = """{"version":"1.0.2","name":"Hero","assetPath":"characters/hero","renderType":"sparrow","scale":1,"singTime":6,"healthIcon":{"id":"hero","custom":true},"unknownCharacter":123,"animations":[{"name":"idle","prefix":"idle","frameRate":24,"looped":false,"frameIndices":[0],"offsets":[0,0],"extraAnim":"retained"}]}"""
        val draft = applyCharacterJson(CharacterDraft(), parseCharacterJson(sourceJson, "hero.json", CharacterSide.OPPONENT)).copy(
            atlasSheet = PickedImage(Uri.fromFile(png), "dad.png", png.absolutePath),
            atlasSheetWidth = 2, atlasSheetHeight = 2,
            atlasXml = PickedImage(Uri.fromFile(xml), "dad.xml", xml.absolutePath),
            atlasFrames = parseSparrowAtlas(xml.readText()),
            danceEvery = "3", singTime = "7")
        val files = build(TargetEngine.VSLICE, draft, "imported")
        assertTrue("data/characters/hero.json" in files)
        val json = JSONObject(File(temp.root, "imported/data/characters/hero.json").readText())
        assertEquals(123, json.getInt("unknownCharacter"))
        assertTrue(json.getJSONObject("healthIcon").getBoolean("custom"))
        assertEquals("hero", json.getJSONObject("healthIcon").getString("id"))
        assertEquals(7, json.getInt("singTime"))
        assertEquals(3, json.getInt("danceEvery"))
        val anim = json.getJSONArray("animations").getJSONObject(0)
        assertEquals("retained", anim.getString("extraAnim"))
        assertEquals(1, anim.getJSONArray("frameIndices").length())
        assertEquals("Dad idle", anim.getString("prefix"))
        assertEquals(1, json.getJSONArray("animations").length())
    }

    @Test fun rotatedAtlasExportIsBlockedExplicitly() {
        val png = fixturePng()
        val xml = File(temp.root, "rot.xml")
        xml.writeText("""<TextureAtlas imagePath="dad.png"><SubTexture name="Dad idle0000" x="0" y="0" width="1" height="1" rotated="true"/></TextureAtlas>""")
        val draft = gridDraft().copy(
            useAtlas = true,
            atlasSheet = PickedImage(Uri.fromFile(png), "dad.png", png.absolutePath),
            atlasSheetWidth = 2, atlasSheetHeight = 2,
            atlasXml = PickedImage(Uri.fromFile(xml), "rot.xml", xml.absolutePath),
            atlasFrames = parseSparrowAtlas(xml.readText()))
        val ex = assertThrows(IllegalArgumentException::class.java) {
            build(TargetEngine.PSYCH, draft, "rot")
        }
        assertTrue(ex.message!!.contains("rotados"))
    }

    @Test fun draftsThatAreNotReadyAreRejectedBeforeWritingAnything() {
        val config = ModConfig().copy(character = CharacterDraft(enabled = true))
        assertThrows(IllegalArgumentException::class.java) {
            CharacterPackage.build(File(temp.root, "bad"), config, TargetEngine.PSYCH, temp.root, {}, mutableListOf())
        }
        assertFalse(File(temp.root, "bad").exists())
    }
}
