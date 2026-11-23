package com.fnfmodmaker.ai

import com.fnfmodmaker.model.*
import com.google.gson.Gson
import org.junit.Assert.*
import org.junit.Test

class ProjectProposalTest {
    private val base = ModConfig(engine = TargetEngine.PSYCH, modTitle = "Test", songs = listOf(SongConfig(songName = "First")))

    @Test fun plansAWholeBatchWithoutMutatingTheOriginal() {
        val args = """{"summary":"Ajustes","changes":[
            {"type":"character","positionX":12,"cameraY":-5,"side":"player"},
            {"type":"animation","name":"idle","fps":18,"offsetX":-4,"loop":true},
            {"type":"song","songIndex":0,"bpm":120,"speed":2.5},
            {"type":"chart","songIndex":0,"difficulty":"normal","notes":[{"timeMs":1600,"lane":0,"lengthMs":300},{"timeMs":0,"lane":4}]},
            {"type":"script","name":"saludo","content":"function onCreate()\n  debugPrint('hola')\nend"}
        ]}"""
        val proposal = ProjectProposalPlanner.plan(base, args)
        assertEquals(base, proposal.base)
        assertEquals("0", base.character.positionX)
        assertTrue(base.psychScripts.isEmpty())
        assertNull(base.getChart("first", "normal"))
        assertEquals("12", proposal.result.character.positionX)
        assertEquals(CharacterSide.PLAYER, proposal.result.character.side)
        assertEquals("18", proposal.result.character.settingsFor("idle").fps)
        assertEquals("120", proposal.result.songs[0].bpm)
        assertEquals(listOf(4, 0), proposal.result.getChart("first", "normal")!!.notes.map { it.lane })
        assertTrue(proposal.result.psychScripts["saludo"]!!.contains("onCreate"))
        assertEquals(5, proposal.items.size)
        assertTrue(proposal.items.last().details.contains("debugPrint"))
        assertTrue(proposal.items.last().details.contains("se ejecutará"))
    }

    @Test fun rejectsInvalidBatchWithoutPartialMutation() {
        val bad = listOf(
            """{"summary":"x","changes":[{"type":"character","positionX":2},{"type":"script","name":"../evil","content":"x"}]}""",
            """{"summary":"x","changes":[{"type":"character","positionX":1.5}]}""",
            """{"summary":"x","changes":[{"type":"song","songIndex":0,"bpm":0}]}""",
            """{"summary":"x","changes":[{"type":"song","songIndex":6,"bpm":100}]}""",
            """{"summary":"x","changes":[{"type":"chart","songIndex":0,"difficulty":"normal","notes":[{"timeMs":1,"lane":8}]}]}""",
            """{"summary":"x","changes":[{"type":"chart","songIndex":0,"difficulty":"normal","notes":[{"timeMs":1,"lane":1},{"timeMs":1,"lane":1}]}]}""",
            """{"summary":"x","changes":[{"type":"script","name":"evil","content":"x","path":"../x"}]}"""
        )
        bad.forEach { args -> assertTrue(args, runCatching { ProjectProposalPlanner.plan(base, args) }.isFailure) }
        assertEquals("0", base.character.positionX)
        assertTrue(base.psychScripts.isEmpty())
    }

    @Test fun applyingAStaleProposalIsRejectedAtomically() {
        val args = """{"summary":"x","changes":[{"type":"character","positionX":10}]}"""
        val plan = ProjectProposalPlanner.plan(base, args)
        assertEquals("10", ProjectProposalPlanner.applyIfUnchanged(base, plan).character.positionX)
        val changed = base.copy(modTitle = "Otro")
        assertTrue(runCatching { ProjectProposalPlanner.applyIfUnchanged(changed, plan) }.isFailure)
        assertEquals("0", changed.character.positionX)
    }

    @Test fun respectsCharacterScopeEngineAndExistingEvents() {
        val script = """{"summary":"x","changes":[{"type":"script","name":"test","content":"return"}]}"""
        assertTrue(runCatching { ProjectProposalPlanner.plan(base, script, characterOnly = true) }.isFailure)
        assertTrue(runCatching { ProjectProposalPlanner.plan(base.copy(engine = TargetEngine.VSLICE), script) }.isFailure)
        val chart = ChartDraft(songFolder = "first", events = listOf(ChartEvent()))
        val withEvents = base.withChart("first", "normal", chart)
        val replacement = """{"summary":"x","changes":[{"type":"chart","songIndex":0,"difficulty":"normal","notes":[]}]}"""
        assertTrue(runCatching { ProjectProposalPlanner.plan(withEvents, replacement) }.isFailure)
    }

    @Test fun createsSongWithoutPretendingItHasAudioAndPreservesOldSavedDefaults() {
        val args = """{"summary":"x","changes":[{"type":"new_song","name":"Second","bpm":120}]}"""
        val plan = ProjectProposalPlanner.plan(base, args)
        assertNull(plan.result.songs.last().instrumental)
        assertTrue(plan.items.first().details.contains("sin audio"))
        val old = Gson().fromJson("""{"modTitle":"Anterior"}""", ModConfig::class.java)
        assertTrue(old.psychScripts.isEmpty())
    }

    @Test fun contextOmitsLocalPathsAndScriptContents() {
        val withScript = base.copy(psychScripts = mapOf("a" to "private code"))
        val context = projectContext(withScript, false)
        assertTrue(context.contains("First"))
        assertFalse(context.contains("private code"))
        assertFalse(context.contains("/home/"))
    }

    @Test fun toolProtocolAcceptsOnlyTheProposalTool() {
        val body = AiProtocol.projectToolsBody("model", "haz cambios", "{}")
        assertTrue(body.contains("propose_project_changes"))
        // Build a correctly escaped response rather than relying on a network provider.
        val arguments = """{"summary":"x","changes":[]}"""
        val function = com.google.gson.JsonObject().apply { addProperty("name", "propose_project_changes"); addProperty("arguments", arguments) }
        val call = com.google.gson.JsonObject().apply { addProperty("type", "function"); add("function", function) }
        val message = com.google.gson.JsonObject().apply { add("tool_calls", com.google.gson.JsonArray().apply { add(call) }) }
        val choice = com.google.gson.JsonObject().apply { add("message", message) }
        val response = com.google.gson.JsonObject().apply { add("choices", com.google.gson.JsonArray().apply { add(choice) }) }.toString()
        assertEquals(arguments, AiProtocol.parseProjectResponse(response).arguments)
        function.addProperty("name", "delete_files")
        assertTrue(runCatching { AiProtocol.parseProjectResponse(com.google.gson.JsonObject().apply { add("choices", com.google.gson.JsonArray().apply { add(choice) }) }.toString()) }.isFailure)
    }
}
