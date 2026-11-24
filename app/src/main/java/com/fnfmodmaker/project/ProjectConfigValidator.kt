package com.fnfmodmaker.project

import com.fnfmodmaker.model.*
import com.google.gson.Gson

/** Shared validation for portable and imported project configurations. */
internal class ProjectConfigValidator(private val autoSaveGson: Gson) {
    private fun configJson(value: ModConfig) = autoSaveGson.toJsonTree(value).asJsonObject
    private fun parseConfig(value: com.google.gson.JsonObject): ModConfig =
        autoSaveGson.fromJson(value, ModConfig::class.java) ?: error("El proyecto está vacío.")
    /** Reject malformed model shapes before they can reach screens or be published. */
    fun validate(value: com.google.gson.JsonObject) {
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
        require(value.get("songs")?.isJsonArray == true && value.get("character")?.isJsonObject == true && value.get("stageDraft")?.isJsonObject == true && value.get("charts")?.isJsonObject == true) { "Faltan datos del proyecto." }
        compare(value, configJson(ModConfig()))
        value.get("engine")?.let { require(it.asString in TargetEngine.entries.map { engine -> engine.name }) { "Motor desconocido." } }
        val character = value.getAsJsonObject("character")
        character.get("side")?.let { require(it.asString in CharacterSide.entries.map { side -> side.name }) { "Lado de personaje inválido." } }
        fun objectArray(parent: com.google.gson.JsonObject, key: String, template: com.google.gson.JsonObject, extra: (com.google.gson.JsonObject) -> Unit = {}) {
            parent.get(key)?.let { array ->
                require(array.isJsonArray) { "$key debe ser una lista." }
                array.asJsonArray.forEach { item ->
                    require(item.isJsonObject) { "Elemento inválido en $key." }
                    compare(item.asJsonObject, template); extra(item.asJsonObject)
                }
            }
        }
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
        objectArray(value.getAsJsonObject("stageDraft"), "sprites", autoSaveGson.toJsonTree(com.fnfmodmaker.model.StageSprite()).asJsonObject) { sprite ->
            sprite.get("layer")?.let { require(it.asString in com.fnfmodmaker.model.StageSprite.Layer.entries.map { layer -> layer.name }) { "Capa inválida." } }
        }
        value.getAsJsonObject("charts").entrySet().forEach { (_, perSong) ->
            require(perSong.isJsonObject) { "Charts inválidos." }
            perSong.asJsonObject.entrySet().forEach { (_, chart) ->
                require(chart.isJsonObject) { "Chart inválido." }
                compare(chart.asJsonObject, configJsonChartTemplate())
                objectArray(chart.asJsonObject, "events", autoSaveGson.toJsonTree(com.fnfmodmaker.model.ChartEvent()).asJsonObject) { event ->
                    event.get("type")?.let { require(it.asString in com.fnfmodmaker.model.ChartEvent.EventType.entries.map { type -> type.name }) { "Evento inválido." } }
                }
                objectArray(chart.asJsonObject, "sections", autoSaveGson.toJsonTree(com.fnfmodmaker.model.ChartSection()).asJsonObject)
                chart.asJsonObject.get("notes")?.let { notes ->
                    require(notes.isJsonArray) { "Notas inválidas." }
                    notes.asJsonArray.forEach { n -> require(n.isJsonObject); compare(n.asJsonObject, autoSaveGson.toJsonTree(ChartNote()).asJsonObject) }
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
                                notes.asJsonArray.forEach { note -> require(note.isJsonObject); compare(note.asJsonObject, autoSaveGson.toJsonTree(ChartNote()).asJsonObject) }
                            }
                            listOf("beforeTemplate", "afterTemplate").forEach { t -> e.get(t)?.let { template ->
                                require(template.isJsonObject) { "Plantilla de historial inválida." }
                                compare(template.asJsonObject, configJsonChartTemplate())
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
    private fun configJsonChartTemplate() = autoSaveGson.toJsonTree(ChartDraft()).asJsonObject

}
