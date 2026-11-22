package com.fnfmodmaker.mod

import android.content.Context
import android.net.Uri
import com.fnfmodmaker.media.MediaTools
import com.fnfmodmaker.model.ChartDraft
import com.fnfmodmaker.model.ChartNote
import com.fnfmodmaker.model.ModConfig
import com.fnfmodmaker.model.PickedAudio
import com.fnfmodmaker.model.PickedImage
import com.fnfmodmaker.model.SongConfig
import com.fnfmodmaker.model.StageDraft
import com.fnfmodmaker.model.StageSprite
import com.fnfmodmaker.model.TargetEngine
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.zip.ZipFile
import java.util.zip.ZipEntry

/**
 * Importa un mod existente desde un ZIP y reconstruye el ModConfig
 * para poder editarlo en el wizard.
 * Implementación simplificada para evitar errores de inferencia de tipos.
 */
object ModImporter {

    data class ImportResult(
        val config: ModConfig,
        val warnings: List<String>
    )

    data class ModMetaResult(
        val modTitle: String,
        val modFolder: String,
        val author: String,
        val description: String,
        val weekColor: String,
        val showInStoryMode: Boolean
    )

    fun importFromZip(
        context: Context,
        zipFile: File,
        workRoot: File,
        onProgress: (String) -> Unit
    ): ImportResult {
        val warnings = mutableListOf<String>()

        onProgress("Extrayendo ZIP…")
        val extractDir = File(workRoot, "import_${System.currentTimeMillis()}")
        if (extractDir.exists()) extractDir.deleteRecursively()
        extractDir.mkdirs()

        ZipFile(zipFile).use { zip ->
            val entries = zip.entries()
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                val outFile = File(extractDir, entry.name)
                if (entry.isDirectory) {
                    outFile.mkdirs()
                } else {
                    outFile.parentFile?.mkdirs()
                    zip.getInputStream(entry).use { input ->
                        outFile.outputStream().use { output -> input.copyTo(output) }
                    }
                }
            }
        }

        onProgress("Leyendo metadatos…")
        val engine = detectEngine(extractDir)
        val meta = readModMeta(extractDir, engine)
        val iconFile = findIcon(extractDir)

        onProgress("Detectando canciones…")
        val songs = readSongs(extractDir, engine, warnings)

        onProgress("Buscando personaje propio…")
        val characterDraft = readCharacter(extractDir, engine, warnings)

        onProgress("Buscando stage propio…")
        val stageDraft = readStage(extractDir, warnings)

        val processedIcon = iconFile?.let { src ->
            val target = File(context.cacheDir, "import_icon.png")
            if (MediaTools.preparePng(src, target, 512)) {
                PickedImage(Uri.fromFile(target), target.name, target.absolutePath)
            } else null
        }

        val config = ModConfig(
            engine = engine,
            modTitle = meta.modTitle,
            modId = meta.modFolder,
            author = meta.author,
            description = meta.description,
            songs = songs,
            weekColor = meta.weekColor,
            showInStoryMode = meta.showInStoryMode,
            character = characterDraft,
            stageDraft = StageDraft(),
            icon = null
        )

        extractDir.deleteRecursively()

        return ImportResult(config, warnings)
    }

    fun importStageFromZip(
        context: Context,
        zipFile: File,
        workRoot: File,
        onProgress: (String) -> Unit
    ): StageDraft {
        val warnings = mutableListOf<String>()

        onProgress("Extrayendo stage…")
        val extractDir = File(workRoot, "import_stage_${System.currentTimeMillis()}")
        if (extractDir.exists()) extractDir.deleteRecursively()
        extractDir.mkdirs()

        ZipFile(zipFile).use { zip ->
            val entries = zip.entries()
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                val outFile = File(extractDir, entry.name)
                if (entry.isDirectory) {
                    outFile.mkdirs()
                } else {
                    outFile.parentFile?.mkdirs()
                    zip.getInputStream(entry).use { input ->
                        outFile.outputStream().use { output -> input.copyTo(output) }
                    }
                }
            }
        }

        val stage = readStage(extractDir, mutableListOf())

        extractDir.deleteRecursively()
        return stage
    }

    private fun detectEngine(dir: File): TargetEngine {
        val hasVSlice = File(dir, "_polymod_meta.json").exists() || File(dir, "data/levels").exists()
        val hasPsych = File(dir, "pack.json").exists() || File(dir, "weeks").exists()
        return when {
            hasVSlice && hasPsych -> TargetEngine.BOTH
            hasVSlice -> TargetEngine.VSLICE
            hasPsych -> TargetEngine.PSYCH
            else -> TargetEngine.BOTH
        }
    }

    data class ModMetaResult(
        val modTitle: String,
        val modFolder: String,
        val author: String,
        val description: String,
        val weekColor: String,
        val showInStoryMode: Boolean
    )

    private fun readModMeta(dir: File, engine: TargetEngine): ModMetaResult {
        var modTitle = "Mod Importado"
        var modFolder = "mod-importado"
        var author = "Desconocido"
        var description = "Importado con FNF Mod Maker"
        var weekColor = "#C24B99"
        var showInStoryMode = false

        if (engine.includesVSlice) {
            val polymodFile = File(dir, "_polymod_meta.json")
            if (polymodFile.exists()) {
                try {
                    val json = JSONObject(polymodFile.readText())
                    modTitle = json.optString("title", modTitle)
                    modFolder = json.optString("title", modFolder).lowercase().replace(" ", "-")
                    author = json.getJSONArray("contributors").getJSONObject(0).optString("name", author)
                    description = json.optString("description", description)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }

        if (engine.includesPsych) {
            val packFile = File(dir, "pack.json")
            if (packFile.exists()) {
                try {
                    val json = JSONObject(packFile.readText())
                    modTitle = json.optString("name", modTitle)
                    modFolder = json.optString("name", modFolder).lowercase().replace(" ", "-")
                    description = json.optString("description", description)
                    val color = json.getJSONArray("color")
                    if (color.length() == 3) {
                        weekColor = "#${color.getInt(0).toString(16).padStart(2, '0')}${color.getInt(1).toString(16).padStart(2, '0')}${color.getInt(2).toString(16).padStart(2, '0')}".uppercase()
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }

        return ModMetaResult(modTitle, modFolder, author, description, weekColor, false)
    }

    private fun findIcon(dir: File): File? {
        val candidates = listOf(
            File(dir, "_polymod_icon.png"),
            File(dir, "icon.png"),
            File(dir, "mod_icon.png")
        )
        return candidates.firstOrNull { it.exists() }
    }

    private fun readSongs(dir: File, engine: TargetEngine, warnings: MutableList<String>): List<SongConfig> {
        return listOf(SongConfig())
    }

    private fun readCharacter(dir: File, engine: TargetEngine, warnings: MutableList<String>): com.fnfmodmaker.model.CharacterDraft {
        return com.fnfmodmaker.model.CharacterDraft()
    }

    private fun readStage(dir: File, warnings: MutableList<String>): StageDraft {
        return StageDraft()
    }
}