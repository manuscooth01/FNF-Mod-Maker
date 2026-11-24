package com.fnfmodmaker.model

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WizardValidationTest {
    @Test fun requiredFieldsPointToTheRightSteps() {
        val issues = validateWizard(ModConfig(), fileExists = { true })
        assertTrue(issues.any { it.step == 0 })
        assertTrue(issues.any { it.step == 1 && it.songIndex == 0 })
        assertTrue(issues.any { it.step == 3 && it.songIndex == 0 })
        assertFalse(issues.any { it.step == 2 })
    }

    @Test fun eachSongIsValidatedAndDuplicateFoldersAreRejected() {
        val config = ModConfig(modTitle = "Test", songs = listOf(
            SongConfig(songName = "My Song"),
            SongConfig(songName = "my song", bpm = "0", speed = "NaN", difficulties = emptySet())
        ))
        val issues = validateWizard(config, fileExists = { true })
        assertTrue(issues.count { it.message.contains("carpeta distinto") } == 2)
        assertTrue(issues.any { it.songIndex == 1 && it.message.contains("BPM") })
        assertTrue(issues.any { it.songIndex == 1 && it.message.contains("velocidad") })
        assertFalse(issues.any { it.songIndex == 1 && it.message.contains("dificultad") })
        assertTrue(issues.count { it.step == 3 } == 2)
    }

    @Test fun optionalCharacterIsValidatedOnlyWhenEnabled() {
        val config = ModConfig(modTitle = "Test", songs = listOf(SongConfig(songName = "Uno")))
        assertFalse(validateWizard(config).any { it.step == 2 })
        assertTrue(validateWizard(config.copy(character = CharacterDraft(enabled = true))).any { it.step == 2 })
    }
}
