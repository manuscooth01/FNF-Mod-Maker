package com.fnfmodmaker.mod

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import com.fnfmodmaker.media.MediaTools
import com.fnfmodmaker.model.CHARACTER_ANIMATIONS
import com.fnfmodmaker.model.CharacterDraft
import com.fnfmodmaker.model.parseSparrowAtlas
import com.fnfmodmaker.model.rewriteSparrowImagePath
import com.fnfmodmaker.model.atlasFramesFit
import com.fnfmodmaker.model.planCharacterAtlas
import com.fnfmodmaker.model.placement
import com.fnfmodmaker.model.psychCameraX
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
        if (draft.importedSourceJson.isNotEmpty()) warnings.add("El personaje se generó desde datos editables de un JSON ${draft.importedSourceFormat}: índices y campos específicos no representados pueden perderse. El original se conserva en el proyecto editable.")
        require(draft.problems.isEmpty()) { draft.problems.joinToString(" ") }

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

        val imageTarget = File(root, "images/characters/$charId.png")
        imageTarget.parentFile?.mkdirs()
        val importedAtlas = charData is CharacterDraft && charData.useAtlas
        val frames = if (importedAtlas && charData is CharacterDraft) {
            val sheetFile = File(charData.atlasSheet?.path ?: throw IllegalStateException("Falta el PNG del atlas."))
            val xmlFile = File(charData.atlasXml?.path ?: throw IllegalStateException("Falta el XML del atlas."))
            require(MediaTools.isPng(sheetFile)) { "El atlas debe ser un PNG válido." }
            require(xmlFile.length() <= 4_000_000) { "El XML es demasiado grande." }
            val xmlText = xmlFile.readText(Charsets.UTF_8)
            val parsed = parseSparrowAtlas(xmlText)
            val size = MediaTools.imageSize(sheetFile) ?: throw IllegalStateException("No se pudo leer el PNG del atlas.")
            require(atlasFramesFit(parsed, size.first, size.second)) { "El XML contiene frames fuera del PNG." }
            require(parsed == charData.atlasFrames) { "El XML cambió; vuelve a seleccionarlo." }
            onProgress("Copiando el atlas Sparrow…")
            sheetFile.copyTo(imageTarget, overwrite = true)
            // Mantener atributos Sparrow (recorte, tamaño original, etc.).
            val rewritten = rewriteSparrowImagePath(xmlText, "$charId.png")
            File(root, "images/characters/$charId.xml").writeText(rewritten, Charsets.UTF_8)
            emptyList()
        } else if (charData is CharacterDraft && charData.useIndividualFrames) {
            onProgress("Creando hoja de sprites a partir de los frames…")
            packIndividualFrames(charData, charId, imageTarget)
        } else {
            if (sheet == null) {
                if (isVariation) warnings.add("Variación $charId no tiene spritesheet, se omite.")
                return
            }
            val sheetFile = File(sheet.path)
            if (!MediaTools.isPng(sheetFile)) {
                throw IllegalStateException("La hoja de sprites de $charId debe ser un PNG válido.")
            }
            if (frameWidth <= 0 || frameHeight <= 0) {
                throw IllegalStateException("El tamaño de frame de $charId debe ser mayor que cero.")
            }
            val size = MediaTools.imageSize(sheetFile)
                ?: throw IllegalStateException("No se pudo leer la hoja de sprites: $charId")
            val columns = size.first / frameWidth
            val rows = size.second / frameHeight
            if (columns <= 0 || rows <= 0) {
                throw IllegalStateException("La hoja de sprites ($size) es más pequeña que un frame ($frameWidth x $frameHeight).")
            }
            if (animations.isEmpty()) throw IllegalStateException("El personaje $charId no tiene animaciones con frames.")
            val total = animations.sumOf { it.second.toLong() }
            if (total > columns.toLong() * rows) {
                throw IllegalStateException("La hoja $charId solo tiene ${columns.toLong() * rows} frames y las animaciones necesitan $total.")
            }
            onProgress("Copiando la hoja de sprites…")
            sheetFile.copyTo(imageTarget, overwrite = true)
            var cursor = 0
            buildList {
                for ((animation, count) in animations) {
                    for (index in 0 until count) {
                        add(Frame("$charId $animation" + index.toString().padStart(4, '0'),
                            (cursor % columns) * frameWidth, (cursor / columns) * frameHeight,
                            frameWidth, frameHeight))
                        cursor++
                    }
                }
            }
        }
        if (!importedAtlas) {
        onProgress("Generando XML Sparrow…")

        val xml = StringBuilder()
        xml.append("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n")
        xml.append("<TextureAtlas imagePath=\"$charId.png\">\n")
        for (frame in frames) {
            xml.append("  <SubTexture name=\"${frame.name}\" x=\"${frame.x}\" y=\"${frame.y}\" ")
            xml.append("width=\"${frame.width}\" height=\"${frame.height}\"/>\n")
        }
        xml.append("</TextureAtlas>\n")
        File(root, "images/characters/$charId.xml").writeText(xml.toString(), Charsets.UTF_8)
        }

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

    private fun packIndividualFrames(draft: CharacterDraft, charId: String, output: File): List<Frame> {
        val ordered = draft.animationNames.flatMap { name ->
            draft.animationFrames[name].orEmpty().mapIndexed { index, frame -> Triple(name, index, frame) }
        }
        require(ordered.size <= 100) { "El máximo es 100 frames por personaje." }
        val sizes = ordered.map { (_, _, frame) ->
            val file = File(frame.image.path)
            if (!MediaTools.isPng(file)) throw IllegalStateException("Frame PNG no encontrado o inválido: ${frame.image.displayName}")
            MediaTools.imageSize(file) ?: throw IllegalStateException("No se pudo leer ${frame.image.displayName}.")
        }
        val layout = planCharacterAtlas(sizes)
            ?: throw IllegalStateException("Los frames no caben en una hoja de 4096 × 4096.")
        val bitmap = Bitmap.createBitmap(layout.width, layout.height, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(bitmap)
            val frames = ordered.mapIndexed { cursor, (animation, index, frame) ->
                val x = cursor % layout.columns * layout.cellWidth
                val y = cursor / layout.columns * layout.cellHeight
                val source = BitmapFactory.decodeFile(frame.image.path)
                    ?: throw IllegalStateException("No se pudo abrir ${frame.image.displayName}.")
                try {
                    canvas.drawBitmap(source, x.toFloat(), y.toFloat(), null)
                } finally {
                    source.recycle()
                }
                Frame("$charId $animation" + index.toString().padStart(4, '0'), x, y, sizes[cursor].first, sizes[cursor].second)
            }
            if (!output.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }) {
                throw IllegalStateException("No se pudo guardar la hoja de sprites.")
            }
            return frames
        } finally {
            bitmap.recycle()
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
            val settings = (charData as? CharacterDraft)?.settingsFor(name)
            animationArray.put(
                JSONObject()
                    .put("anim", name)
                    .put("name", if (charData is CharacterDraft && charData.useAtlas) charData.prefixFor(name) else "$charId $name")
                    .put("fps", settings?.fps?.toIntOrNull() ?: 24)
                    .put("loop", settings?.loop ?: false)
                    .put("indices", JSONArray())
                    .put("offsets", JSONArray().put(settings?.offsetX?.toIntOrNull() ?: 0).put(settings?.offsetY?.toIntOrNull() ?: 0))
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
        val placement = (charData as? CharacterDraft)?.placement()
        val psychCameraX = if (charData is CharacterDraft) placement!!.psychCameraX(charData.side) else 0
        return JSONObject()
            .put("animations", animationArray)
            .put("image", "characters/$charId")
            .put("scale", scaleValue)
            .put("sing_duration", 4)
            .put("healthicon", iconId)
            .put("position", JSONArray().put(placement?.x ?: 0).put(placement?.y ?: 0))
            .put("camera_position", JSONArray().put(psychCameraX).put(placement?.cameraY ?: 0))
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
            val settings = (charData as? CharacterDraft)?.settingsFor(name)
            animationArray.put(
                JSONObject()
                    .put("name", name)
                    .put("prefix", if (charData is CharacterDraft && charData.useAtlas) charData.prefixFor(name) else "$charId $name")
                    .put("frameRate", settings?.fps?.toIntOrNull() ?: 24)
                    .put("looped", settings?.loop ?: false)
                    .put("frameIndices", JSONArray())
                    .put("offsets", JSONArray().put(settings?.offsetX?.toIntOrNull() ?: 0).put(settings?.offsetY?.toIntOrNull() ?: 0))
            )
        }
        val placement = (charData as? CharacterDraft)?.placement()
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
            .put("offsets", JSONArray().put(placement?.x ?: 0).put(placement?.y ?: 0))
            .put("cameraOffsets", JSONArray().put(placement?.cameraX ?: 0).put(placement?.cameraY ?: 0))
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
