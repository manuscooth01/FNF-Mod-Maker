package com.fnfmodmaker.mod

import org.junit.Assert.*
import org.junit.Test

class PsychEventEditingTest {
    @Test fun actionsCanBeMovedWithoutAlteringOtherActionsOrNulls() {
        val original = """[[100.1234567890123456789,[["Hey!","0",null],["custom","á","two"]]],[50,[["Other","",""]]]]"""
        val ref = psychEventRefs(original).first()
        val edited = changePsychEvent(original, ref, "250", "Hey!", "1", null)
        assertEquals("""[[100.1234567890123456789,[["custom","á","two"]]],[50,[["Other","",""]]],[250,[["Hey!","1",null]]]]""", edited)
        val duplicate = duplicatePsychEvent(edited, psychEventRefs(edited).last())
        val sorted = sortPsychEvents(duplicate)
        assertEquals(listOf("50", "100.1234567890123456789", "250", "250"), psychEventRefs(sorted).map { it.time })
        val removed = removePsychEvent(sorted, psychEventRefs(sorted).last())
        assertEquals(3, psychEventRefs(removed).size)
        assertThrows(IllegalArgumentException::class.java) { removePsychEvent(edited, ref) }
        assertThrows(Exception::class.java) { changePsychEvent(original, null, "-1", "foo", "", "") }
        assertThrows(Exception::class.java) { changePsychEvent(original, null, "100", "", "", "") }
    }
    @Test fun unchangedTimestampDoesNotRoundOrSplitGroups() {
        val original = """[[1.1234567890123456789,[["x","a","b"],["y","c","d"]]]]"""
        val changed = changePsychEvent(original, psychEventRefs(original).first(), "1.1234567890123456789", "x", "changed", "b")
        assertEquals("""[[1.1234567890123456789,[["x","changed","b"],["y","c","d"]]]]""", changed)
    }
}
