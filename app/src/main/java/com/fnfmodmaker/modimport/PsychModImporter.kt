package com.fnfmodmaker.modimport

import android.net.Uri
import com.fnfmodmaker.model.*
import org.json.JSONObject
import java.io.File
import java.util.Locale

internal data class PreparedPsychImport(val stage: File, val config: ModConfig, val warnings: List<String>)

/** Converts only the subset that can be represented. Every input is also retained as a project asset. */
internal class PsychModImporter(
    private val imageSize: (File) -> Pair<Int, Int>?,
    private val duration: (File) -> Long,
    private val audioAsset: (File, String, Long) -> PickedAudio = { f, name, ms -> PickedAudio(Uri.EMPTY, name, f.absolutePath, ms) },
    private val imageAsset: (File, String) -> PickedImage = { f, name -> PickedImage(Uri.EMPTY, name, f.absolutePath) }
) {
    fun prepare(extracted: File, names: List<String>, stage: File, ensureActive: () -> Unit = {}): PreparedPsychImport {
        require(!stage.exists() && stage.mkdirs()) { "No se pudo preparar el proyecto temporal." }
        try {
            val packs = names.filter { it.equals("pack.json", true) || it.endsWith("/pack.json", true) }
            require(packs.size == 1) { "Se necesita un único pack.json Psych en la raíz del mod." }
            val prefix = packs.single().removeSuffix(packs.single().substringAfterLast('/'))
            require(names.all { it.startsWith(prefix) && it.length > prefix.length }) { "El ZIP contiene archivos fuera de la carpeta del mod; no se puede importar sin ambigüedad." }
            val relative = names.associateBy { it.removePrefix(prefix) }
            require(relative.keys.none { it.equals("_polymod_meta.json", true) || it.startsWith("data/songs/", true) }) { "Los mods V-Slice o BOTH aún no se importan como proyectos." }
            val packPath = packs.single()
            val packFile = File(extracted, packPath)
            require(packFile.length() in 1..512_000) { "pack.json vacío o demasiado grande." }
            // Pack metadata is shallow; apply the same nesting guard as charts before org.json parses it.
            val packText = packFile.readText(); var depth = 0; var quoted = false; var escaped = false
            packText.forEach { c -> if (quoted) { if (escaped) escaped = false else if (c == '\\') escaped = true else if (c == '"') quoted = false }
                else when (c) { '"' -> quoted = true; '{', '[' -> { depth++; require(depth <= 64) }; '}', ']' -> { depth--; require(depth >= 0) } } }
            require(depth == 0 && !quoted)
            val pack = JSONObject(packText)
            val title = pack.optString("name").take(100).ifBlank { prefix.trimEnd('/').substringAfterLast('/').ifBlank { "Mod Psych importado" } }
            val files = relative.keys.sorted()
            val index = mutableMapOf<String, File>()
            val assetDir = File(stage, "assets/imported"); check(assetDir.mkdirs())
            files.forEachIndexed { i, path ->
                ensureActive()
                val ext = path.substringAfterLast('.', "dat").filter { it.isLetterOrDigit() }.take(8).ifEmpty { "dat" }
                val out = File(assetDir, "original_${i.toString().padStart(5, '0')}.$ext")
                File(extracted, relative.getValue(path)).inputStream().buffered().use { input -> out.outputStream().buffered().use { output ->
                    val buffer = ByteArray(32768)
                    while (true) { ensureActive(); val n = input.read(buffer); if (n < 0) break; if (n > 0) output.write(buffer, 0, n) }
                } }
                index[path] = out
            }
            val converted = mutableSetOf("pack.json")
            val chartPaths = files.filter { Regex("^data/([^/]+)/([^/]+)\\.json$").matches(it) && !it.startsWith("data/songs/") }
            val folderNames = chartPaths.map { it.split('/')[1] }.distinct()
            val songs = mutableListOf<SongConfig>(); val charts = mutableMapOf<String, Map<String, ChartDraft>>()
            val warnings = mutableListOf<String>()
            for (folder in folderNames) {
                ensureActive()
                require(folder == psychSongPath(folder) && folder.matches(Regex("[a-z0-9_-]{1,80}"))) { "Carpeta de canción no compatible: $folder" }
                val perSong = linkedMapOf<String, ChartDraft>()
                val prefix = "data/$folder/$folder"
                val difficulties = chartPaths.mapNotNull { path ->
                    when {
                        path == "$prefix.json" -> "normal"
                        path.startsWith("$prefix-") && path.endsWith(".json") -> path.removePrefix("$prefix-").removeSuffix(".json").takeIf { it != "normal" && isValidDifficulty(it) }
                        else -> null
                    }
                }.distinct().sorted()
                require(difficulties.size <= 64) { "Demasiadas dificultades en $folder." }
                for (difficulty in difficulties) {
                    val path = "data/$folder/${folder}${if (difficulty == "normal") "" else "-$difficulty"}.json"
                    val file = index[path] ?: continue
                    require(file.length() in 1..4_000_000) { "Chart vacío o demasiado grande: $path" }
                    val chart = try { readPsychChart(file.readText(), folder, difficulty) } catch (e: Exception) { throw IllegalArgumentException("No se puede convertir $path: ${e.message}. El archivo original no se modificó.", e) }
                    perSong[difficulty] = chart; converted.add(path)
                }
                if (perSong.isEmpty()) continue
                val base = perSong["normal"] ?: perSong.values.first()
                require(perSong.values.all { it.bpm == base.bpm && it.player1 == base.player1 && it.player2 == base.player2 && it.gfVersion == base.gfVersion && it.stage == base.stage }) { "Las dificultades de $folder tienen metadatos incompatibles entre sí." }
                fun audio(path: String): PickedAudio? = index[path]?.let { f ->
                    require(f.length() > 0) { "Audio vacío: $path" }
                    converted.add(path); audioAsset(f, path.substringAfterLast('/'), duration(f))
                }
                val inst = audio("songs/$folder/Inst.ogg") ?: error("Falta songs/$folder/Inst.ogg.")
                val voice = audio("songs/$folder/Voices.ogg")
                songs.add(SongConfig(songName = folder, bpm = base.bpm.toString(), speed = base.scrollSpeed.toString(), player = base.player1,
                    opponent = base.player2, girlfriend = base.gfVersion, stagePsych = base.stage, difficulties = perSong.keys,
                    practiceNotes = false, offsetMs = base.offsetMs.toString(), instrumental = inst, vocalsPlayer = voice, importedPsychFolder = folder))
                charts[folder] = perSong
            }
            require(songs.isNotEmpty() && songs.size <= 20) { "No se encontraron de 1 a 20 canciones Psych compatibles con chart e instrumental." }
            warnings.add("Los charts compatibles se regeneran: las secciones y campos adicionales pueden cambiar y los tiempos se redondean al milisegundo; los originales se conservan dentro del proyecto.")
            warnings.add("Los eventos, tipos de notas especiales, secciones GF/alt y cambios de BPM todavía no se convierten; la importación se detiene para evitar pérdidas.")
            warnings.add("Archivos no convertidos, incluidos scripts, se exportarán intactos si no colisionan con archivos generados. Los scripts se ejecutan en el juego, no en la app.")
            val characters = mutableListOf<CharacterEntry>()
            val candidates = files.filter { Regex("^characters/[^/]+\\.json$").matches(it) }
            for (path in candidates) {
                ensureActive()
                val id = path.substringAfterLast('/').removeSuffix(".json")
                val side = if (songs.any { it.player == id }) CharacterSide.PLAYER else CharacterSide.OPPONENT
                try {
                    val f = index.getValue(path); require(f.length() <= 512_000)
                    val preview = parseCharacterJson(f.readText(), path.substringAfterLast('/'), side)
                    require(preview.format == "Psych" && preview.id == id)
                    val pngPath = "images/${preview.assetPath}.png"; val xmlPath = "images/${preview.assetPath}.xml"
                    val png = index[pngPath] ?: error("Falta $pngPath"); val xml = index[xmlPath] ?: error("Falta $xmlPath")
                    require(xml.length() <= 4_000_000)
                    val frames = parseSparrowAtlas(xml.readText()); val size = imageSize(png) ?: error("PNG inválido")
                    require(atlasFramesFit(frames, size.first, size.second))
                    fun image(f: File, name: String) = imageAsset(f, name)
                    val iconId = JSONObject(f.readText()).optString("healthicon", "")
                    val iconPath = "images/icons/icon-$iconId.png"
                    val icon = index[iconPath]?.let { image(it, iconPath.substringAfterLast('/')) }
                    val character = applyCharacterJson(CharacterDraft(side = side), preview).copy(importedPsychCharacterId = id, atlasSheet = image(png, pngPath.substringAfterLast('/')),
                        atlasXml = image(xml, xmlPath.substringAfterLast('/')), atlasSheetWidth = size.first, atlasSheetHeight = size.second, atlasFrames = frames, icon = icon)
                    require(character.problems.isEmpty()) { character.problems.joinToString() }
                    converted.add(path)
                    // The builder emits its own canonical atlas names, regardless of original image reference.
                    if (pngPath == "images/characters/$id.png") converted.add(pngPath)
                    if (xmlPath == "images/characters/$id.xml") converted.add(xmlPath)
                    if (icon != null && iconPath == "images/icons/icon-$id.png") converted.add(iconPath)
                    require(characters.size < 64) { "Máximo de 64 personajes editables." }
                    characters.add(CharacterEntry(if (characters.isEmpty()) "legacy" else "imported-${characters.size}", character))
                    warnings.addAll(preview.warnings)
                } catch (e: Exception) {
                    warnings.add("$path: personaje no convertido (${e.message}); sus archivos se conservan sin edición.")
                }
            }
            val resources = files.map { path -> ImportedModResource(path, imageAsset(index.getValue(path), path.substringAfterLast('/')), path in converted) }
            val config = ModConfig(engine = TargetEngine.PSYCH, modTitle = title, modId = slugify(title), description = pack.optString("description").take(1000),
                songs = songs, charts = charts, characterLibrary = characters.ifEmpty { listOf(CharacterEntry()) },
                characterAssignments = if (characters.size > 1 || characters.singleOrNull()?.draft?.let { draft ->
                    songs.any { song -> (if (draft.side == CharacterSide.PLAYER) song.player else song.opponent) != draft.charFolder }
                } == true) CharacterAssignments() else null, importedModResources = resources)
            return PreparedPsychImport(stage, config, warnings.distinct())
        } catch (e: Throwable) { stage.deleteRecursively(); throw e }
    }
}
