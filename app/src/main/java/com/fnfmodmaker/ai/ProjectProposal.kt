package com.fnfmodmaker.ai

import com.fnfmodmaker.model.*
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser

/** A proposal contains only in-memory data. The caller must compare base and confirm before applying. */
data class ProposalItem(val title: String, val details: String)
data class ProjectProposal(val base: ModConfig, val result: ModConfig, val summary: String, val items: List<ProposalItem>)

object ProjectProposalPlanner {
    private fun fail(message: String): Nothing = throw AiException("Propuesta inválida: $message")
    private fun keys(obj: JsonObject, allowed: Set<String>) {
        val extra = obj.keySet() - allowed
        if (extra.isNotEmpty()) fail("campos no admitidos: ${extra.joinToString()}")
    }
    private fun string(obj: JsonObject, key: String, max: Int = 200): String? {
        val el = obj.get(key) ?: return null
        if (!el.isJsonPrimitive || !el.asJsonPrimitive.isString) fail("$key debe ser texto")
        val value = el.asString
        if (value.length > max || value.contains('\u0000')) fail("$key es demasiado largo o contiene caracteres inválidos")
        return value
    }
    private fun int(obj: JsonObject, key: String, min: Int, max: Int): Int? {
        val el = obj.get(key) ?: return null
        if (!el.isJsonPrimitive || !el.asJsonPrimitive.isNumber) fail("$key debe ser un número entero")
        val raw = el.asString
        val value = raw.toIntOrNull() ?: fail("$key debe ser un número entero")
        if (value !in min..max) fail("$key fuera de rango ($min–$max)")
        return value
    }
    private fun number(obj: JsonObject, key: String, min: Float, max: Float): Float? {
        val el = obj.get(key) ?: return null
        if (!el.isJsonPrimitive || !el.asJsonPrimitive.isNumber) fail("$key debe ser un número")
        val n = el.asFloat
        if (!n.isFinite() || n !in min..max) fail("$key fuera de rango")
        return n
    }
    private fun bool(obj: JsonObject, key: String): Boolean? {
        val el = obj.get(key) ?: return null
        if (!el.isJsonPrimitive || !el.asJsonPrimitive.isBoolean) fail("$key debe ser verdadero o falso")
        return el.asBoolean
    }
    private fun requiredString(obj: JsonObject, name: String, max: Int = 200) = string(obj, name, max) ?: fail("falta $name")
    private fun requiredInt(obj: JsonObject, name: String, min: Int, max: Int) = int(obj, name, min, max) ?: fail("falta $name")
    private fun song(config: ModConfig, obj: JsonObject): Pair<Int, SongConfig> {
        val index = requiredInt(obj, "songIndex", 0, (config.songs.size - 1).coerceAtLeast(0))
        return index to (config.songs.getOrNull(index) ?: fail("la canción no existe"))
    }
    private fun nonEmpty(obj: JsonObject, metadata: Set<String>) {
        if ((obj.keySet() - metadata).isEmpty()) fail("no hay cambios")
    }

    private fun entry(config: ModConfig, change: JsonObject, characterOnly: Boolean): CharacterEntry {
        val id = string(change, "characterId", 64) ?: config.selectedCharacterId
        if (characterOnly && id != config.selectedCharacterId) fail("esta herramienta solo edita el personaje seleccionado")
        return config.characterLibrary.singleOrNull { it.id == id } ?: fail("el personaje $id no existe")
    }
    private fun replaceDraft(config: ModConfig, id: String, draft: CharacterDraft): ModConfig =
        config.copy(characterLibrary = config.characterLibrary.map { if (it.id == id) it.copy(draft = draft) else it })

    fun applyIfUnchanged(current: ModConfig, proposal: ProjectProposal): ModConfig {
        if (current != proposal.base) throw AiException("El proyecto cambió desde la propuesta. Solicítala de nuevo antes de aplicar.")
        if (current.characterAssignments != proposal.result.characterAssignments) {
            val problems = characterLibraryProblems(proposal.result)
            if (problems.isNotEmpty()) throw AiException("La asignación ya no es válida: ${problems.joinToString(" ")}")
        }
        return proposal.result
    }

    fun plan(base: ModConfig, arguments: String, characterOnly: Boolean = false): ProjectProposal {
        if (arguments.length > 250_000) fail("demasiado grande")
        val root = try { JsonParser.parseString(arguments).asJsonObject } catch (_: Exception) { fail("JSON incorrecto") }
        keys(root, setOf("summary", "changes"))
        val summary = requiredString(root, "summary", 1000)
        val changes = root.get("changes")?.takeIf { it.isJsonArray }?.asJsonArray ?: fail("falta la lista de cambios")
        if (changes.size() !in 1..12) fail("se permiten entre 1 y 12 cambios por lote")
        var current = base
        val items = mutableListOf<ProposalItem>()
        changes.forEach { element ->
            if (!element.isJsonObject) fail("cada cambio debe ser un objeto")
            val change = element.asJsonObject
            val type = requiredString(change, "type", 30)
            if (characterOnly && type !in setOf("character", "animation")) fail("esta herramienta solo edita el personaje; abre el asistente desde el mod")
            when (type) {
                "character" -> {
                    val metadata = setOf("type", "characterId")
                    keys(change, metadata + setOf("positionX", "positionY", "cameraX", "cameraY", "scale", "flipX", "side"))
                    nonEmpty(change, metadata)
                    val selected = entry(current, change, characterOnly)
                    val before = selected.draft
                    val side = string(change, "side")?.let { when (it) { "player" -> CharacterSide.PLAYER; "opponent" -> CharacterSide.OPPONENT; else -> fail("side debe ser player u opponent") } } ?: before.side
                    val updated = before.copy(
                        positionX = int(change, "positionX", -999999, 999999)?.toString() ?: before.positionX,
                        positionY = int(change, "positionY", -999999, 999999)?.toString() ?: before.positionY,
                        cameraX = int(change, "cameraX", -999999, 999999)?.toString() ?: before.cameraX,
                        cameraY = int(change, "cameraY", -999999, 999999)?.toString() ?: before.cameraY,
                        scale = number(change, "scale", 0.05f, 20f)?.toString() ?: before.scale,
                        flipX = bool(change, "flipX") ?: before.flipX, side = side)
                    current = replaceDraft(current, selected.id, updated)
                    items.add(ProposalItem("Personaje ${before.charFolder} · posición y apariencia", "${if (!before.enabled) "Aviso: el personaje propio está desactivado; actívalo para usarlo en el mod.\n" else ""}Antes: posición (${before.positionX}, ${before.positionY}), cámara (${before.cameraX}, ${before.cameraY}), escala ${before.scale}, voltear ${before.flipX}, lado ${before.side.label}\nDespués: posición (${updated.positionX}, ${updated.positionY}), cámara (${updated.cameraX}, ${updated.cameraY}), escala ${updated.scale}, voltear ${updated.flipX}, lado ${updated.side.label}"))
                }
                "animation" -> {
                    val metadata = setOf("type", "name", "characterId")
                    keys(change, metadata + setOf("fps", "loop", "offsetX", "offsetY", "prefix"))
                    nonEmpty(change, metadata)
                    val name = requiredString(change, "name", 32)
                    if (!name.matches(Regex("[A-Za-z0-9_-]{1,32}"))) fail("nombre de animación inválido")
                    val selected = entry(current, change, characterOnly)
                    var draft = selected.draft
                    val new = name !in draft.animationNames
                    if (new) {
                        if (draft.customAnimations.size >= 30) fail("límite de animaciones")
                        draft = draft.copy(customAnimations = draft.customAnimations + name, customFrameCounts = draft.customFrameCounts + (name to "0"))
                    }
                    val old = draft.settingsFor(name)
                    val setting = old.copy(fps = int(change, "fps", 1, 60)?.toString() ?: old.fps,
                        loop = bool(change, "loop") ?: old.loop,
                        offsetX = int(change, "offsetX", -999999, 999999)?.toString() ?: old.offsetX,
                        offsetY = int(change, "offsetY", -999999, 999999)?.toString() ?: old.offsetY)
                    val prefix = string(change, "prefix")
                    if (prefix != null && !draft.useAtlas) fail("el prefijo solo se puede asignar a un atlas Sparrow")
                    if (prefix != null && prefix.isNotBlank() && atlasFramesForPrefix(draft.atlasFrames, prefix).isEmpty()) fail("el prefijo de $name no coincide con el atlas")
                    val oldPrefix = draft.prefixFor(name)
                    draft = draft.copy(animationSettings = draft.animationSettings + (name to setting),
                        atlasPrefixes = if (prefix != null) draft.atlasPrefixes + (name to prefix) else draft.atlasPrefixes)
                    if (draft.animations.none { it.first == name }) fail("la animación $name no tiene frames; añádelos antes de editarla con IA")
                    current = replaceDraft(current, selected.id, draft)
                    items.add(ProposalItem("${draft.charFolder} · Animación $name${if (new) " · nueva, revisa sus imágenes" else ""}", "Antes: ${old.fps} FPS, bucle ${old.loop}, offset (${old.offsetX}, ${old.offsetY})${if (draft.useAtlas) ", prefijo '$oldPrefix'" else ""}\nDespués: ${setting.fps} FPS, bucle ${setting.loop}, offset (${setting.offsetX}, ${setting.offsetY})${if (draft.useAtlas) ", prefijo '${draft.prefixFor(name)}'" else ""}"))
                }
                "assignment" -> {
                    keys(change, setOf("type", "role", "characterId"))
                    val role = requiredString(change, "role", 20)
                    val id = requiredString(change, "characterId", 64)
                    if (role !in setOf("player", "opponent", "girlfriend")) fail("rol inválido")
                    val selected = current.characterLibrary.singleOrNull { it.id == id } ?: fail("el personaje asignado no existe")
                    if (!selected.draft.enabled) fail("el personaje asignado está desactivado")
                    val a = current.resolvedAssignments()
                    val projectedBefore = current.effectiveExportConfig().songs.map { song ->
                        val value = when (role) { "player" -> song.player; "opponent" -> song.opponent; else -> song.girlfriend }
                        "${song.songName.ifBlank { "Canción" }}: $value → ${selected.draft.charFolder}"
                    }.joinToString("\n")
                    val next = when (role) { "player" -> a.copy(player = id); "opponent" -> a.copy(opponent = id); else -> a.copy(girlfriend = id) }
                    current = current.copy(characterAssignments = next)
                    items.add(ProposalItem("Asignación $role · todas las canciones", "$projectedBefore\nPersonaje: ${selected.draft.displayName} (${selected.draft.charFolder}). Afecta a todas las canciones; las demás referencias se conservan."))
                }
                "stage_actor" -> {
                    val meta = setOf("type", "engine", "role")
                    keys(change, meta + setOf("positionX", "positionY", "cameraX", "cameraY", "scale", "zIndex", "scrollX", "scrollY")); nonEmpty(change, meta)
                    val engine = requiredString(change, "engine", 20)
                    val vslice = when (engine) { "vslice" -> true; "psych" -> false; else -> fail("motor de escenario inválido") }
                    if ((vslice && !current.engine.includesVSlice) || (!vslice && !current.engine.includesPsych)) fail("el motor del escenario no está seleccionado")
                    if (!current.stageDraft.enabled) fail("activa primero el escenario sencillo")
                    if (!vslice && listOf("scale", "zIndex", "scrollX", "scrollY").any { change.has(it) }) fail("Psych no admite escala, Z o scroll de actor en el escenario")
                    val role = when (requiredString(change, "role", 20)) { "bf" -> StageRole.BF; "dad" -> StageRole.DAD; "gf" -> StageRole.GF; else -> fail("rol de escenario inválido") }
                    val stage = current.stageDraft
                    val layout = if (vslice) stage.vSliceLayout else stage.psychLayout
                    val old = layout.actor(role)
                    val next = old.copy(x = number(change, "positionX", -100000f, 100000f) ?: old.x,
                        y = number(change, "positionY", -100000f, 100000f) ?: old.y,
                        cameraX = number(change, "cameraX", -100000f, 100000f) ?: old.cameraX,
                        cameraY = number(change, "cameraY", -100000f, 100000f) ?: old.cameraY,
                        scale = number(change, "scale", .01f, 100f) ?: old.scale,
                        zIndex = int(change, "zIndex", -1000000, 1000000) ?: old.zIndex,
                        scrollX = number(change, "scrollX", -100f, 100f) ?: old.scrollX,
                        scrollY = number(change, "scrollY", -100f, 100f) ?: old.scrollY)
                    val updated = if (vslice) stage.copy(vSliceLayout = layout.withActor(role, next)) else stage.copy(psychLayout = layout.withActor(role, next))
                    val problems = stageLayoutProblems(updated, current.engine)
                    if (problems.isNotEmpty()) fail(problems.joinToString(" "))
                    current = current.copy(stageDraft = updated)
                    items.add(ProposalItem("Escenario $engine · ${role.label}", "Antes: $old\nDespués: $next"))
                }
                "song" -> {
                    val metadata = setOf("type", "songIndex")
                    keys(change, metadata + setOf("artist", "bpm", "speed", "offsetMs", "practiceNotes", "stagePsych", "stageVSlice"))
                    nonEmpty(change, metadata)
                    val (index, old) = song(current, change)
                    val updated = old.copy(artist = string(change, "artist") ?: old.artist,
                        bpm = int(change, "bpm", 1, 400)?.toString() ?: old.bpm,
                        speed = number(change, "speed", 0.1f, 10f)?.toString() ?: old.speed,
                        offsetMs = int(change, "offsetMs", -999999, 999999)?.toString() ?: old.offsetMs,
                        practiceNotes = bool(change, "practiceNotes") ?: old.practiceNotes,
                        stagePsych = string(change, "stagePsych", 64) ?: old.stagePsych, stageVSlice = string(change, "stageVSlice", 64) ?: old.stageVSlice)
                    val songs = current.songs.toMutableList().also { it[index] = updated }
                    current = current.copy(songs = songs)
                    items.add(ProposalItem("Canción ${index + 1} · ${old.songName}", "${if (current.character.enabled) "Aviso: el personaje propio sobrescribe el ID de su lado al exportar.\n" else ""}Antes: BPM ${old.bpm}, velocidad ${old.speed}, offset ${old.offsetMs}, práctica ${old.practiceNotes}, artista ${old.artist}, jugador ${old.player}, rival ${old.opponent}, acompañante ${old.girlfriend}, escenarios ${old.stagePsych} / ${old.stageVSlice}\nDespués: BPM ${updated.bpm}, velocidad ${updated.speed}, offset ${updated.offsetMs}, práctica ${updated.practiceNotes}, artista ${updated.artist}, jugador ${updated.player}, rival ${updated.opponent}, acompañante ${updated.girlfriend}, escenarios ${updated.stagePsych} / ${updated.stageVSlice}"))
                }
                "new_song" -> {
                    keys(change, setOf("type", "name", "bpm"))
                    val name = requiredString(change, "name", 100).trim()
                    if (name.isBlank() || name.any { it in "/\\|:*?\"<>" } || current.songs.any { it.songFolder == psychSongPath(name) }) fail("nombre de canción inválido o repetido")
                    if (current.songs.size >= 20) fail("límite de 20 canciones")
                    val bpm = requiredInt(change, "bpm", 1, 400)
                    current = current.copy(songs = current.songs + SongConfig(songName = name, bpm = bpm.toString()))
                    items.add(ProposalItem("Añadir canción: $name", "BPM $bpm. Se crea sin audio; tendrás que elegir la instrumental antes de exportar."))
                }
                "chart" -> {
                    keys(change, setOf("type", "songIndex", "difficulty", "notes"))
                    val (index, song) = song(current, change)
                    if (song.songName.isBlank() || current.songs.count { it.songFolder == song.songFolder } != 1) fail("la canción necesita un nombre único antes de editar su chart")
                    val diff = requiredString(change, "difficulty", 20)
                    if (diff !in song.difficulties) fail("la dificultad $diff no está activada en la canción")
                    if (song.bpmValue !in 1..400) fail("corrige el BPM de la canción antes de crear el chart")
                    val array = change.get("notes")?.takeIf { it.isJsonArray }?.asJsonArray ?: fail("faltan notas")
                    if (array.size() > 1000) fail("máximo 1000 notas por propuesta de chart")
                    val notes = array.map { e ->
                        if (!e.isJsonObject) fail("cada nota debe ser un objeto")
                        val n = e.asJsonObject; keys(n, setOf("timeMs", "lane", "lengthMs"))
                        val time = requiredInt(n, "timeMs", 0, 1_200_000)
                        val lane = requiredInt(n, "lane", 0, 7)
                        val len = int(n, "lengthMs", 0, 60_000) ?: 0
                        if (time + len > 1_200_000) fail("la nota excede el límite de 20 minutos")
                        ChartNote(time.toLong(), lane, len.toLong())
                    }.sortedWith(compareBy<ChartNote> { it.timeMs }.thenBy { it.lane })
                    if (notes.map { it.timeMs to it.lane }.distinct().size != notes.size) fail("hay notas duplicadas en el mismo instante y carril")
                    val previous = current.getChart(song.songFolder, diff)
                    if (previous != null && previous.notes.size > 1000) fail("el chart existente tiene demasiadas notas para revisarlo en este lote")
                    if (previous != null && (previous.events.isNotEmpty() || previous.sections.isNotEmpty())) fail("el chart contiene eventos o secciones que no se pueden preservar")
                    val chart = ChartDraft(songFolder = song.songFolder, difficulty = diff, notes = notes, bpm = song.bpmValue,
                        scrollSpeed = song.speedValue, offsetMs = song.offsetValue, needsVoices = song.hasVocals,
                        player1 = song.player, player2 = song.opponent, gfVersion = song.girlfriend, stage = song.stagePsych)
                    val history = current.chartHistories[song.songFolder]?.get(diff) ?: ChartPersistentHistory()
                    val recorded = recordChartHistory(history, previous, chart)
                    current = current.withChart(song.songFolder, diff, chart).copy(chartHistories = current.chartHistories +
                        (song.songFolder to (current.chartHistories[song.songFolder].orEmpty() + (diff to recorded))))
                    val previousText = previous?.let { "${it.notes.size} notas editadas" } ?: if (song.practiceNotes) "notas de práctica generadas al exportar" else "chart vacío"
                    fun describeNotes(list: List<ChartNote>) = list.joinToString("\n") { "${it.timeMs} ms · carril ${it.lane} · duración ${it.lengthMs} ms" }.ifEmpty { "(sin notas)" }
                    val oldNotes = previous?.let { "\nNOTAS ANTERIORES:\n${describeNotes(it.notes)}" } ?: ""
                    items.add(ProposalItem("Reemplazar chart ${song.songName} · $diff", "Canción ${index + 1}. Antes: $previousText. Después: ${notes.size} notas. Carriles 0–3 jugador, 4–7 rival.$oldNotes\nNOTAS PROPUESTAS:\n${describeNotes(notes)}"))
                }
                "scene" -> {
                    keys(change, setOf("type", "zoom"))
                    val zoom = number(change, "zoom", 0.4f, 2f) ?: fail("falta zoom")
                    val before = current.stageDraft
                    if (!before.enabled) fail("activa primero el escenario sencillo y elige su fondo")
                    val updated = before.copy(cameraPosition = before.cameraPosition.copy(zoom = zoom))
                    current = current.copy(stageDraft = updated)
                    items.add(ProposalItem("Escenario · encuadre inicial", "Zoom anterior: ${before.cameraPosition.zoom}. Nuevo zoom: $zoom. Se exporta para todas las canciones en los motores seleccionados. La posición es orientativa y debe probarse en el juego."))
                }
                "script" -> fail("la IA no puede crear ni modificar código ejecutable")
                else -> fail("tipo de cambio no reconocido: $type")
            }
        }
        if (changes.any { it.asJsonObject.get("type")?.asString in setOf("assignment", "character", "animation") }) {
            val before = characterLibraryProblems(base).toSet()
            val problems = characterLibraryProblems(current)
            val newlyInvalid = if (changes.any { it.asJsonObject.get("type")?.asString == "assignment" }) problems else problems.filterNot { it in before }
            if (newlyInvalid.isNotEmpty()) fail(newlyInvalid.joinToString(" "))
        }
        val effective = current.effectiveExportConfig()
        val protected = current.songs.zip(effective.songs).flatMap { (raw, projected) -> com.fnfmodmaker.mod.vSliceExportProblems(raw, projected, current) }
        if (protected.isNotEmpty()) fail(protected.joinToString(" "))
        if (current == base) fail("el lote no cambia ningún valor")
        return ProjectProposal(base, current, summary, items)
    }
}

/** Only project metadata and a small note sample are sent; no files, audio, keys or URIs. */
fun projectContext(config: ModConfig, characterOnly: Boolean): String {
    val gson = com.google.gson.Gson()
    val character = config.character
    val result = linkedMapOf<String, Any>(
        "scope" to if (characterOnly) "solo personaje" else "mod completo",
        "engine" to config.engine.name,
        "character" to mapOf(
            "enabled" to character.enabled, "side" to character.side.name, "scale" to character.scale,
            "positionX" to character.positionX, "positionY" to character.positionY,
            "cameraX" to character.cameraX, "cameraY" to character.cameraY, "flipX" to character.flipX,
            "source" to if (character.useAtlas) "atlas Sparrow" else if (character.useIndividualFrames) "frames individuales" else "cuadrícula",
            "animations" to character.animationNames.map { name ->
                val s = character.settingsFor(name)
                mapOf("name" to name, "fps" to s.fps, "loop" to s.loop, "offsetX" to s.offsetX,
                    "offsetY" to s.offsetY, "prefix" to if (character.useAtlas) character.prefixFor(name) else "automático",
                    "frames" to (character.animations.firstOrNull { it.first == name }?.second ?: 0))
            }
        )
    )
    if (!characterOnly) {
        result["selectedCharacterId"] = config.selectedCharacterId
        result["characterLibrary"] = config.characterLibrary.map { entry ->
            val d = entry.draft
            mapOf("id" to entry.id, "fileId" to d.charFolder, "name" to d.displayName, "enabled" to d.enabled,
                "positionX" to d.positionX, "positionY" to d.positionY, "cameraX" to d.cameraX, "cameraY" to d.cameraY,
                "scale" to d.scale, "side" to d.side.name, "animations" to d.animationNames.map { name ->
                    val a = d.settingsFor(name); mapOf("name" to name, "fps" to a.fps, "loop" to a.loop, "offsetX" to a.offsetX, "offsetY" to a.offsetY)
                })
        }
        val assignments = config.resolvedAssignments()
        result["assignments"] = mapOf("player" to assignments.player, "opponent" to assignments.opponent, "girlfriend" to assignments.girlfriend,
            "scope" to "asignaciones globales a todas las canciones; vacío conserva ID de cada canción")
        result["stage"] = mapOf("enabled" to config.stageDraft.enabled, "id" to config.stageDraft.stageFolder,
            "hasBackground" to (config.stageDraft.backgroundImage != null), "zoom" to config.stageDraft.cameraPosition.zoom,
            "vSliceLayout" to config.stageDraft.vSliceLayout, "psychLayout" to config.stageDraft.psychLayout, "psychCameraSpeed" to config.stageDraft.psychCameraSpeed,
            "scope" to "escenario estático para todas las canciones si está activado")
        result["modTitle"] = config.modTitle.take(200)
        result["songs"] = config.songs.take(20).mapIndexed { index, song ->
            mapOf("index" to index, "name" to song.songName.take(100), "artist" to song.artist.take(100),
                "bpm" to song.bpm, "speed" to song.speed, "offsetMs" to song.offsetMs,
                "practiceNotes" to song.practiceNotes, "difficulties" to song.orderedDifficulties,
                "instrumentalDurationMs" to (song.instrumental?.durationMs ?: 0), "player" to song.player,
                "opponent" to song.opponent, "girlfriend" to song.girlfriend, "stagePsych" to song.stagePsych, "stageVSlice" to song.stageVSlice,
                "charts" to song.orderedDifficulties.map { difficulty ->
                    val chart = config.getChart(song.songFolder, difficulty)
                    mapOf("difficulty" to difficulty, "custom" to (chart != null), "noteCount" to (chart?.notes?.size ?: 0),
                        "events" to (chart?.events?.size ?: 0), "sampleNotes" to (chart?.notes?.take(30) ?: emptyList<ChartNote>()),
                        "sampleTruncated" to ((chart?.notes?.size ?: 0) > 30))
                })
        }
        result["psychScripts"] = config.psychScripts.map { (name, code) -> mapOf("name" to name, "length" to code.length) }
    }
    return gson.toJson(result)
}
