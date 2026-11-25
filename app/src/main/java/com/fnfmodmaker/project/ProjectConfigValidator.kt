package com.fnfmodmaker.project

import com.fnfmodmaker.model.*
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject

/**
 * Shared validation for portable and imported project configurations.
 *
 * v2 projects (chartSchemaVersion == 2) are validated against the independent-chart
 * schema: unique song IDs, per-engine variants, valid difficulties per variant,
 * independent histories per (songId, engine, difficulty), valid media references,
 * preserved unknown JSON, and no legacy `charts`/`chartHistories` as editable source.
 */
internal class ProjectConfigValidator(private val autoSaveGson: Gson) {
    private fun configJson(value: ModConfig) = autoSaveGson.toJsonTree(value).asJsonObject
    private fun parseConfig(value: com.google.gson.JsonObject): ModConfig =
        autoSaveGson.fromJson(value, ModConfig::class.java) ?: error("El proyecto está vacío.")

    /** Reject malformed model shapes before they can reach screens or be published. */
    fun validate(source: com.google.gson.JsonObject) {
        val version = source.get("chartSchemaVersion")
        val isV2 = version != null && version.isJsonPrimitive && version.asJsonPrimitive.isNumber && version.asString == "2"
        if (isV2) {
            validateV2(source)
        } else {
            requireSupportedChartSchema(source)
            validateV1(source)
        }
    }

    private fun validateV1(source: com.google.gson.JsonObject) {
        visitProjectAssets(source) { }
        val value = migrateCharacterLibrary(source)
        fun compare(input: com.google.gson.JsonObject, template: com.google.gson.JsonObject) {
            template.entrySet().forEach { (key, sample) ->
                val item = input.get(key) ?: return@forEach
                require(!item.isJsonNull && item.isJsonObject == sample.isJsonObject && item.isJsonArray == sample.isJsonArray && item.isJsonPrimitive == sample.isJsonPrimitive) { "Campo inválido: $key." }
                if (sample.isJsonPrimitive) {
                    val a = item.asJsonPrimitive; val b = sample.asJsonPrimitive
                    require(a.isBoolean == b.isBoolean && a.isNumber == b.isNumber && a.isString == b.isString && (!a.isNumber || a.asDouble.isFinite())) { "Tipo inválido: $key." }
                } else if (sample.isJsonObject) compare(item.asJsonObject, sample.asJsonObject)
                else if (sample.isJsonArray && sample.asJsonArray.size() > 0) {
                    val first = sample.asJsonArray[0]
                    item.asJsonArray.forEach { child ->
                        require(!child.isJsonNull && child.isJsonObject == first.isJsonObject && child.isJsonPrimitive == first.isJsonPrimitive) { "Elemento inválido: $key." }
                        if (first.isJsonObject) compare(child.asJsonObject, first.asJsonObject)
                        else if (first.isJsonPrimitive) require(child.asJsonPrimitive.isString == first.asJsonPrimitive.isString) { "Elemento inválido: $key." }
                    }
                }
            }
        }
        require(value.get("songs")?.isJsonArray == true && value.get("characterLibrary")?.isJsonArray == true && value.get("stageDraft")?.isJsonObject == true && value.get("charts")?.isJsonObject == true) { "Faltan datos del proyecto." }
        compare(value, configJson(ModConfig()))
        value.get("engine")?.let { require(it.asString in TargetEngine.entries.map { engine -> engine.name }) { "Motor desconocido." } }
        value.get("psychChartFormat")?.let { require(it.asString in PsychChartFormat.entries.map { format -> format.name }) { "Formato Psych desconocido." } }
        fun objectArray(parent: com.google.gson.JsonObject, key: String, template: com.google.gson.JsonObject, extra: (com.google.gson.JsonObject) -> Unit = {}) {
            parent.get(key)?.let { array ->
                require(array.isJsonArray) { "$key debe ser una lista." }
                array.asJsonArray.forEach { item ->
                    require(item.isJsonObject) { "Elemento inválido en $key." }
                    compare(item.asJsonObject, template); extra(item.asJsonObject)
                }
            }
        }
        value.getAsJsonArray("songs").forEach { item ->
            require(item.isJsonObject) { "Canción inválida." }
            val song = item.asJsonObject
            song.get("vSliceEventsJson")?.takeUnless { it.isJsonNull }?.let {
                require(it.isJsonPrimitive && it.asJsonPrimitive.isString) { "Eventos V-Slice inválidos." }
                com.fnfmodmaker.mod.validateVSliceEvents(it.asString)
            }
            song.get("vSliceTempoJson")?.takeUnless { it.isJsonNull }?.let {
                require(it.isJsonPrimitive && it.asJsonPrimitive.isString) { "Tempo V-Slice inválido." }
                com.fnfmodmaker.mod.validateVSliceTempo(it.asString)
            }
            listOf("vSliceTempoUndo", "vSliceTempoRedo").forEach { key ->
                song.get(key)?.let { stack ->
                    require(stack.isJsonArray && stack.asJsonArray.size() <= 10) { "Historial de tempo inválido." }
                    stack.asJsonArray.forEach { entry ->
                        require(entry.isJsonPrimitive && entry.asJsonPrimitive.isString) { "Historial de tempo inválido." }
                        com.fnfmodmaker.mod.validateVSliceTempo(entry.asString)
                    }
                }
            }
            listOf("vSliceEventsUndo", "vSliceEventsRedo").forEach { key ->
                song.get(key)?.let { stack ->
                    require(stack.isJsonArray && stack.asJsonArray.size() <= 10) { "Historial de eventos inválido." }
                    stack.asJsonArray.forEach { entry ->
                        require(entry.isJsonPrimitive && entry.asJsonPrimitive.isString) { "Historial de eventos inválido." }
                        com.fnfmodmaker.mod.validateVSliceEvents(entry.asString)
                    }
                }
            }
        }
        val library = value.getAsJsonArray("characterLibrary")
        require(library.size() in 1..64) { "Biblioteca de personajes inválida." }
        val ids = mutableSetOf<String>()
        library.forEach { item ->
            require(item.isJsonObject && item.asJsonObject.get("id")?.isJsonPrimitive == true && item.asJsonObject.get("draft")?.isJsonObject == true) { "Personaje inválido." }
            val entry = item.asJsonObject
            val id = entry.get("id").asString
            require(id.matches(Regex("[A-Za-z0-9_-]{1,64}")) && ids.add(id)) { "ID de biblioteca inválido o repetido." }
            val character = entry.getAsJsonObject("draft")
            character.get("side")?.let { require(it.asString in CharacterSide.entries.map { side -> side.name }) { "Lado de personaje inválido." } }
            objectArray(character, "variations", autoSaveGson.toJsonTree(com.fnfmodmaker.model.CharacterVariation()).asJsonObject)
            objectArray(character, "atlasFrames", autoSaveGson.toJsonTree(com.fnfmodmaker.model.SparrowFrame("frame", 0, 0, 1, 1)).asJsonObject)
            character.get("animationFrames")?.let { frames ->
                require(frames.isJsonObject) { "Frames inválidos." }
                frames.asJsonObject.entrySet().forEach { (_, array) ->
                    require(array.isJsonArray) { "Frames inválidos." }
                    array.asJsonArray.forEach { frame ->
                        require(frame.isJsonObject && frame.asJsonObject.get("image")?.isJsonObject == true && frame.asJsonObject.get("width")?.isJsonPrimitive == true && frame.asJsonObject.get("height")?.isJsonPrimitive == true) { "Frame sin imagen o dimensiones." }
                    }
                }
            }
            character.get("animationSettings")?.let { settings ->
                require(settings.isJsonObject) { "Ajustes de animación inválidos." }
                settings.asJsonObject.entrySet().forEach { (_, item) ->
                    require(item.isJsonObject) { "Ajustes de animación inválidos." }
                    compare(item.asJsonObject, autoSaveGson.toJsonTree(CharacterAnimationSettings()).asJsonObject)
                }
            }
        }
        value.get("selectedCharacterId")?.let { require(it.isJsonPrimitive && it.asString in ids) { "Personaje seleccionado no existe." } }
        value.get("characterAssignments")?.let { assignments ->
            require(assignments.isJsonObject) { "Asignaciones inválidas." }
            listOf("player", "opponent", "girlfriend").forEach { key ->
                assignments.asJsonObject.get(key)?.let { item -> require(item.isJsonPrimitive && item.asJsonPrimitive.isString && (item.asString.isEmpty() || item.asString in ids)) { "Asignación de personaje inválida." } }
            }
        }
        objectArray(value.getAsJsonObject("stageDraft"), "sprites", autoSaveGson.toJsonTree(com.fnfmodmaker.model.StageSprite()).asJsonObject) { sprite ->
            sprite.get("layer")?.let { require(it.asString in com.fnfmodmaker.model.StageSprite.Layer.entries.map { layer -> layer.name }) { "Capa inválida." } }
        }
        fun validatePsychFields(chart: com.google.gson.JsonObject) {
            chart.get("psychLayout")?.let { layout ->
                require(layout.isJsonArray && layout.asJsonArray.size() <= 1000) { "Secciones Psych inválidas." }
                layout.asJsonArray.forEach { entry ->
                    require(entry.isJsonObject) { "Sección Psych inválida." }
                    compare(entry.asJsonObject, autoSaveGson.toJsonTree(com.fnfmodmaker.model.PsychSectionData()).asJsonObject)
                }
                if (layout.asJsonArray.size() > 0) {
                    val parsed = autoSaveGson.fromJson(chart, ChartDraft::class.java)
                    com.fnfmodmaker.model.psychSectionSpans(parsed.psychLayout, parsed.bpm)
                }
            }
            chart.get("psychSongExtras")?.let { extras ->
                require(extras.isJsonPrimitive && extras.asJsonPrimitive.isString) { "Propiedades Psych inválidas." }
                if (extras.asString.isNotBlank()) {
                    require((chart.get("psychLayout")?.takeIf { it.isJsonArray }?.asJsonArray?.size() ?: 0) > 0) { "Propiedades Psych sin secciones." }
                    val obj = com.fnfmodmaker.model.psychObject(extras.asString, "propiedades Psych")
                    require(obj.keySet().none { it in setOf("notes", "events", "bpm", "needsVoices", "speed", "offset", "player1", "player2", "gfVersion", "stage", "format") }) { "Propiedades Psych ambiguas." }
                    obj.get("song")?.let { require(it.isJsonPrimitive && it.asJsonPrimitive.isString) { "Nombre original Psych inválido." } }
                }
            }
        }
        fun validateNote(value: com.google.gson.JsonObject) {
            compare(value, autoSaveGson.toJsonTree(ChartNote()).asJsonObject)
            val note = autoSaveGson.fromJson(value, ChartNote::class.java)
            com.fnfmodmaker.model.psychNoteTypeToken(note)
        }
        value.getAsJsonObject("charts").entrySet().forEach { (_, perSong) ->
            require(perSong.isJsonObject) { "Charts inválidos." }
            perSong.asJsonObject.entrySet().forEach { (_, chart) ->
                require(chart.isJsonObject) { "Chart inválido." }
                compare(chart.asJsonObject, configJsonChartTemplate())
                chart.asJsonObject.get("psychEventsJson")?.let { events ->
                    require(events.isJsonPrimitive && events.asJsonPrimitive.isString) { "Eventos Psych inválidos." }
                    if (events.asString.isNotBlank()) com.fnfmodmaker.mod.validatePsychEvents(events.asString)
                }
                validatePsychFields(chart.asJsonObject)
                objectArray(chart.asJsonObject, "events", autoSaveGson.toJsonTree(com.fnfmodmaker.model.ChartEvent()).asJsonObject) { event ->
                    event.get("type")?.let { require(it.asString in com.fnfmodmaker.model.ChartEvent.EventType.entries.map { type -> type.name }) { "Evento inválido." } }
                }
                objectArray(chart.asJsonObject, "sections", autoSaveGson.toJsonTree(com.fnfmodmaker.model.ChartSection()).asJsonObject)
                chart.asJsonObject.get("notes")?.let { notes ->
                    require(notes.isJsonArray) { "Notas inválidas." }
                    notes.asJsonArray.forEach { n -> require(n.isJsonObject); validateNote(n.asJsonObject) }
                }
            }
        }
        value.get("chartHistories")?.let { all ->
            require(all.isJsonObject && all.asJsonObject.size() <= 256) { "Historial inválido." }
            all.asJsonObject.entrySet().forEach { (_, perSong) ->
                require(perSong.isJsonObject && perSong.asJsonObject.size() <= 16) { "Historial de canción inválido." }
                perSong.asJsonObject.entrySet().forEach { (_, item) ->
                    require(item.isJsonObject) { "Historial de dificultad inválido." }
                    var noteCount = 0
                    listOf("undo", "redo").forEach { key ->
                        val stack = item.asJsonObject.get(key) ?: return@forEach
                        require(stack.isJsonArray && stack.asJsonArray.size() <= 50) { "Historial demasiado grande." }
                        stack.asJsonArray.forEach { entry ->
                            require(entry.isJsonObject) { "Entrada de historial inválida." }
                            val e = entry.asJsonObject
                            listOf("beforeHash", "afterHash").forEach { h -> require(e.get(h)?.asString?.matches(Regex("[0-9a-f]{64}")) == true) { "Huella de historial inválida." } }
                            listOf("added", "removed").forEach { n ->
                                val notes = e.get(n); require(notes != null && notes.isJsonArray) { "Notas de historial inválidas." }
                                noteCount += notes.asJsonArray.size()
                                notes.asJsonArray.forEach { note -> require(note.isJsonObject); validateNote(note.asJsonObject) }
                            }
                            listOf("beforeTemplate", "afterTemplate").forEach { t -> e.get(t)?.let { template ->
                                require(template.isJsonObject) { "Plantilla de historial inválida." }
                                compare(template.asJsonObject, configJsonChartTemplate())
                                validatePsychFields(template.asJsonObject)
                                require(template.asJsonObject.get("notes")?.asJsonArray?.size() == 0) { "Plantilla de historial inválida." }
                            } }
                        }
                    }
                    require(noteCount <= 40000) { "Historial demasiado grande." }
                }
            }
        }
        parseConfig(value)
    }

    private fun validateV2(source: com.google.gson.JsonObject) {
        source.get("chartSchemaVersion")?.let {
            require(it.isJsonPrimitive && it.asJsonPrimitive.isNumber && it.asString == "2") {
                "chartSchemaVersion debe ser 2 para proyectos con charts independientes."
            }
        }
        require(source.has("independentCharts")) { "Falta independentCharts en el esquema v2." }
        val independent = source.getAsJsonObject("independentCharts")

        // Visit media references: songs metadata audio/images plus variant originals.
        visitProjectAssets(source) { }

        val songIds = mutableSetOf<String>()
        independent.entrySet().forEach { (songId, songNode) ->
            require(songNode.isJsonObject) { "Canción inválida: $songId." }
            require(songId.matches(Regex("[A-Za-z0-9_-]{1,80}")) && songIds.add(songId)) {
                "ID de canción inválido o duplicado: $songId."
            }
            val songObj = songNode.asJsonObject
            val psychNode = songObj.get("psych")
            val vSliceNode = songObj.get("vslice")
            require((psychNode == null || psychNode.isJsonObject || psychNode.isJsonNull) &&
                (vSliceNode == null || vSliceNode.isJsonObject || vSliceNode.isJsonNull)) {
                "Variante de canción inválida: $songId."
            }
            require(psychNode != null && !psychNode.isJsonNull || vSliceNode != null && !vSliceNode.isJsonNull) {
                "Canción $songId debe tener al menos una variante."
            }
            if (psychNode.isJsonObject) validateVariant(songId, ChartEngine.PSYCH, psychNode.asJsonObject)
            if (vSliceNode.isJsonObject) validateVariant(songId, ChartEngine.VSLICE, vSliceNode.asJsonObject)
        }

        // Legacy charts/histories must not be the editable source in v2.
        source.get("charts")?.let { require(it.isJsonNull || it.asJsonObject.size() == 0) {
            "charts legacy no debe usarse como almacenamiento editable en v2."
        } }
        source.get("chartHistories")?.let { require(it.isJsonNull || it.asJsonObject.size() == 0) {
            "chartHistories legacy no debe usarse como almacenamiento editable en v2."
        } }
    }

    private fun validateVariant(songId: String, engine: ChartEngine, variant: JsonObject) {
        val metadataNode = variant.get("metadata") ?: error("Falta metadata de la variante $engine para $songId.")
        require(metadataNode.isJsonObject) { "metadata debe ser un objeto." }
        val metadata = autoSaveGson.fromJson(metadataNode, SongConfig::class.java)
            ?: error("metadata inválido.")
        require(metadata.songFolder.isNotEmpty()) { "metadata.songFolder requerido." }

        val difficulties = variant.get("difficulties")?.let {
            require(it.isJsonArray) { "difficulties debe ser una lista." }
            it.asJsonArray.map { it.asString }.toSet()
        } ?: emptySet()
        require(difficulties.all { isValidDifficulty(it) }) { "Dificultad inválida en la variante $engine de $songId." }

        val charts = variant.get("charts")?.let {
            require(it.isJsonObject) { "charts debe ser un objeto." }
            it.asJsonObject
        } ?: error("Falta charts en la variante $engine de $songId.")
        val histories = variant.get("histories")?.let {
            require(it.isJsonObject) { "histories debe ser un objeto." }
            it.asJsonObject
        } ?: error("Falta histories en la variante $engine de $songId.")
        val uneditable = variant.get("uneditableDifficulties")?.let {
            require(it.isJsonObject) { "uneditableDifficulties debe ser un objeto." }
            it.asJsonObject
        } ?: JsonObject()

        // Every chart difficulty must be declared; every declared editable difficulty must have a chart.
        val chartKeys = mutableSetOf<String>()
        charts.entrySet().forEach { (difficulty, chartNode) ->
            require(chartNode.isJsonObject) { "Chart inválido para $difficulty." }
            require(chartKeys.add(difficulty)) { "Dificultad duplicada $difficulty en $engine/$songId." }
            val draft = autoSaveGson.fromJson(chartNode, ChartDraft::class.java)
                ?: error("Chart inválido para $difficulty.")
            require(draft.songFolder == metadata.songFolder) { "El chart no pertenece a la canción $songId." }
            require(draft.difficulty == difficulty) { "El chart no corresponde a la dificultad $difficulty." }
            validateChartContent(autoSaveGson.toJsonTree(draft).asJsonObject)
        }
        histories.keySet().forEach { difficulty ->
            require(difficulty in difficulties) { "Historial para dificultad no declarada $difficulty en $engine/$songId." }
        }
        uneditable.entrySet().forEach { (difficulty, path) ->
            require(difficulty in difficulties) { "Dificultad no editable no declarada: $difficulty." }
            require(path.isJsonPrimitive && path.asString.isNotBlank()) { "Ruta original inválida para $difficulty." }
        }

        // Unknown metadata JSON preserved, not interpreted as paths.
        variant.get("unknownMetadataJson")?.takeUnless { it.isJsonNull }?.let {
            require(it.isJsonPrimitive && it.asString.length <= 256_000) {
                "unknownMetadataJson inválido en la variante $engine de $songId."
            }
        }

        // Originals: either a typed media reference or an opaque document.
        variant.get("originals")?.let { originals ->
            require(originals.isJsonArray) { "originals debe ser una lista." }
            originals.asJsonArray.forEach { ref ->
                require(ref.isJsonObject) { "Original inválido." }
                val obj = ref.asJsonObject
                val assetNode = obj.get("asset")
                if (assetNode != null && !assetNode.isJsonNull) {
                    require(assetNode.isJsonObject) { "Recurso original inválido en $engine/$songId." }
                    val asset = assetNode.asJsonObject
                    require(asset.get("kind")?.isJsonPrimitive == true && asset.get("kind").asJsonPrimitive.isString && asset.get("kind").asString in setOf("image", "audio")) {
                        "Tipo de medio inválido en original de $engine/$songId."
                    }
                    require(asset.get("path")?.isJsonPrimitive == true && asset.get("path").asJsonPrimitive.isString && asset.get("path").asString.isNotEmpty() &&
                        asset.get("path").asString.split('/').none { it == "." || it == ".." }) {
                        "Ruta de original insegura en $engine/$songId."
                    }
                    require(asset.get("displayName")?.isJsonPrimitive == true && asset.get("displayName").asJsonPrimitive.isString) { "Falta displayName en original de $engine/$songId." }
                    asset.get("uri")?.let { uri ->
                        require(uri.isJsonNull || (uri.isJsonPrimitive && uri.asJsonPrimitive.isString)) {
                            "URI inválida en original de $engine/$songId."
                        }
                    }
                    obj.get("contentJson")?.let { content ->
                        require(content.isJsonNull || (content.isJsonPrimitive && content.asJsonPrimitive.isString && content.asString.isEmpty())) {
                            "Un original no puede ser medio y documento a la vez."
                        }
                    }
                } else {
                    val content = obj.get("contentJson")
                    require(content?.isJsonPrimitive == true && content.asJsonPrimitive.isString && content.asString.isNotEmpty()) {
                        "Original sin medio ni documento opaco en $engine/$songId."
                    }
                }
            }
        }
    }

    private fun validateChartContent(chart: JsonObject) {
        chart.get("psychEventsJson")?.let { events ->
            require(events.isJsonPrimitive && events.asJsonPrimitive.isString) { "Eventos Psych inválidos." }
            if (events.asString.isNotBlank()) com.fnfmodmaker.mod.validatePsychEvents(events.asString)
        }
        chart.get("psychLayout")?.let { layout ->
            require(layout.isJsonArray && layout.asJsonArray.size() <= 1000) { "Secciones Psych inválidas." }
            layout.asJsonArray.forEach { entry ->
                require(entry.isJsonObject) { "Sección Psych inválida." }
                compareTemplate(entry.asJsonObject, autoSaveGson.toJsonTree(PsychSectionData()).asJsonObject)
            }
        }
        chart.get("psychSongExtras")?.let { extras ->
            require(extras.isJsonPrimitive && extras.asJsonPrimitive.isString) { "Propiedades Psych inválidas." }
        }
        objectArray(chart, "events", autoSaveGson.toJsonTree(ChartEvent()).asJsonObject) { event ->
            event.get("type")?.let { require(it.asString in ChartEvent.EventType.entries.map { type -> type.name }) { "Evento inválido." } }
        }
        objectArray(chart, "sections", autoSaveGson.toJsonTree(ChartSection()).asJsonObject)
        chart.get("notes")?.let { notes ->
            require(notes.isJsonArray) { "Notas inválidas." }
            notes.asJsonArray.forEach { n -> require(n.isJsonObject); validateNote(n.asJsonObject) }
        }
    }

    private fun validateNote(value: JsonObject) {
        compareTemplate(value, autoSaveGson.toJsonTree(ChartNote()).asJsonObject)
        val note = autoSaveGson.fromJson(value, ChartNote::class.java)
        com.fnfmodmaker.model.psychNoteTypeToken(note)
    }

    private fun compareTemplate(input: JsonObject, template: JsonObject) {
        template.entrySet().forEach { (key, sample) ->
            val item = input.get(key) ?: return@forEach
            require(!item.isJsonNull && item.isJsonObject == sample.isJsonObject && item.isJsonArray == sample.isJsonArray && item.isJsonPrimitive == sample.isJsonPrimitive) { "Campo inválido: $key." }
            if (sample.isJsonPrimitive) {
                val a = item.asJsonPrimitive; val b = sample.asJsonPrimitive
                require(a.isBoolean == b.isBoolean && a.isNumber == b.isNumber && a.isString == b.isString && (!a.isNumber || a.asDouble.isFinite())) { "Tipo inválido: $key." }
            } else if (sample.isJsonObject) compareTemplate(item.asJsonObject, sample.asJsonObject)
            else if (sample.isJsonArray && sample.asJsonArray.size() > 0) {
                val first = sample.asJsonArray[0]
                item.asJsonArray.forEach { child ->
                    require(!child.isJsonNull && child.isJsonObject == first.isJsonObject && child.isJsonPrimitive == first.isJsonPrimitive) { "Elemento inválido: $key." }
                    if (first.isJsonObject) compareTemplate(child.asJsonObject, first.asJsonObject)
                    else if (first.isJsonPrimitive) require(child.asJsonPrimitive.isString == first.asJsonPrimitive.isString) { "Elemento inválido: $key." }
                }
            }
        }
    }

    private fun objectArray(parent: JsonObject, key: String, template: JsonObject, extra: (JsonObject) -> Unit = {}) {
        parent.get(key)?.let { array ->
            require(array.isJsonArray) { "$key debe ser una lista." }
            array.asJsonArray.forEach { item ->
                require(item.isJsonObject) { "Elemento inválido en $key." }
                compareTemplate(item.asJsonObject, template); extra(item.asJsonObject)
            }
        }
    }

    private fun configJsonChartTemplate() = autoSaveGson.toJsonTree(ChartDraft()).asJsonObject
}
