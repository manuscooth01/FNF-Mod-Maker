package com.fnfmodmaker.mod

import com.fnfmodmaker.media.MediaTools
import com.fnfmodmaker.model.CharacterDraft
import com.fnfmodmaker.model.CharacterSide
import com.fnfmodmaker.model.CharacterVariation
import com.fnfmodmaker.model.ModConfig
import com.fnfmodmaker.model.hexToRgb
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Crea un personaje jugable a partir de una hoja de sprites en cuadrícula.
 *
 * Genera lo mismo que el "Spritesheet and XML generator" de escritorio:
 *  - images/characters/<id>.png  (la hoja)
 *  - images/characters/<id>.xml  (atlas Sparrow v2)
 *  - characters/<id>.json        (Psych Engine)
 *  - data/characters/<id>.json   (V-Slice, renderType "sparrow")
 *  - images/icons/icon-<id>.png  (icono de vida 300x150, dos frames)
 *  - Para cada variación: images/characters/<id>-<var>.png/.xml, characters/<id>-<var>.json, data/characters/<id>-<var>.json, icons
 */
object CharacterBuilder {

    data class Frame(val name: String, val x: Int, val y: Int, val width: Int, val height: Int)

    fun build(
        root: File,
        config: ModConfig,
        onProgress: (String) -> Unit,
        warnings: MutableList<String>
    ) {
        val draft = config.character
        if (!draft.enabled) return

        val id = draft.charFolder
        
        // Procesar personaje base
        processCharacter(root, config, draft, id, onProgress, warnings, isVariation = false)
        
        // Procesar variaciones
        for (variation in draft.variations) {
            val varId = "${id}-${variation.variationId}"
            processCharacter(root, config, variation, varId, onProgress, warnings, isVariation = true, baseId = id)
        }
    }

    private fun processCharacter(
        root: File,
        config: ModConfig,
        charData: Any, // CharacterDraft o CharacterVariation
        charId: String,
        onProgress: (String) -> Unit,
        warnings: MutableList<String>,
        isVariation: Boolean,
        baseId: String? = null
    ) {
        val sheet: com.fnfmodmaker.model.PickedImage?
        val frameWidth: Int
        val frameHeight: Int
        val animations: List<Pair<String, Int>>
        val scaleValue: Float
        val flipX: Boolean
        val displayName: String
        val icon: com.fnfmodmaker.model.PickedImage?
        val side: com.fnfmodmaker.model.CharacterSide

        when (charData) {
            is CharacterDraft -> {
                sheet = charData.sheet
                frameWidth = charData.frameWidthValue
                frameHeight = charData.frameHeightValue
                animations = charData.animations
                scaleValue = charData.scaleValue
                flipX = charData.flipX
                displayName = charData.displayName
                icon = charData.icon
                side = charData.side
            }
            is com.fnfmodmaker.model.CharacterVariation -> {
                sheet = charData.sheet
                frameWidth = charData.frameWidthValue
                frameHeight = charData.frameHeightValue
                animations = charData.animations
                scaleValue = charData.scaleValue
                flipX = charData.flipX
                displayName = charData.displayName
                icon = charData.icon
                side = config.character.side
            }
            else -> throw IllegalStateException("Tipo de personaje no soportado")
        }

        if (!isVariation && sheet == null) return
        if (isVariation && sheet == null) {
            warnings.add("Variación ${charId} no tiene spritesheet, se omite.")
            return
        }

        val sheetFile = File(sheet!!.path)
        val size = MediaTools.imageSize(sheetFile)
            ?: throw IllegalStateException("No se pudo leer la hoja de sprites: $charId")
        val columns = size.first / frameWidth
        val rows = size.second / frameHeight
        if (columns <= 0 || rows <= 0) {
            throw IllegalStateException("La hoja de sprites ($size) es más pequeña que un frame ($frameWidth x $frameHeight).")
        }

        if (animations.isEmpty()) {
            throw IllegalStateException("El personaje $charId no tiene animaciones con frames.")
        }
        val total = animations.sumOf { it.second }
        if (total > columns * rows) {
            throw IllegalStateException("La hoja $charId solo tiene ${columns * rows} frames y las animaciones necesitan $total.")
        }

        // ---------------- Imagen y XML ----------------
        onProgress(if (isVariation) "Copiando spritesheet variación: $charId…" else "Copiando la hoja de sprites…")
        val imageTarget = File(root, "images/characters/$charId.png")
        imageTarget.parentFile?.mkdirs()
        sheetFile.copyTo(imageTarget, overwrite = true)

        onProgress(if (isVariation) "Cortando frames variación: $charId…" else "Cortando la hoja en frames (XML Sparrow)…")
        val frames = mutableListOf<Frame>()
        var cursor = 0
        for ((animation, count) in animations) {
            val prefix = "$charId $animation"
            for (index in 0 until count) {
                val column = cursor % columns
                val row = cursor / columns
                frames.add(
                    Frame(
                        name = prefix + index.toString().padStart(4, '0'),
                        x = column * frameWidth,
                        y = row * frameHeight,
                        width = frameWidth,
                        height = frameHeight
                    )
                )
                cursor++
            }
        }

        val xml = StringBuilder()
        xml.append("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n")
        xml.append("<TextureAtlas imagePath=\"$charId.png\">\n")
        for (frame in frames) {
            xml.append("  <SubTexture name=\"${frame.name}\" x=\"${frame.x}\" y=\"${frame.y}\" ")
            xml.append("width=\"${frame.width}\" height=\"${frame.height}\"/>\n")
        }
        xml.append("</TextureAtlas>\n")
        File(root, "images/characters/$charId.xml").writeText(xml.toString(), Charsets.UTF_8)

        // ---------------- Icono de vida ----------------
        val iconId = prepareIcon(root, charData, charId, onProgress, warnings, isVariation)

        // ---------------- JSON de Psych ----------------
        if (config.engine.includesPsych) {
            onProgress(if (isVariation) "Escribiendo variación Psych: $charId…" else "Escribiendo el personaje para Psych…")
            File(root, "characters").mkdirs()
            File(root, "characters/$charId.json").writeText(
                psychCharacterJson(config, charData, charId, iconId, animations, isVariation),
                Charsets.UTF_8
            )
        }

        // ---------------- JSON de V-Slice ----------------
        if (config.engine.includesVSlice) {
            onProgress(if (isVariation) "Escribiendo variación V-Slice: $charId…" else "Escribiendo el personaje para V-Slice…")
            File(root, "data/characters").mkdirs()
            File(root, "data/characters/$charId.json").writeText(
                vsliceCharacterJson(config, charData, charId, iconId, animations, isVariation),
                Charsets.UTF_8
            )
        }
    }

    private fun prepareIcon(
        root: File,
        charData: Any,
        charId: String,
        onProgress: (String) -> Unit,
        warnings: MutableList<String>,
        isVariation: Boolean
    ): String {
        val icon = when (charData) {
            is CharacterDraft -> charData.icon
            is com.fnfmodmaker.model.CharacterVariation -> charData.icon
            else -> null
        }
        if (icon != null) {
            onProgress("Preparando el icono de vida para $charId…")
            val target = File(root, "images/icons/icon-$charId.png")
            if (MediaTools.prepareHealthIcon(File(icon.path), target)) {
                return charId
            }
            warnings.add("El icono de vida de $charId no se pudo procesar; se usará el del juego base.")
        }
        // Sin icono propio: usamos el del personaje base del lado elegido.
        val side = when (charData) {
            is CharacterDraft -> charData.side
            is com.fnfmodmaker.model.CharacterVariation -> com.fnfmodmaker.model.CharacterSide.OPPONENT // default para variaciones
            else -> com.fnfmodmaker.model.CharacterSide.OPPONENT
        }
        return if (side == com.fnfmodmaker.model.CharacterSide.PLAYER) "bf" else "dad"
    }

    private fun psychCharacterJson(
        config: ModConfig,
        charData: Any,
        charId: String,
        iconId: String,
        animations: List<Pair<String, Int>>,
        isVariation: Boolean
    ): String {
        val animationArray = JSONArray()
        for ((name, _) in animations) {
            animationArray.put(
                JSONObject()
                    .put("anim", name)
                    .put("name", "$charId $name")
                    .put("fps", 24)
                    .put("loop", false)
                    .put("indices", JSONArray())
                    .put("offsets", JSONArray().put(0).put(0))
            )
        }
        val scaleValue: Float
        val flipX: Boolean
        val colors: List<Int>
        when (charData) {
            is CharacterDraft -> {
                scaleValue = charData.scaleValue
                flipX = charData.flipX
                colors = hexToRgb(config.weekColor)
            }
            is com.fnfmodmaker.model.CharacterVariation -> {
                scaleValue = charData.scaleValue
                flipX = charData.flipX
                colors = hexToRgb(config.weekColor)
            }
            else -> {
                scaleValue = 1.0f
                flipX = false
                colors = hexToRgb(config.weekColor)
            }
        }
        return JSONObject()
            .put("animations", animationArray)
            .put("image", "characters/$charId")
            .put("scale", scaleValue)
            .put("sing_duration", 4)
            .put("healthicon", iconId)
            .put("position", JSONArray().put(0).put(0))
            .put("camera_position", JSONArray().put(0).put(0))
            .put("flip_x", flipX)
            .put("no_antialiasing", false)
            .put(
                "healthbar_colors",
                JSONArray().put(colors[0]).put(colors[1]).put(colors[2])
            )
            .toString(2)
    }

    private fun vsliceCharacterJson(
        config: ModConfig,
        charData: Any,
        charId: String,
        iconId: String,
        animations: List<Pair<String, Int>>,
        isVariation: Boolean
    ): String {
        val (displayName, flipX) = when (charData) {
            is CharacterDraft -> charData.displayName to charData.flipX
            is com.fnfmodmaker.model.CharacterVariation -> charData.displayName to charData.flipX
            else -> charId to false
        }
        val animationArray = JSONArray()
        for ((name, _) in animations) {
            animationArray.put(
                JSONObject()
                    .put("name", name)
                    .put("prefix", "$charId $name")
                    .put("frameRate", 24)
                    .put("looped", false)
                    .put("frameIndices", JSONArray())
                    .put("offsets", JSONArray().put(0).put(0))
            )
        }
        return JSONObject()
            .put("version", "1.0.0")
            .put("name", displayName.trim().ifEmpty { charId })
            .put("renderType", "sparrow")
            .put("assetPath", "characters/$charId")
            .put("scale", when (charData) {
                is CharacterDraft -> charData.scaleValue
                is com.fnfmodmaker.model.CharacterVariation -> charData.scaleValue
                else -> 1.0f
            })
            .put("healthIcon", JSONObject().put("id", iconId))
            .put("offsets", JSONArray().put(0).put(0))
            .put("cameraOffsets", JSONArray().put(0).put(0))
            .put("singTime", 4.0)
            .put("animations", animationArray)
            .put("startingAnimation", "idle")
            .put("flipX", flipX)
            .put("isPixel", false)
            .toString(2)
    }

    private fun prepareIcon(
        root: File,
        draft: CharacterDraft,
        id: String,
        onProgress: (String) -> Unit,
        warnings: MutableList<String>
    ): String {
        val icon = draft.icon
        if (icon != null) {
            onProgress("Preparando el icono de vida…")
            val target = File(root, "images/icons/icon-$id.png")
            if (MediaTools.prepareHealthIcon(File(icon.path), target)) {
                return id
            }
            warnings.add("El icono de vida no se pudo procesar; se usará el icono del juego base.")
        }
        // Sin icono propio: usamos el del personaje base del lado elegido.
        return if (draft.side == CharacterSide.PLAYER) "bf" else "dad"
    }

    private fun psychCharacterJson(
        config: ModConfig,
        draft: CharacterDraft,
        id: String,
        iconId: String,
        animations: List<Pair<String, Int>>
    ): String {
        val animationArray = JSONArray()
        for ((name, _) in animations) {
            animationArray.put(
                JSONObject()
                    .put("anim", name)
                    .put("name", "$id $name")
                    .put("fps", 24)
                    .put("loop", false)
                    .put("indices", JSONArray())
                    .put("offsets", JSONArray().put(0).put(0))
            )
        }
        val colors = hexToRgb(config.weekColor)
        return JSONObject()
            .put("animations", animationArray)
            .put("image", "characters/$id")
            .put("scale", draft.scaleValue)
            .put("sing_duration", 4)
            .put("healthicon", iconId)
            .put("position", JSONArray().put(0).put(0))
            .put("camera_position", JSONArray().put(0).put(0))
            .put("flip_x", draft.flipX)
            .put("no_antialiasing", false)
            .put(
                "healthbar_colors",
                JSONArray().put(colors[0]).put(colors[1]).put(colors[2])
            )
            .toString(2)
    }

    private fun vsliceCharacterJson(
        config: ModConfig,
        draft: CharacterDraft,
        id: String,
        iconId: String,
        animations: List<Pair<String, Int>>
    ): String {
        val animationArray = JSONArray()
        for ((name, _) in animations) {
            animationArray.put(
                JSONObject()
                    .put("name", name)
                    .put("prefix", "$id $name")
                    .put("frameRate", 24)
                    .put("looped", false)
                    .put("frameIndices", JSONArray())
                    .put("offsets", JSONArray().put(0).put(0))
            )
        }
        return JSONObject()
            .put("version", "1.0.0")
            .put("name", draft.displayName.trim().ifEmpty { id })
            .put("renderType", "sparrow")
            .put("assetPath", "characters/$id")
            .put("scale", draft.scaleValue)
            .put("healthIcon", JSONObject().put("id", iconId))
            .put("offsets", JSONArray().put(0).put(0))
            .put("cameraOffsets", JSONArray().put(0).put(0))
            .put("singTime", 4.0)
            .put("animations", animationArray)
            .put("startingAnimation", "idle")
            .put("flipX", draft.flipX)
            .put("isPixel", false)
            .toString(2)
    }
}
