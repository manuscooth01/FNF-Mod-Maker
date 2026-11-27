package com.fnfmodmaker.model

import com.fnfmodmaker.project.parseIndependentCharts
import com.google.gson.Gson
import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class IndependentChartIdentityTest {
    private val gson = Gson()

    private fun schema(text: String) = parseIndependentCharts(JsonParser.parseString(text).asJsonObject, gson)
    private fun adapter(text: String) = gson.fromJson(text, IndependentChartCollection::class.java)

    @Test fun validMatchingOrAbsentRedundantIdentityRemainsReadable() {
        listOf("""{"stable":{"id":"stable"}}""", """{"stable":{}}""").forEach { json ->
            assertEquals("stable", schema(json).songs.getValue("stable").id)
            assertEquals("stable", adapter(json).songs.getValue("stable").id)
        }
    }

    @Test fun contradictoryIdentityIsRejectedWithoutModifyingDocument() {
        val original = """{"stable":{"id":"different"}}"""
        val document = JsonParser.parseString(original).asJsonObject
        val before = document.toString()
        assertThrows(IllegalArgumentException::class.java) { parseIndependentCharts(document, gson) }
        assertEquals(before, document.toString())
        assertThrows(Exception::class.java) { adapter(original) }
    }

    @Test fun nonStringIdentityIsRejectedByBothReaders() {
        listOf("""{"stable":{"id":42}}""", """{"stable":{"id":null}}""").forEach { json ->
            assertThrows(IllegalArgumentException::class.java) { schema(json) }
            assertThrows(Exception::class.java) { adapter(json) }
        }
    }
}
