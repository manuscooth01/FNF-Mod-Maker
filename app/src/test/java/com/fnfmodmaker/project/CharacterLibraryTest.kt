package com.fnfmodmaker.project

import com.fnfmodmaker.model.*
import com.google.gson.Gson
import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class CharacterLibraryTest {
    @get:Rule val temp = TemporaryFolder()
    private val gson = com.google.gson.GsonBuilder().registerTypeAdapter(android.net.Uri::class.java,
        object : com.google.gson.TypeAdapter<android.net.Uri>() {
            override fun write(out: com.google.gson.stream.JsonWriter, value: android.net.Uri?) { out.nullValue() }
            override fun read(input: com.google.gson.stream.JsonReader): android.net.Uri? { input.skipValue(); return null }
        }.nullSafe()).create()
    @Test fun migrationRetainsEveryLegacyFieldAndRejectsAmbiguity() {
        val old = JsonParser.parseString("""{"songs":[],"charts":{},"stageDraft":{},"character":{"charId":"Hero","sheet":{"path":"/kept","uri":"x","displayName":"hero.png"},"variations":[{"variationId":"night"}],"animationSettings":{"idle":{"offsetX":"19"}},"futureData":{"value":42}}}""").asJsonObject
        val converted = migrateCharacterLibrary(old)
        assertFalse(converted.has("character"))
        assertEquals(old.getAsJsonObject("character"), converted.getAsJsonArray("characterLibrary")[0].asJsonObject.getAsJsonObject("draft"))
        assertEquals("legacy", converted.get("selectedCharacterId").asString)
        val config = gson.fromJson(converted, ModConfig::class.java)
        assertEquals("night", config.character.variations.single().variationId)
        assertEquals("19", config.character.settingsFor("idle").offsetX)
        assertThrows(IllegalArgumentException::class.java) { migrateCharacterLibrary(converted.deepCopy().apply { add("character", old.get("character")) }) }
    }
    @Test fun multipleAssignmentsReuseAndPortableRoundTripDoNotAffectStage() {
        val a = CharacterEntry("a", CharacterDraft(enabled = true, charId = "hero", variations = listOf(CharacterVariation(variationId = "night"))))
        val b = CharacterEntry("b", CharacterDraft(enabled = true, charId = "rival"))
        val config = ModConfig(characterLibrary = listOf(a, b), selectedCharacterId = "b",
            characterAssignments = CharacterAssignments(player = "a", opponent = "b", girlfriend = "a"))
        assertEquals(listOf("a", "b"), config.assignedCharacters().map { it.id })
        assertEquals("hero", config.withCharacter().firstSong.player)
        assertEquals("rival", config.withCharacter().firstSong.opponent)
        assertEquals("hero", config.withCharacter().firstSong.girlfriend)
        assertEquals(config.stageDraft, config.withCharacter().stageDraft)
        val store = ProjectStore(File(temp.root, "projects"), temp.root)
        val id = store.newId(); val json = gson.toJsonTree(config).asJsonObject
        store.save(id, json)
        val portable = PortableProject(temp.root); val zip = File(temp.root, "test.fnfproject")
        portable.export(store.load(id).config, store.assets(id), zip)
        val imported = store.publishImport(portable.prepare(zip, store.newImportStage()), portable, ProjectConfigValidator(gson)::validate)
        val reopened = gson.fromJson(migrateCharacterLibrary(store.load(imported).config), ModConfig::class.java)
        assertEquals(config.characterLibrary, reopened.characterLibrary)
        assertEquals(config.characterAssignments, reopened.characterAssignments)
        assertEquals("night", reopened.characterLibrary.first().draft.variations.single().variationId)
    }
    @Test fun duplicateIdentityAndDanglingAssignmentAreRejected() {
        val invalid = ModConfig(characterLibrary = listOf(CharacterEntry("same"), CharacterEntry("same")), selectedCharacterId = "same", characterAssignments = CharacterAssignments(player = "missing"))
        assertTrue(characterLibraryProblems(invalid).any { it.contains("duplicados") })
        assertTrue(characterLibraryProblems(invalid).any { it.contains("no existe") })
        assertThrows(IllegalArgumentException::class.java) { ProjectConfigValidator(gson).validate(gson.toJsonTree(invalid).asJsonObject) }
    }
    @Test fun variationIdsCannotCollideWithAnotherCharacter() {
        val a = CharacterEntry("a", CharacterDraft(enabled = true, charId = "hero", variations = listOf(CharacterVariation(variationId = "night"))))
        val b = CharacterEntry("b", CharacterDraft(enabled = true, charId = "hero-night"))
        val config = ModConfig(characterLibrary = listOf(a, b), selectedCharacterId = "a", characterAssignments = CharacterAssignments(player = "a", opponent = "b"))
        assertTrue(characterLibraryProblems(config) { true }.any { it.contains("duplicado") })
    }
}
