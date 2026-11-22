package com.fnfmodmaker.mod

import android.content.Context
import com.fnfmodmaker.media.MediaTools
import com.fnfmodmaker.model.ChartDraft
import com.fnfmodmaker.model.ChartNote
import com.fnfmodmaker.model.ModConfig
import com.fnfmodmaker.model.PickedAudio
import com.fnfmodmaker.model.PickedImage
import com.fnfmodmaker.model.SongConfig
import com.fnfmodmaker.model.TargetEngine
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.zip.ZipFile

/**
 * Importa un mod existente desde un ZIP y reconstruye el ModConfig
 * para poder editarlo en el wizard.
 */
object ModImporter {

    data class ImportResult(
        val config: ModConfig,
        val warnings: List<String>
    )

    fun importFromZip(
        context: Context,
        zipFile: File,
        workRoot: File,
        onProgress: (String) -> Unit
    ): ImportResult {
        val warnings = mutableListOf<String>()

        // Extraer ZIP a carpeta temporal
        onProgress("Extrayendo ZIP…")
        val extractDir = File(workRoot, "import_${System.currentTimeMillis()}")
        if (extractDir.exists()) extractDir.deleteRecursively()
        extractDir.mkdirs()

        ZipFile(zipFile).use { zip ->
            zip.entries().forEach { entry ->
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

        // Detectar motor(es) y leer metadatos
        onProgress("Leyendo metadatos…")
        val engine = detectEngine(extractDir)
        val (modTitle, modFolder, author, description, weekColor, showInStoryMode) = readModMeta(extractDir, engine)
        val iconFile = findIcon(extractDir)

        // Leer canciones (V-Slice: data/songs/*, Psych: data/* + weeks/*)
        onProgress("Detectando canciones…")
        val songs = readSongs(extractDir, engine, warnings)

        // Leer personaje propio si existe
        onProgress("Buscando personaje propio…")
        val characterDraft = readCharacter(extractDir, engine, warnings)

        // Reconstruir ModConfig
        val config = ModConfig(
            engine = engine,
            modTitle = modTitle,
            modId = modFolder,
            author = author,
            description = description,
            songs = songs,
            weekColor = weekColor,
            showInStoryMode = showInStoryMode,
            character = characterDraft,
            icon = iconFile?.let { MediaTools.preparePng(it, File(context.cacheDir, "import_icon.png"), 512)?.let { PickedImage(Uri.fromFile(it), it.name, it.absolutePath) } }
        )

        // Limpiar carpeta temporal
        extractDir.deleteRecursively()

        return ImportResult(config, warnings)
    }

    private fun detectEngine(dir: File): TargetEngine {
        val hasVSlice = File(dir, "_polymod_meta.json").exists() || File(dir, "data/levels").exists()
        val hasPsych = File(dir, "pack.json").exists() || File(dir, "weeks").exists()
        return when {
            hasVSlice && hasPsych -> TargetEngine.BOTH
            hasVSlice -> TargetEngine.VSLICE
            hasPsych -> TargetEngine.PSYCH
            else -> TargetEngine.BOTH // default
        }
    }

    private fun readModMeta(dir: File, engine: TargetEngine): Pair<String, Pair<String, Pair<String, Pair<String, Pair<String, Boolean>>>>> {
        var modTitle = "Mod Importado"
        var modFolder = "mod-importado"
        var author = "Desconocido"
        var description = "Importado con FNF Mod Maker"
        var weekColor = "#C24B99"
        var showInStoryMode = false

        // V-Slice: _polymod_meta.json
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

        // Psych: pack.json
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

        return modTitle to (modFolder to (author to (description to (weekColor to showInStoryMode))))
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
                songsDir.listFiles()?.filter { it.isDirectory }?.forEach { songDir ->
                    val song = readVSliceSong(songDir, warnings)
                    if (song != null) songs.add(song)
                }
            }
        }

        if (engine.includesPsych) {
            val dataDir = File(dir, "data")
            val weeksDir = File(dir, "weeks")
            if (dataDir.exists() && weeksDir.exists()) {
                weeksDir.listFiles()?.filter { it.extension == "json" }?.forEach { weekFile ->
                    val song = readPsychSong(weekFile, dataDir, warnings)
                    if (song != null) songs.add(song)
                }
            }
        }

        // Si no se detectaron canciones, crear una vacía
        if (songs.isEmpty()) {
            songs.add(SongConfig())
            warnings.add("No se detectaron canciones en el mod; se creó una vacía.")
        }

        // Deduplicar por songFolder
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
            val speed = meta.optJSONObject("playData")?.optJSONObject("scrollSpeed")?.keys()?.firstOrNull()?.toIntOrNull()?.toDouble()?.let { (it / 100.0).toString() } ?: "2.0"

            val playData = meta.optJSONObject("playData")
            val difficulties = mutableSetOf<String>()
            playData?.optJSONArray("difficulties")?.forEachIndexed { _, v -> difficulties.add(v.toString()) }
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
            val audioFile = File(songDataDir, "Inst.ogg") // Psych usa data/<song>/Inst.ogg? No, usa songs/<song>/Inst.ogg
            // Buscar audio en songs/
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
        // Buscar personaje en images/characters/ y data/characters/
        val charDirs = if (engine.includesVSlice) File(dir, "data/characters") else File(dir, "characters")
        if (!charDirs.exists()) return com.fnfmodmaker.model.CharacterDraft()

        val charFiles = charDirs.listFiles()?.filter { it.extension == "json" } ?: return com.fnfmodmaker.model.CharacterDraft()
        val charFile = charFiles.firstOrNull() ?: return com.fnfmodmaker.model.CharacterDraft()

        try {
            val json = JSONObject(charFile.readText())
            val name = json.optString("name", charFile.nameWithoutExtension)
            val id = json.optString("name", charFile.nameWithoutExtension).lowercase().replace(" ", "-")
            val scale = json.optDouble("scale", 1.0)
            val flipX = json.optBoolean("flipX", false)
            val healthIcon = json.optString("healthicon", json.optString("healthIcon", ""))

            // Leer animaciones
            val animations = json.optJSONArray("animations")
            var idleFrames = "2"
            var leftFrames = "1"
            var downFrames = "1"
            var upFrames = "1"
            var rightFrames = "1"
            animations?.forEach { anim ->
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

            // Buscar spritesheet e icono
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
}