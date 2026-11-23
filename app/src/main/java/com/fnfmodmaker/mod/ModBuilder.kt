package com.fnfmodmaker.mod

import android.content.Context
import com.fnfmodmaker.media.MediaTools
import com.fnfmodmaker.model.ModConfig
import com.fnfmodmaker.model.SongConfig
import com.fnfmodmaker.model.StageDraft
import com.fnfmodmaker.model.hasUnsupportedFeatures
import com.fnfmodmaker.model.StageSprite
import org.json.JSONArray
import org.json.JSONObject
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
        // Fail before creating or clearing output directories, even if called outside the wizard.
        rawConfig.songs.forEach { song -> song.orderedDifficulties.forEach { difficulty ->
            rawConfig.getChart(song.songFolder, difficulty)?.requireExportable()
        } }
        val warnings = mutableListOf<String>()
        // Si hay personaje propio, el chart de cada canción debe apuntar a él.
        val characterConfig = rawConfig.withCharacter()
        val config = if (characterConfig.stageDraft.enabled) characterConfig.copy(songs = characterConfig.songs.map {
            it.copy(stagePsych = characterConfig.stageDraft.stageFolder, stageVSlice = characterConfig.stageDraft.stageFolder)
        }) else characterConfig
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

            // Una entrada por canción, todas comparten la imagen de título del mod.
            for ((index, entry) in songEntries.withIndex()) {
                val levelName = if (songEntries.size == 1) modFolder else "$modFolder-${index + 1}"
                writeText(root, "data/levels/$levelName.json", JsonBuilders.vsliceLevel(
                    entry.song, modFolder, config.modTitle, config.showInStoryMode
                ))
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
            for ((name, code) in config.psychScripts) {
                require(com.fnfmodmaker.model.isValidPsychScript(name, code)) { "Script Psych inválido: $name" }
                writeText(root, "scripts/$name.lua", code)
                warnings.add("El script scripts/$name.lua se ejecuta en Psych Engine; revisa su código antes de instalar el mod.")
            }

            for (entry in songEntries) {
                val song = entry.song
                val songFolder = song.songFolder
                val difficulties = song.orderedDifficulties
                for (difficulty in difficulties) {
                    val fileName = if (difficulty == "normal") "$songFolder.json" else "$songFolder-$difficulty.json"
                    val chartJson = JsonBuilders.psychChart(config, song, entry.durationMs, difficulty)
                    writeText(root, "data/$songFolder/$fileName", chartJson)
                }
                // Psych abre siempre la dificultad "Normal", que es el archivo SIN sufijo.
                if (difficulties.none { it == "normal" }) {
                    val chartJson = JsonBuilders.psychChart(config, song, entry.durationMs, difficulties.first())
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
    fun build(root: File, config: ModConfig, onProgress: (String) -> Unit, warnings: MutableList<String>) {
        val stage = config.stageDraft
        if (!stage.enabled) return
        require(!stage.hasUnsupportedFeatures()) { "Los objetos avanzados del escenario no son compatibles; desactiva el escenario para no perderlos." }
        require(stage.stageId.isNotBlank() && stage.backgroundImage != null && stage.cameraPosition.zoom.isFinite() && stage.cameraPosition.zoom in 0.4f..2f) { "Completa el escenario, su fondo y el zoom." }
        onProgress("Preparando escenario sencillo…")
        val folder = stage.stageFolder
        val image = File(root, "images/stages/$folder/background.png")
        image.parentFile?.mkdirs()
        check(MediaTools.preparePng(File(stage.backgroundImage.path), image, 1920)) { "No se pudo procesar la imagen del escenario." }
        if (config.engine.includesVSlice) {
            val file = File(root, "data/stages/$folder.json"); file.parentFile?.mkdirs()
            file.writeText(StageTemplate.vslice(stage), Charsets.UTF_8)
        }
        if (config.engine.includesPsych) {
            val file = File(root, "stages/$folder.json"); file.parentFile?.mkdirs()
            file.writeText(StageTemplate.psych(stage), Charsets.UTF_8)
            File(root, "stages/$folder.lua").writeText(StageTemplate.psychLua(stage), Charsets.UTF_8)
            warnings.add("El escenario usa un script Lua generado para Psych; comprueba el resultado en tu versión del motor.")
        }
        warnings.add("El fondo y las posiciones son una base orientativa; ajusta y prueba el escenario en el juego.")
    }
}
