package com.fnfmodmaker.mod

import org.junit.Assert.*
import org.junit.Test

class VSliceTempoTest {
    @Test fun editDuplicateRemoveAndCalculateBeatTime() {
        val original = """[{"t":0,"bpm":120,"n":4,"d":4,"extra":123456789012345678901234567890},{"t":2000,"bpm":180,"n":3,"d":8}]"""
        assertEquals(4.0, vSliceBeatAt(original, 2000.0), 0.00001)
        assertEquals(10.0, vSliceBeatAt(original, 3000.0), 0.00001)
        val edited = changeVSliceTempo(original, 1, "2500", "150", "3", "8")
        assertTrue(edited.contains("123456789012345678901234567890"))
        val duplicated = duplicateVSliceTempo(edited, 1, "4000")
        assertEquals(3, com.google.gson.JsonParser.parseString(duplicated).asJsonArray.size())
        assertEquals(edited, removeVSliceTempo(duplicated, 2))
        assertThrows(Exception::class.java) { removeVSliceTempo(original, 0) }
        assertThrows(Exception::class.java) { changeVSliceTempo(original, null, "2000", "100", "4", "4") }
        assertThrows(Exception::class.java) { changeVSliceTempo(original, 1, "2000", "-5", "4", "3") }
        val withTuplets = """[{"t":0,"bpm":120,"n":4,"bt":[4,3,2,5]}]"""
        assertThrows(Exception::class.java) { changeVSliceTempo(withTuplets, 0, "0", "120", "3", "4") }
        val adapted = changeVSliceTempo(withTuplets, 0, "0", "120", "3", "4", true)
        assertEquals("[4,3,2]", com.google.gson.JsonParser.parseString(adapted).asJsonArray[0].asJsonObject.get("bt").toString())
    }
}
