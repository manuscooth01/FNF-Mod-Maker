package com.fnfmodmaker.model

import com.fnfmodmaker.mod.StageTemplate
import com.google.gson.Gson
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class StagePositionStudioTest {
    @Test fun oldProjectsKeepExactFormerStageDefaults() {
        val old = """{"engine":"BOTH","stageDraft":{"enabled":true,"stageId":"stage","cameraPosition":{"zoom":1.25}},"character":{"enabled":false},"songs":[],"charts":{}}"""
        val restored = Gson().fromJson(old, ModConfig::class.java)
        val v = JSONObject(StageTemplate.vslice(restored.stageDraft)).getJSONObject("characters")
        val p = JSONObject(StageTemplate.psych(restored.stageDraft))
        assertEquals(989.5, v.getJSONObject("bf").getJSONArray("position").getDouble(0), 0.0)
        assertEquals(885.0, v.getJSONObject("dad").getJSONArray("position").getDouble(1), 0.0)
        assertEquals(770.0, p.getJSONArray("boyfriend").getDouble(0), 0.0)
        assertEquals(130.0, p.getJSONArray("girlfriend").getDouble(1), 0.0)
        assertEquals(1.0, p.getDouble("camera_speed"), 0.0)
    }
    @Test fun editsPersistIndependentlyAndExportRealEngineFields() {
        val oldChar = CharacterDraft(charId = "old", animationSettings = mapOf("idle" to CharacterAnimationSettings(offsetX = "19")))
        val v = defaultVSliceLayout().copy(bf = defaultVSliceLayout().bf.copy(x = 123f, cameraY = 67f, scale = 1.5f, zIndex = 450, scrollX = .5f),
            dad = defaultVSliceLayout().dad.copy(y = 333f), gf = defaultVSliceLayout().gf.copy(cameraX = 88f))
        val p = defaultPsychLayout().copy(bf = defaultPsychLayout().bf.copy(x = 90f), dad = defaultPsychLayout().dad.copy(cameraX = 22f), gf = defaultPsychLayout().gf.copy(y = 44f, cameraY = 66f))
        val config = ModConfig(engine = TargetEngine.BOTH, characterLibrary = listOf(CharacterEntry(draft = oldChar)),
            stageDraft = StageDraft(vSliceLayout = v, psychLayout = p, psychCameraSpeed = 1.7f, cameraPosition = CameraPosition(zoom = 1.2f)))
        val restored = Gson().fromJson(Gson().toJson(config), ModConfig::class.java)
        assertEquals(oldChar.animationSettings, restored.character.animationSettings)
        assertTrue(stageLayoutProblems(restored.stageDraft, TargetEngine.BOTH).isEmpty())
        val vs = JSONObject(StageTemplate.vslice(restored.stageDraft))
        val bf = vs.getJSONObject("characters").getJSONObject("bf")
        assertEquals(123.0, bf.getJSONArray("position").getDouble(0), 0.0)
        assertEquals(67.0, bf.getJSONArray("cameraOffsets").getDouble(1), 0.0)
        assertEquals(1.5, bf.getDouble("scale"), 0.0); assertEquals(450, bf.getInt("zIndex")); assertEquals(.5, bf.getJSONArray("scroll").getDouble(0), 0.0)
        assertEquals(333.0, vs.getJSONObject("characters").getJSONObject("dad").getJSONArray("position").getDouble(1), 0.0)
        assertEquals(88.0, vs.getJSONObject("characters").getJSONObject("gf").getJSONArray("cameraOffsets").getDouble(0), 0.0)
        val psych = JSONObject(StageTemplate.psych(restored.stageDraft))
        assertEquals(90.0, psych.getJSONArray("boyfriend").getDouble(0), 0.0)
        assertEquals(22.0, psych.getJSONArray("camera_opponent").getDouble(0), 0.0)
        assertEquals(44.0, psych.getJSONArray("girlfriend").getDouble(1), 0.0)
        assertEquals(66.0, psych.getJSONArray("camera_girlfriend").getDouble(1), 0.0)
        assertEquals(1.7, psych.getDouble("camera_speed"), .0001)
    }
    @Test fun unsupportedPsychActorTransformsAndInvalidNumbersAreBlocked() {
        val stage = StageDraft(psychLayout = defaultPsychLayout().copy(bf = defaultPsychLayout().bf.copy(scale = 2f)))
        assertTrue(stageLayoutProblems(stage, TargetEngine.PSYCH).any { it.contains("no se representan") })
        assertTrue(stageLayoutProblems(stage.copy(vSliceLayout = defaultVSliceLayout().copy(gf = StageActorPlacement(x = Float.NaN))), TargetEngine.VSLICE).isNotEmpty())
        assertTrue(stageLayoutProblems(stage.copy(psychCameraSpeed = -1f), TargetEngine.PSYCH).any { it.contains("velocidad") })
    }
}
