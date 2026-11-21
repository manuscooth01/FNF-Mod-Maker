package com.fnfmodmaker.ui

import android.app.Application
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.fnfmodmaker.media.MediaTools
import com.fnfmodmaker.model.AudioSlot
import com.fnfmodmaker.model.CharacterDraft
import com.fnfmodmaker.model.CharacterSide
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

/** Pantalla en la que está el usuario: inicio, asistente o una herramienta suelta. */
enum class Tool { HOME, MOD, CHARACTER, AUDIO }

class WizardViewModel(application: Application) : AndroidViewModel(application) {

    var tool by mutableStateOf(Tool.HOME)
        private set

    /** ¿Qué falta para poder exportar el mod completo? */
    val missingForExport: List<String>
        get() = buildList {
            if (config.modTitle.isBlank()) add("Nombre del mod")
            if (config.songName.isBlank()) add("Nombre de la canción")
            if (config.bpmValue !in 1..400) add("BPM válido")
            if (config.difficulties.isEmpty()) add("Al menos una dificultad")
            if (config.instrumental == null) add("La instrumental")
        }

    val exportReady: Boolean get() = missingForExport.isEmpty()

    /** Personaje suelto: mismo formulario, pero sin depender del resto del mod. */
    val characterOnlyReady: Boolean
        get() = config.character.copy(enabled = true).isReady

    val characterZipName: String
        get() = "${config.character.charFolder}-personaje.zip"

    // ---------------- Herramientas sueltas ----------------

    var toolAudio by mutableStateOf<PickedAudio?>(null)
        private set

    var toolAudioSaved by mutableStateOf<String?>(null)
        private set

    var characterSaved by mutableStateOf<String?>(null)
        private set

    val toolAudioFileName: String
        get() {
            val base = toolAudio?.displayName?.substringBeforeLast('.') ?: "audio"
            return (slugify(base).ifEmpty { "audio" }) + ".ogg"
        }

    fun openTool(target: Tool) {
        if (busy) return
        error = null
        status = ""
        if (target == Tool.CHARACTER) {
            updateCharacter { it.copy(enabled = true) }
            characterSaved = null
        }
        if (target == Tool.AUDIO) toolAudioSaved = null
        tool = target
    }

    fun goHome() {
        if (busy) return
        error = null
        status = ""
        tool = Tool.HOME
    }

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
            2 -> config.character.isReady
            3 -> config.instrumental != null
            else -> false
        }

    val zipFileName: String
        get() = "${config.modFolder}.zip"

    // ---------------- Navegación ----------------

    fun next() {
        if (step < 4 && canAdvance) {
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
        if (target in 0..4) {
            step = target
            error = null
        }
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
        // Estos caracteres no pueden formar parte de una ruta de archivo:
        // si se colasen, la carpeta de la canción quedaría rota.
        val clean = value.filterNot { it in "/\\|:*?\"<>" }
        config = config.copy(songName = clean)
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

    fun setOffsetMs(value: String) {
        val filtered = value.filter { it.isDigit() || it == '-' }.take(6)
        val clean = if (filtered.startsWith("-")) "-" + filtered.drop(1).filter { it.isDigit() } else filtered
        config = config.copy(offsetMs = clean)
    }

    fun setShowInStoryMode(enabled: Boolean) {
        config = config.copy(showInStoryMode = enabled)
    }

    fun toggleDifficulty(difficulty: String) {
        val current = config.difficulties
        val updated = if (difficulty in current) current - difficulty else current + difficulty
        config = config.copy(difficulties = updated)
    }

    fun setPracticeNotes(enabled: Boolean) {
        config = config.copy(practiceNotes = enabled)
    }

    // ---------------- Personaje propio ----------------

    private fun updateCharacter(block: (CharacterDraft) -> CharacterDraft) {
        config = config.copy(character = block(config.character))
    }

    fun setCharacterEnabled(enabled: Boolean) = updateCharacter { it.copy(enabled = enabled) }

    fun setCharId(value: String) = updateCharacter { it.copy(charId = slugify(value)) }

    fun setCharName(value: String) = updateCharacter { it.copy(displayName = value) }

    fun setCharSide(side: CharacterSide) = updateCharacter { it.copy(side = side) }

    fun setCharFrameWidth(value: String) =
        updateCharacter { it.copy(frameWidth = value.filter { c -> c.isDigit() }.take(4)) }

    fun setCharFrameHeight(value: String) =
        updateCharacter { it.copy(frameHeight = value.filter { c -> c.isDigit() }.take(4)) }

    fun setCharScale(value: String) =
        updateCharacter { it.copy(scale = value.filter { c -> c.isDigit() || c == '.' || c == ',' }.replace(',', '.').take(5)) }

    fun setCharFlipX(enabled: Boolean) = updateCharacter { it.copy(flipX = enabled) }

    fun setCharIdleFrames(value: String) =
        updateCharacter { it.copy(idleFrames = value.filter { c -> c.isDigit() }.take(2)) }

    fun setCharLeftFrames(value: String) =
        updateCharacter { it.copy(leftFrames = value.filter { c -> c.isDigit() }.take(2)) }

    fun setCharDownFrames(value: String) =
        updateCharacter { it.copy(downFrames = value.filter { c -> c.isDigit() }.take(2)) }

    fun setCharUpFrames(value: String) =
        updateCharacter { it.copy(upFrames = value.filter { c -> c.isDigit() }.take(2)) }

    fun setCharRightFrames(value: String) =
        updateCharacter { it.copy(rightFrames = value.filter { c -> c.isDigit() }.take(2)) }

    // ---------------- Archivos ----------------

    fun pickAudio(uri: Uri, slot: AudioSlot) {
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
                config = when (slot) {
                    AudioSlot.INSTRUMENTAL -> config.copy(instrumental = picked)
                    AudioSlot.VOICES_PLAYER -> config.copy(vocalsPlayer = picked)
                    AudioSlot.VOICES_OPPONENT -> config.copy(vocalsOpponent = picked)
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

    fun pickCharacterSheet(uri: Uri) {
        if (busy) return
        val app = getApplication<Application>()
        busy = true
        error = null
        status = "Copiando hoja de sprites…"
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val displayName = MediaTools.displayName(app, uri)
                    val safeName = displayName.replace(Regex("[^A-Za-z0-9._-]"), "_").ifBlank { "spritesheet.png" }
                    val target = File(app.cacheDir, "inputs/${System.currentTimeMillis()}_$safeName")
                    MediaTools.copyTo(app, uri, target)
                    val size = MediaTools.imageSize(target)
                        ?: throw IllegalStateException("No se pudo leer la imagen de la hoja de sprites.")
                    PickedImage(uri = uri, displayName = displayName, path = target.absolutePath) to size
                }
            }
            result.onSuccess { (picked, size) ->
                updateCharacter {
                    it.copy(sheet = picked, sheetWidth = size.first, sheetHeight = size.second)
                }
            }.onFailure {
                error = it.message ?: "No se pudo leer la hoja de sprites."
            }
            status = ""
            busy = false
        }
    }

    fun pickCharacterIcon(uri: Uri) {
        if (busy) return
        val app = getApplication<Application>()
        busy = true
        error = null
        status = "Copiando icono…"
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
                updateCharacter { it.copy(icon = picked) }
            }.onFailure {
                error = it.message ?: "No se pudo leer la imagen del icono."
            }
            status = ""
            busy = false
        }
    }

    fun pickToolAudio(uri: Uri) {
        if (busy) return
        val app = getApplication<Application>()
        busy = true
        error = null
        toolAudioSaved = null
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
            result.onSuccess { picked -> toolAudio = picked }
                .onFailure { error = it.message ?: "No se pudo leer el archivo elegido." }
            status = ""
            busy = false
        }
    }

    /** Convierte el audio de la herramienta suelta y lo guarda donde elija el usuario. */
    fun convertToolAudio(uri: Uri) {
        if (busy) return
        val source = toolAudio ?: return
        val app = getApplication<Application>()
        busy = true
        error = null
        toolAudioSaved = null
        status = "Convirtiendo a OGG…"
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val input = File(source.path)
                    val temp = File(app.cacheDir, "tool/out.ogg")
                    temp.parentFile?.mkdirs()
                    if (source.isOgg) {
                        input.copyTo(temp, overwrite = true)
                    } else {
                        val failure = MediaTools.convertToOgg(input, temp)
                        if (failure != null) {
                            throw IllegalStateException("No se pudo convertir:\n$failure")
                        }
                    }
                    val stream = app.contentResolver.openOutputStream(uri)
                        ?: throw IllegalStateException("No se pudo abrir el destino elegido.")
                    stream.use { output -> temp.inputStream().use { it.copyTo(output) } }
                    MediaTools.displayName(app, uri).ifBlank { toolAudioFileName }
                }
            }
            result.onSuccess { name ->
                toolAudioSaved = name
                status = "¡Listo!"
            }.onFailure {
                status = ""
                error = it.message ?: it.toString()
            }
            busy = false
        }
    }

    /** Crea un ZIP solo con el personaje (XML, JSON de los dos motores e icono). */
    fun exportCharacter(uri: Uri) {
        if ((busy) || !characterOnlyReady) return
        val app = getApplication<Application>()
        busy = true
        error = null
        characterSaved = null
        status = "Preparando el personaje…"
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val draft = config.character.copy(enabled = true)
                    val workRoot = File(app.cacheDir, "chartool")
                    if (workRoot.exists()) workRoot.deleteRecursively()
                    val root = File(workRoot, draft.charFolder)
                    if (!root.mkdirs()) throw IllegalStateException("No se pudo crear la carpeta de trabajo.")
                    val tempConfig = ModConfig(engine = TargetEngine.BOTH)
                        .copy(character = draft)
                    val warningsList = mutableListOf<String>()
                    com.fnfmodmaker.mod.CharacterBuilder.build(
                        root = root,
                        config = tempConfig,
                        onProgress = { message -> status = message },
                        warnings = warningsList
                    )
                    File(root, "LEEME.txt").writeText(characterReadme(draft.charFolder), Charsets.UTF_8)
                    val stream = app.contentResolver.openOutputStream(uri)
                        ?: throw IllegalStateException("No se pudo abrir el destino elegido.")
                    stream.use { ModBuilder.zip(root, it) }
                    MediaTools.displayName(app, uri).ifBlank { characterZipName } to warningsList
                }
            }
            result.onSuccess { (name, list) ->
                warnings = list
                characterSaved = name
                status = "¡Personaje listo!"
            }.onFailure {
                status = ""
                error = it.message ?: it.toString()
            }
            busy = false
        }
    }

    private fun characterReadme(id: String): String = buildString {
        appendLine("PERSONAJE: $id")
        appendLine()
        appendLine("Este ZIP NO es un mod: son los archivos de un personaje para meterlos")
        appendLine("dentro de un mod que ya tengas (o en el que crees con esta misma app).")
        appendLine()
        appendLine("CÓMO USARLO")
        appendLine("1. Descomprime el ZIP dentro de la carpeta de tu mod")
        appendLine("   (la que está dentro de mods/). Debe quedar así:")
        appendLine("     characters/$id.json            (Psych)")
        appendLine("     data/characters/$id.json       (V-Slice)")
        appendLine("     images/characters/$id.png")
        appendLine("     images/characters/$id.xml")
        appendLine("     images/icons/icon-$id.png")
        appendLine("2. Dile al chart que use el personaje \"$id\":")
        appendLine("   - Psych: en el chart, player1 (si es el jugador) o player2 (si es el rival).")
        appendLine("   - V-Slice: en la metadata, playData.characters.player u .opponent.")
        appendLine()
        appendLine("Si una animación sale mal, revisa el orden de los frames de tu hoja:")
        appendLine("idle, singLEFT, singDOWN, singUP, singRIGHT.")
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
                    // Evita que se acumulen los audios de exportaciones anteriores.
                    if (workRoot.exists()) workRoot.deleteRecursively()
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
