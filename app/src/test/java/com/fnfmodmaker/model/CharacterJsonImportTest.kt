package com.fnfmodmaker.model

import org.junit.Assert.*
import org.junit.Test

class CharacterJsonImportTest {
    @Test fun psychImportsOffsetsAnimationAndRetainsSource() {
        val text = """{"image":"characters/example","scale":1.5,"flip_x":true,"position":[10,-2],"camera_position":[5,6],"animations":[{"anim":"idle","name":"Idle","fps":24,"loop":true,"offsets":[1,-3],"indices":[2]}]}"""
        val preview = parseCharacterJson(text, "Example.json", CharacterSide.PLAYER)
        val result = applyCharacterJson(CharacterDraft(side = CharacterSide.PLAYER), preview)
        assertEquals("Psych", preview.format); assertEquals("-5", result.cameraX)
        assertEquals("Idle", result.prefixFor("idle")); assertEquals("-3", result.settingsFor("idle").offsetY)
        assertEquals(text, result.importedSourceJson); assertTrue(result.useAtlas)
        assertTrue(preview.warnings.any { it.contains("índices") })
        val gson = com.google.gson.Gson()
        assertEquals(text, gson.fromJson(gson.toJson(result), CharacterDraft::class.java).importedSourceJson)
    }
    @Test fun vsliceDetectsIncompatibleRendererAndImportsCustomAnimation() {
        val text = """{"assetPath":"shared:characters/dad","renderType":"animateatlas","name":"Dad","offsets":[13,3],"cameraOffsets":[11,6],"animations":[{"name":"idle","prefix":"Idle"},{"name":"idle-hold","prefix":"Idle","frameIndices":[1,2],"looped":true}]}"""
        val result = parseCharacterJson(text, "dad.json", CharacterSide.OPPONENT)
        assertEquals("V-Slice", result.format); assertEquals("13", result.positionX)
        assertTrue(result.settings.getValue("idle-hold").loop)
        assertTrue(result.warnings.any { it.contains("animateatlas") })
        assertEquals(listOf("idle-hold"), applyCharacterJson(CharacterDraft(), result).customAnimations)
    }
    @Test fun invalidInputsNeverBecomeACharacter() {
        assertThrows(Exception::class.java) { parseCharacterJson("{}", "x.json", CharacterSide.OPPONENT) }
        assertThrows(Exception::class.java) { parseCharacterJson("""{"image":"x","animations":[]}""", "x.json", CharacterSide.OPPONENT) }
        assertThrows(Exception::class.java) { parseCharacterJson("""{"image":"x","position":[0.5,1],"animations":[{"anim":"idle","name":"Idle"}]}""", "x.json", CharacterSide.OPPONENT) }
    }
}
