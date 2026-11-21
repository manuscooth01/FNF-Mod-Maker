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
        config: ModConfig,
        workRoot: File,
        onProgress: (String) -> Unit
    ): BuildResult {
        val warnings = mutableListOf<String>()
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

        config.vocals?.let { vocals ->
            onProgress(if (vocals.isOgg) "Copiando voces…" else "Convirtiendo voces a OGG…")
            val voicesTarget = File(songDir, "Voices.ogg")
            if (vocals.isOgg) {
                File(vocals.path).copyTo(voicesTarget, overwrite = true)
            } else {
                val error = MediaTools.convertToOgg(File(vocals.path), voicesTarget)
                if (error != null) {
                    warnings.add("Las voces no se pudieron convertir, el mod se creó sin ellas.")
                    if (voicesTarget.exists()) voicesTarget.delete()
                }
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
        }

        // ---------------- Psych Engine ----------------
        if (config.engine.includesPsych) {
            onProgress("Escribiendo archivos de Psych Engine…")
            writeText(root, "pack.json", JsonBuilders.psychPack(config))
            val chartJson = JsonBuilders.psychChart(config, durationMs)
            for (difficulty in config.orderedDifficulties) {
                val fileName = if (difficulty == "normal") "$songFolder.json" else "$songFolder-$difficulty.json"
                writeText(root, "data/$songFolder/$fileName", chartJson)
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

    fun zip(rootDir: File, output: OutputStream) {
        val base = rootDir.parentFile ?: rootDir
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
        return output.toString()
    }
}
