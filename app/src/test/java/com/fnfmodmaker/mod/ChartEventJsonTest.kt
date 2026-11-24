package com.fnfmodmaker.mod

import org.junit.Assert.*
import org.junit.Test

class ChartEventJsonTest {
    @Test fun preservesNestedValuesAndPreciseNumbers() {
        val events = """[{"t":0.25,"e":"custom","v":{"value":123456789012345678901234567890},"unknown":true}]"""
        assertEquals(events, validateVSliceEvents(events))
    }
    @Test fun visualOperationsKeepUnknownValuesAndRejectStaleOrInvalidChanges() {
        val source = """[{"t":1,"e":"custom","v":{"amount":1},"unknown":123456789012345678901234567890}]"""
        val changed = changeVSliceEvent(source, 0, "12.5", "custom2", "{\"amount\":2}")
        assertEquals("""[{"t":12.5,"e":"custom2","v":{"amount":2},"unknown":123456789012345678901234567890}]""", changed)
        val added = changeVSliceEvent(changed, null, "24", "another", "true")
        val duplicated = duplicateVSliceEvent(added, 0)
        assertEquals(3, com.google.gson.JsonParser.parseString(duplicated).asJsonArray.size())
        assertEquals(added, removeVSliceEvent(duplicated, 2))
        assertThrows(Exception::class.java) { changeVSliceEvent(source, 3, "1", "x", "null") }
        assertThrows(Exception::class.java) { changeVSliceEvent(source, 0, "1", "x", "{\"x\":1,\"x\":2}") }
    }
    @Test fun validatesPsychGroupsWithoutFlattening() {
        val events = """[[100,[["Hey!","0",""],["Custom","one","two"]]]]"""
        assertEquals(events, validatePsychEvents(events))
        listOf("[[1,[[\"x\",1,\"\"]]]]", "[[1,[[\"\",\"\",\"\"]]]]", "[[1,{}]]").forEach {
            assertThrows(Exception::class.java) { validatePsychEvents(it) }
        }
    }
    @Test fun rejectsInvalidAndAmbiguousEvents() {
        listOf("{}", "[{\"t\":-1,\"e\":\"x\"}]", "[{\"t\":1,\"e\":\"x\",\"e\":\"y\"}]", "[{\"t\":1}]", "[{\"t\":\"1\",\"e\":\"x\"}]").forEach {
            assertThrows(Exception::class.java) { validateVSliceEvents(it) }
        }
    }
}
