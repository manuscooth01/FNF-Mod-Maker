package com.fnfmodmaker.mod

import com.fnfmodmaker.model.ModConfig
import com.fnfmodmaker.model.TargetEngine
import java.io.File

/**
 * Character Studio package export: builds the character files for exactly one engine
 * target into [root] (no mod scaffolding around them) and patches the generated JSON
 * from the retained original so fields the editor does not represent survive.
 * BOTH is never a mixed package: callers build one package per engine, each with its
 * own JSON paths and instructions.
 */
object CharacterPackage {

    /** Builds one engine-targeted character package. Returns the generated file paths. */
    fun build(
        root: File,
        config: ModConfig,
        engine: TargetEngine,
        appRoot: File,
        onProgress: (String) -> Unit,
        warnings: MutableList<String>
    ): List<String> {
        val draft = config.character
        require(draft.enabled) { "Activa el personaje antes de exportar." }
        require(draft.problems.isEmpty()) { draft.problems.joinToString(" ") }
        if (root.exists()) check(root.deleteRecursively()) { "No se pudo limpiar la carpeta de trabajo." }
        check(root.mkdirs()) { "No se pudo crear la carpeta de trabajo." }
        CharacterBuilder.build(root, config.copy(engine = engine), onProgress, warnings)
        val id = draft.charFolder
        if (engine.includesVSlice && draft.importedSourceFormat == "V-Slice") {
            val generated = File(root, "data/characters/$id.json")
            require(generated.isFile) { "No se generó el JSON de V-Slice." }
            val original = if (draft.importedVSliceCharacterId.isNotEmpty())
                originalVSliceText(config, "data/characters/${draft.importedVSliceCharacterId}.json", appRoot, true)
            else draft.importedSourceJson
            if (original.isNotEmpty()) generated.writeText(patchVSliceCharacter(original, generated.readText(), draft.icon != null), Charsets.UTF_8)
        }
        if (engine.includesPsych && draft.importedSourceFormat == "Psych") {
            val generated = File(root, "characters/$id.json")
            require(generated.isFile) { "No se generó el JSON de Psych." }
            val originalPath = "characters/${draft.importedPsychCharacterId.ifEmpty { id }}.json"
            val original = if (config.importedModResources.any { it.converted && it.originalPath == originalPath })
                originalVSliceText(config, originalPath, appRoot, true)
            else draft.importedSourceJson
            if (original.isNotEmpty()) generated.writeText(patchPsychCharacter(original, generated.readText(), draft.icon != null), Charsets.UTF_8)
        }
        File(root, "LEEME.txt").writeText(readme(id, engine, draft.icon != null), Charsets.UTF_8)
        return root.walkTopDown().filter { it.isFile }
            .map { it.relativeTo(root).invariantSeparatorsPath }.sorted().toList()
    }

    /** Installation instructions; each package explains only the engine it targets. */
    fun readme(id: String, engine: TargetEngine, hasIcon: Boolean): String = buildString {
        appendLine("PERSONAJE: $id · destino ${engine.label}")
        appendLine()
        appendLine("Este ZIP NO es un mod: son los archivos de un personaje para meterlos")
        appendLine("dentro de un mod que ya tengas (o en el que crees con esta misma app).")
        appendLine()
        appendLine("CÓMO USARLO")
        appendLine("1. Descomprime el ZIP dentro de la carpeta de tu mod (la de mods/):")
        if (engine.includesPsych) {
            appendLine("   - Psych: characters/$id.json + images/characters/$id.png y .xml")
            appendLine("   - En el chart, usa \"$id\" como player1 (jugador) o player2 (rival).")
        }
        if (engine.includesVSlice) {
            appendLine("   - V-Slice: data/characters/$id.json + images/characters/$id.png y .xml")
            appendLine("   - En la metadata, pon \"$id\" en playData.characters.player u .opponent.")
        }
        if (hasIcon) appendLine("2. El icono de vida quedó en images/icons/icon-$id.png.")
        appendLine()
        appendLine("En personajes importados, los campos del JSON original que el editor no")
        appendLine("representa se conservan al exportar; el proyecto guarda el original íntegro.")
    }
}
