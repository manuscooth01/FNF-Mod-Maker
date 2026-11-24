package com.fnfmodmaker.mod

import org.junit.Assert.*
import org.junit.Test

class ChartEventJsonTest {
    @Test fun preservesNestedValuesAndPreciseNumbers() {
        val events = """[{"t":0.25,"e":"custom","v":{"value":123456789012345678901234567890},"unknown":true}]"""
        assertEquals(events, validateVSliceEvents(events))
    }
    @Test fun rejectsInvalidAndAmbiguousEvents() {
        listOf("{}", "[{\"t\":-1,\"e\":\"x\"}]", "[{\"t\":1,\"e\":\"x\",\"e\":\"y\"}]", "[{\"t\":1}]", "[{\"t\":\"1\",\"e\":\"x\"}]").forEach {
            assertThrows(Exception::class.java) { validateVSliceEvents(it) }
        }
    }
}
