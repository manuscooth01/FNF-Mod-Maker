package com.fnfmodmaker.model

import com.fnfmodmaker.mod.StageTemplate
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class SimpleStageTest {
    @Test fun bothEnginesHaveTheirOwnStageSchemasAndSafeId() {
        val stage = StageDraft(enabled = true, stageId = "../Mi-Stage", cameraPosition = CameraPosition(zoom = 1.3f))
        assertEquals("fnfmm-mi-stage", stage.stageFolder)
        val v = JSONObject(StageTemplate.vslice(stage))
        assertEquals("1.0.1", v.getString("version"))
        assertEquals("stages/fnfmm-mi-stage/background", v.getJSONArray("props").getJSONObject(0).getString("assetPath"))
        assertEquals(3, v.getJSONObject("characters").length())
        val psych = JSONObject(StageTemplate.psych(stage))
        assertEquals(1.3, psych.getDouble("defaultZoom"), 0.0001)
        assertTrue(psych.has("boyfriend"))
        assertTrue(StageTemplate.psychLua(stage).contains("stages/fnfmm-mi-stage/background"))
    }
    @Test fun stageValidationRequiresBackgroundAndRejectsUnsupportedData() {
        val stage = StageDraft(enabled = true, stageId = "foo", sprites = listOf(StageSprite()))
        val issues = validateWizard(ModConfig(stageDraft = stage), { true })
        assertTrue(issues.any { it.message.contains("fondo") })
        assertTrue(issues.any { it.message.contains("objetos avanzados") })
    }
}
