package com.fnfmodmaker.mod

import com.fnfmodmaker.model.StageDraft
import org.json.JSONArray
import org.json.JSONObject

/** A deliberately small static-background stage, with separate engine schemas. */
object StageTemplate {
    private fun pair(x: Number, y: Number) = JSONArray().put(x).put(y)
    fun vslice(stage: StageDraft): String {
        fun character(actor: com.fnfmodmaker.model.StageActorPlacement) = JSONObject()
            .put("position", pair(actor.x, actor.y)).put("zIndex", actor.zIndex)
            .put("scale", actor.scale).put("scroll", pair(actor.scrollX, actor.scrollY))
            .put("cameraOffsets", pair(actor.cameraX, actor.cameraY))
        return JSONObject().put("version", "1.0.1").put("name", stage.displayName.ifBlank { stage.stageId })
            .put("cameraZoom", stage.cameraPosition.zoom)
            .put("props", JSONArray().put(JSONObject().put("name", "background")
                .put("assetPath", "stages/${stage.stageFolder}/background")
                .put("position", pair(-600, -200)).put("scale", pair(1, 1)).put("scroll", pair(1, 1)).put("zIndex", 10)))
            .put("characters", JSONObject().put("bf", character(stage.vSliceLayout.bf))
                .put("dad", character(stage.vSliceLayout.dad))
                .put("gf", character(stage.vSliceLayout.gf))).toString(2)
    }
    fun psych(stage: StageDraft): String {
        val layout = stage.psychLayout
        return JSONObject().put("directory", "").put("defaultZoom", stage.cameraPosition.zoom)
            .put("stageUI", "normal").put("isPixelStage", false).put("hide_girlfriend", false)
            .put("boyfriend", pair(layout.bf.x, layout.bf.y)).put("opponent", pair(layout.dad.x, layout.dad.y)).put("girlfriend", pair(layout.gf.x, layout.gf.y))
            .put("camera_boyfriend", pair(layout.bf.cameraX, layout.bf.cameraY)).put("camera_opponent", pair(layout.dad.cameraX, layout.dad.cameraY)).put("camera_girlfriend", pair(layout.gf.cameraX, layout.gf.cameraY))
            .put("camera_speed", stage.psychCameraSpeed).toString(2)
    }
    fun psychLua(stage: StageDraft): String = """-- Fondo estático creado por FNF Mod Maker.
function onCreate()
    makeLuaSprite('fnfmmBackground', 'stages/${stage.stageFolder}/background', -600, -200)
    setScrollFactor('fnfmmBackground', 1, 1)
    addLuaSprite('fnfmmBackground', false)
end
"""
}
