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
}
