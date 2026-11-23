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

    @Test fun rejectsDoctypesRotatedAndInvalidFrames() {
        val bad = listOf(
            """<!DOCTYPE TextureAtlas [<!ENTITY xxe SYSTEM "file:///etc/passwd">]><TextureAtlas><SubTexture name="&xxe;" x="0" y="0" width="1" height="1"/></TextureAtlas>""",
            """<TextureAtlas><SubTexture name="x" x="0" y="0" width="1" height="1" rotated="true"/></TextureAtlas>""",
            """<TextureAtlas><SubTexture name="x" x="-1" y="0" width="1" height="1"/></TextureAtlas>""",
            """<TextureAtlas><SubTexture name="x" x="0" y="0" width="1" height="1"/><SubTexture name="x" x="1" y="0" width="1" height="1"/></TextureAtlas>"""
        )
        bad.forEach { value -> assertTrue(runCatching { parseSparrowAtlas(value) }.isFailure) }
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
