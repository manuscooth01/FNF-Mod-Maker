package com.fnfmodmaker.ui

import android.app.Application
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.fnfmodmaker.media.MediaTools
import com.fnfmodmaker.model.ModConfig
import com.fnfmodmaker.model.PickedAudio
import com.fnfmodmaker.model.PickedImage
import com.fnfmodmaker.model.TargetEngine
import com.fnfmodmaker.model.slugify
import com.fnfmodmaker.mod.ModBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class WizardViewModel(application: Application) : AndroidViewModel(application) {

    var config by mutableStateOf(ModConfig())
        private set

    var step by mutableStateOf(0)
        private set

    var busy by mutableStateOf(false)
        private set

    var status by mutableStateOf("")
        private set

    var error by mutableStateOf<String?>(null)
        private set

    var warnings by mutableStateOf<List<String>>(emptyList())
        private set

    var savedName by mutableStateOf<String?>(null)
        private set

    var builtFiles by mutableStateOf<List<String>>(emptyList())
        private set

    private var modIdEdited = false

    val canAdvance: Boolean
        get() = when (step) {
            0 -> config.modTitle.isNotBlank()
            1 -> config.songName.isNotBlank() &&
                config.bpmValue in 1..400 &&
                config.speedValue > 0f &&
                config.difficulties.isNotEmpty()
            2 -> config.instrumental != null
            else -> false
        }

    val zipFileName: String
        get() = "${config.modFolder}.zip"

    // ---------------- Navegación ----------------

    fun next() {
        if (step < 3 && canAdvance) {
            step += 1
            error = null
        }
    }

    fun back() {
        if (step > 0) {
            step -= 1
            error = null
        }
    }

    fun goTo(target: Int) {
        if (target in 0..3 && target <= step) step = target
    }

    fun reset() {
        config = ModConfig()
        modIdEdited = false
        step = 0
        busy = false
        status = ""
        error = null
        warnings = emptyList()
        savedName = null
        builtFiles = emptyList()
    }

    // ---------------- Datos del mod ----------------

    fun setEngine(engine: TargetEngine) {
        config = config.copy(engine = engine)
    }

    fun setModTitle(value: String) {
        config = if (modIdEdited) {
            config.copy(modTitle = value)
        } else {
            config.copy(modTitle = value, modId = slugify(value))
        }
    }

    fun setModId(value: String) {
        modIdEdited = true
        config = config.copy(modId = value)
    }

    fun setAuthor(value: String) {
        config = config.copy(author = value)
    }

    fun setDescription(value: String) {
        config = config.copy(description = value)
    }

    // ---------------- Canción ----------------

    fun setSongName(value: String) {
        config = config.copy(songName = value)
    }

    fun setArtist(value: String) {
        config = config.copy(artist = value)
    }

    fun setBpm(value: String) {
        config = config.copy(bpm = value.filter { it.isDigit() }.take(3))
    }

    fun setSpeed(value: String) {
        val filtered = value.filter { it.isDigit() || it == '.' || it == ',' }.replace(',', '.').take(5)
        config = config.copy(speed = filtered)
    }

    fun setPlayer(value: String) {
        config = config.copy(player = value)
    }

    fun setGirlfriend(value: String) {
        config = config.copy(girlfriend = value)
    }

    fun setOpponent(value: String) {
        config = config.copy(opponent = value)
    }

    fun setStageVSlice(value: String) {
        config = config.copy(stageVSlice = value)
    }

    fun setStagePsych(value: String) {
        config = config.copy(stagePsych = value)
    }

    fun setWeekColor(value: String) {
        config = config.copy(weekColor = value)
    }

    fun toggleDifficulty(difficulty: String) {
        val current = config.difficulties
        val updated = if (difficulty in current) current - difficulty else current + difficulty
        config = config.copy(difficulties = updated)
    }

    fun setPracticeNotes(enabled: Boolean) {
        config = config.copy(practiceNotes = enabled)
    }

    // ---------------- Archivos ----------------

    fun pickAudio(uri: Uri, instrumental: Boolean) {
        if (busy) return
        val app = getApplication<Application>()
        busy = true
        error = null
        status = "Copiando archivo…"
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val displayName = MediaTools.displayName(app, uri)
                    val safeName = displayName.replace(Regex("[^A-Za-z0-9._-]"), "_").ifBlank { "audio" }
                    val target = File(app.cacheDir, "inputs/${System.currentTimeMillis()}_$safeName")
                    MediaTools.copyTo(app, uri, target)
                    PickedAudio(
                        uri = uri,
                        displayName = displayName,
                        path = target.absolutePath,
                        durationMs = MediaTools.readDurationMs(target.absolutePath)
                    )
                }
            }
            result.onSuccess { picked ->
                config = if (instrumental) {
                    config.copy(instrumental = picked)
                } else {
                    config.copy(vocals = picked)
                }
                if (picked.durationMs <= 0L) {
                    warnings = warnings + "No se pudo leer la duración de \"${picked.displayName}\". El chart se generará corto; ajusta el BPM o usa otro archivo."
                }
            }.onFailure {
                error = it.message ?: "No se pudo leer el archivo elegido."
            }
            status = ""
            busy = false
        }
    }

    fun pickIcon(uri: Uri) {
        if (busy) return
        val app = getApplication<Application>()
        busy = true
        error = null
        status = "Copiando imagen…"
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val displayName = MediaTools.displayName(app, uri)
                    val safeName = displayName.replace(Regex("[^A-Za-z0-9._-]"), "_").ifBlank { "icono.png" }
                    val target = File(app.cacheDir, "inputs/${System.currentTimeMillis()}_$safeName")
                    MediaTools.copyTo(app, uri, target)
                    PickedImage(uri = uri, displayName = displayName, path = target.absolutePath)
                }
            }
            result.onSuccess { picked ->
                config = config.copy(icon = picked)
            }.onFailure {
                error = it.message ?: "No se pudo leer la imagen elegida."
            }
            status = ""
            busy = false
        }
    }

    // ---------------- Exportar ----------------

    fun export(uri: Uri) {
        if (busy) return
        val app = getApplication<Application>()
        busy = true
        error = null
        savedName = null
        builtFiles = emptyList()
        warnings = emptyList()
        status = "Preparando…"

        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val workRoot = File(app.cacheDir, "modmaker")
                    val built = ModBuilder.build(app, config, workRoot) { message -> status = message }
                    val stream = app.contentResolver.openOutputStream(uri)
                        ?: throw IllegalStateException("No se pudo abrir el destino elegido.")
                    stream.use { ModBuilder.zip(built.rootDir, it) }
                    built
                }
            }
            result.onSuccess { built ->
                builtFiles = built.files
                warnings = built.warnings
                status = "¡Mod creado!"
                savedName = MediaTools.displayName(app, uri).let {
                    if (it.isBlank() || it == "archivo") zipFileName else it
                }
            }.onFailure {
                status = ""
                error = it.message ?: it.toString()
            }
            busy = false
        }
    }
}
