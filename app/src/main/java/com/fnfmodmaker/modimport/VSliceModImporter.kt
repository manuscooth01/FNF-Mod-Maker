package com.fnfmodmaker.modimport

import android.net.Uri
import com.fnfmodmaker.model.*
import org.json.JSONObject
import java.io.File

internal data class PreparedVSliceImport(val stage: File, val config: ModConfig, val warnings: List<String>)

/** V-Slice 2.2.x metadata / 2.0.x charts; copies every validated input into ProjectStore staging. */
internal class VSliceModImporter(
    private val imageSize: (File) -> Pair<Int, Int>?,
    private val duration: (File) -> Long,
    private val audioAsset: (File, String, Long) -> PickedAudio = { f, name, ms -> PickedAudio(Uri.EMPTY, name, f.absolutePath, ms) },
    private val imageAsset: (File, String) -> PickedImage = { f, name -> PickedImage(Uri.EMPTY, name, f.absolutePath) }
) {
    fun prepare(extracted: File, names: List<String>, stage: File, ensureActive: () -> Unit = {}): PreparedVSliceImport {
        require(!stage.exists() && stage.mkdirs()) { "No se pudo preparar el proyecto temporal." }
        try {
            val pattern = Regex("^(.*)data/songs/([a-z0-9_-]{1,80})/\\2-metadata\\.json$")
            val matches = names.mapNotNull { pattern.matchEntire(it) }
            require(matches.isNotEmpty()) { "No se encontró metadata V-Slice base en data/songs/." }
            val prefixes = matches.map { it.groupValues[1] }.distinct()
            require(prefixes.size == 1 && names.all { it.startsWith(prefixes.single()) && it.length > prefixes.single().length }) { "Carpeta raíz V-Slice ambigua o archivos fuera de ella." }
            val prefix = prefixes.single()
            val relative = names.associateBy { it.removePrefix(prefix) }
            val files = relative.keys.sorted()
            val psychChart = files.any { Regex("^data/(?!songs/)[^/]+/[^/]+\\.json$").matches(it) && !it.startsWith("data/characters/") && !it.startsWith("data/stages/") && !it.startsWith("data/levels/") }
            require(!(files.any { it.equals("pack.json", true) } && psychChart)) { "El ZIP contiene estructuras Psych y V-Slice; todavía no se resuelven mods BOTH." }
            val index = linkedMapOf<String, File>(); val assetDir = File(stage, "assets/imported"); check(assetDir.mkdirs())
            files.forEachIndexed { i, path ->
                ensureActive()
                val ext = path.substringAfterLast('.', "dat").filter(Char::isLetterOrDigit).take(8).ifEmpty { "dat" }
                val out = File(assetDir, "original_${i.toString().padStart(5, '0')}.$ext")
                File(extracted, relative.getValue(path)).inputStream().buffered().use { input -> out.outputStream().buffered().use { output ->
                    val buffer = ByteArray(32768)
                    while (true) { ensureActive(); val n = input.read(buffer); if (n < 0) break; if (n > 0) output.write(buffer, 0, n) }
                } }
                index[path] = out
            }
            fun json(path: String): JSONObject {
                val file = index[path] ?: error("Falta $path.")
                require(file.length() in 1..4_000_000) { "$path: JSON vacío o demasiado grande." }
                return readVSliceJson(file.readText(), path)
            }
            val polymod = if ("_polymod_meta.json" in index) json("_polymod_meta.json") else JSONObject()
            val title = polymod.optString("title", "").take(100).ifBlank { prefix.trimEnd('/').substringAfterLast('/').ifBlank { "Mod V-Slice importado" } }
            val converted = mutableSetOf<String>()
            if ("_polymod_meta.json" in index) converted.add("_polymod_meta.json")
            val songs = mutableListOf<SongConfig>(); val charts = linkedMapOf<String, Map<String, ChartDraft>>()
            val warnings = mutableListOf<String>()
            for (match in matches.sortedBy { it.groupValues[2] }) {
                ensureActive()
                val folder = match.groupValues[2]
                val metadataPath = "data/songs/$folder/$folder-metadata.json"
                val chartPath = "data/songs/$folder/$folder-chart.json"
                val metadata = json(metadataPath); val chart = json(chartPath)
                require(metadata.optString("version").matches(Regex("2\\.2\\.[0-9]+"))) { "$metadataPath: solo se admite metadata 2.2.x." }
                require(chart.optString("version").matches(Regex("2\\.0\\.[0-9]+"))) { "$chartPath: solo se admiten charts 2.0.x." }
                require(metadata.optString("timeFormat", "ms") == "ms") { "$metadataPath: formato de tiempo distinto de ms no editable." }
                val times = metadata.optJSONArray("timeChanges") ?: error("$metadataPath: falta timeChanges.")
                require(times.length() > 0) { "$metadataPath: falta BPM inicial." }
                val bpm = times.optJSONObject(0)?.optDouble("bpm", Double.NaN) ?: Double.NaN
                require(bpm.isFinite() && bpm in 1.0..400.0 && bpm % 1 == 0.0) { "$metadataPath: el editor requiere BPM inicial entero entre 1 y 400." }
                val play = metadata.optJSONObject("playData") ?: error("$metadataPath: falta playData.")
                val chars = play.optJSONObject("characters") ?: JSONObject()
                val player = chars.optString("player", ""); val opponent = chars.optString("opponent", "")
                val gf = chars.optString("girlfriend", ""); val stageId = play.optString("stage", "mainStage")
                val nameFromMetadata = metadata.optString("songName", folder).take(100)
                val songName = if (psychSongPath(nameFromMetadata) == folder) nameFromMetadata else folder
                val artist = metadata.optString("artist", "").take(100)
                val speedJson = chart.optJSONObject("scrollSpeed") ?: JSONObject()
                fun speedFor(diff: String): Float {
                    val v = speedJson.optDouble(diff, speedJson.optDouble("default", 1.0))
                    require(v.isFinite() && v > 0 && v <= 100) { "$chartPath: velocidad no compatible para $diff." }
                    return v.toFloat()
                }
                val sourceNotes = chart.optJSONObject("notes") ?: error("$chartPath: falta notes.")
                val metadataDifficulties = play.optJSONArray("difficulties")
                val declared = if (metadataDifficulties == null) sourceNotes.keys().asSequence().toSet() else (0 until metadataDifficulties.length()).map { metadataDifficulties.optString(it) }.toSet()
                val perSong = linkedMapOf<String, ChartDraft>()
                for (difficulty in ALL_DIFFICULTIES.filter { it in declared && sourceNotes.has(it) }) {
                    try { perSong[difficulty] = readVSliceDifficulty(chart, folder, difficulty, bpm.toInt(), speedFor(difficulty)) }
                    catch (e: Exception) { warnings.add("$chartPath · $difficulty: no editable (${e.message}); notas originales conservadas sin modificar.") }
                }
                val uneditable = (declared + sourceNotes.keys().asSequence().toSet()) - perSong.keys
                if (uneditable.isNotEmpty()) warnings.add("$chartPath: dificultades conservadas sin edición: ${uneditable.sorted().joinToString()}.")
                require(perSong.isNotEmpty()) { "$chartPath: ninguna dificultad compatible; aún no se puede crear un proyecto editable para esta canción." }
                if ((chart.optJSONArray("events")?.length() ?: 0) > 0) warnings.add("$chartPath: eventos conservados sin edición; se mantendrán al modificar notas.")
                if (times.length() > 1) warnings.add("$metadataPath: cambios de BPM conservados; la cuadrícula usa solo el BPM inicial.")
                if ((play.optJSONArray("songVariations")?.length() ?: 0) > 0 || files.any { it.startsWith("data/songs/$folder/$folder-chart-") || it.startsWith("data/songs/$folder/$folder-metadata-") })
                    warnings.add("$metadataPath: variaciones conservadas sin edición; solo se edita el chart base.")
                val initialSpeed = speedFor(if ("normal" in perSong) "normal" else perSong.keys.first()).toString()
                val offsetDouble = metadata.optJSONObject("offsets")?.optDouble("instrumental", 0.0) ?: 0.0
                val offset = if (offsetDouble.isFinite() && offsetDouble % 1 == 0.0 && offsetDouble >= Int.MIN_VALUE && offsetDouble <= Int.MAX_VALUE) offsetDouble.toInt() else 0
                if (offset.toDouble() != offsetDouble) warnings.add("$metadataPath: offset no entero conservado; la previsualización puede diferir.")
                val suffix = chars.optString("instrumental", "")
                require(suffix.isEmpty() || suffix.matches(Regex("[A-Za-z0-9_-]{1,70}"))) { "$metadataPath: instrumental alternativa no compatible." }
                val instName = "Inst${if (suffix.isEmpty()) "" else "-$suffix"}.ogg"
                fun audio(filename: String): PickedAudio? = index["songs/$folder/$filename"]?.let { f ->
                    require(f.length() > 0) { "$filename está vacío." }
                    converted.add("songs/$folder/$filename"); audioAsset(f, filename, duration(f))
                }
                val inst = audio(instName) ?: error("Falta songs/$folder/$instName.")
                fun voiceFile(key: String, fallback: String): String? {
                    val array = chars.optJSONArray(key)
                    val ids = if (array == null) listOf(fallback) else (0 until array.length()).map { array.optString(it) }
                    return ids.filter { it.matches(Regex("[A-Za-z0-9_-]{1,70}")) }.map { "Voices-$it.ogg" }.firstOrNull { "songs/$folder/$it" in index }
                }
                var playerFile = voiceFile("playerVocals", player)
                var opponentFile = voiceFile("opponentVocals", opponent)
                if (playerFile != null && playerFile == opponentFile) { opponentFile = null; warnings.add("$folder: ambas voces apuntan al mismo archivo; se muestra una sola pista.") }
                if (playerFile == null && opponentFile == null && "songs/$folder/Voices.ogg" in index) playerFile = "Voices.ogg"
                val playerVoice = playerFile?.let(::audio); val opponentVoice = opponentFile?.let(::audio)
                if (files.count { it.startsWith("songs/$folder/Voices") && it.endsWith(".ogg", true) } > listOfNotNull(playerFile, opponentFile).size)
                    warnings.add("songs/$folder/: voces adicionales conservadas sin edición.")
                val origin = VSliceSongOrigin(folder, metadataPath, chartPath, perSong.keys, songName, artist, bpm.toInt().toString(), initialSpeed,
                    offset.toString(), player, opponent, gf, stageId, instName, playerFile.orEmpty(), opponentFile.orEmpty())
                val song = SongConfig(songName = songName, artist = artist, bpm = bpm.toInt().toString(), speed = initialSpeed, offsetMs = offset.toString(),
                    player = player, opponent = opponent, girlfriend = gf, stageVSlice = stageId, difficulties = perSong.keys,
                    practiceNotes = false, instrumental = inst, vocalsPlayer = playerVoice, vocalsOpponent = opponentVoice, vSliceOrigin = origin)
                songs.add(song); charts[folder] = perSong; converted.add(chartPath)
            }
            require(songs.size in 1..20) { "Se admiten de 1 a 20 canciones V-Slice." }
            warnings.add("La metadata original, sus campos adicionales y la velocidad por dificultad se conservan sin edición. Solo se editan notas de las dificultades indicadas y las pistas seleccionadas. El editor redondea tiempos al milisegundo; las notas no modificadas conservan su precisión original.")
            warnings.add("La exportación se bloqueará si cambias nombre, BPM, velocidad, offset, personajes o escenario de una canción importada; esos campos aún no se pueden actualizar sin pérdida.")
            warnings.add("Los recursos y scripts no editables se conservan sin ejecución; los scripts pueden ejecutarse en el juego. Colisiones desconocidas bloquean el ZIP.")
            var character = CharacterDraft()
            val candidates = files.filter { Regex("^data/characters/[^/]+\\.json$").matches(it) }
            for (path in candidates) {
                ensureActive(); if (character.enabled) break
                val id = path.substringAfterLast('/').removeSuffix(".json")
                val side = when { songs.all { it.opponent == id } -> CharacterSide.OPPONENT; songs.all { it.player == id } -> CharacterSide.PLAYER; else -> continue }
                try {
                    val data = json(path); require(data.optString("version").matches(Regex("1\\.0\\.[0-9]+"))) { "Versión de personaje no compatible" }
                    require(data.optString("renderType", "sparrow").equals("sparrow", true)) { "Solo se admite renderType Sparrow" }
                    val source = index.getValue(path); require(source.length() <= 512_000)
                    val preview = parseCharacterJson(source.readText(), path.substringAfterLast('/'), side)
                    require(preview.format == "V-Slice" && preview.id == id)
                    val pngPath = "images/${preview.assetPath}.png"; val xmlPath = "images/${preview.assetPath}.xml"
                    val png = index[pngPath] ?: error("Falta $pngPath"); val xml = index[xmlPath] ?: error("Falta $xmlPath")
                    require(xml.length() <= 4_000_000)
                    val frames = parseSparrowAtlas(xml.readText()); val size = imageSize(png) ?: error("PNG inválido")
                    require(atlasFramesFit(frames, size.first, size.second))
                    val iconId = data.optJSONObject("healthIcon")?.optString("id", "") ?: ""
                    val iconPath = "images/icons/icon-$iconId.png"
                    character = applyCharacterJson(CharacterDraft(side = side), preview).copy(
                        atlasSheet = imageAsset(png, pngPath.substringAfterLast('/')), atlasXml = imageAsset(xml, xmlPath.substringAfterLast('/')),
                        atlasSheetWidth = size.first, atlasSheetHeight = size.second, atlasFrames = frames,
                        icon = index[iconPath]?.let { imageAsset(it, iconPath.substringAfterLast('/')) })
                    require(character.problems.isEmpty()) { character.problems.joinToString() }
                    converted.add(path)
                    if (pngPath == "images/characters/$id.png") converted.add(pngPath)
                    if (xmlPath == "images/characters/$id.xml") converted.add(xmlPath)
                    if (character.icon != null && iconPath == "images/icons/icon-$id.png") converted.add(iconPath)
                    warnings.add("$path: personaje Sparrow editable; se conservan campos adicionales e índices originales, pero comprueba sus animaciones en el juego.")
                } catch (e: Exception) { character = CharacterDraft(); warnings.add("$path: personaje conservado sin edición (${e.message}).") }
            }
            if (candidates.size > if (character.enabled) 1 else 0) warnings.add("Solo se edita un personaje compatible; los demás se conservan sin edición.")
            val resources = files.map { path -> ImportedModResource(path, imageAsset(index.getValue(path), path.substringAfterLast('/')), path in converted) }
            val config = ModConfig(engine = TargetEngine.VSLICE, modTitle = title, modId = slugify(title), description = polymod.optString("description", "").take(1000),
                songs = songs, charts = charts, character = character, importedModResources = resources)
            return PreparedVSliceImport(stage, config, warnings.distinct())
        } catch (e: Throwable) { stage.deleteRecursively(); throw e }
    }
}
