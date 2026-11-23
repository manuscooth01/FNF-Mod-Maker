package com.fnfmodmaker.model

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CharacterValidationTest {
    private fun problems(
        hasSheet: Boolean = true,
        sheetWidth: Int = 300,
        sheetHeight: Int = 300,
        width: String = "150",
        height: String = "150",
        scale: String = "1.0",
        counts: List<String> = listOf("1", "1", "1", "1", "0")
    ) = characterProblems(hasSheet, sheetWidth, sheetHeight, width, height, scale, counts)

    @Test fun validGridIsReady() {
        assertTrue(problems().isEmpty())
    }

    @Test fun missingSheetAndInvalidDimensionsAreReported() {
        val errors = problems(hasSheet = false, width = "0", height = "")
        assertTrue(errors.any { it.contains("hoja") })
        assertTrue(errors.any { it.contains("ancho") })
    }

    @Test fun idleAndValidCountsAreRequired() {
        assertTrue(problems(counts = listOf("0", "1", "1", "1", "0")).any { it.contains("idle") })
        assertTrue(problems(counts = listOf("1", "", "1", "1", "0")).any { it.contains("número") })
    }

    @Test fun rejectsFramesThatDoNotFitAndInvalidScale() {
        assertTrue(problems(counts = listOf("2", "1", "1", "1", "1")).any { it.contains("solo caben") })
        assertTrue(problems(width = "301").any { it.contains("más pequeña") })
        for (scale in listOf("0", "-1", "NaN", "Infinity", "")) {
            assertTrue(problems(scale = scale).any { it.contains("escala") })
        }
    }

    @Test fun disabledCharacterDoesNotBlockMod() {
        val draft = CharacterDraft(enabled = false)
        assertTrue(draft.isReady)
        assertFalse(draft.copy(enabled = true).isReady)
    }
    @Test fun animationSettingsAreValidatedAndDefaultTo24Fps() {
        val draft = CharacterDraft(enabled = true)
        assertTrue(draft.settingsFor("idle").fps == "24")
        val changed = draft.copy(animationSettings = mapOf("idle" to CharacterAnimationSettings(fps = "0", offsetX = "abc")))
        assertTrue(changed.problems.any { it.contains("FPS") })
        assertTrue(changed.problems.any { it.contains("offsets") })
        assertFalse(draft.problems.any { it.contains("FPS") || it.contains("offsets") })
    }

    @Test fun individualFramesRequireIdle() {
        val draft = CharacterDraft(enabled = true, useIndividualFrames = true)
        assertTrue(draft.problems.any { it.contains("idle") })
        assertFalse(draft.problems.any { it.contains("hoja de sprites PNG") })
    }

    @Test fun atlasLayoutFitsAndRejectsOversizedFrames() {
        val layout = planCharacterAtlas(listOf(100 to 200, 200 to 100, 100 to 100), limit = 600)!!
        assertTrue(layout.width <= 600 && layout.height <= 600)
        assertTrue(layout.columns * (layout.height / layout.cellHeight) >= 3)
        assertTrue(layout.cellWidth == 200 && layout.cellHeight == 200)
        assertTrue(planCharacterAtlas(emptyList()) == null)
        assertTrue(planCharacterAtlas(listOf(4097 to 2)) == null)
        assertTrue(planCharacterAtlas(List(5) { 3000 to 3000 }) == null)
    }
    @Test fun gridPreviewUsesSameAnimationOrderAsExport() {
        val animations = listOf("idle" to 2, "singLEFT" to 3, "singDOWN" to 1)
        val regions = gridAnimationRegions(300, 200, 100, 100, animations, "singLEFT")
        assertTrue(regions == listOf(
            GridFrameRegion(200, 0, 100, 100),
            GridFrameRegion(0, 100, 100, 100),
            GridFrameRegion(100, 100, 100, 100)
        ))
        assertTrue(gridAnimationRegions(300, 100, 100, 100, animations, "singLEFT").isEmpty())
        assertTrue(gridAnimationRegions(300, 100, 0, 100, animations, "idle").isEmpty())
    }

    @Test fun positionValidationAndCameraCoordinateConversion() {
        val draft = CharacterDraft(enabled = true, positionX = "123", positionY = "-45", cameraX = "20", cameraY = "-7")
        val placement = draft.placement()
        assertTrue(placement == CharacterPlacement(123, -45, 20, -7))
        assertTrue(placement.psychCameraX(CharacterSide.PLAYER) == -20)
        assertTrue(placement.psychCameraX(CharacterSide.OPPONENT) == 20)
        assertTrue(draft.copy(positionX = "-").problems.any { it.contains("posiciones") })
        assertFalse(draft.problems.any { it.contains("posiciones") })
    }
}
