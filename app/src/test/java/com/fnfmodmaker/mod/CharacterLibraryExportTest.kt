package com.fnfmodmaker.mod

import android.net.Uri
import com.fnfmodmaker.model.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.zip.ZipInputStream
import java.io.ByteArrayOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class CharacterLibraryExportTest {
    @get:Rule val temp = TemporaryFolder()
    @Test fun realBuilderExportsAssignedCharactersAndVariationForBothEngines() {
        val png = File(temp.root, "source.png"); javaClass.getResourceAsStream("/psych-fixture/dad.png")!!.use { png.writeBytes(it.readBytes()) }
        val ogg = File(temp.root, "Inst.ogg"); javaClass.getResourceAsStream("/psych-fixture/Inst.ogg")!!.use { ogg.writeBytes(it.readBytes()) }
        val image = PickedImage(Uri.fromFile(png), "source.png", png.path)
        fun draft(id: String) = CharacterDraft(enabled = true, charId = id, displayName = id, sheet = image,
            sheetWidth = 2, sheetHeight = 2, frameWidth = "1", frameHeight = "1",
            idleFrames = "1", leftFrames = "0", downFrames = "0", upFrames = "0", rightFrames = "0")
        val variation = CharacterVariation(variationId = "night", sheet = image, sheetWidth = 2, sheetHeight = 2,
            frameWidth = "1", frameHeight = "1", idleFrames = "1", leftFrames = "0", downFrames = "0", upFrames = "0", rightFrames = "0")
        val library = listOf(CharacterEntry("a", draft("hero").copy(variations = listOf(variation))), CharacterEntry("b", draft("rival")), CharacterEntry("c", draft("unused")))
        val config = ModConfig(engine = TargetEngine.BOTH, modTitle = "Prueba", songs = listOf(SongConfig(songName = "Test", difficulties = setOf("normal"),
            instrumental = PickedAudio(Uri.fromFile(ogg), "Inst.ogg", ogg.path, 1000))), characterLibrary = library, selectedCharacterId = "c",
            characterAssignments = CharacterAssignments(player = "a", opponent = "b", girlfriend = "a"))
        val services = ModBuildServices(object : AudioEncoder {
            override fun convert(input: File, output: File): String? = error("OGG no necesita conversión")
            override fun mix(first: File, second: File, output: File): String? = error("No hay voces")
        }, CharacterBuilder::build, { _, _, _, _ -> }, { _, _ -> true }, { _, _, _ -> true })
        val built = ModBuilder.buildInternal(config, File(temp.root, "build"), temp.root, {}, services)
        val bytes = ByteArrayOutputStream().also { ModBuilder.zip(built.rootDir, it, false) }.toByteArray()
        val files = mutableMapOf<String, ByteArray>()
        ZipInputStream(bytes.inputStream()).use { zip -> while (true) { val entry = zip.nextEntry ?: break; files[entry.name] = zip.readBytes() } }
        for (id in listOf("hero", "hero-night", "rival")) for (path in listOf("images/characters/$id.png", "images/characters/$id.xml", "characters/$id.json", "data/characters/$id.json")) assertTrue(path, path in files)
        assertFalse(files.containsKey("characters/unused.json"))
        val meta = JSONObject(files.getValue("data/songs/test/test-metadata.json").toString(Charsets.UTF_8)).getJSONObject("playData").getJSONObject("characters")
        assertEquals("hero", meta.getString("player")); assertEquals("rival", meta.getString("opponent")); assertEquals("hero", meta.getString("girlfriend"))
        val psych = JSONObject(files.getValue("data/test/test.json").toString(Charsets.UTF_8))
        assertEquals("hero", psych.getString("player1")); assertEquals("rival", psych.getString("player2")); assertEquals("hero", psych.getString("gfVersion"))
    }
}
