package com.fnfmodmaker.mod

import com.fnfmodmaker.media.MediaTools
import com.fnfmodmaker.model.CharacterDraft
import com.fnfmodmaker.model.CharacterSide
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
        val sheet = draft.sheet ?: return
        val frameWidth = draft.frameWidthValue
        val frameHeight = draft.frameHeightValue
        if (frameWidth <= 0 || frameHeight <= 0) {
            throw IllegalStateException("El tamaño de cada frame del personaje no es válido.")
        }

        val sheetFile = File(sheet.path)
        val size = MediaTools.imageSize(sheetFile)
            ?: throw IllegalStateException("No se pudo leer la hoja de sprites del personaje.")
        val columns = size.first / frameWidth
        val rows = size.second / frameHeight
        if (columns <= 0 || rows <= 0) {
            throw IllegalStateException(
                "La hoja de sprites (${size.first}x${size.second}) es más pequeña que un frame " +
                    "(${frameWidth}x$frameHeight)."
            )
        }

        val animations = draft.animations
        if (animations.isEmpty()) {
            throw IllegalStateException("El personaje no tiene ninguna animación con frames.")
        }
        val total = draft.totalFrames
        if (total > columns * rows) {
            throw IllegalStateException(
                "La hoja solo tiene ${columns * rows} frames (${columns}x$rows) y las animaciones " +
                    "necesitan $total. Baja los frames o corrige el tamaño del frame."
            )
        }

        // ---------------- Imagen y XML ----------------
        onProgress("Copiando la hoja de sprites…")
        val imageTarget = File(root, "images/characters/$id.png")
        imageTarget.parentFile?.mkdirs()
        sheetFile.copyTo(imageTarget, overwrite = true)

        onProgress("Cortando la hoja en frames (XML Sparrow)…")
        val frames = mutableListOf<Frame>()
        var cursor = 0
        for ((animation, count) in animations) {
            val prefix = "$id $animation"
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
        xml.append("<TextureAtlas imagePath=\"$id.png\">\n")
        for (frame in frames) {
            xml.append("  <SubTexture name=\"${frame.name}\" x=\"${frame.x}\" y=\"${frame.y}\" ")
            xml.append("width=\"${frame.width}\" height=\"${frame.height}\"/>\n")
        }
        xml.append("</TextureAtlas>\n")
        File(root, "images/characters/$id.xml").writeText(xml.toString(), Charsets.UTF_8)

        // ---------------- Icono de vida ----------------
        val iconId = prepareIcon(root, draft, id, onProgress, warnings)

        // ---------------- JSON de Psych ----------------
        if (config.engine.includesPsych) {
            onProgress("Escribiendo el personaje para Psych…")
            File(root, "characters").mkdirs()
            File(root, "characters/$id.json").writeText(
                psychCharacterJson(config, draft, id, iconId, animations),
                Charsets.UTF_8
            )
        }

        // ---------------- JSON de V-Slice ----------------
        if (config.engine.includesVSlice) {
            onProgress("Escribiendo el personaje para V-Slice…")
            File(root, "data/characters").mkdirs()
            File(root, "data/characters/$id.json").writeText(
                vsliceCharacterJson(config, draft, id, iconId, animations),
                Charsets.UTF_8
            )
        }
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
