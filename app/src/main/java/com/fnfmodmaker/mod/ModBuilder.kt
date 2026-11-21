package com.fnfmodmaker.mod

import android.content.Context
import com.fnfmodmaker.media.MediaTools
import com.fnfmodmaker.model.ModConfig
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
 * Crea la carpeta del mod y todos sus archivos, y la empaqueta en un ZIP.
 */
object ModBuilder {

    fun build(
        context: Context,
        rawConfig: ModConfig,
        workRoot: File,
        onProgress: (String) -> Unit
    ): BuildResult {
        val warnings = mutableListOf<String>()
        // Si hay personaje propio, el chart debe apuntar a él.
        val config = rawConfig.withCharacter()
        val modFolder = config.modFolder
        val songFolder = config.songFolder
        val root = File(workRoot, modFolder)
        if (root.exists()) root.deleteRecursively()
        if (!root.mkdirs()) throw IllegalStateException("No se pudo crear la carpeta de trabajo.")

        // ---------------- Audio ----------------
        val songDir = File(root, "songs/$songFolder")
        songDir.mkdirs()

        val instrumental = config.instrumental
            ?: throw IllegalArgumentException("Falta la instrumental.")

        onProgress(if (instrumental.isOgg) "Copiando instrumental…" else "Convirtiendo instrumental a OGG…")
        val instTarget = File(songDir, "Inst.ogg")
        if (instrumental.isOgg) {
            File(instrumental.path).copyTo(instTarget, overwrite = true)
        } else {
            val error = MediaTools.convertToOgg(File(instrumental.path), instTarget)
            if (error != null) {
                throw IllegalStateException("No se pudo convertir la instrumental a OGG:\n$error")
            }
        }

        // ---------------- Voces ----------------
        // V-Slice usa una pista por personaje (Voices-bf.ogg, Voices-dad.ogg),
        // como los mods reales. Psych solo lee Voices.ogg, así que si hay dos
        // pistas se mezclan en una.
        val playerVocals = config.vocalsPlayer
        val opponentVocals = config.vocalsOpponent

        if (config.hasVocals) {
            if (config.hasSplitVocals) {
                if (config.engine.includesVSlice) {
                    encodeAudio(
                        playerVocals!!,
                        File(songDir, "Voices-${characterFileId(config.player)}.ogg"),
                        "las voces del jugador",
                        warnings,
                        onProgress
                    )
                    encodeAudio(
                        opponentVocals!!,
                        File(songDir, "Voices-${characterFileId(config.opponent)}.ogg"),
                        "las voces del oponente",
                        warnings,
                        onProgress
                    )
                }
                if (config.engine.includesPsych) {
                    onProgress("Mezclando las dos pistas de voces para Psych…")
                    val mixed = File(songDir, "Voices.ogg")
                    val error = MediaTools.mixToOgg(
                        File(playerVocals!!.path),
                        File(opponentVocals!!.path),
                        mixed
                    )
                    if (error != null) {
                        warnings.add("No se pudieron mezclar las voces para Psych: $error")
                        if (mixed.exists()) mixed.delete()
                    }
                }
            } else {
                val single = playerVocals ?: opponentVocals!!
                encodeAudio(single, File(songDir, "Voices.ogg"), "las voces", warnings, onProgress)
            }
        }

        val durationMs = instrumental.durationMs

        // ---------------- Icono ----------------
        config.icon?.let { icon ->
            onProgress("Preparando el icono…")
            val iconTarget = File(root, "_polymod_icon.png")
            if (!MediaTools.preparePng(File(icon.path), iconTarget, 512)) {
                warnings.add("El icono no se pudo procesar, el mod se creó sin icono.")
            }
        }

        // ---------------- Personaje propio ----------------
        CharacterBuilder.build(root, config, onProgress, warnings)

        // ---------------- V-Slice ----------------
        if (config.engine.includesVSlice) {
            onProgress("Escribiendo archivos de V-Slice…")
            writeText(root, "_polymod_meta.json", JsonBuilders.polymodMeta(config))
            writeText(
                root,
                "data/songs/$songFolder/$songFolder-metadata.json",
                JsonBuilders.vsliceMetadata(config, durationMs)
            )
            writeText(
                root,
                "data/songs/$songFolder/$songFolder-chart.json",
                JsonBuilders.vsliceChart(config, durationMs)
            )
            writeText(root, "data/levels/$modFolder.json", JsonBuilders.vsliceLevel(config))

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
            val chartJson = JsonBuilders.psychChart(config, durationMs)
            val difficulties = config.orderedDifficulties
            for (difficulty in difficulties) {
                val fileName = if (difficulty == "normal") "$songFolder.json" else "$songFolder-$difficulty.json"
                writeText(root, "data/$songFolder/$fileName", chartJson)
            }
            // Psych abre siempre la dificultad "Normal", que es el archivo SIN sufijo.
            // Si no la marcaste, se copia la primera que elegiste para que no falle al entrar.
            if (difficulties.none { it == "normal" }) {
                writeText(root, "data/$songFolder/$songFolder.json", chartJson)
                warnings.add(
                    "Psych arranca en la dificultad Normal: se copió " +
                        "${difficulties.first()} también como $songFolder.json."
                )
            }
            writeText(root, "weeks/$modFolder.json", JsonBuilders.psychWeek(config))
        }

        writeText(root, "LEEME.txt", readmeText(config))

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

    private fun readmeText(config: ModConfig): String {
        val output = StringBuilder()
        output.appendLine("MOD: ${config.modTitle}")
        output.appendLine("Canción: ${config.songName}")
        output.appendLine("Creado con FNF Mod Maker")
        output.appendLine()
        if (config.engine.includesVSlice) {
            output.appendLine("== V-Slice (FNF 0.8.x de Android) ==")
            output.appendLine("1. Descomprime este ZIP en la carpeta de mods del juego:")
            output.appendLine("   Android/obb/me.funkin.fnf/mods/")
            output.appendLine("   (también vale Android/data/me.funkin.fnf/files/mods/)")
            output.appendLine("2. Abre el juego y busca la canción en Freeplay.")
            output.appendLine()
        }
        if (config.engine.includesPsych) {
            output.appendLine("== Psych Engine (Android / PC) ==")
            output.appendLine("1. Descomprime este ZIP en la carpeta mods del juego:")
            output.appendLine("   - PC: <carpeta de Psych Engine>/mods/")
            output.appendLine("   - Android: Android/data/<paquete de Psych>/files/mods/")
            output.appendLine("2. Abre el juego: la canción aparece en Freeplay.")
            output.appendLine()
        }
        output.appendLine("El chart trae notas de práctica para comprobar que todo suena.")
        output.appendLine("Para chartear en serio, abre el editor de charts del juego")
        output.appendLine("(en Psych: tecla 7 / en V-Slice: menú de debug) y guarda encima.")
        output.appendLine()
        output.appendLine("Dificultades incluidas: ${config.orderedDifficulties.joinToString(", ")}")
        output.appendLine("Offset: ${config.offsetValue} ms")
        when {
            config.hasSplitVocals -> output.appendLine(
                "Voces: Voices-<personaje>.ogg para V-Slice y Voices.ogg (mezcla) para Psych."
            )
            config.hasVocals -> output.appendLine("Voces: una pista Voices.ogg para los dos motores.")
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
        return output.toString()
    }
}
