package com.fnfmodmaker.mod

import com.fnfmodmaker.model.StageDraft
import org.json.JSONArray
import org.json.JSONObject

/** A deliberately small static-background stage, with separate engine schemas. */
object StageTemplate {
    private fun pair(x: Number, y: Number) = JSONArray().put(x).put(y)
    fun vslice(stage: StageDraft): String {
        fun character(x: Number, y: Number, z: Int, cx: Int, cy: Int) = JSONObject()
            .put("position", pair(x, y)).put("zIndex", z).put("cameraOffsets", pair(cx, cy))
        return JSONObject().put("version", "1.0.1").put("name", stage.displayName.ifBlank { stage.stageId })
            .put("cameraZoom", stage.cameraPosition.zoom)
            .put("props", JSONArray().put(JSONObject().put("name", "background")
                .put("assetPath", "stages/${stage.stageFolder}/background")
                .put("position", pair(-600, -200)).put("scale", pair(1, 1)).put("scroll", pair(1, 1)).put("zIndex", 10)))
            .put("characters", JSONObject().put("bf", character(989.5, 885, 300, -100, -100))
                .put("dad", character(335, 885, 200, 150, -100))
                .put("gf", character(751.5, 787, 100, 0, 0))).toString(2)
    }
    fun psych(stage: StageDraft): String = JSONObject().put("directory", "").put("defaultZoom", stage.cameraPosition.zoom)
        .put("stageUI", "normal").put("isPixelStage", false).put("hide_girlfriend", false)
        .put("boyfriend", pair(770, 100)).put("opponent", pair(100, 100)).put("girlfriend", pair(400, 130))
        .put("camera_boyfriend", pair(0, 0)).put("camera_opponent", pair(0, 0)).put("camera_girlfriend", pair(0, 0))
        .put("camera_speed", 1).toString(2)
    fun psychLua(stage: StageDraft): String = """-- Fondo estático creado por FNF Mod Maker.
function onCreate()
    makeLuaSprite('fnfmmBackground', 'stages/${stage.stageFolder}/background', -600, -200)
    setScrollFactor('fnfmmBackground', 1, 1)
    addLuaSprite('fnfmmBackground', false)
end
"""
}
