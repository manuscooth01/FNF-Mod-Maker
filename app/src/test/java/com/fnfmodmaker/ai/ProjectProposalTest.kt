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
            {"type":"chart","songIndex":0,"difficulty":"normal","notes":[{"timeMs":1600,"lane":0,"lengthMs":300},{"timeMs":0,"lane":4}]}
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
        assertTrue(proposal.result.psychScripts.isEmpty())
        assertEquals(4, proposal.items.size)
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
        assertTrue(runCatching { ProjectProposalPlanner.plan(base, script) }.isFailure)
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

    @Test fun libraryAssignmentsAndEngineSpecificStageEditsAreRealAndValidated() {
        val file = kotlin.io.path.createTempFile("fnf-ai", ".png").toFile()
        try {
            file.writeText("fixture")
            val gson = com.google.gson.GsonBuilder().registerTypeAdapter(android.net.Uri::class.java, object : com.google.gson.TypeAdapter<android.net.Uri>() {
                override fun write(out: com.google.gson.stream.JsonWriter, value: android.net.Uri?) { out.nullValue() }
                override fun read(input: com.google.gson.stream.JsonReader): android.net.Uri? { input.skipValue(); return null }
            }.nullSafe()).create()
            val image = gson.fromJson("""{"displayName":"sheet.png","path":${gson.toJson(file.path)},"uri":null}""", PickedImage::class.java)
            val hero = CharacterEntry("hero-key", CharacterDraft(enabled = true, charId = "metza", displayName = "Metza", sheet = image, sheetWidth = 2, sheetHeight = 2,
                frameWidth = "1", frameHeight = "1", idleFrames = "1", leftFrames = "0", downFrames = "0", upFrames = "0", rightFrames = "0"))
            val config = base.copy(engine = TargetEngine.BOTH, characterLibrary = listOf(CharacterEntry(), hero), stageDraft = StageDraft(enabled = true, stageId = "club"))
            val args = """{"summary":"roles","changes":[{"type":"assignment","role":"opponent","characterId":"hero-key"},{"type":"animation","characterId":"hero-key","name":"idle","fps":30,"offsetX":-7},{"type":"stage_actor","engine":"vslice","role":"bf","positionX":1100},{"type":"stage_actor","engine":"psych","role":"gf","cameraY":21}]}"""
            val proposal = ProjectProposalPlanner.plan(config, args)
            assertEquals("metza", proposal.result.effectiveExportConfig().firstSong.opponent)
            assertEquals("30", proposal.result.characterLibrary[1].draft.settingsFor("idle").fps)
            assertEquals("-7", proposal.result.characterLibrary[1].draft.settingsFor("idle").offsetX)
            assertEquals(1100f, proposal.result.stageDraft.vSliceLayout.bf.x, 0f)
            assertEquals(21f, proposal.result.stageDraft.psychLayout.gf.cameraY, 0f)
            val vs = org.json.JSONObject(com.fnfmodmaker.mod.StageTemplate.vslice(proposal.result.stageDraft))
            val psych = org.json.JSONObject(com.fnfmodmaker.mod.StageTemplate.psych(proposal.result.stageDraft))
            assertEquals(1100.0, vs.getJSONObject("characters").getJSONObject("bf").getJSONArray("position").getDouble(0), 0.0)
            assertEquals(21.0, psych.getJSONArray("camera_girlfriend").getDouble(1), 0.0)
            assertEquals(config.stageDraft.psychLayout.bf, proposal.result.stageDraft.psychLayout.bf)
            assertEquals(config.character, proposal.result.character)
            val context = projectContext(config, false)
            assertTrue(context.contains("hero-key") && context.contains("metza") && context.contains("vSliceLayout"))
            assertFalse(context.contains(file.path))
            val bad = listOf(
                """{"summary":"x","changes":[{"type":"assignment","role":"opponent","characterId":"missing"}]}""",
                """{"summary":"x","changes":[{"type":"stage_actor","engine":"psych","role":"bf","scale":2}]}""",
                """{"summary":"x","changes":[{"type":"character","characterId":"missing","positionX":3}]}"""
            )
            bad.forEach { assertTrue(it, runCatching { ProjectProposalPlanner.plan(config, it) }.isFailure) }
            file.delete()
            assertTrue(runCatching { ProjectProposalPlanner.applyIfUnchanged(config, proposal) }.isFailure)
            assertTrue(runCatching { ProjectProposalPlanner.plan(config, args) }.isFailure)
        } finally { file.delete() }
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
    @Test fun sceneZoomRequiresEnabledStageAndExplicitApproval() {
        val args = """{"summary":"Cámara","changes":[{"type":"scene","zoom":1.3}]}"""
        assertTrue(runCatching { ProjectProposalPlanner.plan(base, args) }.isFailure)
        val config = base.copy(stageDraft = StageDraft(enabled = true, stageId = "club"))
        val proposal = ProjectProposalPlanner.plan(config, args)
        assertEquals(1f, config.stageDraft.cameraPosition.zoom)
        assertEquals(1.3f, proposal.result.stageDraft.cameraPosition.zoom)
        assertTrue(proposal.items.single().details.contains("todas las canciones"))
        assertTrue(runCatching { ProjectProposalPlanner.plan(config, args, true) }.isFailure)
        assertTrue(projectContext(config, false).contains("fnfmm-club"))
    }
}
