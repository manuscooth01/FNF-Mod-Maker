package com.fnfmodmaker.model

import org.junit.Assert.*
import org.junit.Test

/** Pure JVM rules of the character ZIP import: nothing touches files or projects here. */
class CharacterZipImportTest {
    private fun entry(name: String, text: String = "") = CharacterZipImport.Entry(name, text.toByteArray(Charsets.UTF_8))
    private val png = entry("dummy", "")
    private val xml = """<TextureAtlas imagePath="x"><SubTexture name="Hero idle0000" x="0" y="0" width="1" height="1"/></TextureAtlas>"""
    private val psychJson = """{"image":"characters/hero","scale":1.5,"healthicon":"hero","animations":[{"anim":"idle","name":"Idle","fps":24,"loop":false,"offsets":[1,-3]}]}"""
    private val vsliceJson = """{"version":"1.0.0","name":"Rival","renderType":"sparrow","assetPath":"shared:characters/rival","healthIcon":{"id":"rival"},"animations":[{"name":"idle","prefix":"Rival idle"}]}"""

    @Test fun psychZipProducesCandidateWithSheetXmlAndIcon() {
        val analysis = CharacterZipImport.analyze(listOf(
            entry("characters/hero.json", psychJson),
            entry("images/characters/hero.png", ""),
            entry("images/characters/hero.xml", xml),
            entry("images/icons/icon-hero.png", "")))
        assertTrue(analysis.importable)
        val candidate = analysis.candidates.single()
        assertEquals("Psych", candidate.format)
        assertEquals("hero", candidate.id)
        assertEquals("images/characters/hero.png", candidate.sheet.name)
        assertEquals("images/characters/hero.xml", candidate.xml.name)
        assertEquals("images/icons/icon-hero.png", candidate.icon!!.name)
        assertTrue(analysis.ignored.isEmpty())
    }

    @Test fun vsliceZipResolvesSharedSchemeAndBasenameFallback() {
        val analysis = CharacterZipImport.analyze(listOf(
            entry("rival.json", vsliceJson),
            entry("stuff/rival.png", ""),
            entry("atlas/rival.xml", xml)))
        assertTrue(analysis.importable)
        val candidate = analysis.candidates.single()
        assertEquals("V-Slice", candidate.format)
        assertEquals("Rival", candidate.name)
        assertEquals("stuff/rival.png", candidate.sheet.name)
        assertEquals("atlas/rival.xml", candidate.xml.name)
        assertTrue(candidate.icon == null)
        assertTrue(candidate.warnings.any { it.contains("icono") })
    }

    @Test fun dangerousPathsAndDuplicatesRejectTheWholeImport() {
        val analysis = CharacterZipImport.analyze(listOf(
            entry("characters/hero.json", psychJson),
            entry("images/characters/hero.png", ""),
            entry("images/characters/hero.xml", xml),
            entry("../escape.png", ""),
            entry("images/characters/hero.PNG", "")))
        assertFalse(analysis.importable)
        assertTrue(analysis.problems.any { it.contains("Ruta peligrosa") })
        assertTrue(analysis.problems.any { it.contains("duplicada") })
    }

    @Test fun malformedJsonIsReportedAndGoodCandidatesStillImport() {
        val analysis = CharacterZipImport.analyze(listOf(
            entry("broken.json", "{ not json"),
            entry("hero.json", psychJson),
            entry("images/characters/hero.png", ""),
            entry("images/characters/hero.xml", xml)))
        assertTrue(analysis.problems.any { it.contains("JSON malformado") })
        assertEquals(listOf("hero"), analysis.candidates.map { it.id })
    }

    @Test fun missingResourcesRejectOnlyTheirOwnCandidate() {
        val analysis = CharacterZipImport.analyze(listOf(
            entry("complete.json", psychJson),
            entry("incomplete.json", """{"image":"characters/gone","animations":[{"anim":"idle","name":"Idle"}]}"""),
            entry("images/characters/hero.png", ""),
            entry("images/characters/hero.xml", xml)))
        assertTrue(analysis.problems.any { it.contains("incomplete.json") && it.contains("PNG") })
        // El identificador viene del nombre del JSON: complete.json → "complete".
        assertEquals(listOf("complete"), analysis.candidates.map { it.id })
        assertTrue(analysis.candidates.single().sheet.name.endsWith("hero.png"))
    }

    @Test fun ambiguousJsonAndZipWithoutCharactersAreNotImportable() {
        val both = CharacterZipImport.analyze(listOf(entry("both.json", """{"image":"x","assetPath":"x","animations":[]}""")))
        assertFalse(both.importable)
        assertTrue(both.problems.any { it.contains("no reconocido") })
        val empty = CharacterZipImport.analyze(listOf(entry("a.png", ""), entry("readme.txt", "hola")))
        assertTrue(empty.candidates.isEmpty())
        assertTrue(empty.problems.isEmpty())
        assertFalse(empty.importable)
        assertTrue(empty.ignored.containsAll(listOf("a.png", "readme.txt")))
    }
}
