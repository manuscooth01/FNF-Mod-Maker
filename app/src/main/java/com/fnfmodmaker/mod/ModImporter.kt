package com.fnfmodmaker.mod

import android.content.Context
import android.net.Uri
import com.fnfmodmaker.media.MediaTools
import com.fnfmodmaker.model.ModConfig
import com.fnfmodmaker.model.SongConfig
import com.fnfmodmaker.model.StageDraft
import com.fnfmodmaker.model.TargetEngine
import java.io.File
import java.util.zip.ZipFile
import java.util.zip.ZipEntry

/**
 * Importa un mod existente desde un ZIP y reconstruye el ModConfig
 * para poder editarlo en el wizard.
 * Implementación simple que solo extrae archivos sin parsear JSON.
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

        onProgress("Procesando mod…")
        val modTitle = "Mod Importado"
        val modFolder = "mod-${System.currentTimeMillis() % 100000}"

        val config = ModConfig(
            engine = TargetEngine.BOTH,
            modTitle = modTitle,
            modId = modFolder,
            author = "Importado",
            description = "Mod importado desde ZIP",
            songs = listOf(SongConfig(
                songName = "Canción 1",
                artist = "Desconocido",
                bpm = "150",
                speed = "2.0",
                player = "bf",
                girlfriend = "gf",
                opponent = "dad",
                stageVSlice = "mainStage",
                stagePsych = "stage",
                weekColor = "#C24B99",
                difficulties = setOf("easy", "normal", "hard"),
                practiceNotes = true,
                offsetMs = "0",
                instrumental = null,
                vocalsPlayer = null,
                vocalsOpponent = null
            )),
            weekColor = "#C24B99",
            showInStoryMode = false,
            character = com.fnfmodmaker.model.CharacterDraft(),
            stageDraft = com.fnfmodmaker.model.StageDraft(),
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

        val stage = StageDraft()

        extractDir.deleteRecursively()
        return stage
    }
}