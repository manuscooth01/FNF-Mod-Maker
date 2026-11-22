package com.fnfmodmaker.ui

import android.app.Application
import android.media.MediaPlayer
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.fnfmodmaker.media.MediaTools
import com.fnfmodmaker.model.AudioSlot
import com.fnfmodmaker.model.ChartDraft
import com.fnfmodmaker.model.ChartEditorState
import com.fnfmodmaker.model.ChartNote
import com.fnfmodmaker.model.CharacterDraft
import com.fnfmodmaker.model.CharacterSide
import com.fnfmodmaker.model.ModConfig
import com.fnfmodmaker.model.PickedAudio
import com.fnfmodmaker.model.PickedImage
import com.fnfmodmaker.model.SongConfig
import com.fnfmodmaker.model.TargetEngine
import com.fnfmodmaker.model.slugify
import com.fnfmodmaker.mod.ModBuilder
import com.fnfmodmaker.mod.ModImporter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Pantalla en la que está el usuario: inicio, asistente o una herramienta suelta. */
enum class Tool { HOME, MOD, CHARACTER, AUDIO, IMPORT, STAGE }

class WizardViewModel(application: Application) : AndroidViewModel(application) {

    var tool by mutableStateOf(Tool.HOME)
        private set

    /** Índice de la canción que se está editando en el asistente. */
    var activeSongIndex by mutableStateOf(0)
        private set

    /** ¿Qué falta para poder exportar el mod completo? */
    val missingForExport: List<String>
        get() = buildList {
            if (config.modTitle.isBlank()) add("Nombre del mod")
            if (config.songs.isEmpty()) add("Al menos una canción")
            config.songs.forEachIndexed { index, song ->
                if (song.songName.isBlank()) add("Nombre de la canción ${index + 1}")
                if (song.bpmValue !in 1..400) add("BPM válido en la canción ${index + 1}")
                if (song.difficulties.isEmpty()) add("Al menos una dificultad en la canción ${index + 1}")
                if (song.instrumental == null) add("La instrumental de la canción ${index + 1}")
            }
        }

    val exportReady: Boolean get() = missingForExport.isEmpty()

    /** Personaje suelto: mismo formulario, pero sin depender del resto del mod. */
    val characterOnlyReady: Boolean
        get() = config.character.copy(enabled = true).isReady

    val characterZipName: String
        get() = "${config.character.charFolder}-personaje.zip"

    /** Canción que se está editando actualmente. */
    val activeSong: SongConfig
        get() = config.songs.getOrElse(activeSongIndex) { SongConfig() }

    /** Índice de la canción que se está charteando. */
    var activeChartSongIndex by mutableStateOf(0)
        private set

    /** Dificultad que se está charteando. */
    var activeChartDifficulty by mutableStateOf("normal")
        private set

    /** Estado del editor de chart. */
    var chartEditorState by mutableStateOf(ChartEditorState())
        private set

    /** Chart actual que se está editando (crea uno nuevo basado en notas de práctica si no existe). */
    val activeChartDraft: ChartDraft
        get() {
            val song = config.songs.getOrElse(activeChartSongIndex) { return ChartDraft() }
            val difficulty = activeChartDifficulty
            return config.getChart(song.songFolder, difficulty)
                ?: ChartDraft(
                    songFolder = song.songFolder,
                    difficulty = difficulty,
                    scrollSpeed = song.speedValue,
                    bpm = song.bpmValue,
                    offsetMs = song.offsetValue,
                    needsVoices = song.hasVocals,
                    player1 = song.player,
                    player2 = song.opponent,
                    gfVersion = song.girlfriend,
                    stage = song.stagePsych
                )
        }

    // ---------------- Navegación de canciones ----------------

    fun addSong() {
        if (busy) return
        config = config.copy(songs = config.songs + SongConfig())
        activeSongIndex = config.songs.lastIndex
    }

    fun removeSong(index: Int) {
        if (busy || config.songs.size <= 1) return
        val updated = config.songs.toMutableList()
        updated.removeAt(index)
        if (activeSongIndex >= updated.size) activeSongIndex = updated.lastIndex
        config = config.copy(songs = updated)
    }

    fun selectSong(index: Int) {
        if (busy) return
        activeSongIndex = index.coerceIn(0, config.songs.lastIndex)
    }

    // ---------------- Navegación de chart ----------------

    fun selectChartSong(index: Int) {
        if (busy) return
        releaseMediaPlayer()
        activeChartSongIndex = index.coerceIn(0, config.songs.lastIndex)
        val song = config.songs[activeChartSongIndex]
        activeChartDifficulty = song.orderedDifficulties.firstOrNull() ?: "normal"
        updateChartEditorState()
        initChartHistory()
    }

    fun selectChartDifficulty(difficulty: String) {
        if (busy) return
        // No liberar MediaPlayer al cambiar dificultad de la misma canción
        activeChartDifficulty = difficulty
        updateChartEditorState()
        initChartHistory()
    }

    private fun updateChartEditorState() {
        releaseMediaPlayer()
        val song = config.songs.getOrElse(activeChartSongIndex) { return }
        chartEditorState = ChartEditorState(
            songFolder = song.songFolder,
            currentDifficulty = activeChartDifficulty,
            isPlaying = false,
            currentTimeMs = 0L
        )
    }

    // ---------------- Edición de notas ----------------

    /** Agrega o actualiza una nota en el chart activo. */
    fun addChartNote(note: com.fnfmodmaker.model.ChartNote) {
        val draft = activeChartDraft
        val updatedNotes = draft.notes.toMutableList()
        // Buscar si ya hay nota en misma posición (mismo timeMs y lane)
        val existingIndex = updatedNotes.indexOfFirst { it.timeMs == note.timeMs && it.lane == note.lane }
        if (existingIndex >= 0) {
            updatedNotes[existingIndex] = note
        } else {
            updatedNotes.add(note)
        }
        val updatedDraft = draft.copy(notes = updatedNotes.sortedBy { it.timeMs })
        pushHistoryAndSave(updatedDraft)
    }

    /** Elimina una nota del chart activo. */
    fun removeChartNote(timeMs: Long, lane: Int) {
        val draft = activeChartDraft
        val updatedNotes = draft.notes.filterNot { it.timeMs == timeMs && it.lane == lane }
        val updatedDraft = draft.copy(notes = updatedNotes)
        pushHistoryAndSave(updatedDraft)
    }

    /** Genera notas de práctica automáticas para el chart activo. */
    fun generatePracticeNotes() {
        val song = config.songs.getOrElse(activeChartSongIndex) { return }
        val difficulty = activeChartDifficulty
        val bpm = song.bpmValue
        if (bpm <= 0) return
        val instrumental = song.instrumental
        val durationMs = instrumental?.durationMs ?: 120000L // 2 min default
        val beatMs = 60000.0 / bpm
        val beats = (durationMs / beatMs).toInt().coerceIn(8..2000)
        val notes = mutableListOf<com.fnfmodmaker.model.ChartNote>()
        for (beat in 0 until beats) {
            val isPlayerSide = beat % 2 == 0
            val lane = beat % 4
            val data = if (isPlayerSide) lane else lane + 4
            notes.add(com.fnfmodmaker.model.ChartNote(
                timeMs = (beat * beatMs).roundToLong(),
                lane = data,
                lengthMs = 0L,
                isSustain = false
            ))
        }
        val draft = com.fnfmodmaker.model.ChartDraft(
            songFolder = song.songFolder,
            difficulty = difficulty,
            notes = notes,
            scrollSpeed = song.speedValue,
            bpm = bpm,
            offsetMs = song.offsetValue,
            needsVoices = song.hasVocals,
            player1 = song.player,
            player2 = song.opponent,
            gfVersion = song.girlfriend,
            stage = song.stagePsych
        )
        pushHistoryAndSave(draft)
    }

    /** Limpia el chart activo. */
    fun clearChart() {
        val song = config.songs.getOrElse(activeChartSongIndex) { return }
        val difficulty = activeChartDifficulty
        val draft = com.fnfmodmaker.model.ChartDraft(
            songFolder = song.songFolder,
            difficulty = difficulty,
            notes = emptyList(),
            scrollSpeed = song.speedValue,
            bpm = song.bpmValue,
            offsetMs = song.offsetValue,
            needsVoices = song.hasVocals,
            player1 = song.player,
            player2 = song.opponent,
            gfVersion = song.girlfriend,
            stage = song.stagePsych
        )
        pushHistoryAndSave(draft)
    }

    /** Actualiza el tiempo de reproducción actual. */
    fun updatePlaybackTime(timeMs: Long) {
        chartEditorState = chartEditorState.copy(currentTimeMs = timeMs)
    }

    /** Actualiza zoom y scroll del editor. */
    fun updateChartView(zoom: Float, scrollX: Float) {
        chartEditorState = chartEditorState.copy(zoom = zoom, scrollX = scrollX)
    }

    /** Actualiza la resolución de snap (grid). */
    fun updateSnapResolution(resolution: com.fnfmodmaker.model.SnapResolution) {
        chartEditorState = chartEditorState.copy(snapResolution = resolution)
    }

    // ---------------- Eventos y Secciones ----------------

    /** Agrega o actualiza un evento en el chart activo. */
    fun addChartEvent(event: com.fnfmodmaker.model.ChartEvent) {
        val draft = activeChartDraft
        val updatedEvents = draft.events.toMutableList()
        val existingIndex = updatedEvents.indexOfFirst { it.timeMs == event.timeMs && it.type == event.type }
        if (existingIndex >= 0) {
            updatedEvents[existingIndex] = event
        } else {
            updatedEvents.add(event)
        }
        val updatedDraft = draft.copy(events = updatedEvents.sortedBy { it.timeMs })
        pushHistoryAndSave(updatedDraft)
    }

    /** Elimina un evento del chart activo. */
    fun removeChartEvent(timeMs: Long, type: com.fnfmodmaker.model.ChartEvent.EventType) {
        val draft = activeChartDraft
        val updatedEvents = draft.events.filterNot { it.timeMs == timeMs && it.type == type }
        val updatedDraft = draft.copy(events = updatedEvents)
        pushHistoryAndSave(updatedDraft)
    }

    /** Agrega o actualiza una sección en el chart activo. */
    fun addChartSection(section: com.fnfmodmaker.model.ChartSection) {
        val draft = activeChartDraft
        val updatedSections = draft.sections.toMutableList()
        val existingIndex = updatedSections.indexOfFirst { it.startTimeMs == section.startTimeMs }
        if (existingIndex >= 0) {
            updatedSections[existingIndex] = section
        } else {
            updatedSections.add(section)
        }
        val updatedDraft = draft.copy(sections = updatedSections.sortedBy { it.startTimeMs })
        pushHistoryAndSave(updatedDraft)
    }

    /** Elimina una sección del chart activo. */
    fun removeChartSection(startTimeMs: Long) {
        val draft = activeChartDraft
        val updatedSections = draft.sections.filterNot { it.startTimeMs == startTimeMs }
        val updatedDraft = draft.copy(sections = updatedSections)
        pushHistoryAndSave(updatedDraft)
    }

    // ---------------- Reproducción de Audio (MediaPlayer) ----------------

    private var mediaPlayer: MediaPlayer? = null
    private var playbackJob: kotlinx.coroutines.Job? = null

    /** Inicializa MediaPlayer con la instrumental de la canción activa. */
    private fun initMediaPlayer(): MediaPlayer? {
        val song = config.songs.getOrElse(activeChartSongIndex) { return null }
        val instrumental = song.instrumental ?: return null
        val mp = MediaPlayer().apply {
            try {
                setDataSource(applicationContext, Uri.parse(instrumental.path))
                prepare()
                setOnCompletionListener { onPlaybackComplete() }
            } catch (e: Exception) {
                e.printStackTrace()
                release()
                return@apply null
}
        }
    }

    // ---------------- Importar Mod ----------------

    /** Importa un mod desde un ZIP y carga el config en el wizard. */
    fun importMod(uri: Uri) {
        if (busy) return
        val app = getApplication<Application>()
        busy = true
        error = null
        status = "Importando mod…"
        warnings = emptyList()
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val workRoot = File(app.cacheDir, "modimport")
                    if (workRoot.exists()) workRoot.deleteRecursively()
                    workRoot.mkdirs()
                    // Copiar ZIP a archivo temporal
                    val tempZip = File(workRoot, "import_${System.currentTimeMillis()}.zip")
                    app.contentResolver.openInputStream(uri)?.use { input ->
                        tempZip.outputStream().use { output -> input.copyTo(output) }
                    }
                    val imported = ModImporter.importFromZip(app, tempZip, workRoot) { message -> status = message }
                    imported
                }
            }
            result.onSuccess { imported ->
                config = imported.config
                warnings = imported.warnings
                // Ir al wizard (paso 0 = Mod)
                tool = Tool.MOD
                step = 0
                activeSongIndex = 0
                activeChartSongIndex = 0
                activeChartDifficulty = config.songs.firstOrNull()?.orderedDifficulties?.firstOrNull() ?: "normal"
                initChartHistory()
                status = "¡Mod importado! Revisa los datos y edita lo que necesites."
            }.onFailure {
                status = ""
                error = it.message ?: "No se pudo importar el mod: ${it}"
            }
            busy = false
        }
    }
}

    /** Inicia o reanuda la reproducción. */
    fun startPlayback() {
        if (mediaPlayer == null) {
            mediaPlayer = initMediaPlayer() ?: return
        }
        val mp = mediaPlayer!!
        if (!mp.isPlaying) {
            val currentPos = chartEditorState.currentTimeMs.toInt()
            if (currentPos > 0 && currentPos < mp.duration) {
                mp.seekTo(currentPos)
            }
            mp.start()
            chartEditorState = chartEditorState.copy(isPlaying = true)
            startPlaybackPolling()
        }
    }

    /** Pausa la reproducción. */
    fun pausePlayback() {
        mediaPlayer?.pause()
        chartEditorState = chartEditorState.copy(isPlaying = false)
        playbackJob?.cancel()
    }

    /** Alterna play/pausa. */
    fun togglePlayback() {
        if (chartEditorState.isPlaying) {
            pausePlayback()
        } else {
            startPlayback()
        }
    }

    /** Polling para actualizar currentTimeMs desde MediaPlayer. */
    private fun startPlaybackPolling() {
        playbackJob?.cancel()
        playbackJob = viewModelScope.launch {
            while (mediaPlayer?.isPlaying == true) {
                val currentPos = mediaPlayer?.currentPosition ?: 0
                chartEditorState = chartEditorState.copy(currentTimeMs = currentPos.toLong())
                delay(16) // ~60fps
            }
        }
    }

    /** Callback al terminar reproducción. */
    private fun onPlaybackComplete() {
        chartEditorState = chartEditorState.copy(isPlaying = false, currentTimeMs = 0)
        playbackJob?.cancel()
        mediaPlayer?.seekTo(0)
    }

    /** Busca a una posición específica (ms). */
    fun seekTo(timeMs: Long) {
        mediaPlayer?.seekTo(timeMs.toInt())
        chartEditorState = chartEditorState.copy(currentTimeMs = timeMs)
    }

    /** Limpia MediaPlayer al cambiar de canción o cerrar. */
    fun releaseMediaPlayer() {
        playbackJob?.cancel()
        mediaPlayer?.apply {
            if (isPlaying) pause()
            release()
        }
        mediaPlayer = null
    }

    /** Actualiza togglePlayback para usar MediaPlayer real. */
    // (la función togglePlayback de arriba ya llama a startPlayback/pausePlayback)

    // ---------------- Chart Editor: Selección, Drag, Undo/Redo ----------------

    /** Inicia drag de scroll (pan). */
    fun startScrollDrag(startX: Float, startScroll: Float) {
        chartEditorState = chartEditorState.copy(
            dragState = com.fnfmodmaker.model.ChartEditorState.DragState.Scroll(startX, startScroll)
        )
    }

    /** Actualiza drag de scroll. */
    fun updateScrollDrag(currentX: Float) {
        val state = chartEditorState.dragState
        if (state is com.fnfmodmaker.model.ChartEditorState.DragState.Scroll) {
            val newScroll = (state.startScroll - (currentX - state.startX)).coerceIn(0f, Float.MAX_VALUE)
            chartEditorState = chartEditorState.copy(scrollX = newScroll)
        }
    }

    /** Termina drag de scroll. */
    fun endScrollDrag() {
        chartEditorState = chartEditorState.copy(dragState = null)
    }

    /** Inicia drag de resize hold note. */
    fun startHoldResize(noteIndex: Int, startTimeMs: Long, lane: Int, startLengthMs: Long, dragStartX: Float) {
        chartEditorState = chartEditorState.copy(
            dragState = com.fnfmodmaker.model.ChartEditorState.DragState.HoldResize(
                noteIndex, startTimeMs, lane, startLengthMs, dragStartX
            )
        )
    }

    /** Actualiza drag de resize hold note. */
    fun updateHoldResize(currentX: Float, beatWidthPx: Float, beatMs: Double) {
        val state = chartEditorState.dragState
        if (state is com.fnfmodmaker.model.ChartEditorState.DragState.HoldResize) {
            val deltaX = currentX - state.dragStartX
            val deltaBeats = deltaX / beatWidthPx * 4 // 4 beats por unidad visual
            val deltaMs = (deltaBeats * beatMs).roundToLong()
            val newLength = (state.startLengthMs + deltaMs).coerceAtLeast(0L)
            
            val draft = activeChartDraft
            val updatedNotes = draft.notes.toMutableList()
            if (state.noteIndex < updatedNotes.size) {
                val note = updatedNotes[state.noteIndex]
                updatedNotes[state.noteIndex] = note.copy(lengthMs = newLength, isSustain = newLength > 0)
                val updatedDraft = draft.copy(notes = updatedNotes)
                pushHistoryAndSave(updatedDraft)
            }
        }
    }

    /** Termina drag de hold resize. */
    fun endHoldResize() {
        chartEditorState = chartEditorState.copy(dragState = null)
    }

    /** Inicia selección múltiple (box selection). */
    fun startSelectionBox(startX: Float, startY: Float) {
        chartEditorState = chartEditorState.copy(
            dragState = com.fnfmodmaker.model.ChartEditorState.DragState.SelectionBox(startX, startY),
            selectedNotes = emptyList()
        )
    }

    /** Actualiza selección múltiple. */
    fun updateSelectionBox(currentX: Float, currentY: Float, beatWidthPx: Float, beatMs: Double, laneHeightPx: Float, timeRulerHeightPx: Float, headerHeightPx: Float) {
        val state = chartEditorState.dragState
        if (state is com.fnfmodmaker.model.ChartEditorState.DragState.SelectionBox) {
            val draft = activeChartDraft
            val minX = minOf(state.startX, currentX)
            val maxX = maxOf(state.startX, currentX)
            val minY = minOf(state.startY, currentY)
            val maxY = maxOf(state.startY, currentY)

            // Convertir a tiempo y lanes
            val startTimeMs = ((minX / beatWidthPx * 4) * beatMs).roundToLong()
            val endTimeMs = ((maxX / beatWidthPx * 4) * beatMs).roundToLong()
            val startLane = ((minY - timeRulerHeightPx - headerHeightPx) / laneHeightPx).toInt().coerceIn(0, 7)
            val endLane = ((maxY - timeRulerHeightPx - headerHeightPx) / laneHeightPx).toInt().coerceIn(0, 7)

            val selected = draft.notes
                .mapIndexed { index, note ->
                    val inTime = note.timeMs >= startTimeMs && note.timeMs <= endTimeMs
                    val inLane = note.lane >= startLane && note.lane <= endLane
                    if (inTime && inLane) com.fnfmodmaker.model.SelectedNote(note.timeMs, note.lane, index) else null
                }
                .filterNotNull()
                .toList()

            chartEditorState = chartEditorState.copy(selectedNotes = selected)
        }
    }

    /** Termina selección múltiple. */
    fun endSelectionBox() {
        val state = chartEditorState.dragState
        if (state is com.fnfmodmaker.model.ChartEditorState.DragState.SelectionBox) {
            chartEditorState = chartEditorState.copy(dragState = null)
        }
    }

    /** Inicia drag de notas seleccionadas. */
    fun startMultiDrag(dragStartX: Float, dragStartY: Float) {
        val selected = chartEditorState.selectedNotes
        if (selected.isNotEmpty()) {
            chartEditorState = chartEditorState.copy(
                dragState = com.fnfmodmaker.model.ChartEditorState.DragState.MultiDrag(selected, dragStartX, dragStartY)
            )
        }
    }

    /** Actualiza drag de notas seleccionadas. */
    fun updateMultiDrag(currentX: Float, currentY: Float, beatWidthPx: Float, beatMs: Double, laneHeightPx: Float) {
        val state = chartEditorState.dragState
        if (state is com.fnfmodmaker.model.ChartEditorState.DragState.MultiDrag) {
            val deltaX = currentX - state.dragStartX
            val deltaY = currentY - state.dragStartY
            val deltaBeats = deltaX / beatWidthPx * 4
            val deltaMs = (deltaBeats * beatMs).roundToLong()
            val deltaLanes = (deltaY / laneHeightPx).roundToInt()

            val draft = activeChartDraft
            val updatedNotes = draft.notes.toMutableList()
            var changed = false

            for (sel in state.selectedNotes) {
                if (sel.noteIndex < updatedNotes.size) {
                    val note = updatedNotes[sel.noteIndex]
                    val newTime = (note.timeMs + deltaMs).coerceAtLeast(0L)
                    val newLane = (note.lane + deltaLanes).coerceIn(0, 7)
                    if (newTime != note.timeMs || newLane != note.lane) {
                        updatedNotes[sel.noteIndex] = note.copy(timeMs = newTime, lane = newLane)
                        changed = true
                    }
                }
            }

            if (changed) {
                val updatedDraft = draft.copy(notes = updatedNotes.sortedBy { it.timeMs })
                pushHistoryAndSave(updatedDraft)
            }
        }
    }

    /** Termina drag de notas seleccionadas. */
    fun endMultiDrag() {
        chartEditorState = chartEditorState.copy(dragState = null)
    }

    /** Agrega nota a selección (toggle). */
    fun toggleNoteSelection(timeMs: Long, lane: Int) {
        val draft = activeChartDraft
        val noteIndex = draft.notes.indexOfFirst { it.timeMs == timeMs && it.lane == lane }
        if (noteIndex >= 0) {
            val selected = chartEditorState.selectedNotes.toMutableList()
            val existing = selected.indexOfFirst { it.timeMs == timeMs && it.lane == lane }
            if (existing >= 0) {
                selected.removeAt(existing)
            } else {
                selected.add(com.fnfmodmaker.model.SelectedNote(timeMs, lane, noteIndex))
            }
            chartEditorState = chartEditorState.copy(selectedNotes = selected)
        }
    }

    /** Limpia selección. */
    fun clearSelection() {
        chartEditorState = chartEditorState.copy(selectedNotes = emptyList())
    }

    /** Copia notas seleccionadas al portapapeles interno. */
    private var clipboardNotes: List<com.fnfmodmaker.model.ChartNote> = emptyList()

    fun copySelectedNotes() {
        val draft = activeChartDraft
        clipboardNotes = chartEditorState.selectedNotes
            .map { sel -> draft.notes.getOrNull(sel.noteIndex) }
            .filterNotNull()
            .toList()
    }

    /** Pega notas del portapapeles en la posición actual del playhead. */
    fun pasteNotes() {
        if (clipboardNotes.isEmpty()) return
        val draft = activeChartDraft
        val pasteTime = chartEditorState.currentTimeMs
        val firstNoteTime = clipboardNotes.first().timeMs
        val offset = pasteTime - firstNoteTime

        val updatedNotes = draft.notes.toMutableList()
        for (note in clipboardNotes) {
            updatedNotes.add(note.copy(timeMs = (note.timeMs + offset).coerceAtLeast(0L)))
        }
        val updatedDraft = draft.copy(notes = updatedNotes.sortedBy { it.timeMs })
        pushHistoryAndSave(updatedDraft)
    }

    /** Guarda el estado actual en el historial para undo/redo. */
    private fun pushHistoryAndSave(draft: com.fnfmodmaker.model.ChartDraft) {
        val newHistory = chartEditorState.history.take(chartEditorState.historyIndex + 1) + draft
        val newIndex = newHistory.size - 1
        chartEditorState = chartEditorState.copy(history = newHistory, historyIndex = newIndex)
        saveChartDraft(draft)
    }

    /** Sobrescribe saveChartDraft para no duplicar historial cuando se llama desde UI. */
    fun saveChartDraft(chart: com.fnfmodmaker.model.ChartDraft) {
        config = config.withChart(chart.songFolder, chart.difficulty, chart)
    }

    /** Undo. */
    fun undo() {
        if (chartEditorState.canUndo) {
            val newIndex = chartEditorState.historyIndex - 1
            val prevDraft = chartEditorState.history[newIndex]
            chartEditorState = chartEditorState.copy(historyIndex = newIndex)
            saveChartDraft(prevDraft)
        }
    }

    /** Redo. */
    fun redo() {
        if (chartEditorState.canRedo) {
            val newIndex = chartEditorState.historyIndex + 1
            val nextDraft = chartEditorState.history[newIndex]
            chartEditorState = chartEditorState.copy(historyIndex = newIndex)
            saveChartDraft(nextDraft)
        }
    }

    /** Al cambiar de canción/dificultad, inicializa historial con el chart actual. */
    fun initChartHistory() {
        val draft = activeChartDraft
        chartEditorState = chartEditorState.copy(
            history = listOf(draft),
            historyIndex = 0,
            selectedNotes = emptyList(),
            dragState = null
        )
    }

    // Modificar selectChartSong y selectChartDifficulty para llamar initChartHistory
    // (ya están definidos arriba, los actualizaremos)

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
        if (target == Tool.IMPORT) {
            // Import no necesita setup previo, el file picker está en la pantalla
        }
        if (target == Tool.STAGE) {
            // Stage tool - no setup needed
        }
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
            1 -> config.songs.isNotEmpty() && activeSong.songName.isNotBlank() &&
                activeSong.bpmValue in 1..400 &&
                activeSong.speedValue > 0f &&
                activeSong.difficulties.isNotEmpty()
            2 -> config.character.isReady
            3 -> activeSong.instrumental != null
            4 -> true // Chart step siempre puede avanzar (es opcional)
            else -> false
        }

    val zipFileName: String
        get() = "${config.modFolder}.zip"

    // ---------------- Navegación ----------------

    fun next() {
        if (step < 5 && canAdvance) {
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
        if (target in 0..5) {
            step = target
            error = null
        }
    }

    fun reset() {
        config = ModConfig()
        activeSongIndex = 0
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

    fun setWeekColor(value: String) {
        config = config.copy(weekColor = value)
    }

    fun setShowInStoryMode(enabled: Boolean) {
        config = config.copy(showInStoryMode = enabled)
    }

    // ---------------- Canción activa ----------------

    fun setActiveSongName(value: String) {
        val clean = value.filterNot { it in "/\\|:*?\"<>" }
        updateActiveSong { it.copy(songName = clean) }
    }

    fun setActiveSongArtist(value: String) {
        updateActiveSong { it.copy(artist = value) }
    }

    fun setActiveSongBpm(value: String) {
        updateActiveSong { it.copy(bpm = value.filter { it.isDigit() }.take(3)) }
    }

    fun setActiveSongSpeed(value: String) {
        val filtered = value.filter { it.isDigit() || it == '.' || it == ',' }.replace(',', '.').take(5)
        updateActiveSong { it.copy(speed = filtered) }
    }

    fun setActiveSongPlayer(value: String) {
        updateActiveSong { it.copy(player = value) }
    }

    fun setActiveSongGirlfriend(value: String) {
        updateActiveSong { it.copy(girlfriend = value) }
    }

    fun setActiveSongOpponent(value: String) {
        updateActiveSong { it.copy(opponent = value) }
    }

    fun setActiveSongStageVSlice(value: String) {
        updateActiveSong { it.copy(stageVSlice = value) }
    }

    fun setActiveSongStagePsych(value: String) {
        updateActiveSong { it.copy(stagePsych = value) }
    }

    fun setActiveSongWeekColor(value: String) {
        updateActiveSong { it.copy(weekColor = value) }
    }

    fun setActiveSongOffsetMs(value: String) {
        val filtered = value.filter { it.isDigit() || it == '-' }.take(6)
        val clean = if (filtered.startsWith("-")) "-" + filtered.drop(1).filter { it.isDigit() } else filtered
        updateActiveSong { it.copy(offsetMs = clean) }
    }

    fun toggleActiveSongDifficulty(difficulty: String) {
        updateActiveSong { song ->
            val current = song.difficulties
            val updated = if (difficulty in current) current - difficulty else current + difficulty
            song.copy(difficulties = updated)
        }
    }

    fun setActiveSongPracticeNotes(enabled: Boolean) {
        updateActiveSong { it.copy(practiceNotes = enabled) }
    }

    private fun updateActiveSong(block: (SongConfig) -> SongConfig) {
        val index = activeSongIndex.coerceIn(0, config.songs.lastIndex)
        val updated = config.songs.toMutableList()
        updated[index] = block(updated[index])
        config = config.copy(songs = updated)
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

    // ---------------- Archivos de la canción activa ----------------

    fun pickActiveSongAudio(uri: Uri, slot: AudioSlot) {
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
                updateActiveSong { song ->
                    when (slot) {
                        AudioSlot.INSTRUMENTAL -> song.copy(instrumental = picked)
                        AudioSlot.VOICES_PLAYER -> song.copy(vocalsPlayer = picked)
                        AudioSlot.VOICES_OPPONENT -> song.copy(vocalsOpponent = picked)
                    }
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
                    // Sin carpeta contenedora: el ZIP se descomprime dentro del mod.
                    stream.use { ModBuilder.zip(root, it, includeRoot = false) }
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

    /** Crea un ZIP solo con el stage (fondo, sprites, JSONs). */
    fun exportStage(uri: Uri) {
        if (busy) return
        val draft = config.stageDraft
        if (!draft.isReady) return
        val app = getApplication<Application>()
        busy = true
        error = null
        status = "Preparando el stage…"
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val workRoot = File(app.cacheDir, "stagetool")
                    if (workRoot.exists()) workRoot.deleteRecursively()
                    val root = File(workRoot, draft.stageFolder)
                    if (!root.mkdirs()) throw IllegalStateException("No se pudo crear la carpeta de trabajo.")
                    val tempConfig = ModConfig(engine = TargetEngine.BOTH)
                        .copy(stageDraft = draft)
                    val warningsList = mutableListOf<String>()
                    com.fnfmodmaker.mod.StageBuilder.build(
                        root = root,
                        config = tempConfig,
                        onProgress = { message -> status = message },
                        warnings = warningsList
                    )
                    File(root, "LEEME.txt").writeText(stageReadme(draft.stageFolder), Charsets.UTF_8)
                    val stream = app.contentResolver.openOutputStream(uri)
                        ?: throw IllegalStateException("No se pudo abrir el destino elegido.")
                    // Sin carpeta contenedora: el ZIP se descomprime dentro del mod.
                    stream.use { ModBuilder.zip(root, it, includeRoot = false) }
                    MediaTools.displayName(app, uri).ifBlank { "${draft.stageFolder}-stage.zip" } to warningsList
                }
            }
            result.onSuccess { (name, list) ->
                warnings = list
                status = "¡Stage listo!"
            }.onFailure {
                status = ""
                error = it.message ?: it.toString()
            }
            busy = false
        }
    }

    private fun stageReadme(id: String): String = buildString {
        appendLine("STAGE: $id")
        appendLine()
        appendLine("Este ZIP NO es un mod: son los archivos de un stage para meterlos")
        appendLine("dentro de un mod que ya tengas (o en el que crees con esta misma app).")
        appendLine()
        appendLine("CÓMO USARLO")
        appendLine("1. Descomprime el ZIP dentro de la carpeta de tu mod")
        appendLine("   (la que está dentro de mods/). Debe quedar así:")
        appendLine("     images/stages/$id/background.png")
        appendLine("     images/stages/$id/sprites/")
        appendLine("     images/stages/$id/sprites.xml")
        appendLine("     data/stages/$id.json       (V-Slice)")
        appendLine("     stages/$id.json            (Psych)")
        appendLine("2. En la canción, pon el escenario como \"$id\" (V-Slice) o usa el stage base.")
        appendLine()
        appendLine("Si el fondo no se ve, revisa que la imagen sea PNG/JPG válida.")
    }

    // ---------------- Stage: pickers y setters ----------------

    fun pickStageBackground(uri: Uri) {
        if (busy) return
        val app = getApplication<Application>()
        busy = true
        error = null
        status = "Copiando fondo…"
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val displayName = MediaTools.displayName(app, uri)
                    val safeName = displayName.replace(Regex("[^A-Za-z0-9._-]"), "_").ifBlank { "background.png" }
                    val target = File(app.cacheDir, "inputs/${System.currentTimeMillis()}_$safeName")
                    MediaTools.copyTo(app, uri, target)
                    com.fnfmodmaker.model.PickedImage(uri = uri, displayName = displayName, path = target.absolutePath)
                }
            }
            result.onSuccess { picked ->
                config = config.copy(stageDraft = config.stageDraft.copy(backgroundImage = picked))
            }.onFailure {
                error = it.message ?: "No se pudo leer la imagen."
            }
            status = ""
            busy = false
        }
    }

    fun pickStageSprite(uri: Uri) {
        // TODO: implementar selector de sprite individual
        // Por ahora solo fondo
    }

    fun setStageId(value: String) {
        config = config.copy(stageDraft = config.stageDraft.copy(stageId = slugify(value)))
    }

    fun setStageDisplayName(value: String) {
        config = config.copy(stageDraft = config.stageDraft.copy(displayName = value))
    }

    fun setStageBackgroundColor(value: String) {
        config = config.copy(stageDraft = config.stageDraft.copy(backgroundColor = value))
    }

    fun setStageCamX(value: String) {
        val v = value.toFloatOrNull() ?: 0f
        config = config.copy(stageDraft = config.stageDraft.copy(cameraPosition = config.stageDraft.cameraPosition.copy(x = v)))
    }

    fun setStageCamY(value: String) {
        val v = value.toFloatOrNull() ?: 0f
        config = config.copy(stageDraft = config.stageDraft.copy(cameraPosition = config.stageDraft.cameraPosition.copy(y = v)))
    }

    fun setStageCamZoom(value: String) {
        val v = value.toFloatOrNull() ?: 1f
        config = config.copy(stageDraft = config.stageDraft.copy(cameraPosition = config.stageDraft.cameraPosition.copy(zoom = v)))
    }

    fun setStageCamFollow(enabled: Boolean) {
        config = config.copy(stageDraft = config.stageDraft.copy(cameraPosition = config.stageDraft.cameraPosition.copy(followPlayer = enabled)))
    }

    fun setStageCamBoundLeft(value: String) {
        val v = value.toFloatOrNull() ?: -1000f
        config = config.copy(stageDraft = config.stageDraft.copy(cameraPosition = config.stageDraft.cameraPosition.copy(bounds = config.stageDraft.cameraPosition.bounds.copy(left = v))))
    }

    fun setStageCamBoundRight(value: String) {
        val v = value.toFloatOrNull() ?: 1000f
        config = config.copy(stageDraft = config.stageDraft.copy(cameraPosition = config.stageDraft.cameraPosition.copy(bounds = config.stageDraft.cameraPosition.bounds.copy(right = v))))
    }

    fun setStageCamBoundTop(value: String) {
        val v = value.toFloatOrNull() ?: -500f
        config = config.copy(stageDraft = config.stageDraft.copy(cameraPosition = config.stageDraft.cameraPosition.copy(bounds = config.stageDraft.cameraPosition.bounds.copy(top = v))))
    }

    fun setStageCamBoundBottom(value: String) {
        val v = value.toFloatOrNull() ?: 500f
        config = config.copy(stageDraft = config.stageDraft.copy(cameraPosition = config.stageDraft.cameraPosition.copy(bounds = config.stageDraft.cameraPosition.bounds.copy(bottom = v))))
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
