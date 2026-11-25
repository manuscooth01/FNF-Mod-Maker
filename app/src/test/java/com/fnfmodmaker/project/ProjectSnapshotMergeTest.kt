package com.fnfmodmaker.project

import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Test

class ProjectSnapshotMergeTest {
    private fun json(value: String) = JsonParser.parseString(value).asJsonObject

    @Test fun appliesKnownEditsWithoutDroppingUnknownDocuments() {
        val raw = json("""{"title":"old","songs":[{"name":"a","extra":{"path":"x","displayName":"x","uri":"x"}}],"custom":{"path":"relative"}}""")
        val base = json("""{"title":"old","songs":[{"name":"a"}]}""")
        val edited = json("""{"title":"new","songs":[{"name":"a"}]}""")
        val result = mergeProjectSnapshot(raw, base, edited)
        assertEquals("new", result.get("title").asString)
        assertEquals(raw.get("custom"), result.get("custom"))
        assertEquals(raw.get("songs"), result.get("songs"))
        assertEquals("old", raw.get("title").asString)
    }
    @Test fun rejectsDeletingOrReplacingAnObjectWithUnknownData() {
        val raw = json("""{"charts":{"song":{"normal":{"notes":[],"custom":{"path":"keep"}}}}}""")
        val base = json("""{"charts":{"song":{"normal":{"notes":[]}}}}""")
        val before = raw.deepCopy()
        for (edit in listOf("""{"charts":{}}""", """{"charts":{"song":{"normal":null}}}""")) {
            assertThrows(IllegalArgumentException::class.java) { mergeProjectSnapshot(raw, base, json(edit)) }
            assertEquals(before, raw)
        }
    }
    @Test fun rejectsChangedArraysWithUnknownDataRatherThanReassigningIt() {
        val raw = json("""{"songs":[{"name":"a","unknown":{"path":"x"}},{"name":"b"}]}""")
        val base = json("""{"songs":[{"name":"a"},{"name":"b"}]}""")
        val edited = json("""{"songs":[{"name":"b"}]}""")
        val before = raw.deepCopy()
        assertThrows(IllegalArgumentException::class.java) { mergeProjectSnapshot(raw, base, edited) }
        assertEquals(before, raw)
    }
}
