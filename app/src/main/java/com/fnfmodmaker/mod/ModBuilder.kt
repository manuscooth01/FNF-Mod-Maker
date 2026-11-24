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

/** Adapters keep Android media operations replaceable in deterministic integration tests. */
internal data class ModBuildServices(
    val audio: AudioEncoder,
    val character: (File, ModConfig, (String) -> Unit, MutableList<String>) -> Unit,
    val stage: (File, ModConfig, (String) -> Unit, MutableList<String>) -> Unit,
    val icon: (File, File) -> Boolean,
    val title: (String, String, File) -> Boolean
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
    ): BuildResult = buildInternal(rawConfig, workRoot, File(context.applicationInfo.dataDir), onProgress,
        ModBuildServices(
            object : AudioEncoder {
                override fun convert(input: File, output: File) = MediaTools.convertToOgg(input, output)
                override fun mix(first: File, second: File, output: File) = MediaTools.mixToOgg(first, second, output)
            }, CharacterBuilder::build, StageBuilder::build,
            { input, output -> MediaTools.preparePng(input, output, 512) }, MediaTools::makeTitleCard
        ))

    internal fun buildInternal(rawConfig: ModConfig, workRoot: File, appRoot: File, onProgress: (String) -> Unit, services: ModBuildServices): BuildResult {
        // Fail before creating or clearing output directories, even if called outside the wizard.
        require(rawConfig.songs.isNotEmpty()) { "Añade al menos una canción." }
        require(rawConfig.songs.all { it.songFolder.matches(Regex("[a-z0-9_-]{1,80}")) }) { "Nombre de carpeta de canción inválido." }
        rawConfig.songs.forEach { song -> song.orderedDifficulties.forEach { difficulty ->
            rawConfig.getChart(song.songFolder, difficulty)?.requireExportable()
        } }
        val warnings = mutableListOf<String>()
        val importedVSlice = rawConfig.songs.any { it.vSliceOrigin != null }
        require(rawConfig.importedModResources.isEmpty() || rawConfig.engine == if (importedVSlice) com.fnfmodmaker.model.TargetEngine.VSLICE else com.fnfmodmaker.model.TargetEngine.PSYCH) {
            "Los proyectos importados solo se exportan para su motor original; BOTH aún no está soportado."
        }
        require(!importedVSlice || rawConfig.engine == com.fnfmodmaker.model.TargetEngine.VSLICE) { "El proyecto importado debe exportarse para V-Slice." }
        val libraryProblems = com.fnfmodmaker.model.characterLibraryProblems(rawConfig)
        require(libraryProblems.isEmpty()) { libraryProblems.joinToString(" ") }
        val config = rawConfig.effectiveExportConfig()
        val importedCharts = config.songs.filter { it.vSliceOrigin != null }.associate { song ->
            song.songFolder to buildImportedVSliceChart(config, song, appRoot)
        }
        config.songs.forEach { if (com.fnfmodmaker.model.exportDifficulties(config, it).isEmpty()) warnings.add("${it.songName}: sin dificultades; no será jugable hasta añadir un chart.") }
        if (config.importedModResources.isNotEmpty()) warnings.add("Edición libre: los archivos editados se regeneran y pueden descartar datos no representados; el proyecto conserva los originales.")
        if (rawConfig.characterAssignments == null && config.character.enabled && config.songs.any { song ->
                song.vSliceOrigin != null && (if (config.character.side == com.fnfmodmaker.model.CharacterSide.PLAYER) song.player else song.opponent) != config.character.charFolder
            }) warnings.add("Las canciones V-Slice importadas conservan sus personajes originales; el personaje generado no se asigna automáticamente a su metadata.")
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
            if (!services.icon(File(icon.path), iconTarget)) {
                warnings.add("El icono no se pudo procesar, el mod se creó sin icono.")
            }
        }

        val patchedMetadataPaths = mutableSetOf<String>()
        // Each independently assigned identity is built exactly once, even if used by several roles.
        config.charactersToExport().forEach { entry ->
            val characterConfig = config.copy(selectedCharacterId = entry.id)
            services.character(root, characterConfig, onProgress, warnings)
            val draft = entry.draft
            if (importedVSlice && rawConfig.characterAssignments != null) {
                val id = draft.charFolder
                val required = mutableListOf("data/characters/$id.json", "images/characters/$id.png", "images/characters/$id.xml")
                if (draft.icon != null) required.add("images/icons/icon-$id.png")
                required.forEach { path -> require(File(root, path).isFile && File(root, path).length() > 0) { "Falta el archivo generado del personaje asignado: $path" } }
            }
            if (importedVSlice && draft.importedSourceFormat == "V-Slice") {
                val path = "data/characters/${draft.charFolder}.json"
                val generated = File(root, path)
                require(generated.isFile) { "No se generó el personaje importado: $path" }
                val original = originalVSliceText(config, path, appRoot, true)
                generated.writeText(patchVSliceCharacter(original, generated.readText(), draft.icon != null))
            }
            if (config.engine == com.fnfmodmaker.model.TargetEngine.PSYCH && draft.importedSourceFormat == "Psych") {
                val path = "characters/${draft.charFolder}.json"
                if (config.importedModResources.any { it.converted && it.originalPath == path }) {
                    val generated = File(root, path)
                    require(generated.isFile) { "No se generó el personaje importado: $path" }
                    val original = originalVSliceText(config, path, appRoot, true)
                    generated.writeText(patchPsychCharacter(original, generated.readText(), draft.icon != null))
                }
            }
        }

        // ---------------- Stage propio (uno solo para todo el mod) ----------------
        services.stage(root, config, onProgress, warnings)

        // Recopilamos metadatos por canción para los niveles/semanas.
        val songEntries = mutableListOf<SongEntry>()

        for ((index, song) in songs.withIndex()) {
            val songFolder = song.songFolder
            val songLabel = if (songs.size > 1) "canción ${index + 1} ($songFolder)" else "canción"
            onProgress("Procesando $songLabel…")

            val songDir = File(root, "songs/$songFolder")
            songDir.mkdirs()

            val instrumental = song.instrumental
                ?: throw IllegalArgumentException("Falta la instrumental de '$songFolder'.")
            fun source(audio: com.fnfmodmaker.model.PickedAudio) = AudioSource(File(audio.path), audio.isOgg)
            val origin = song.vSliceOrigin
            exportSongAudio(songDir, songFolder, config.engine, song.player, song.opponent,
                source(instrumental), song.vocalsPlayer?.let(::source), song.vocalsOpponent?.let(::source),
                services.audio, onProgress, origin?.instrumentalFile ?: "Inst.ogg",
                origin?.playerVoiceFile?.takeIf { it.isNotEmpty() }, origin?.opponentVoiceFile?.takeIf { it.isNotEmpty() })
            val durationMs = instrumental.durationMs

            songEntries.add(SongEntry(song, durationMs))
        }

        // ---------------- V-Slice ----------------
        if (config.engine.includesVSlice) {
            onProgress("Escribiendo archivos de V-Slice…")
            val originalPolymod = config.importedModResources.singleOrNull { it.originalPath == "_polymod_meta.json" }
            if (importedVSlice && originalPolymod != null) {
                val meta = com.fnfmodmaker.modimport.readVSliceJson(originalVSliceText(config, "_polymod_meta.json", appRoot, true), "_polymod_meta.json")
                meta.put("title", config.modTitle.trim()); meta.put("description", config.description.trim())
                writeText(root, "_polymod_meta.json", meta.toString(2))
            } else writeText(root, "_polymod_meta.json", JsonBuilders.polymodMeta(config))

            for (entry in songEntries) {
                val song = entry.song
                val songFolder = song.songFolder
                if (song.vSliceOrigin == null) writeText(
                    root, "data/songs/$songFolder/$songFolder-metadata.json", JsonBuilders.vsliceMetadata(song, entry.durationMs)
                ) else {
                    val raw = rawConfig.songs.single { it.songFolder == songFolder }
                    val origin = song.vSliceOrigin
                    require(origin.folder.matches(Regex("[a-z0-9_-]{1,80}")) && origin.metadataPath == "data/songs/${origin.folder}/${origin.folder}-metadata.json") { "Ruta de metadata V-Slice incompatible; no se exportó." }
                    val original = originalVSliceText(config, origin.metadataPath, appRoot, false)
                    val patched = freeVSliceMetadata(original, raw, song, com.fnfmodmaker.model.exportDifficulties(config, song))
                    val target = "data/songs/$songFolder/$songFolder-metadata.json"
                    writeText(root, target, patched)
                    if (target == origin.metadataPath) patchedMetadataPaths.add(target)
                }
                writeText(root, "data/songs/$songFolder/$songFolder-chart.json",
                    importedCharts[songFolder] ?: JsonBuilders.vsliceChart(config, song, entry.durationMs))
            }

            if (config.importedModResources.none { it.originalPath.startsWith("data/levels/", true) && it.originalPath.endsWith(".json", true) }) {
                // Una entrada por canción, todas comparten la imagen de título del mod.
                for ((index, entry) in songEntries.withIndex()) {
                    val levelName = if (songEntries.size == 1) modFolder else "$modFolder-${index + 1}"
                    writeText(root, "data/levels/$levelName.json", JsonBuilders.vsliceLevel(
                        entry.song, modFolder, config.modTitle, config.showInStoryMode
                    ))
                }

                onProgress("Generando el título de la semana…")
                val titleFile = File(root, "images/storymenu/titles/$modFolder.png")
                if (!services.title(config.modTitle, config.weekColor, titleFile)) {
                    warnings.add("No se pudo generar la imagen del título de la semana.")
                }
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
                val difficulties = com.fnfmodmaker.model.exportDifficulties(config, song)
                for (difficulty in difficulties) {
                    val fileName = if (difficulty == "normal") "$songFolder.json" else "$songFolder-$difficulty.json"
                    val chartJson = JsonBuilders.psychChart(config, song, entry.durationMs, difficulty)
                    writeText(root, "data/$songFolder/$fileName", chartJson)
                }
                // Psych abre siempre la dificultad "Normal", que es el archivo SIN sufijo.
                if (config.importedModResources.isEmpty() && difficulties.isNotEmpty() && difficulties.none { it == "normal" }) {
                    val chartJson = JsonBuilders.psychChart(config, song, entry.durationMs, difficulties.first())
                    writeText(root, "data/$songFolder/$songFolder.json", chartJson)
                    warnings.add(
                        "Psych arranca en la dificultad Normal de $songFolder: se copió " +
                            "${difficulties.first()} también como $songFolder.json."
                    )
                }
            }

            // Imported weeks may contain gameplay metadata not represented by this app.
            // Preserve them verbatim instead of creating colliding replacement weeks.
            if (config.importedModResources.none { it.originalPath.startsWith("weeks/", ignoreCase = true) && it.originalPath.endsWith(".json", ignoreCase = true) }) {
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
        }

        writeText(root, "LEEME.txt", readmeText(config, songEntries))
        if (config.importedModResources.isNotEmpty()) {
            onProgress("Conservando recursos originales…")
            val omitted = mutableSetOf<String>()
            config.songs.forEach { song ->
                song.vSliceOrigin?.let { origin ->
                    if (origin.folder != song.songFolder) {
                        omitted.add(origin.metadataPath); omitted.add(origin.chartPath)
                        config.importedModResources.filter { it.converted && it.originalPath.startsWith("songs/${origin.folder}/") }.forEach { omitted.add(it.originalPath) }
                    }
                }
                val oldFolder = song.importedPsychFolder.ifEmpty { song.songFolder }
                if (config.engine == com.fnfmodmaker.model.TargetEngine.PSYCH) {
                    com.fnfmodmaker.model.ALL_DIFFICULTIES.forEach { difficulty ->
                        val path = "data/$oldFolder/$oldFolder${if (difficulty == "normal") "" else "-$difficulty"}.json"
                        if (oldFolder != song.songFolder || difficulty !in com.fnfmodmaker.model.exportDifficulties(config, song)) omitted.add(path)
                    }
                    if (oldFolder != song.songFolder) config.importedModResources.filter { it.converted && it.originalPath.startsWith("songs/$oldFolder/") }.forEach { omitted.add(it.originalPath) }
                }
            }
            mergeImportedResources(root, config.importedModResources.filterNot { it.originalPath in omitted }.map { resource ->
                if (resource.originalPath in patchedMetadataPaths) resource.copy(converted = true) else resource
            }, appRoot, replaceExisting = true)
            warnings.add("Los archivos convertidos se regeneraron; sus originales se conservan en el proyecto. Revisa el mod en el motor correspondiente.")
        }

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
        output.appendLine("Comprueba el chart y el audio en la versión del motor que uses.")
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
            if (config.showInStoryMode) "Configurado para Freeplay y modo historia; verifica en el motor."
            else "Configurado para Freeplay; verifica en el motor."
        )
        if (config.charactersToExport().isNotEmpty()) {
            output.appendLine("Personajes propios: ${config.charactersToExport().joinToString { it.draft.charFolder }}.")
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
        val layoutProblems = com.fnfmodmaker.model.stageLayoutProblems(stage, config.engine)
        require(layoutProblems.isEmpty()) { layoutProblems.joinToString(" ") }
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
