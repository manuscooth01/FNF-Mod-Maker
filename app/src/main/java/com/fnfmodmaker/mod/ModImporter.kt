package com.fnfmodmaker.mod

import android.content.Context
import android.net.Uri
import com.fnfmodmaker.media.MediaTools
import com.fnfmodmaker.model.ChartDraft
import com.fnfmodmaker.model.ChartNote
import com.fnfmodmaker.model.ModConfig
import com.fnfmodmaker.model.PickedAudio
import com.fnfmodmaker.model.PickedImage
import com.fnfmodmaker.model.SongConfig
import com.fnfmodmaker.model.StageDraft
import com.fnfmodmaker.model.StageSprite
import com.fnfmodmaker.model.TargetEngine
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.zip.ZipFile
import java.util.zip.ZipEntry

/**
 * Importa un mod existente desde un ZIP y reconstruye el ModConfig
 * para poder editarlo en el wizard.
 */
object ModImporter {

    data class ImportResult(
        val config: ModConfig,
        val warnings: List<String>
    )

    data class ModMetaResult(
        val modTitle: String,
        val modFolder: String,
        val author: String,
        val description: String,
        val weekColor: String,
        val showInStoryMode: Boolean
    )

    fun importFromZip(
        context: Context,
        zipFile: File,
        workRoot: File,
        onProgress: (String) -> Unit
    ): ImportResult {
        val warnings = mutableListOf<String>()

        onProgress("Extrayendo ZIP…")
        val extractDir = File(workRoot, "import_${System.currentTimeMillis()}")
        if (extractDir.exists()) extractDir.deleteRecursively()
        extractDir.mkdirs()

        ZipFile(zipFile).use { zip ->
            val entries = zip.entries()
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                val outFile = File(extractDir, entry.name)
                if (entry.isDirectory) {
                    outFile.mkdirs()
                } else {
                    outFile.parentFile?.mkdirs()
                    zip.getInputStream(entry).use { input ->
                        outFile.outputStream().use { output -> input.copyTo(output) }
                    }
                }
            }
        }

        onProgress("Leyendo metadatos…")
        val engine = detectEngine(extractDir)
        val meta = readModMeta(extractDir, engine)
        val iconFile = findIcon(extractDir)

        onProgress("Detectando canciones…")
        val songs = readSongs(extractDir, engine, warnings)

        onProgress("Buscando personaje propio…")
        val characterDraft = readCharacter(extractDir, engine, warnings)

        onProgress("Buscando stage propio…")
        val stageDraft = readStage(extractDir, warnings)

        val processedIcon = iconFile?.let { src ->
            val target = File(context.cacheDir, "import_icon.png")
            if (MediaTools.preparePng(src, target, 512)) {
                PickedImage(Uri.fromFile(target), target.name, target.absolutePath)
            } else null
        }

        val config = ModConfig(
            engine = engine,
            modTitle = meta.modTitle,
            modId = meta.modFolder,
            author = meta.author,
            description = meta.description,
            songs = songs,
            weekColor = meta.weekColor,
            showInStoryMode = meta.showInStoryMode,
            character = characterDraft,
            stageDraft = stageDraft,
            icon = processedIcon
        )

        extractDir.deleteRecursively()

        return ImportResult(config, warnings)
    }

    fun importStageFromZip(
        context: Context,
        zipFile: File,
        workRoot: File,
        onProgress: (String) -> Unit
    ): StageDraft {
        val warnings = mutableListOf<String>()

        onProgress("Extrayendo stage…")
        val extractDir = File(workRoot, "import_stage_${System.currentTimeMillis()}")
        if (extractDir.exists()) extractDir.deleteRecursively()
        extractDir.mkdirs()

        ZipFile(zipFile).use { zip ->
            val entries = zip.entries()
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                val outFile = File(extractDir, entry.name)
                if (entry.isDirectory) {
                    outFile.mkdirs()
                } else {
                    outFile.parentFile?.mkdirs()
                    zip.getInputStream(entry).use { input ->
                        outFile.outputStream().use { output -> input.copyTo(output) }
                    }
                }
            }
        }

        val stage = readStage(extractDir, warnings)

        extractDir.deleteRecursively()
        return stage
    }

    private fun detectEngine(dir: File): TargetEngine {
        val hasVSlice = File(dir, "_polymod_meta.json").exists() || File(dir, "data/levels").exists()
        val hasPsych = File(dir, "pack.json").exists() || File(dir, "weeks").exists()
        return when {
            hasVSlice && hasPsych -> TargetEngine.BOTH
            hasVSlice -> TargetEngine.VSLICE
            hasPsych -> TargetEngine.PSYCH
            else -> TargetEngine.BOTH
        }
    }

    private fun readModMeta(dir: File, engine: TargetEngine): ModMetaResult {
        var modTitle = "Mod Importado"
        var modFolder = "mod-importado"
        var author = "Desconocido"
        var description = "Importado con FNF Mod Maker"
        var weekColor = "#C24B99"
        var showInStoryMode = false

        if (engine.includesVSlice) {
            val polymodFile = File(dir, "_polymod_meta.json")
            if (polymodFile.exists()) {
                try {
                    val json = JSONObject(polymodFile.readText())
                    modTitle = json.optString("title", modTitle)
                    modFolder = json.optString("title", modFolder).lowercase().replace(" ", "-")
                    author = json.getJSONArray("contributors").getJSONObject(0).optString("name", author)
                    description = json.optString("description", description)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }

        if (engine.includesPsych) {
            val packFile = File(dir, "pack.json")
            if (packFile.exists()) {
                try {
                    val json = JSONObject(packFile.readText())
                    modTitle = json.optString("name", modTitle)
                    modFolder = json.optString("name", modFolder).lowercase().replace(" ", "-")
                    description = json.optString("description", description)
                    val color = json.getJSONArray("color")
                    if (color.length() == 3) {
                        weekColor = "#${color.getInt(0).toString(16).padStart(2, '0')}${color.getInt(1).toString(16).padStart(2, '0')}${color.getInt(2).toString(16).padStart(2, '0')}".uppercase()
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }

        return ModMetaResult(modTitle, modFolder, author, description, weekColor, false)
    }

    private fun findIcon(dir: File): File? {
        val candidates = listOf(
            File(dir, "_polymod_icon.png"),
            File(dir, "icon.png"),
            File(dir, "mod_icon.png")
        )
        return candidates.firstOrNull { it.exists() }
    }

    private fun readSongs(dir: File, engine: TargetEngine, warnings: MutableList<String>): List<SongConfig> {
        val songs = mutableListOf<SongConfig>()

        if (engine.includesVSlice) {
            val songsDir = File(dir, "data/songs")
            if (songsDir.exists()) {
                val dirs = songsDir.listFiles()?.filter { it.isDirectory } ?: emptyArray()
                for (songDir in dirs) {
                    val song = readVSliceSong(songDir, warnings)
                    if (song != null) songs.add(song)
                }
            }
        }

        if (engine.includesPsych) {
            val dataDir = File(dir, "data")
            val weeksDir = File(dir, "weeks")
            if (dataDir.exists() && weeksDir.exists()) {
                val weekFiles = weeksDir.listFiles()?.filter { it.extension == "json" } ?: emptyArray()
                for (weekFile in weekFiles) {
                    val song = readPsychSong(weekFile, dataDir, warnings)
                    if (song != null) songs.add(song)
                }
            }
        }

        if (songs.isEmpty()) {
            songs.add(SongConfig())
            warnings.add("No se detectaron canciones en el mod; se creó una vacía.")
        }

        return songs.distinctBy { it.songFolder }
    }

    private fun readVSliceSong(songDir: File, warnings: MutableList<String>): SongConfig? {
        val metadataFile = songDir.listFiles()?.firstOrNull { it.name.endsWith("-metadata.json") }
        val chartFile = songDir.listFiles()?.firstOrNull { it.name.endsWith("-chart.json") }
        val audioFile = songDir.listFiles()?.firstOrNull { it.name == "Inst.ogg" }
        val voicesFile = songDir.listFiles()?.firstOrNull { it.name.startsWith("Voices") && it.extension == "ogg" }

        metadataFile ?: return null

        try {
            val meta = JSONObject(metadataFile.readText())
            val songName = meta.optString("songName", songDir.name)
            val artist = meta.optString("artist", "Desconocido")
            val bpm = meta.optJSONObject("playData")?.optInt("bpm") ?: meta.optInt("bpm") ?: 150
            val offset = meta.optJSONObject("offsets")?.optInt("instrumental") ?: 0
            val speed = meta.optJSONObject("playData")?.optJSONObject("scrollSpeed")?.keys()?.firstOrNull()?.toIntOrNull()?.let { (it / 100.0).toString() } ?: "2.0"

            val playData = meta.optJSONObject("playData")
            val difficulties = mutableSetOf<String>()
            playData?.optJSONArray("difficulties")?.let { arr ->
                for (i in 0 until arr.length()) {
                    difficulties.add(arr.getString(i))
                }
            }
            if (difficulties.isEmpty()) difficulties.addAll(setOf("easy", "normal", "hard"))

            val characters = playData?.optJSONObject("characters")
            val player = characters?.optString("player", "bf")
            val girlfriend = characters?.optString("girlfriend", "gf")
            val opponent = characters?.optString("opponent", "dad")
            val stage = playData?.optString("stage", "mainStage")

            return SongConfig(
                songName = songName,
                artist = artist,
                bpm = bpm.toString(),
                speed = speed,
                player = player,
                girlfriend = girlfriend,
                opponent = opponent,
                stageVSlice = stage,
                stagePsych = "stage",
                difficulties = difficulties,
                practiceNotes = true,
                offsetMs = offset.toString(),
                instrumental = audioFile?.let { PickedAudio(Uri.fromFile(it), it.name, it.absolutePath, MediaTools.readDurationMs(it.absolutePath)) },
                vocalsPlayer = voicesFile?.let { PickedAudio(Uri.fromFile(it), it.name, it.absolutePath, MediaTools.readDurationMs(it.absolutePath)) }
            )
        } catch (e: Exception) {
            warnings.add("Error leyendo canción V-Slice ${songDir.name}: ${e.message}")
            return null
        }
    }

    private fun readPsychSong(weekFile: File, dataDir: File, warnings: MutableList<String>): SongConfig? {
        try {
            val weekJson = JSONObject(weekFile.readText())
            val songsArray = weekJson.getJSONArray("songs")
            if (songsArray.length() == 0) return null

            val songEntry = songsArray.getJSONArray(0)
            val songName = songEntry.getString(0)
            val opponent = songEntry.getString(1)
            val weekColor = songEntry.getJSONArray(2).let { (it.getInt(0) shl 16 or it.getInt(1) shl 8 or it.getInt(2)).toString(16).padStart(6, '0') }
                .let { "#$it" }

            val songFolder = songName.lowercase().replace(" ", "-").replace(Regex("[^a-z0-9-]"), "")
            val songDataDir = File(dataDir, songFolder)
            val chartFile = songDataDir.listFiles()?.firstOrNull { it.name == "$songFolder.json" || it.name.startsWith("$songFolder-") && it.extension == "json" }
            val songsDir = File(dataDir.parentFile, "songs/$songFolder")
            val audioFileReal = songsDir.listFiles()?.firstOrNull { it.name == "Inst.ogg" }
            val voicesFile = songsDir.listFiles()?.firstOrNull { it.name == "Voices.ogg" }

            var bpm = 150
            var offset = 0
            var speed = 2.0
            var player = "bf"
            var girlfriend = "gf"
            var stage = "stage"
            val difficulties = mutableSetOf<String>()

            chartFile?.let {
                try {
                    val chart = JSONObject(it.readText())
                    bpm = chart.optInt("bpm", bpm)
                    offset = chart.optInt("offset", offset)
                    speed = chart.optDouble("speed", speed)
                    player = chart.optString("player1", player)
                    opponent = chart.optString("player2", opponent)
                    girlfriend = chart.optString("gfVersion", girlfriend)
                    stage = chart.optString("stage", stage)
                    chart.optJSONArray("notes")?.forEach { section ->
                        val sec = section as JSONObject
                        val diff = sec.optString("difficulty", "").lowercase()
                        if (diff.isNotEmpty()) difficulties.add(diff)
                    }
                    if (difficulties.isEmpty()) difficulties.addAll(setOf("easy", "normal", "hard"))
                } catch (e: Exception) {
                    warnings.add("Error leyendo chart Psych ${it.name}: ${e.message}")
                }
            }

            return SongConfig(
                songName = songName,
                artist = "Desconocido",
                bpm = bpm.toString(),
                speed = speed.toString(),
                player = player,
                girlfriend = girlfriend,
                opponent = opponent,
                stageVSlice = "mainStage",
                stagePsych = stage,
                weekColor = weekColor,
                difficulties = difficulties,
                practiceNotes = true,
                offsetMs = offset.toString(),
                instrumental = audioFileReal?.let { PickedAudio(Uri.fromFile(it), it.name, it.absolutePath, MediaTools.readDurationMs(it.absolutePath)) },
                vocalsPlayer = voicesFile?.let { PickedAudio(Uri.fromFile(it), it.name, it.absolutePath, MediaTools.readDurationMs(it.absolutePath)) }
            )
        } catch (e: Exception) {
            warnings.add("Error leyendo semana Psych ${weekFile.name}: ${e.message}")
            return null
        }
    }

    private fun readCharacter(dir: File, engine: TargetEngine, warnings: MutableList<String>): com.fnfmodmaker.model.CharacterDraft {
        val charDirs = if (engine.includesVSlice) File(dir, "data/characters") else File(dir, "characters")
        if (!charDirs.exists()) return com.fnfmodmaker.model.CharacterDraft()

        val charFiles = charDirs.listFiles()?.filter { it.extension == "json" } ?: emptyArray()
        val charFile = charFiles.firstOrNull() ?: return com.fnfmodmaker.model.CharacterDraft()

        try {
            val json = JSONObject(charFile.readText())
            val name = json.optString("name", charFile.nameWithoutExtension)
            val id = json.optString("name", charFile.nameWithoutExtension).lowercase().replace(" ", "-")
            val scale = json.optDouble("scale", 1.0).toFloat()
            val flipX = json.optBoolean("flip_x", false)
            val healthIcon = json.optString("healthicon", json.optString("healthIcon", ""))

            val animations = json.optJSONArray("animations")
            var idleFrames = "2"
            var leftFrames = "1"
            var downFrames = "1"
            var upFrames = "1"
            var rightFrames = "1"
            animations?.forEachIndexed { _, anim ->
                val a = anim as JSONObject
                val animName = a.optString("name", a.optString("anim", ""))
                when (animName) {
                    "idle" -> idleFrames = "2"
                    "singLEFT" -> leftFrames = "1"
                    "singDOWN" -> downFrames = "1"
                    "singUP" -> upFrames = "1"
                    "singRIGHT" -> rightFrames = "1"
                }
            }

            val spritesheet = File(dir, "images/characters/${charFile.nameWithoutExtension}.png")
            val iconFile = File(dir, "images/icons/icon-${charFile.nameWithoutExtension}.png")

            return com.fnfmodmaker.model.CharacterDraft(
                enabled = true,
                charId = id,
                displayName = name,
                sheet = if (spritesheet.exists()) com.fnfmodmaker.model.PickedImage(Uri.fromFile(spritesheet), spritesheet.name, spritesheet.absolutePath) else null,
                sheetWidth = 0,
                sheetHeight = 0,
                icon = if (iconFile.exists()) com.fnfmodmaker.model.PickedImage(Uri.fromFile(iconFile), iconFile.name, iconFile.absolutePath) else null,
                frameWidth = "150",
                frameHeight = "150",
                scale = scale.toString(),
                flipX = flipX,
                side = if (json.has("player")) com.fnfmodmaker.model.CharacterSide.PLAYER else com.fnfmodmaker.model.CharacterSide.OPPONENT,
                idleFrames = idleFrames,
                leftFrames = leftFrames,
                downFrames = downFrames,
                upFrames = upFrames,
                rightFrames = rightFrames
            )
        } catch (e: Exception) {
            warnings.add("Error leyendo personaje: ${e.message}")
            return com.fnfmodmaker.model.CharacterDraft()
        }
    }

    private fun readStage(dir: File, warnings: MutableList<String>): StageDraft {
        val vsliceStageDir = File(dir, "data/stages")
        val psychStageDir = File(dir, "stages")
        val imagesStagesDir = File(dir, "images/stages")

        val stageDir = if (vsliceStageDir.exists()) vsliceStageDir else psychStageDir
        if (!stageDir.exists()) {
            warnings.add("No se encontró carpeta de stage en el ZIP")
            return StageDraft()
        }

        val stageJsonFile = stageDir.listFiles()?.firstOrNull { it.extension == "json" }
            ?: return StageDraft()

        val stageId = stageJsonFile.nameWithoutExtension
        var stageName = stageId
        var backgroundImage: PickedImage? = null
        var backgroundColor = "#000000"
        val sprites = mutableListOf<StageSprite>()
        val cameraPosition = com.fnfmodmaker.model.CameraPosition()
        var baseStageVSlice = "mainStage"
        var baseStagePsych = "stage"

        try {
            val json = JSONObject(stageJsonFile.readText())
            stageName = json.optString("name", stageId)

            if (json.has("background")) {
                val bgPath = json.optString("background", "").replace("stages/", "").replace("/", "")
                val bgFile = File(imagesStagesDir, "$bgPath/background.png")
                if (bgFile.exists()) {
                    backgroundImage = PickedImage(Uri.fromFile(bgFile), bgFile.name, bgFile.absolutePath)
                }
                baseStageVSlice = json.optString("baseStage", "mainStage")

                val cam = json.optJSONObject("cameraPosition")
                if (cam != null) {
                    cameraPosition = com.fnfmodmaker.model.CameraPosition(
                        x = cam.optDouble("x", 0.0).toFloat(),
                        y = cam.optDouble("y", 0.0).toFloat(),
                        zoom = cam.optDouble("zoom", 1.0).toFloat(),
                        followPlayer = cam.optBoolean("followPlayer", true)
                    )
                    val bounds = cam.optJSONObject("bounds")
                    if (bounds != null) {
                        cameraPosition = cameraPosition.copy(bounds = com.fnfmodmaker.model.CameraBounds(
                            left = bounds.optDouble("left", -1000.0).toFloat(),
                            right = bounds.optDouble("right", 1000.0).toFloat(),
                            top = bounds.optDouble("top", -500.0).toFloat(),
                            bottom = bounds.optDouble("bottom", 500.0).toFloat()
                        ))
                    }
                }
            }

            if (json.has("background") && json.has("sprites")) {
                val spritesArray = json.optJSONArray("sprites")
                spritesArray?.forEachIndexed { _, spriteJson ->
                    val sprite = spriteJson as JSONObject
                    val spriteId = sprite.optString("name", sprite.optString("spriteId", ""))
                    val spriteDisplayName = spriteId
                    val spriteImagePath = sprite.optString("image", "").replace("stages/", "").replace("/", "")
                    val spriteFile = File(imagesStagesDir, "$spriteImagePath.png")
                    val spriteImage = if (spriteFile.exists()) {
                        PickedImage(Uri.fromFile(spriteFile), spriteFile.name, spriteFile.absolutePath)
                    } else null

                    sprites.add(StageSprite(
                        spriteId = spriteId,
                        displayName = spriteDisplayName,
                        image = spriteImage,
                        x = sprite.optDouble("x", 0.0).toFloat(),
                        y = sprite.optDouble("y", 0.0).toFloat(),
                        scale = sprite.optDouble("scale", 1.0).toFloat(),
                        rotation = sprite.optDouble("rotation", 0.0).toFloat(),
                        animated = sprite.optBoolean("animated", false),
                        animationFrames = sprite.optInt("frames", 1),
                        animationFps = sprite.optInt("fps", 24),
                        animationLoop = sprite.optBoolean("loop", true),
                        layer = StageSprite.Layer.valueOf(sprite.optString("layer", "FOREGROUND").uppercase())
                    ))
                }
                baseStagePsych = json.optString("baseStage", "stage")

                val cam = json.optJSONObject("camera")
                if (cam != null) {
                    cameraPosition = com.fnfmodmaker.model.CameraPosition(
                        x = cam.optDouble("x", 0.0).toFloat(),
                        y = cam.optDouble("y", 0.0).toFloat(),
                        zoom = cam.optDouble("zoom", 1.0).toFloat(),
                        followPlayer = cam.optBoolean("followPlayer", true)
                    )
                    val bounds = cam.optJSONObject("bounds")
                    if (bounds != null) {
                        cameraPosition = cameraPosition.copy(bounds = com.fnfmodmaker.model.CameraBounds(
                            left = bounds.optDouble("left", -1000.0).toFloat(),
                            right = bounds.optDouble("right", 1000.0).toFloat(),
                            top = bounds.optDouble("top", -500.0).toFloat(),
                            bottom = bounds.optDouble("bottom", 500.0).toFloat()
                        ))
                    }
                }
            }

        } catch (e: Exception) {
            warnings.add("Error leyendo stage JSON: ${e.message}")
        }

        if (backgroundImage == null) {
            val bgCandidates = listOf(
                File(imagesStagesDir, "$stageId/background.png"),
                File(imagesStagesDir, "background.png"),
                File(dir, "_polymod_icon.png")
            )
            backgroundImage = bgCandidates.firstOrNull { it.exists() }?.let {
                PickedImage(Uri.fromFile(it), it.name, it.absolutePath)
            }
        }

        return StageDraft(
            enabled = true,
            stageId = stageId,
            displayName = stageName,
            backgroundImage = backgroundImage,
            backgroundColor = backgroundColor,
            sprites = sprites,
            cameraPosition = cameraPosition,
            baseStageVSlice = baseStageVSlice,
            baseStagePsych = baseStagePsych
        )
    }
}