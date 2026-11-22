package com.fnfmodmaker.mod

import android.content.Context
import com.fnfmodmaker.media.MediaTools
import com.fnfmodmaker.model.ModConfig
import com.fnfmodmaker.model.SongConfig
import com.fnfmodmaker.model.StageDraft
import com.fnfmodmaker.model.StageSprite
import java.io.BufferedOutputStream
import java.io.File
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

data class BuildResult(
    val rootDir: File,
    val files: List<String>,
    val warnings: List<String>
)

/**
 * Crea la carpeta del mod y todos sus archivos (una o varias canciones),
 * y la empaqueta en un ZIP.
 */
object ModBuilder {

    fun build(
        context: Context,
        rawConfig: ModConfig,
        workRoot: File,
        onProgress: (String) -> Unit
    ): BuildResult {
        val warnings = mutableListOf<String>()
        // Si hay personaje propio, el chart de cada canción debe apuntar a él.
        val config = rawConfig.withCharacter()
        val modFolder = config.modFolder
        val root = File(workRoot, modFolder)
        if (root.exists()) root.deleteRecursively()
        if (!root.mkdirs()) throw IllegalStateException("No se pudo crear la carpeta de trabajo.")

        // Si no hay canciones, usamos una vacía para no romper.
        val songs = if (config.songs.isEmpty()) listOf(SongConfig()) else config.songs

        // ---------------- Icono del mod (uno solo) ----------------
        config.icon?.let { icon ->
            onProgress("Preparando el icono del mod…")
            val iconTarget = File(root, "_polymod_icon.png")
            if (!MediaTools.preparePng(File(icon.path), iconTarget, 512)) {
                warnings.add("El icono no se pudo procesar, el mod se creó sin icono.")
            }
        }

        // ---------------- Personaje propio (uno solo para todo el mod) ----------------
        CharacterBuilder.build(root, config, onProgress, warnings)

        // ---------------- Stage propio (uno solo para todo el mod) ----------------
        StageBuilder.build(root, config, onProgress, warnings)

        // Recopilamos metadatos por canción para los niveles/semanas.
        val songEntries = mutableListOf<SongEntry>()

        for ((index, song) in songs.withIndex()) {
            val songFolder = song.songFolder
            val songLabel = if (songs.size > 1) "canción ${index + 1} ($songFolder)" else "canción"
            onProgress("Procesando $songLabel…")

            val songDir = File(root, "songs/$songFolder")
            songDir.mkdirs()

            // ---------------- Audio de esta canción ----------------
            val instrumental = song.instrumental
                ?: throw IllegalArgumentException("Falta la instrumental de '$songFolder'.")

            onProgress(if (instrumental.isOgg) "Copiando instrumental de $songFolder…" else "Convirtiendo instrumental de $songFolder a OGG…")
            val instTarget = File(songDir, "Inst.ogg")
            if (instrumental.isOgg) {
                File(instrumental.path).copyTo(instTarget, overwrite = true)
            } else {
                val error = MediaTools.convertToOgg(File(instrumental.path), instTarget)
                if (error != null) {
                    throw IllegalStateException("No se pudo convertir la instrumental de $songFolder a OGG:\n$error")
                }
            }

            val durationMs = instrumental.durationMs

            // ---------------- Voces de esta canción ----------------
            val playerVocals = song.vocalsPlayer
            val opponentVocals = song.vocalsOpponent

            if (song.hasVocals) {
                if (song.hasSplitVocals) {
                    if (config.engine.includesVSlice) {
                        encodeAudio(
                            playerVocals!!,
                            File(songDir, "Voices-${characterFileId(song.player)}.ogg"),
                            "las voces del jugador de $songFolder",
                            warnings,
                            onProgress
                        )
                        encodeAudio(
                            opponentVocals!!,
                            File(songDir, "Voices-${characterFileId(song.opponent)}.ogg"),
                            "las voces del oponente de $songFolder",
                            warnings,
                            onProgress
                        )
                    }
                    if (config.engine.includesPsych) {
                        onProgress("Mezclando las dos pistas de voces para Psych en $songFolder…")
                        val mixed = File(songDir, "Voices.ogg")
                        val error = MediaTools.mixToOgg(
                            File(playerVocals!!.path),
                            File(opponentVocals!!.path),
                            mixed
                        )
                        if (error != null) {
                            warnings.add("No se pudieron mezclar las voces de $songFolder para Psych: $error")
                            if (mixed.exists()) mixed.delete()
                        }
                    }
                } else {
                    val single = playerVocals ?: opponentVocals!!
                    encodeAudio(single, File(songDir, "Voices.ogg"), "las voces de $songFolder", warnings, onProgress)
                }
            }

            songEntries.add(SongEntry(song, durationMs))
        }

        // ---------------- V-Slice ----------------
        if (config.engine.includesVSlice) {
            onProgress("Escribiendo archivos de V-Slice…")
            writeText(root, "_polymod_meta.json", JsonBuilders.polymodMeta(config))

            for (entry in songEntries) {
                val song = entry.song
                val songFolder = song.songFolder
                writeText(
                    root,
                    "data/songs/$songFolder/$songFolder-metadata.json",
                    JsonBuilders.vsliceMetadata(song, entry.durationMs)
                )
                writeText(
                    root,
                    "data/songs/$songFolder/$songFolder-chart.json",
                    JsonBuilders.vsliceChart(config, song, entry.durationMs)
                )
            }

            // Un solo nivel que referencia todas las canciones.
            for (entry in songEntries) {
                val song = entry.song
                writeText(
                    root,
                    "data/levels/$modFolder.json",
                    JsonBuilders.vsliceLevel(
                        song,
                        modFolder,
                        config.modTitle,
                        config.showInStoryMode
                    )
                )
                // Si hay varias canciones, sobreescribiríamos el nivel; en su lugar
                // generamos un nivel por canción con nombre único para no perder nada.
                // Pero para mantener compatibilidad con un nivel por mod, dejamos el último.
            }
            // Nota: si hay varias canciones, idealmente habría un nivel por canción.
            // Por ahora generamos un nivel por canción con sufijo si hay más de una.
            if (songEntries.size > 1) {
                for ((index, entry) in songEntries.withIndex()) {
                    val song = entry.song
                    val levelName = if (songEntries.size == 1) modFolder else "$modFolder-${index + 1}"
                    writeText(
                        root,
                        "data/levels/$levelName.json",
                        JsonBuilders.vsliceLevel(
                            song,
                            levelName,
                            config.modTitle,
                            config.showInStoryMode
                        )
                    )
                }
            }

            onProgress("Generando el título de la semana…")
            val titleFile = File(root, "images/storymenu/titles/$modFolder.png")
            if (!MediaTools.makeTitleCard(config.modTitle, config.weekColor, titleFile)) {
                warnings.add("No se pudo generar la imagen del título de la semana.")
            }
        }

        // ---------------- Psych Engine ----------------
        if (config.engine.includesPsych) {
            onProgress("Escribiendo archivos de Psych Engine…")
            writeText(root, "pack.json", JsonBuilders.psychPack(config))

            for (entry in songEntries) {
                val song = entry.song
                val songFolder = song.songFolder
                val chartJson = JsonBuilders.psychChart(config, song, entry.durationMs)
                val difficulties = song.orderedDifficulties
                for (difficulty in difficulties) {
                    val fileName = if (difficulty == "normal") "$songFolder.json" else "$songFolder-$difficulty.json"
                    writeText(root, "data/$songFolder/$fileName", chartJson)
                }
                // Psych abre siempre la dificultad "Normal", que es el archivo SIN sufijo.
                if (difficulties.none { it == "normal" }) {
                    writeText(root, "data/$songFolder/$songFolder.json", chartJson)
                    warnings.add(
                        "Psych arranca en la dificultad Normal de $songFolder: se copió " +
                            "${difficulties.first()} también como $songFolder.json."
                    )
                }
            }

            // Semana única con todas las canciones.
            if (songEntries.size == 1) {
                val song = songEntries.first().song
                writeText(
                    root,
                    "weeks/$modFolder.json",
                    JsonBuilders.psychWeek(
                        song,
                        modFolder,
                        config.modTitle,
                        config.showInStoryMode,
                        config.firstSong.player,
                        config.firstSong.girlfriend
                    )
                )
            } else {
                // Múltiples semanas: una por canción con nombre único.
                for ((index, entry) in songEntries.withIndex()) {
                    val song = entry.song
                    val weekName = "$modFolder-${index + 1}"
                    writeText(
                        root,
                        "weeks/$weekName.json",
                        JsonBuilders.psychWeek(
                            song,
                            weekName,
                            config.modTitle,
                            config.showInStoryMode,
                            song.player,
                            song.girlfriend
                        )
                    )
                }
            }
        }

        writeText(root, "LEEME.txt", readmeText(config, songEntries))

        onProgress("Empaquetando…")
        val files = root.walkTopDown()
            .filter { it.isFile }
            .map { it.relativeTo(root).invariantSeparatorsPath }
            .sorted()
            .toList()

        return BuildResult(root, files, warnings)
    }

    fun zip(rootDir: File, output: OutputStream, includeRoot: Boolean = true) {
        val base = if (includeRoot) (rootDir.parentFile ?: rootDir) else rootDir
        ZipOutputStream(BufferedOutputStream(output)).use { zip ->
            rootDir.walkTopDown().filter { it.isFile }.sortedBy { it.relativeTo(base).path }.forEach { file ->
                val entryName = file.relativeTo(base).invariantSeparatorsPath
                zip.putNextEntry(ZipEntry(entryName))
                file.inputStream().use { input -> input.copyTo(zip) }
                zip.closeEntry()
            }
        }
    }

    /**
     * Copia el audio tal cual si ya es OGG; si no, lo convierte.
     * Si falla, deja un aviso y no rompe el resto del mod.
     */
    private fun encodeAudio(
        source: com.fnfmodmaker.model.PickedAudio,
        target: File,
        label: String,
        warnings: MutableList<String>,
        onProgress: (String) -> Unit
    ) {
        onProgress(if (source.isOgg) "Copiando $label…" else "Convirtiendo $label a OGG…")
        if (source.isOgg) {
            File(source.path).copyTo(target, overwrite = true)
            return
        }
        val error = MediaTools.convertToOgg(File(source.path), target)
        if (error != null) {
            warnings.add("No se pudieron convertir $label, el mod se creó sin esa pista.")
            if (target.exists()) target.delete()
        }
    }

    /** Nombre de archivo válido para Voices-<personaje>.ogg */
    private fun characterFileId(raw: String): String {
        val clean = raw.trim().filterNot { it in "/\\:*?\"<>|" }
        return clean.ifEmpty { "bf" }
    }

    private fun writeText(root: File, relativePath: String, content: String) {
        val file = File(root, relativePath)
        file.parentFile?.mkdirs()
        file.writeText(content, Charsets.UTF_8)
    }

    private fun readmeText(config: ModConfig, songEntries: List<SongEntry>): String {
        val output = StringBuilder()
        output.appendLine("MOD: ${config.modTitle}")
        output.appendLine("Canciones: ${songEntries.map { it.song.songName }.joinToString(", ")}")
        output.appendLine("Creado con FNF Mod Maker")
        output.appendLine()
        if (config.engine.includesVSlice) {
            output.appendLine("== V-Slice (FNF 0.8.x de Android) ==")
            output.appendLine("1. Descomprime este ZIP en la carpeta de mods del juego:")
            output.appendLine("   Android/obb/me.funkin.fnf/mods/")
            output.appendLine("   (también vale Android/data/me.funkin.fnf/files/mods/)")
            output.appendLine("2. Abre el juego y busca las canciones en Freeplay.")
            output.appendLine()
        }
        if (config.engine.includesPsych) {
            output.appendLine("== Psych Engine (Android / PC) ==")
            output.appendLine("1. Descomprime este ZIP en la carpeta mods del juego:")
            output.appendLine("   - PC: <carpeta de Psych Engine>/mods/")
            output.appendLine("   - Android: Android/data/<paquete de Psych>/files/mods/")
            output.appendLine("2. Abre el juego: las canciones aparecen en Freeplay.")
            output.appendLine()
        }
        output.appendLine("El chart trae notas de práctica para comprobar que todo suena.")
        output.appendLine("Para chartear en serio, abre el editor de charts del juego")
        output.appendLine("(en Psych: tecla 7 / en V-Slice: menú de debug) y guarda encima.")
        output.appendLine()
        output.appendLine("Dificultades incluidas: ${songEntries.map { it.song.orderedDifficulties.joinToString(", ") }.joinToString(" | ")}")
        output.appendLine("Offset por canción: ${songEntries.map { "${it.song.songFolder}=${it.song.offsetMs} ms" }.joinToString(", ")}")
        when {
            config.songs.any { it.hasSplitVocals } -> output.appendLine(
                "Voces: Voices-<personaje>.ogg para V-Slice y Voices.ogg (mezcla) para Psych."
            )
            config.hasAnyVocals -> output.appendLine("Voces: una pista Voices.ogg por canción.")
            else -> output.appendLine("Voces: sin pista de voces.")
        }
        output.appendLine(
            if (config.showInStoryMode) "Aparece en Freeplay y en el modo historia."
            else "Aparece solo en Freeplay."
        )
        if (config.character.enabled) {
            output.appendLine(
                "Personaje propio: ${config.character.charFolder} " +
                    "(lado ${config.character.side.label.lowercase()})."
            )
        }
        output.appendLine()
        output.appendLine("Carpetas generadas:")
        songEntries.forEach { entry ->
            output.appendLine("  - songs/${entry.song.songFolder}/")
        }
        return output.toString()
    }

    private data class SongEntry(val song: SongConfig, val durationMs: Long)
}

/**
 * Construye el stage (escenario) personalizado.
 * Genera: fondo, sprites, XML atlas, JSON para V-Slice y Psych.
 */
object StageBuilder {

    fun build(
        root: File,
        config: ModConfig,
        onProgress: (String) -> Unit,
        warnings: MutableList<String>
    ) {
        val stage = config.stageDraft
        if (!stage.enabled || stage.backgroundImage == null) return

        onProgress("Procesando stage personalizado…")

        val stageFolder = stage.stageFolder
        val imagesDir = File(root, "images/stages/$stageFolder")
        imagesDir.mkdirs()

        // 1. Copiar imagen de fondo
        val bgFile = File(stage.backgroundImage!!.path)
        val bgTarget = File(imagesDir, "background.png")
        if (!MediaTools.preparePng(bgFile, bgTarget, 1920)) { // max 1920px ancho
            warnings.add("No se pudo procesar el fondo del stage.")
            return
        }

        // 2. Procesar sprites del stage
        val spritesDir = File(imagesDir, "sprites")
        spritesDir.mkdirs()
        
        val atlasFrames = mutableListOf<Frame>()
        var cursor = 0
        
        for (sprite in stage.sprites) {
            if (sprite.image == null) continue
            onProgress("Procesando sprite: ${sprite.displayName}…")
            
            val spriteFile = File(sprite.image!!.path)
            val spriteTarget = File(spritesDir, "${sprite.spriteId}.png")
            if (!MediaTools.preparePng(spriteFile, spriteTarget, 1024)) {
                warnings.add("No se pudo procesar sprite ${sprite.displayName}")
                continue
            }

            // Para atlas simple: un frame por sprite
            atlasFrames.add(Frame(
                name = "${stageFolder} ${sprite.spriteId}",
                x = 0, y = cursor * 256, // simplificado: apilados verticalmente
                width = 256, height = 256
            ))
            cursor++
        }

        // 3. Generar XML Sparrow para sprites del stage
        if (atlasFrames.isNotEmpty()) {
            val xml = StringBuilder()
            xml.append("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n")
            xml.append("<TextureAtlas imagePath=\"sprites.png\">\n")
            // Nota: en producción real se haría un atlas combinado con libgdx texture packer
            // Aquí simplificado: un sprite por archivo
            for (frame in atlasFrames) {
                xml.append("  <SubTexture name=\"${frame.name}\" x=\"${frame.x}\" y=\"${frame.y}\" ")
                xml.append("width=\"${frame.width}\" height=\"${frame.height}\"/>\n")
            }
            xml.append("</TextureAtlas>\n")
            File(imagesDir, "sprites.xml").writeText(xml.toString(), Charsets.UTF_8)
            
            // Crear imagen combinada simple (placeholder)
            createSpritesAtlas(spritesDir, imagesDir)
        }

        // 4. Generar JSON de stage para V-Slice
        if (config.engine.includesVSlice) {
            onProgress("Generando stage para V-Slice…")
            val vsliceStage = generateVSliceStageJson(stage, stageFolder)
            File(root, "data/stages/$stageFolder.json").writeText(vsliceStage, Charsets.UTF_8)
        }

        // 5. Generar JSON de stage para Psych
        if (config.engine.includesPsych) {
            onProgress("Generando stage para Psych Engine…")
            val psychStage = generatePsychStageJson(stage, stageFolder)
            File(root, "stages/$stageFolder.json").writeText(psychStage, Charsets.UTF_8)
        }
    }

    private data class Frame(val name: String, val x: Int, val y: Int, val width: Int, val height: Int)

    private fun createSpritesAtlas(spritesDir: File, outputDir: File) {
        // Placeholder: en producción usar libgdx TexturePacker
        // Aquí solo copiamos el primer sprite como atlas.png
        val firstSprite = spritesDir.listFiles()?.firstOrNull { it.extension == "png" }
        firstSprite?.copyTo(File(outputDir, "sprites.png"), overwrite = true)
    }

    private fun generateVSliceStageJson(stage: StageDraft, stageFolder: String): String {
        import org.json.JSONArray
        import org.json.JSONObject

        val camera = stage.cameraPosition
        val bounds = camera.bounds

        val stageJson = JSONObject()
            .put("version", "1.0.0")
            .put("name", stage.displayName.ifEmpty { stageFolder })
            .put("background", "stages/$stageFolder/background")
            .put("cameraPosition", JSONObject().put("x", camera.x).put("y", camera.y))
            .put("cameraZoom", camera.zoom)
            .put("followPlayer", camera.followPlayer)
            .put("bounds", JSONObject()
                .put("left", bounds.left)
                .put("right", bounds.right)
                .put("top", bounds.top)
                .put("bottom", bounds.bottom)
            )
            .put("sprites", JSONObject().put("atlas", "stages/$stageFolder/sprites"))
            .put("zoneColors", JSONArray().put(stage.zoneColors))
            .put("baseStage", stage.baseStageVSlice)
        
        return stageJson.toString(2)
    }

    private fun generatePsychStageJson(stage: StageDraft, stageFolder: String): String {
        import org.json.JSONArray
        import org.json.JSONObject

        val camera = stage.cameraPosition

        val spritesArray = JSONArray()
        for (sprite in stage.sprites) {
            spritesArray.put(
                JSONObject()
                    .put("name", sprite.spriteId)
                    .put("image", "stages/$stageFolder/sprites/${sprite.spriteId}")
                    .put("x", sprite.x)
                    .put("y", sprite.y)
                    .put("scale", sprite.scale)
                    .put("rotation", sprite.rotation)
                    .put("animated", sprite.animated)
                    .put("frames", sprite.animationFrames)
                    .put("fps", sprite.animationFps)
                    .put("layer", sprite.layer.name.lowercase())
            )
        }

        val stageJson = JSONObject()
            .put("name", stage.displayName.ifEmpty { stageFolder })
            .put("background", "stages/$stageFolder/background")
            .put("camera", JSONObject()
                .put("x", camera.x)
                .put("y", camera.y)
                .put("zoom", camera.zoom)
                .put("followPlayer", camera.followPlayer)
            )
            .put("bounds", JSONObject()
                .put("left", camera.bounds.left)
                .put("right", camera.bounds.right)
                .put("top", camera.bounds.top)
                .put("bottom", camera.bounds.bottom)
            )
            .put("sprites", spritesArray)
            .put("baseStage", stage.baseStagePsych)
        
        return stageJson.toString(2)
    }
}
