package com.fnfmodmaker.model

import org.junit.Assert.*
import org.junit.Test

class SparrowAtlasTest {
    private val xml = """<?xml version="1.0"?><TextureAtlas imagePath="old.png">
        <SubTexture name="Idle 0001" x="20" y="0" width="10" height="20" frameX="-2" frameY="-3" frameWidth="16" frameHeight="25"/>
        <SubTexture name="Idle 0000" x="0" y="0" width="10" height="20"/>
        <SubTexture name="Left 0000" x="30" y="0" width="10" height="20"/>
    </TextureAtlas>"""

    @Test fun parsesBoundsTrimmingAndSortsByPrefix() {
        val frames = parseSparrowAtlas(xml)
        assertEquals(-2, frames[0].frameX)
        assertEquals(16, frames[0].frameWidth)
        assertEquals(listOf("Idle 0000", "Idle 0001"), atlasFramesForPrefix(frames, "Idle ").map { it.name })
        assertTrue(atlasFramesForPrefix(frames, "").isEmpty())
        assertTrue(atlasFramesFit(frames, 40, 20))
        assertFalse(atlasFramesFit(frames, 39, 20))
    }

    @Test fun rewritingPreservesTrimAndChangesImageReference() {
        val rewritten = rewriteSparrowImagePath(xml, "hero.png")
        assertTrue(rewritten.contains("hero.png"))
        assertEquals(parseSparrowAtlas(xml), parseSparrowAtlas(rewritten))
    }

    @Test fun rejectsDoctypesInvalidRotatedAttributeAndInvalidFrames() {
        val bad = listOf(
            """<!DOCTYPE TextureAtlas [<!ENTITY xxe SYSTEM "file:///etc/passwd">]><TextureAtlas><SubTexture name="&xxe;" x="0" y="0" width="1" height="1"/></TextureAtlas>""",
            """<TextureAtlas><SubTexture name="x" x="0" y="0" width="1" height="1" rotated="maybe"/></TextureAtlas>""",
            """<TextureAtlas><SubTexture name="x" x="0" y="0" width="0" height="1"/></TextureAtlas>""",
            """<TextureAtlas><SubTexture name="x" x="-1" y="0" width="1" height="1"/></TextureAtlas>""",
            """<TextureAtlas><SubTexture name="x" x="0" y="0" width="1" height="1" frameWidth="0"/></TextureAtlas>""",
            """<TextureAtlas><SubTexture name="x" x="0" y="0" width="1" height="1"/><SubTexture name="x" x="1" y="0" width="1" height="1"/></TextureAtlas>"""
        )
        bad.forEach { value -> assertTrue(runCatching { parseSparrowAtlas(value) }.isFailure) }
    }

    @Test fun rotatedFramesAreReadFlaggedAndDrawnSwapped() {
        val rotated = parseSparrowAtlas("""<TextureAtlas imagePath="a.png">
            <SubTexture name="Turn 0000" x="4" y="6" width="30" height="12" rotated="true"/>
            <SubTexture name="Flat 0000" x="0" y="0" width="10" height="20" rotated="0"/>
        </TextureAtlas>""")
        assertTrue(rotated[0].rotated)
        assertFalse(rotated[1].rotated)
        // Upright drawing swaps the stored region: 30×12 stored → 12×30 drawn.
        assertEquals(12, rotated[0].drawnWidth)
        assertEquals(30, rotated[0].drawnHeight)
        assertTrue(atlasFramesFit(rotated, 34, 20))
        assertFalse(atlasFramesFit(rotated, 34, 19))
        // Rewriting the image reference keeps the rotated attribute verbatim.
        val rewritten = rewriteSparrowImagePath(
            """<TextureAtlas imagePath="old.png"><SubTexture name="Turn 0000" x="4" y="6" width="30" height="12" rotated="true"/></TextureAtlas>""",
            "new.png")
        assertTrue(rewritten.contains("rotated=\"true\""))
        assertEquals(12, parseSparrowAtlas(rewritten).single().drawnWidth)
    }

    @Test fun oldSavedDraftGetsDefaultsForNewFields() {
        val draft = com.google.gson.Gson().fromJson("""{"displayName":"Anterior"}""", CharacterDraft::class.java)
        assertEquals("Anterior", draft.displayName)
        assertFalse(draft.useAtlas)
        assertTrue(draft.customAnimations.isEmpty())
        assertTrue(draft.atlasFrames.isEmpty())
    }

    @Test fun customAnimationsParticipateInAllModes() {
        val grid = CharacterDraft(customAnimations = listOf("hey"), customFrameCounts = mapOf("hey" to "2"))
        assertEquals(2, grid.animations.first { it.first == "hey" }.second)
        val atlas = grid.copy(useAtlas = true, atlasFrames = parseSparrowAtlas(xml), atlasPrefixes = mapOf("idle" to "Idle ", "hey" to "Left "))
        assertEquals(2, atlas.animations.first { it.first == "idle" }.second)
        assertEquals(1, atlas.animations.first { it.first == "hey" }.second)
        assertFalse(atlas.problems.any { it.contains("prefijo de hey") })
        assertTrue(atlas.copy(atlasPrefixes = mapOf("idle" to "Missing")).problems.any { it.contains("idle") })
    }
}
