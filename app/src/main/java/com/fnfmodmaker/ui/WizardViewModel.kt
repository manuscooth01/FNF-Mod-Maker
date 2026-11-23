package com.fnfmodmaker.ui

import android.app.Application
import android.media.MediaPlayer
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.gson.GsonBuilder
import com.google.gson.TypeAdapter
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import com.google.gson.stream.JsonWriter
import com.fnfmodmaker.media.MediaTools
import com.fnfmodmaker.model.AudioSlot
import com.fnfmodmaker.model.ChartDraft
import com.fnfmodmaker.model.ChartEditorState
import com.fnfmodmaker.model.ChartNote
import com.fnfmodmaker.model.CHARACTER_ANIMATIONS
import com.fnfmodmaker.model.CharacterAdjustment
import com.fnfmodmaker.model.nudged
import com.fnfmodmaker.model.CharacterFrame
import com.fnfmodmaker.model.CharacterAnimationSettings
import com.fnfmodmaker.model.CharacterDraft
import com.fnfmodmaker.model.CharacterSide
import com.fnfmodmaker.model.ModConfig
import com.fnfmodmaker.model.PickedAudio
import com.fnfmodmaker.model.PickedImage
import com.fnfmodmaker.model.SongConfig
import com.fnfmodmaker.model.TargetEngine
import com.fnfmodmaker.model.WizardIssue
import com.fnfmodmaker.model.validateWizard
import com.fnfmodmaker.model.characterInputProblems
import com.fnfmodmaker.model.parseSparrowAtlas
import com.fnfmodmaker.model.slugify
import com.fnfmodmaker.mod.ModBuilder
import com.fnfmodmaker.mod.ModImporter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import com.fnfmodmaker.project.ProjectStore
import com.fnfmodmaker.project.ProjectSummary
import com.fnfmodmaker.project.PortableProject
import com.fnfmodmaker.project.PreparedPortable
import com.fnfmodmaker.project.PortableSummary
import com.fnfmodmaker.model.DragState
import com.fnfmodmaker.model.SelectedNote
import java.io.File
import org.json.JSONObject
import kotlin.math.roundToInt
import kotlin.math.roundToLong
/** Pantalla en la que está el usuario: inicio, asistente o una herramienta suelta. */
enum class Tool { HOME, MOD, CHARACTER, AUDIO, IMPORT, STAGE, ASSISTANT }

class WizardViewModel(application: Application) : AndroidViewModel(application) {

    var tool by mutableStateOf(Tool.HOME)
        internal set

    // The credential is deliberately kept only in memory, never in the auto-saved project.
    var aiBaseUrl by mutableStateOf("https://api.openai.com/v1")
    var aiKey by mutableStateOf("")
    var aiChatModel by mutableStateOf("gpt-4.1-mini")
    var aiImageModel by mutableStateOf("gpt-image-1")
    var aiPrompt by mutableStateOf("")
    var aiResult by mutableStateOf<String?>(null)
        private set
    var aiError by mutableStateOf<String?>(null)
        private set
    var aiStatus by mutableStateOf("")
        private set
    var aiBusy by mutableStateOf(false)
        private set
    var aiAnalysisFile by mutableStateOf<String?>(null)
        private set
    var aiAnalysisName by mutableStateOf<String?>(null)
        private set
    var aiGeneratedFile by mutableStateOf<String?>(null)
        private set
    var aiSavedImage by mutableStateOf<String?>(null)
        private set
    var aiProposal by mutableStateOf<com.fnfmodmaker.ai.ProjectProposal?>(null)
        private set
    var aiProposalNotice by mutableStateOf<String?>(null)
        private set

    internal fun clearAiKey() { aiKey = "" }
    internal fun clearAiSession() {
        if (aiBusy) return
        aiKey = ""; aiPrompt = ""; aiResult = null; aiError = null
        aiAnalysisFile = null; aiAnalysisName = null; aiGeneratedFile = null; aiSavedImage = null
        aiProposal = null; aiProposalNotice = null
        File(getApplication<Application>().cacheDir, "ai").deleteRecursively()
    }

    /** Select a local image; resize and strip metadata before any optional upload. */
    internal fun pickAiAnalysisImage(uri: Uri) {
        if (aiBusy) return
        aiBusy = true
        aiError = null
        aiStatus = "Preparando imagen…"
        val app = getApplication<Application>()
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val dir = File(app.cacheDir, "ai").apply { mkdirs() }
                    val temp = File.createTempFile("selected_", ".img", dir)
                    var output: File? = null
                    try {
                        val input = app.contentResolver.openInputStream(uri) ?: throw com.fnfmodmaker.ai.AiException("No se pudo abrir la imagen.")
                        input.use { stream -> temp.outputStream().use { dest ->
                            val buffer = ByteArray(8192); var total = 0L
                            while (true) {
                                val n = stream.read(buffer); if (n < 0) break
                                total += n
                                if (total > 15L * 1024 * 1024) throw com.fnfmodmaker.ai.AiException("La imagen supera 15 MB.")
                                dest.write(buffer, 0, n)
                            }
                        } }
                        val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                        android.graphics.BitmapFactory.decodeFile(temp.path, bounds)
                        if (bounds.outWidth !in 1..12000 || bounds.outHeight !in 1..12000) throw com.fnfmodmaker.ai.AiException("La imagen no es válida o es demasiado grande.")
                        var sample = 1
                        while (bounds.outWidth / sample > 1024 || bounds.outHeight / sample > 1024) sample *= 2
                        val bitmap = android.graphics.BitmapFactory.decodeFile(temp.path, android.graphics.BitmapFactory.Options().apply { inSampleSize = sample })
                            ?: throw com.fnfmodmaker.ai.AiException("No se pudo leer la imagen.")
                        try {
                            val white = android.graphics.Bitmap.createBitmap(bitmap.width, bitmap.height, android.graphics.Bitmap.Config.ARGB_8888)
                            try {
                                val canvas = android.graphics.Canvas(white)
                                canvas.drawColor(android.graphics.Color.WHITE)
                                canvas.drawBitmap(bitmap, 0f, 0f, null)
                                output = File.createTempFile("analysis_", ".jpg", dir)
                                output!!.outputStream().use { if (!white.compress(android.graphics.Bitmap.CompressFormat.JPEG, 85, it)) throw com.fnfmodmaker.ai.AiException("No se pudo preparar la imagen.") }
                            } finally { white.recycle() }
                        } finally { bitmap.recycle() }
                        output!!.absolutePath to MediaTools.displayName(app, uri)
                    } catch (e: Exception) {
                        output?.delete()
                        throw e
                    } finally { temp.delete() }
                }
            }
            result.onSuccess { (path, name) ->
                aiAnalysisFile?.let { File(it).delete() }
                aiAnalysisFile = path; aiAnalysisName = name
            }.onFailure { aiError = if (it is com.fnfmodmaker.ai.AiException) it.message else "No se pudo preparar la imagen." }
            aiStatus = ""; aiBusy = false
        }
    }

    internal fun clearAiAnalysisImage() {
        if (aiBusy) return
        aiAnalysisFile?.let { File(it).delete() }
        aiAnalysisFile = null; aiAnalysisName = null
    }

    /** No request is made until the user explicitly chooses an action. */
    internal fun askCharacterAi(analyzeImage: Boolean) {
        if (aiBusy) return
        val question = aiPrompt.trim()
        val key = aiKey.trim()
        val model = aiChatModel.trim()
        if (question.isBlank() || question.length > 4000) { aiError = "Escribe una consulta de hasta 4000 caracteres."; return }
        if (key.isBlank() || key.contains('\n') || key.contains('\r')) { aiError = "Introduce una clave API válida en la app."; return }
        if (model.isBlank()) { aiError = "Indica un modelo de texto compatible."; return }
        val image = if (analyzeImage) aiAnalysisFile?.let(::File) else null
        if (analyzeImage && (image == null || !image.isFile)) { aiError = "Elige primero una imagen para analizar."; return }
        val context = com.fnfmodmaker.ai.projectContext(config, tool == Tool.CHARACTER)
        val base = aiBaseUrl
        aiBusy = true; aiError = null; aiResult = null; aiProposal = null; aiProposalNotice = null; aiStatus = "Consultando al proveedor…"
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { com.fnfmodmaker.ai.AiClient.chat(base, key, model, question, context, image) } }
            result.onSuccess { aiResult = it }.onFailure { aiError = if (it is com.fnfmodmaker.ai.AiException) it.message else "No se pudo conectar con el proveedor. Comprueba Internet y la configuración." }
            aiStatus = ""; aiBusy = false
        }
    }

    internal fun proposeAiChanges() {
        if (aiBusy || busy) return
        val question = aiPrompt.trim(); val key = aiKey.trim(); val model = aiChatModel.trim(); val baseUrl = aiBaseUrl
        if (question.isBlank() || question.length > 4000) { aiError = "Escribe una petición de hasta 4000 caracteres."; return }
        if (key.isBlank() || key.contains('\n') || key.contains('\r')) { aiError = "Introduce una clave API válida en la app."; return }
        if (model.isBlank()) { aiError = "Indica un modelo compatible con herramientas."; return }
        val snapshot = config
        val characterOnly = tool == Tool.CHARACTER
        val context = com.fnfmodmaker.ai.projectContext(snapshot, characterOnly)
        aiBusy = true; aiError = null; aiResult = null; aiProposal = null; aiProposalNotice = null
        aiStatus = "Preparando propuesta…"
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { runCatching {
                val response = com.fnfmodmaker.ai.AiClient.propose(baseUrl, key, model, question, context)
                response to response.arguments?.let { com.fnfmodmaker.ai.ProjectProposalPlanner.plan(snapshot, it, characterOnly) }
            } }
            result.onSuccess { (response, proposal) ->
                aiResult = response.text
                if (proposal != null && config != snapshot) aiError = "El proyecto cambió mientras se preparaba la propuesta. Vuelve a solicitarla."
                else if (proposal != null) aiProposal = proposal
                else aiProposalNotice = "El modelo respondió sin proponer cambios. Comprueba que admite herramientas y pide un cambio concreto."
            }.onFailure { aiError = if (it is com.fnfmodmaker.ai.AiException) it.message else "No se pudo preparar la propuesta. Comprueba el proveedor y que el modelo admita herramientas." }
            aiStatus = ""; aiBusy = false
        }
    }

    internal fun applyAiProposal() {
        val proposal = aiProposal ?: return
        if (busy || aiBusy) return
        val updated = try { com.fnfmodmaker.ai.ProjectProposalPlanner.applyIfUnchanged(config, proposal) }
        catch (e: com.fnfmodmaker.ai.AiException) {
            aiProposal = null
            aiError = e.message
            return
        }
        config = updated
        characterSaved = null
        aiProposal = null
        aiError = null
        aiProposalNotice = "Cambios aplicados al borrador. Revisa y prueba el mod antes de exportar."
        scheduleAutoSave()
    }

    internal fun discardAiProposal() { if (!aiBusy) { aiProposal = null; aiProposalNotice = "Propuesta descartada; no se cambió el proyecto." } }
    internal fun removePsychScript(name: String) {
        if (busy || name !in config.psychScripts) return
        config = config.copy(psychScripts = config.psychScripts - name)
        scheduleAutoSave()
    }
    internal fun returnToMod() { if (!busy) { forceAutoSave(); tool = Tool.MOD } }

    internal fun generateCharacterAiImage() {
        if (aiBusy) return
        val prompt = aiPrompt.trim(); val key = aiKey.trim(); val model = aiImageModel.trim(); val base = aiBaseUrl
        if (prompt.isBlank() || prompt.length > 4000) { aiError = "Escribe una descripción de hasta 4000 caracteres."; return }
        if (key.isBlank() || key.contains('\n') || key.contains('\r')) { aiError = "Introduce una clave API válida en la app."; return }
        if (model.isBlank()) { aiError = "Indica un modelo de imágenes compatible."; return }
        aiBusy = true; aiError = null; aiSavedImage = null; aiProposal = null; aiProposalNotice = null; aiStatus = "Generando imagen…"
        val app = getApplication<Application>()
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { runCatching {
                val dir = File(app.cacheDir, "ai").apply { mkdirs() }
                val output = File.createTempFile("generated_", ".png", dir)
                try { com.fnfmodmaker.ai.AiClient.generate(base, key, model, prompt, output); output.absolutePath }
                catch (e: Exception) { output.delete(); throw e }
            } }
            result.onSuccess { path -> aiGeneratedFile?.let { File(it).delete() }; aiGeneratedFile = path }
                .onFailure { aiError = if (it is com.fnfmodmaker.ai.AiException) it.message else "No se pudo conectar o generar la imagen. Comprueba la configuración." }
            aiStatus = ""; aiBusy = false
        }
    }

    internal fun saveAiGeneratedImage(uri: Uri) {
        if (aiBusy) return
        val file = aiGeneratedFile?.let(::File) ?: return
        aiBusy = true; aiError = null; aiStatus = "Guardando imagen…"
        val app = getApplication<Application>()
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { runCatching {
                val output = app.contentResolver.openOutputStream(uri) ?: throw IllegalStateException()
                output.use { file.inputStream().use { input -> input.copyTo(it) } }
                MediaTools.displayName(app, uri)
            } }
            result.onSuccess { aiSavedImage = it }.onFailure { aiError = "No se pudo guardar la imagen." }
            aiStatus = ""; aiBusy = false
        }
    }

    /** Índice de la canción que se está editando en el asistente. */
    var activeSongIndex by mutableStateOf(0)
        internal set

    /** ¿Qué falta para poder exportar el mod completo? */
    val validationIssues: List<WizardIssue> get() = validateWizard(config)
    val missingForExport: List<String> get() = validationIssues.map { it.message }
    val exportReady: Boolean get() = validationIssues.isEmpty()
    val currentStepIssues: List<WizardIssue> get() = validationIssues.filter { it.step == step }

    /** Personaje suelto: mismo formulario, pero sin depender del resto del mod. */
    val characterIssues: List<String> get() = characterInputProblems(config.character)
    val characterOnlyReady: Boolean get() = characterIssues.isEmpty()

    val characterZipName: String
        get() = "${config.character.charFolder}-personaje.zip"

    /** Canción que se está editando actualmente. */
    val activeSong: SongConfig
        get() = config.songs.getOrElse(activeSongIndex) { SongConfig() }

    /** Índice de la canción que se está charteando. */
    var activeChartSongIndex by mutableStateOf(0)
        internal set

    /** Dificultad que se está charteando. */
    var activeChartDifficulty by mutableStateOf("normal")
        internal set

    /** Índice de la variación que se está editando. */
    var activeVariationIndex by mutableStateOf<Int?>(null)
        internal set

    /** Estado del editor de chart. */
    var chartEditorState by mutableStateOf(ChartEditorState())
        internal set

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

    // ---------------- Grabación táctil de charts ----------------
    var liveSongIndex by mutableStateOf(0)
        private set
    var liveDifficulty by mutableStateOf("normal")
        private set
    var liveOpponent by mutableStateOf(false)
        private set
    var liveSnap by mutableStateOf(com.fnfmodmaker.model.RecordingSnap.FREE)
        private set
    var liveCorrectionMs by mutableStateOf(0)
        private set
    var livePositionMs by mutableStateOf(0L)
        private set
    var liveDurationMs by mutableStateOf(0L)
        private set
    var livePlaying by mutableStateOf(false)
        private set
    var liveRecording by mutableStateOf(false)
        private set
    var liveLoading by mutableStateOf(false)
        private set
    var liveError by mutableStateOf<String?>(null)
        private set
    var liveNotice by mutableStateOf<String?>(null)
        private set
    private var livePlayer: MediaPlayer? = null
    private var livePolling: kotlinx.coroutines.Job? = null
    private var livePrepared = false
    private var liveFocusHeld = false
    private data class LiveUndo(val note: ChartNote, val createdChart: Boolean)
    private val liveUndo = mutableListOf<LiveUndo>()
    private data class LiveHold(val id: Long, val direction: Int, val note: ChartNote, val pressedAt: Long)
    private val liveHolds = mutableMapOf<Int, LiveHold>()
    private var nextLiveHoldId = 0L
    val livePreviewNotes: List<ChartNote> get() {
        val chart = liveChart ?: return emptyList()
        val song = liveSong ?: return chart.notes
        if (liveHolds.isEmpty()) return chart.notes
        return chart.notes.map { note ->
            val hold = liveHolds.values.firstOrNull { it.note == note } ?: return@map note
            note.copy(lengthMs = com.fnfmodmaker.model.recordedHoldLength(note, hold.pressedAt, livePositionMs,
                liveCorrectionMs, song.bpmValue, liveSnap, liveDurationMs, chart.notes))
        }
    }

    var liveUndoCount by mutableStateOf(0)
        private set
    private val liveAudioManager get() = getApplication<Application>().getSystemService(android.content.Context.AUDIO_SERVICE) as android.media.AudioManager
    private val liveFocusListener = android.media.AudioManager.OnAudioFocusChangeListener { change ->
        if (change == android.media.AudioManager.AUDIOFOCUS_LOSS || change == android.media.AudioManager.AUDIOFOCUS_LOSS_TRANSIENT || change == android.media.AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK) {
            pauseLivePlayback()
            liveNotice = "Reproducción pausada por otra aplicación."
        }
    }

    val liveSong: SongConfig? get() = config.songs.getOrNull(liveSongIndex)
    val liveChart: ChartDraft? get() = liveSong?.let { config.getChart(it.songFolder, liveDifficulty) }
    val liveChartAvailable: Boolean get() {
        val song = liveSong ?: return false
        val chart = liveChart
        return song.songName.isNotBlank() && config.songs.count { it.songFolder == song.songFolder } == 1 &&
            liveDifficulty in song.difficulties && (chart == null || (chart.events.isEmpty() && chart.sections.isEmpty()))
    }
    val liveCanPlay: Boolean get() = liveChartAvailable && liveSong?.instrumental?.let { File(it.path).isFile } == true

    internal fun openLiveChart() {
        closeLiveChart()
        liveSongIndex = activeSongIndex.coerceIn(0, (config.songs.size - 1).coerceAtLeast(0))
        val song = liveSong
        liveDifficulty = if (song != null && "normal" in song.difficulties) "normal" else song?.orderedDifficulties?.firstOrNull() ?: "normal"
        liveDurationMs = song?.instrumental?.durationMs ?: 0L
        livePositionMs = 0L; liveError = null; liveNotice = null
        liveUndo.clear(); liveUndoCount = 0
    }
    internal fun selectLiveSong(index: Int) {
        if (liveLoading || livePlaying || index !in config.songs.indices) return
        closeLiveChart()
        liveSongIndex = index
        val song = config.songs[index]
        liveDifficulty = if ("normal" in song.difficulties) "normal" else song.orderedDifficulties.firstOrNull() ?: "normal"
        liveDurationMs = song.instrumental?.durationMs ?: 0L; livePositionMs = 0L
        liveError = null; liveNotice = null; liveUndo.clear(); liveUndoCount = 0
    }
    internal fun selectLiveDifficulty(difficulty: String) {
        if (liveLoading || livePlaying || difficulty !in (liveSong?.difficulties ?: emptySet())) return
        closeLiveChart(); liveDifficulty = difficulty; livePositionMs = 0L
        liveDurationMs = liveSong?.instrumental?.durationMs ?: 0L
        liveError = null; liveNotice = null; liveUndo.clear(); liveUndoCount = 0
    }
    internal fun selectLiveSide(opponent: Boolean) { if (!livePlaying && !liveLoading) liveOpponent = opponent }
    internal fun selectLiveSnap(snap: com.fnfmodmaker.model.RecordingSnap) { if (!livePlaying && !liveLoading) liveSnap = snap }
    internal fun setLiveCorrection(value: Int) { if (!livePlaying && !liveLoading) liveCorrectionMs = value.coerceIn(-500, 500) }
    internal fun setLivePosition(value: Long) {
        if (!livePlaying && !liveLoading) livePositionMs = value.coerceIn(0L, liveDurationMs.coerceAtLeast(0L))
    }

    @Suppress("DEPRECATION")
    private fun acquireLiveFocus(): Boolean {
        if (liveFocusHeld) return true
        val result = liveAudioManager.requestAudioFocus(liveFocusListener, android.media.AudioManager.STREAM_MUSIC, android.media.AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
        liveFocusHeld = result == android.media.AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        return liveFocusHeld
    }
    @Suppress("DEPRECATION")
    private fun abandonLiveFocus() {
        if (liveFocusHeld) liveAudioManager.abandonAudioFocus(liveFocusListener)
        liveFocusHeld = false
    }
    internal fun startLivePlayback(record: Boolean) {
        if (busy || liveLoading || livePlaying) return
        if (!liveCanPlay) { liveError = "Elige una instrumental disponible y comprueba el nombre y la dificultad de la canción."; return }
        if (record && liveSnap != com.fnfmodmaker.model.RecordingSnap.FREE && (liveSong?.bpmValue ?: 0) !in 1..400) {
            liveError = "Indica un BPM válido para usar el ajuste a la cuadrícula."; return
        }
        liveError = null; liveNotice = null
        if (!acquireLiveFocus()) { liveError = "No se pudo obtener el audio; cierra otras aplicaciones de música e inténtalo de nuevo."; return }
        val existing = livePlayer
        if (existing != null && livePrepared) {
            seekAndStartLive(existing, record)
            return
        }
        val player = MediaPlayer()
        livePlayer = player; livePrepared = false; liveLoading = true
        try {
            player.setAudioAttributes(android.media.AudioAttributes.Builder().setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MUSIC).build())
            player.setDataSource(liveSong!!.instrumental!!.path)
            player.setOnPreparedListener { mp ->
                if (livePlayer !== mp) return@setOnPreparedListener
                livePrepared = true
                liveDurationMs = runCatching { mp.duration.toLong() }.getOrDefault(0L).coerceAtLeast(0L)
                seekAndStartLive(mp, record)
            }
            player.setOnCompletionListener { mp ->
                if (livePlayer === mp) {
                    finishAllLiveHolds(liveDurationMs)
                    livePolling?.cancel(); livePlaying = false; liveRecording = false; liveLoading = false
                    livePositionMs = liveDurationMs; abandonLiveFocus(); forceAutoSave()
                    liveNotice = "Terminó la instrumental. Las notas grabadas están en el borrador."
                }
            }
            player.setOnErrorListener { mp, _, _ ->
                if (livePlayer === mp) { closeLiveChart(); liveError = "No se pudo reproducir la instrumental. Prueba otro archivo." }
                true
            }
            player.prepareAsync()
        } catch (_: Exception) {
            closeLiveChart(); liveError = "No se pudo abrir la instrumental. Prueba otro archivo."
        }
    }
    private fun seekAndStartLive(player: MediaPlayer, record: Boolean) {
        if (livePlayer !== player) return
        liveLoading = true
        val target = if (liveDurationMs > 0 && livePositionMs >= liveDurationMs) 0L else livePositionMs.coerceAtLeast(0L)
        fun start() {
            if (livePlayer !== player) return
            try {
                player.setOnSeekCompleteListener(null)
                player.start()
                livePositionMs = player.currentPosition.toLong()
                liveLoading = false; livePlaying = true; liveRecording = record
                livePolling?.cancel()
                livePolling = viewModelScope.launch {
                    while (livePlayer === player && livePlaying) {
                        livePositionMs = runCatching { player.currentPosition.toLong() }.getOrDefault(livePositionMs)
                        delay(50)
                    }
                }
            } catch (_: Exception) { closeLiveChart(); liveError = "No se pudo iniciar la reproducción." }
        }
        try {
            if (kotlin.math.abs(player.currentPosition.toLong() - target) > 10L) {
                player.setOnSeekCompleteListener { start() }
                player.seekTo(target.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
            } else start()
        } catch (_: Exception) { closeLiveChart(); liveError = "No se pudo buscar en la instrumental." }
    }
    internal fun pauseLivePlayback() {
        if (liveLoading) { closeLiveChart(); return }
        val player = livePlayer
        if (livePlaying && player != null) {
            livePositionMs = runCatching { player.currentPosition.toLong() }.getOrDefault(livePositionMs)
            finishAllLiveHolds(livePositionMs)
            runCatching { player.pause() }
        }
        livePolling?.cancel(); livePlaying = false; liveRecording = false; abandonLiveFocus()
        forceAutoSave()
    }
    internal fun closeLiveChart() {
        livePolling?.cancel(); livePolling = null
        val player = livePlayer
        val finalPosition = if (livePlaying) runCatching { player?.currentPosition?.toLong() }.getOrNull() ?: livePositionMs else livePositionMs
        finishAllLiveHolds(finalPosition)
        livePlayer = null; livePrepared = false
        runCatching { player?.release() }
        liveLoading = false; livePlaying = false; liveRecording = false
        abandonLiveFocus()
        forceAutoSave()
    }
    /** Starts on pointer down, not on release. Multiple directions can be held at once. */
    internal fun beginLiveArrow(direction: Int): Long? {
        if (!liveRecording || !livePlaying || direction !in 0..3 || !liveChartAvailable || direction in liveHolds) return null
        val song = liveSong ?: return null
        val position = runCatching { livePlayer?.currentPosition?.toLong() }.getOrNull() ?: return null
        val time = com.fnfmodmaker.model.recordedNoteTime(position, liveCorrectionMs, song.bpmValue, liveSnap, liveDurationMs)
        val note = ChartNote(timeMs = time, lane = direction + if (liveOpponent) 4 else 0)
        val existing = config.getChart(song.songFolder, liveDifficulty)
        val draft = existing ?: ChartDraft(songFolder = song.songFolder, difficulty = liveDifficulty, bpm = song.bpmValue,
            scrollSpeed = song.speedValue, offsetMs = song.offsetValue, needsVoices = song.hasVocals,
            player1 = song.player, player2 = song.opponent, gfVersion = song.girlfriend, stage = song.stagePsych)
        if (draft.notes.size >= 10000) { liveNotice = "Límite de 10 000 notas en esta dificultad."; return null }
        val notes = com.fnfmodmaker.model.appendRecordedNote(draft.notes, note)
        if (notes == null) { liveNotice = "Ya hay una nota o sostenido en ese carril y tiempo; no se ha sobrescrito."; return null }
        config = config.withChart(song.songFolder, liveDifficulty, draft.copy(notes = notes))
        liveUndo.add(LiveUndo(note, existing == null)); liveUndoCount = liveUndo.size
        val id = ++nextLiveHoldId
        liveHolds[direction] = LiveHold(id, direction, note, position)
        liveNotice = null; scheduleAutoSave()
        return id
    }
    internal fun endLiveArrow(direction: Int, id: Long) {
        val position = runCatching { livePlayer?.currentPosition?.toLong() }.getOrNull() ?: livePositionMs
        finishLiveHold(direction, id, position)
    }
    private fun finishAllLiveHolds(position: Long) {
        liveHolds.values.toList().forEach { finishLiveHold(it.direction, it.id, position) }
    }
    private fun finishLiveHold(direction: Int, id: Long, position: Long) {
        val hold = liveHolds[direction] ?: return
        if (hold.id != id) return
        liveHolds.remove(direction)
        val song = liveSong ?: return
        val chart = config.getChart(song.songFolder, liveDifficulty) ?: return
        val index = chart.notes.indexOf(hold.note)
        if (index < 0) return
        val length = com.fnfmodmaker.model.recordedHoldLength(hold.note, hold.pressedAt, position,
            liveCorrectionMs, song.bpmValue, liveSnap, liveDurationMs, chart.notes)
        if (length == 0L) return
        val updated = hold.note.copy(lengthMs = length)
        val notes = chart.notes.toMutableList().apply { this[index] = updated }
        config = config.withChart(song.songFolder, liveDifficulty, chart.copy(notes = notes))
        val undoIndex = liveUndo.indexOfLast { it.note == hold.note }
        if (undoIndex >= 0) liveUndo[undoIndex] = liveUndo[undoIndex].copy(note = updated)
        val desired = com.fnfmodmaker.model.recordedNoteTime(position, liveCorrectionMs, song.bpmValue, liveSnap, liveDurationMs) - hold.note.timeMs
        if (length < desired) liveNotice = "Sostenido limitado para no cubrir otra nota del mismo carril."
        scheduleAutoSave()
    }
    /** Accessibility click remains a short note; duration can be edited afterwards. */
    internal fun recordLiveArrow(direction: Int) {
        val id = beginLiveArrow(direction) ?: return
        endLiveArrow(direction, id)
    }
    internal fun editLiveNoteLength(note: ChartNote, length: Long): Boolean {
        if (livePlaying || liveLoading || !liveChartAvailable || length < 0) return false
        val song = liveSong ?: return false
        val chart = config.getChart(song.songFolder, liveDifficulty) ?: return false
        val index = chart.notes.indexOf(note)
        if (index < 0 || com.fnfmodmaker.model.safeHoldLength(note, length, liveDurationMs, chart.notes) != length) return false
        val replacement = note.copy(lengthMs = length)
        config = config.withChart(song.songFolder, liveDifficulty, chart.copy(notes = chart.notes.toMutableList().apply { this[index] = replacement }))
        val undoIndex = liveUndo.indexOfLast { it.note == note }
        if (undoIndex >= 0) liveUndo[undoIndex] = liveUndo[undoIndex].copy(note = replacement)
        liveNotice = "Duración actualizada."; scheduleAutoSave()
        return true
    }
    internal fun undoLiveNote() {
        if (livePlaying || liveLoading || liveUndo.isEmpty()) return
        val song = liveSong ?: return
        val chart = config.getChart(song.songFolder, liveDifficulty) ?: return
        val action = liveUndo.last()
        val notes = com.fnfmodmaker.model.removeRecordedNote(chart.notes, action.note) ?: return
        liveUndo.removeAt(liveUndo.lastIndex); liveUndoCount = liveUndo.size
        config = if (action.createdChart && notes.isEmpty()) removeChartFromConfig(config, song.songFolder, liveDifficulty)
            else config.withChart(song.songFolder, liveDifficulty, chart.copy(notes = notes))
        liveNotice = "Se deshizo la última nota grabada en esta sesión."; scheduleAutoSave()
    }
    internal fun deleteLiveNote(note: ChartNote) {
        if (livePlaying || liveLoading || !liveChartAvailable) return
        val song = liveSong ?: return
        val chart = config.getChart(song.songFolder, liveDifficulty) ?: return
        val notes = com.fnfmodmaker.model.removeRecordedNote(chart.notes, note) ?: return
        config = config.withChart(song.songFolder, liveDifficulty, chart.copy(notes = notes))
        liveUndo.removeAll { it.note == note }; liveUndoCount = liveUndo.size
        liveNotice = "Nota eliminada."; scheduleAutoSave()
    }
    // Grid edits have their own bounded undo stack; never share stale live-recording undo entries.
    private val gridHistory = com.fnfmodmaker.model.GridUndoHistory()
    var gridUndoCount by mutableStateOf(0)
        private set
    var gridRedoCount by mutableStateOf(0)
        private set
    private var gridSessionKey: Pair<String, String>? = null
    internal fun beginGridSession() {
        gridHistory.clear(); gridUndoCount = 0; gridRedoCount = 0
        gridSessionKey = liveSong?.songFolder?.let { it to liveDifficulty }
    }
    internal fun endGridSession() {
        if (livePlaying || liveLoading) pauseLivePlayback()
        gridHistory.clear(); gridUndoCount = 0; gridRedoCount = 0; gridSessionKey = null; forceAutoSave()
    }
    private fun gridSessionMatches(): Boolean = gridSessionKey != null && gridSessionKey == liveSong?.songFolder?.let { it to liveDifficulty }
    internal fun applyGridNote(original: ChartNote?, replacement: ChartNote?): Boolean {
        if (!gridSessionMatches() || busy || livePlaying || liveLoading || !liveChartAvailable || !liveCanPlay || liveDurationMs <= 0L) return false
        val song = liveSong ?: return false
        val existing = config.getChart(song.songFolder, liveDifficulty)
        if (existing == null && original != null) return false
        val draft = existing ?: ChartDraft(songFolder = song.songFolder, difficulty = liveDifficulty, bpm = song.bpmValue,
            scrollSpeed = song.speedValue, offsetMs = song.offsetValue, needsVoices = song.hasVocals,
            player1 = song.player, player2 = song.opponent, gfVersion = song.girlfriend, stage = song.stagePsych)
        if (original == null && draft.notes.size >= 10000) { liveNotice = "Límite de 10 000 notas en esta dificultad."; return false }
        val notes = com.fnfmodmaker.model.replaceGridNote(draft.notes, original, replacement, liveDurationMs)
        if (notes == null) { liveNotice = "No se puede colocar: hay otra nota o sostenido en ese carril, o supera el final."; return false }
        if (notes == draft.notes) return false
        gridHistory.push(existing); gridUndoCount = gridHistory.size; gridRedoCount = gridHistory.redoSize
        liveUndo.clear(); liveUndoCount = 0
        config = config.withChart(song.songFolder, liveDifficulty, draft.copy(notes = notes))
        liveNotice = "Cambio guardado en el borrador."; scheduleAutoSave()
        return true
    }
    internal fun undoGridEdit() = restoreGridHistory(redo = false)
    internal fun redoGridEdit() = restoreGridHistory(redo = true)
    private fun restoreGridHistory(redo: Boolean) {
        if (!gridSessionMatches() || busy || livePlaying || liveLoading || !liveChartAvailable) return
        val song = liveSong ?: return
        val current = config.getChart(song.songFolder, liveDifficulty)
        val entry = (if (redo) gridHistory.redo(current) else gridHistory.undo(current)) ?: return
        gridUndoCount = gridHistory.size; gridRedoCount = gridHistory.redoSize
        config = if (entry.chart == null) removeChartFromConfig(config, song.songFolder, liveDifficulty)
            else config.withChart(song.songFolder, liveDifficulty, entry.chart)
        liveNotice = if (redo) "Se rehízo el último cambio de la cuadrícula." else "Se deshizo el último cambio de la cuadrícula."
        scheduleAutoSave()
    }
    internal fun deleteLiveCustomChart() {
        if (livePlaying || liveLoading) return
        val song = liveSong ?: return
        config = removeChartFromConfig(config, song.songFolder, liveDifficulty)
        liveUndo.clear(); liveUndoCount = 0
        liveNotice = "Se eliminó el chart personalizado de esta dificultad."; scheduleAutoSave()
    }
    private fun removeChartFromConfig(config: ModConfig, folder: String, difficulty: String): ModConfig {
        val remaining = config.charts[folder].orEmpty() - difficulty
        return config.copy(charts = if (remaining.isEmpty()) config.charts - folder else config.charts + (folder to remaining))
    }

    // ---------------- Navegación de canciones ----------------

    internal fun addSong() {
        if (busy) return
        config = config.copy(songs = config.songs + SongConfig())
        activeSongIndex = config.songs.lastIndex
        scheduleAutoSave()
    }

    internal fun removeSong(index: Int) {
        if (busy || config.songs.size <= 1 || index !in config.songs.indices) return
        val removedFolder = config.songs[index].songFolder
        val updated = config.songs.toMutableList()
        updated.removeAt(index)
        activeSongIndex = when {
            index < activeSongIndex -> activeSongIndex - 1
            activeSongIndex >= updated.size -> updated.lastIndex
            else -> activeSongIndex
        }
        activeChartSongIndex = activeChartSongIndex.coerceIn(0, updated.lastIndex)
        config = config.copy(songs = updated, charts = if (updated.any { it.songFolder == removedFolder }) config.charts else config.charts - removedFolder)
        scheduleAutoSave()
    }

    internal fun selectSong(index: Int) {
        if (busy || config.songs.isEmpty()) return
        activeSongIndex = index.coerceIn(0, config.songs.lastIndex)
    }

    // ---------------- Navegación de chart ----------------

    internal fun selectChartSong(index: Int) {
        if (busy) return
        releaseMediaPlayer()
        activeChartSongIndex = index.coerceIn(0, config.songs.lastIndex)
        val song = config.songs[activeChartSongIndex]
        activeChartDifficulty = song.orderedDifficulties.firstOrNull() ?: "normal"
        updateChartEditorState()
        initChartHistory()
    }

    internal fun selectChartDifficulty(difficulty: String) {
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
    internal fun addChartNote(note: com.fnfmodmaker.model.ChartNote) {
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
    internal fun removeChartNote(timeMs: Long, lane: Int) {
        val draft = activeChartDraft
        val updatedNotes = draft.notes.filterNot { it.timeMs == timeMs && it.lane == lane }
        val updatedDraft = draft.copy(notes = updatedNotes)
        pushHistoryAndSave(updatedDraft)
    }

    /** Genera notas de práctica automáticas para el chart activo. */
    internal fun generatePracticeNotes() {
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
    internal fun clearChart() {
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
    internal fun updatePlaybackTime(timeMs: Long) {
        chartEditorState = chartEditorState.copy(currentTimeMs = timeMs)
    }

    /** Actualiza zoom y scroll del editor. */
    internal fun updateChartView(zoom: Float, scrollX: Float) {
        chartEditorState = chartEditorState.copy(zoom = zoom, scrollX = scrollX)
    }

    /** Actualiza la resolución de snap (grid). */
    internal fun updateSnapResolution(resolution: com.fnfmodmaker.model.SnapResolution) {
        chartEditorState = chartEditorState.copy(snapResolution = resolution)
    }

    // ---------------- Eventos y Secciones ----------------

    /** Agrega o actualiza un evento en el chart activo. */
    internal fun addChartEvent(event: com.fnfmodmaker.model.ChartEvent) {
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
    internal fun removeChartEvent(timeMs: Long, type: com.fnfmodmaker.model.ChartEvent.EventType) {
        val draft = activeChartDraft
        val updatedEvents = draft.events.filterNot { it.timeMs == timeMs && it.type == type }
        val updatedDraft = draft.copy(events = updatedEvents)
        pushHistoryAndSave(updatedDraft)
    }

    /** Agrega o actualiza una sección en el chart activo. */
    internal fun addChartSection(section: com.fnfmodmaker.model.ChartSection) {
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
    internal fun removeChartSection(startTimeMs: Long) {
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
        val mp = MediaPlayer()
        return try {
            mp.setDataSource(instrumental.path)
            mp.prepare()
            mp.setOnCompletionListener { onPlaybackComplete() }
            mp
        } catch (e: Exception) {
            e.printStackTrace()
            mp.release()
            null
        }
    }

    // ---------------- Importar Mod ----------------

    /** Importa un mod desde un ZIP y carga el config en el wizard. */
    internal fun importMod(uri: Uri) {
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
    internal fun seekTo(timeMs: Long) {
        mediaPlayer?.seekTo(timeMs.toInt())
        chartEditorState = chartEditorState.copy(currentTimeMs = timeMs)
    }

    /** Limpia MediaPlayer al cambiar de canción o cerrar. */
    internal fun releaseMediaPlayer() {
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
    internal fun startScrollDrag(startX: Float, startScroll: Float) {
        chartEditorState = chartEditorState.copy(
            dragState = DragState.Scroll(startX, startScroll)
        )
    }

    /** Actualiza drag de scroll. */
    internal fun updateScrollDrag(currentX: Float) {
        val state = chartEditorState.dragState
        if (state is DragState.Scroll) {
            val newScroll = (state.startScroll - (currentX - state.startX)).coerceIn(0f, Float.MAX_VALUE)
            chartEditorState = chartEditorState.copy(scrollX = newScroll)
        }
    }

    /** Termina drag de scroll. */
    internal fun endScrollDrag() {
        chartEditorState = chartEditorState.copy(dragState = null)
    }

    /** Inicia drag de resize hold note. */
    internal fun startHoldResize(noteIndex: Int, startTimeMs: Long, lane: Int, startLengthMs: Long, dragStartX: Float) {
        chartEditorState = chartEditorState.copy(
            dragState = DragState.HoldResize(
                noteIndex, startTimeMs, lane, startLengthMs, dragStartX
            )
        )
    }

    /** Actualiza drag de resize hold note. */
    internal fun updateHoldResize(currentX: Float, beatWidthPx: Float, beatMs: Double) {
        val state = chartEditorState.dragState
        if (state is DragState.HoldResize) {
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
    internal fun endHoldResize() {
        chartEditorState = chartEditorState.copy(dragState = null)
    }

    /** Inicia selección múltiple (box selection). */
    internal fun startSelectionBox(startX: Float, startY: Float) {
        chartEditorState = chartEditorState.copy(
            dragState = DragState.SelectionBox(startX, startY),
            selectedNotes = emptyList()
        )
    }

    /** Actualiza selección múltiple. */
    internal fun updateSelectionBox(currentX: Float, currentY: Float, beatWidthPx: Float, beatMs: Double, laneHeightPx: Float, timeRulerHeightPx: Float, headerHeightPx: Float) {
        val state = chartEditorState.dragState
        if (state is DragState.SelectionBox) {
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
    internal fun endSelectionBox() {
        val state = chartEditorState.dragState
        if (state is DragState.SelectionBox) {
            chartEditorState = chartEditorState.copy(dragState = null)
        }
    }

    /** Inicia drag de notas seleccionadas. */
    internal fun startMultiDrag(dragStartX: Float, dragStartY: Float) {
        val selected = chartEditorState.selectedNotes
        if (selected.isNotEmpty()) {
            chartEditorState = chartEditorState.copy(
                dragState = DragState.MultiDrag(selected, dragStartX, dragStartY)
            )
        }
    }

    /** Actualiza drag de notas seleccionadas. */
    internal fun updateMultiDrag(currentX: Float, currentY: Float, beatWidthPx: Float, beatMs: Double, laneHeightPx: Float) {
        val state = chartEditorState.dragState
        if (state is DragState.MultiDrag) {
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
    internal fun endMultiDrag() {
        chartEditorState = chartEditorState.copy(dragState = null)
    }

    /** Agrega nota a selección (toggle). */
    internal fun toggleNoteSelection(timeMs: Long, lane: Int) {
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
    internal fun clearSelection() {
        chartEditorState = chartEditorState.copy(selectedNotes = emptyList())
    }

    /** Copia notas seleccionadas al portapapeles interno. */
    private var clipboardNotes: List<com.fnfmodmaker.model.ChartNote> = emptyList()

    internal fun copySelectedNotes() {
        val draft = activeChartDraft
        clipboardNotes = chartEditorState.selectedNotes
            .map { sel -> draft.notes.getOrNull(sel.noteIndex) }
            .filterNotNull()
            .toList()
    }

    /** Pega notas del portapapeles en la posición actual del playhead. */
    internal fun pasteNotes() {
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
    internal fun saveChartDraft(chart: com.fnfmodmaker.model.ChartDraft) {
        config = config.withChart(chart.songFolder, chart.difficulty, chart)
    }

    /** Undo. */
    internal fun undo() {
        if (chartEditorState.canUndo) {
            val newIndex = chartEditorState.historyIndex - 1
            val prevDraft = chartEditorState.history[newIndex]
            chartEditorState = chartEditorState.copy(historyIndex = newIndex)
            saveChartDraft(prevDraft)
        }
    }

    /** Redo. */
    internal fun redo() {
        if (chartEditorState.canRedo) {
            val newIndex = chartEditorState.historyIndex + 1
            val nextDraft = chartEditorState.history[newIndex]
            chartEditorState = chartEditorState.copy(historyIndex = newIndex)
            saveChartDraft(nextDraft)
        }
    }

    /** Al cambiar de canción/dificultad, inicializa historial con el chart actual. */
    internal fun initChartHistory() {
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

    internal fun openTool(target: Tool) {
        if (busy || currentProjectId == null) return
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

    internal fun goHome() {
        if (busy) return
        forceAutoSave()
        error = null
        status = ""
        tool = Tool.HOME
    }

    var config by mutableStateOf(ModConfig())
        private set

    // Project snapshots are serialized through one mutex; disk work runs off the UI thread.
    private var autoSaveJob: kotlinx.coroutines.Job? = null
    private var pendingAutoSaveJob: kotlinx.coroutines.Job? = null
    private val projectMutex = Mutex()
    private val projectStore = ProjectStore(File(application.filesDir, "projects"), application.applicationInfo.dataDir.let(::File))
    var currentProjectId by mutableStateOf<String?>(null)
        private set
    internal var projects by mutableStateOf<List<ProjectSummary>>(emptyList())
        private set
    var projectMessage by mutableStateOf<String?>(null)
        private set
    var projectSaveStatus by mutableStateOf("Abriendo proyecto…")
        private set
    private var projectRevision = 0L
    private val autoSaveGson = GsonBuilder().registerTypeAdapter(Uri::class.java, object : TypeAdapter<Uri>() {
        override fun write(out: JsonWriter, value: Uri) = out.value(value.toString()).let { Unit }
        override fun read(input: JsonReader): Uri = if (input.peek() == JsonToken.STRING) Uri.parse(input.nextString()) else {
            input.skipValue(); Uri.EMPTY
        }
    }.nullSafe()).create()
    private val autoSaveFile: File get() = File(getApplication<Application>().filesDir, "autosave_modconfig.json")
    private var modIdEdited = false
    private val portable = PortableProject(File(application.applicationInfo.dataDir))
    private var preparedImport: PreparedPortable? = null
    private var preparedExport: File? = null
    internal var portableImportSummary by mutableStateOf<PortableSummary?>(null)
        private set
    var portableExportName by mutableStateOf<String?>(null)
        private set
    var portableExportSize by mutableStateOf(0L)
        private set
    var portableProgress by mutableStateOf<String?>(null)
        private set
    var portableMessage by mutableStateOf<String?>(null)
        private set
    private val portableCache get() = File(getApplication<Application>().cacheDir, "portable-projects")
    private val progressHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var portableOperation = 0L
    private fun reportPortableProgress(token: Long, text: String) {
        progressHandler.post { if (portableOperation == token && busy) portableProgress = text }
    }

    private fun configJson(value: ModConfig) = autoSaveGson.toJsonTree(value).asJsonObject
    /** Fill fields missing from older local saves while retaining unknown JSON fields. */
    private fun portableConfig(source: com.google.gson.JsonObject): com.google.gson.JsonObject {
        val target = configJson(parseConfig(source))
        fun merge(to: com.google.gson.JsonObject, from: com.google.gson.JsonObject) {
            from.entrySet().forEach { (name, value) ->
                val existing = to.get(name)
                if (value.isJsonObject && existing?.isJsonObject == true) merge(existing.asJsonObject, value.asJsonObject)
                else to.add(name, value.deepCopy())
            }
        }
        merge(target, source)
        validatePortableConfig(target)
        return target
    }
    private fun parseConfig(value: com.google.gson.JsonObject): ModConfig =
        autoSaveGson.fromJson(value, ModConfig::class.java) ?: error("El proyecto está vacío.")

    private fun beginProjects() {
        viewModelScope.launch {
            val app = getApplication<Application>()
            val result = withContext(Dispatchers.IO) { runCatching {
                projectStore.clearAbandonedImports()
                portableCache.deleteRecursively()
                val active = projectStore.activeId()
                val existing = projectStore.list()
                val id = active ?: existing.firstOrNull()?.id
                if (id != null) {
                    val record = projectStore.load(id)
                    projectStore.setActive(id)
                    Triple(record.id to parseConfig(record.config), projectStore.list(), emptyList<String>())
                } else {
                    val oldCache = File(app.cacheDir, "autosave_modconfig.json")
                    val source = if (autoSaveFile.exists()) autoSaveFile else oldCache
                    val old = if (source.exists()) com.google.gson.JsonParser.parseString(source.readText()).asJsonObject else configJson(ModConfig())
                    // Validate before committing the migration; never delete the legacy file.
                    parseConfig(old)
                    val newId = projectStore.newId()
                    val copied = projectStore.copyAssets(old, newId)
                    projectStore.save(newId, copied.config)
                    projectStore.setActive(newId)
                    Triple(newId to parseConfig(projectStore.load(newId).config), projectStore.list(), copied.missing)
                }
            } }
            result.onSuccess { (record, list, missing) ->
                config = record.second
                currentProjectId = record.first; projects = list; projectSaveStatus = "Guardado en este dispositivo"
                modIdEdited = config.modId.isNotBlank() && config.modId != slugify(config.modTitle)
                projectMessage = if (missing.isEmpty()) null else "No se encontraron ${missing.size} recursos del borrador anterior: ${missing.joinToString()}. Vuelve a elegirlos."
            }.onFailure { projectSaveStatus = "Proyecto no disponible"; projectMessage = "No se pudo abrir o migrar el proyecto: ${it.message}. Los archivos anteriores se conservan; reinicia la app para reintentar." }
            busy = false
        }
        autoSaveJob = viewModelScope.launch {
            while (true) { delay(30_000); saveAutoSave() }
        }
    }
    private fun saveAutoSave() {
        val id = currentProjectId ?: return
        val snapshot = config
        val revision = projectRevision
        projectSaveStatus = "Guardando…"
        viewModelScope.launch {
            val result = projectMutex.withLock { withContext(Dispatchers.IO) { runCatching {
                projectStore.save(id, configJson(snapshot)); projectStore.list()
            } } }
            result.onSuccess { list ->
                projects = list
                if (currentProjectId == id && projectRevision == revision) {
                    projectSaveStatus = "Guardado en este dispositivo"
                    if (projectMessage?.startsWith("No se pudo guardar el proyecto:") == true) projectMessage = null
                }
            }.onFailure {
                projectSaveStatus = "Error al guardar"
                projectMessage = "No se pudo guardar el proyecto: ${it.message}. Comprueba el espacio e intenta de nuevo."
            }
        }
    }
    fun forceAutoSave() { pendingAutoSaveJob?.cancel(); saveAutoSave() }
    private fun projectAssetDirectory(): File = projectStore.assets(currentProjectId ?: error("El proyecto aún no está listo."))
    private fun newProjectAsset(name: String): File {
        val ext = name.substringAfterLast('.', "").filter(Char::isLetterOrDigit).take(10)
        return File.createTempFile("asset_", if (ext.isEmpty()) ".dat" else ".$ext", projectAssetDirectory())
    }

    /** Create a verified local package before asking the user where to save it. */
    internal fun preparePortableExport(id: String) {
        if (busy || currentProjectId == null || preparedImport != null) return
        val active = currentProjectId; val snapshot = config
        pendingAutoSaveJob?.cancel()
        busy = true; portableMessage = null; portableProgress = "Preparando proyecto…"
        val token = ++portableOperation
        viewModelScope.launch {
            val result = projectMutex.withLock { withContext(Dispatchers.IO) { runCatching {
                if (id == active) projectStore.save(id, configJson(snapshot))
                val record = projectStore.load(id)
                check(portableCache.isDirectory || portableCache.mkdirs()) { "No se pudo crear el archivo temporal." }
                val output = File.createTempFile("export_", ".fnfproject", portableCache)
                portable.export(portableConfig(record.config), projectStore.assets(id), output) { reportPortableProgress(token, it) }
                val title = record.config.get("modTitle")?.asString.orEmpty()
                Triple(output, slugify(title).ifBlank { "proyecto" } + ".fnfproject", output.length())
            } } }
            result.onSuccess { (file, name, size) ->
                preparedExport?.delete(); preparedExport = file; portableExportName = name; portableExportSize = size
            }.onFailure { portableMessage = "No se pudo exportar: ${it.message}. Revisa los recursos y el espacio disponible." }
            portableProgress = null; busy = false
        }
    }
    internal fun cancelPortableExport() {
        if (busy) return
        preparedExport?.delete(); preparedExport = null; portableExportName = null; portableExportSize = 0
    }
    internal fun savePortableExport(uri: Uri) {
        if (busy) return
        val file = preparedExport ?: return
        val app = getApplication<Application>()
        busy = true; portableMessage = null; portableProgress = "Guardando paquete en el archivo elegido…"
        ++portableOperation
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { runCatching {
                val destination = app.contentResolver.openOutputStream(uri, "w") ?: error("No se pudo abrir el destino.")
                destination.use { output -> file.inputStream().use { it.copyTo(output) } }
                MediaTools.displayName(app, uri)
            } }
            result.onSuccess {
                portableMessage = "Proyecto exportado: $it (${file.length()} bytes)."
                preparedExport?.delete(); preparedExport = null; portableExportName = null; portableExportSize = 0
            }
                .onFailure {
                    // Some document providers cannot delete a failed output; tell the user to check it.
                    val deleted = runCatching { app.contentResolver.delete(uri, null, null) > 0 }.getOrDefault(false)
                    portableMessage = "No se pudo guardar el paquete: ${it.message}. " + if (deleted) "Se eliminó el archivo incompleto." else "Comprueba y elimina el archivo incompleto en el destino."
                }
            portableProgress = null; busy = false
        }
    }
    internal fun preparePortableImport(uri: Uri) {
        if (busy || currentProjectId == null || preparedImport != null) return
        val app = getApplication<Application>()
        busy = true; portableMessage = null; portableProgress = "Leyendo paquete…"
        val token = ++portableOperation
        viewModelScope.launch {
            val result = projectMutex.withLock { withContext(Dispatchers.IO) { runCatching {
                check(portableCache.isDirectory || portableCache.mkdirs()) { "No se pudo crear la carpeta temporal." }
                val archive = File.createTempFile("import_", ".fnfproject", portableCache)
                val stage = projectStore.newImportStage()
                try {
                    val source = app.contentResolver.openInputStream(uri) ?: error("No se pudo abrir el archivo.")
                    source.use { input -> archive.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024); var total = 0L
                        while (true) {
                            val n = input.read(buffer); if (n < 0) break
                            total += n; require(total <= 1024L * 1024 * 1024) { "El paquete supera 1 GiB." }
                            output.write(buffer, 0, n)
                        }
                    } }
                    val prepared = portable.prepare(archive, stage) { reportPortableProgress(token, it) }
                    try { validatePortableConfig(prepared.config) } catch (e: Exception) { projectStore.discardImportStage(stage); throw e }
                    prepared
                } finally { archive.delete() }
            } } }
            result.onSuccess { prepared -> preparedImport = prepared; portableImportSummary = prepared.summary }
                .onFailure { portableMessage = "No se pudo validar el proyecto: ${it.message}. Los proyectos existentes no cambiaron." }
            portableProgress = null; busy = false
        }
    }
    internal fun cancelPortableImport() {
        if (busy) return
        val prepared = preparedImport
        preparedImport = null; portableImportSummary = null
        if (prepared != null) viewModelScope.launch(Dispatchers.IO) { projectStore.discardImportStage(prepared.directory) }
    }
    internal fun confirmPortableImport() {
        if (busy) return
        val prepared = preparedImport ?: return
        busy = true; portableMessage = null; portableProgress = "Importando proyecto…"
        ++portableOperation
        viewModelScope.launch {
            val result = projectMutex.withLock { withContext(Dispatchers.IO) { runCatching {
                try { projectStore.publishImport(prepared, portable, ::validatePortableConfig) }
                catch (e: Exception) { projectStore.discardImportStage(prepared.directory); throw e }
            } } }
            preparedImport = null; portableImportSummary = null
            result.onSuccess { id ->
                projects = withContext(Dispatchers.IO) { runCatching { projectStore.list() }.getOrDefault(projects) }
                portableMessage = "Proyecto importado como copia independiente. El proyecto actual no cambió."
            }.onFailure { portableMessage = "No se pudo importar: ${it.message}. Los proyectos existentes no cambiaron." }
            portableProgress = null; busy = false
        }
    }

    /** Reject malformed model shapes before they can reach screens or be published. */
    private fun validatePortableConfig(value: com.google.gson.JsonObject) {
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
        parseConfig(value)
    }
    private fun configJsonChartTemplate() = autoSaveGson.toJsonTree(ChartDraft()).asJsonObject

    /** Save current work before switching. Failed copies/opening never replace the current draft. */
    internal fun switchProject(target: String? = null, duplicate: Boolean = false) {
        if (busy || currentProjectId == null) return
        val oldId = currentProjectId ?: return
        val oldConfig = config
        pendingAutoSaveJob?.cancel()
        busy = true; projectMessage = null
        viewModelScope.launch {
            val result = projectMutex.withLock { withContext(Dispatchers.IO) { runCatching {
                projectStore.save(oldId, configJson(oldConfig))
                val id = if (target != null) target else projectStore.newId()
                val missing = if (target == null) {
                    val source = if (duplicate) configJson(oldConfig).also {
                        val name = oldConfig.modTitle.ifBlank { "Proyecto sin nombre" }
                        it.addProperty("modTitle", "$name (copia)")
                    } else configJson(ModConfig())
                    val copied = projectStore.copyAssets(source, id)
                    if (duplicate && copied.missing.isNotEmpty()) error("No se puede duplicar: faltan ${copied.missing.joinToString()}. Vuelve a elegirlos.")
                    projectStore.save(id, copied.config)
                    copied.missing
                } else emptyList()
                val loaded = projectStore.load(id)
                val validated = parseConfig(loaded.config)
                projectStore.setActive(id)
                Triple(loaded.id to validated, projectStore.list(), missing)
            } } }
            result.onSuccess { (project, list, missing) ->
                closeLiveChart(); releaseMediaPlayer()
                currentProjectId = project.first; config = project.second; projects = list
                projectRevision++; projectSaveStatus = "Guardado en este dispositivo"
                activeSongIndex = 0; activeChartSongIndex = 0; activeChartDifficulty = "normal"; activeVariationIndex = null
                chartEditorState = ChartEditorState(); aiProposal = null; aiProposalNotice = null; step = 0
                modIdEdited = config.modId.isNotBlank() && config.modId != slugify(config.modTitle)
                projectMessage = if (missing.isEmpty()) null else "Faltan recursos: ${missing.joinToString()}."
                tool = Tool.MOD
            }.onFailure { projectMessage = "No se pudo abrir el proyecto: ${it.message}. El proyecto actual no se ha reemplazado." }
            busy = false
        }
    }

    var step by mutableStateOf(0)
        private set

    var busy by mutableStateOf(true)
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
    init {
        File(application.cacheDir, "ai").deleteRecursively()
        beginProjects()
    }

    val canAdvance: Boolean get() = step < 5 && currentStepIssues.isEmpty()

    internal fun resolveIssue(issue: WizardIssue) {
        if (busy) return
        issue.songIndex?.let { selectSong(it) }
        step = issue.step
        error = null
    }

    val zipFileName: String
        get() = "${config.modFolder}.zip"

    // ---------------- Navegación ----------------

    internal fun next() {
        if (step < 5 && canAdvance) {
            step += 1
            error = null
        }
    }

    internal fun back() {
        if (step > 0) {
            step -= 1
            error = null
        }
    }

    internal fun goTo(target: Int) {
        if (target in 0..5) {
            step = target
            error = null
        }
    }

    internal fun reset() {
        closeLiveChart()
        releaseMediaPlayer()
        config = ModConfig()
        aiProposal = null
        aiProposalNotice = null
        activeSongIndex = 0
        activeChartSongIndex = 0
        activeChartDifficulty = "normal"
        activeVariationIndex = null
        chartEditorState = ChartEditorState()
        modIdEdited = false
        step = 0
        busy = false
        status = ""
        error = null
        warnings = emptyList()
        savedName = null
        builtFiles = emptyList()
        scheduleAutoSave()
    }

    // ---------------- Datos del mod ----------------

    internal fun setEngine(engine: TargetEngine) {
        config = config.copy(engine = engine)
        scheduleAutoSave()
    }

    internal fun setModTitle(value: String) {
        config = if (modIdEdited) {
            config.copy(modTitle = value)
        } else {
            config.copy(modTitle = value, modId = slugify(value))
        }
        scheduleAutoSave()
    }

    internal fun setModId(value: String) {
        modIdEdited = true
        config = config.copy(modId = value)
        scheduleAutoSave()
    }

    internal fun setAuthor(value: String) {
        config = config.copy(author = value)
        scheduleAutoSave()
    }

    internal fun setDescription(value: String) {
        config = config.copy(description = value)
        scheduleAutoSave()
    }

    internal fun setWeekColor(value: String) {
        config = config.copy(weekColor = value)
        scheduleAutoSave()
    }

    internal fun setShowInStoryMode(enabled: Boolean) {
        config = config.copy(showInStoryMode = enabled)
        scheduleAutoSave()
    }

    // ---------------- Canción activa ----------------

    internal fun setActiveSongName(value: String) {
        val clean = value.filterNot { it in "/\\|:*?\"<>" }
        val oldFolder = activeSong.songFolder
        updateActiveSong { it.copy(songName = clean) }
        val newFolder = activeSong.songFolder
        if (oldFolder != newFolder && oldFolder in config.charts && newFolder !in config.charts &&
            config.songs.count { it.songFolder == oldFolder } == 0) {
            val moved = config.charts.getValue(oldFolder).mapValues { (_, chart) -> chart.copy(songFolder = newFolder) }
            config = config.copy(charts = config.charts - oldFolder + (newFolder to moved))
            scheduleAutoSave()
        }
    }

    internal fun setActiveSongArtist(value: String) {
        updateActiveSong { it.copy(artist = value) }
    }

    internal fun setActiveSongBpm(value: String) {
        updateActiveSong { it.copy(bpm = value.filter { it.isDigit() }.take(3)) }
    }

    internal fun setActiveSongSpeed(value: String) {
        val filtered = value.filter { it.isDigit() || it == '.' || it == ',' }.replace(',', '.').take(5)
        updateActiveSong { it.copy(speed = filtered) }
    }

    internal fun setActiveSongPlayer(value: String) {
        updateActiveSong { it.copy(player = value) }
    }

    internal fun setActiveSongGirlfriend(value: String) {
        updateActiveSong { it.copy(girlfriend = value) }
    }

    internal fun setActiveSongOpponent(value: String) {
        updateActiveSong { it.copy(opponent = value) }
    }

    internal fun setActiveSongStageVSlice(value: String) {
        updateActiveSong { it.copy(stageVSlice = value) }
    }

    internal fun setActiveSongStagePsych(value: String) {
        updateActiveSong { it.copy(stagePsych = value) }
    }

    internal fun setActiveSongWeekColor(value: String) {
        updateActiveSong { it.copy(weekColor = value) }
    }

    internal fun setActiveSongOffsetMs(value: String) {
        val filtered = value.filter { it.isDigit() || it == '-' }.take(6)
        val clean = if (filtered.startsWith("-")) "-" + filtered.drop(1).filter { it.isDigit() } else filtered
        updateActiveSong { it.copy(offsetMs = clean) }
    }

    internal fun toggleActiveSongDifficulty(difficulty: String) {
        updateActiveSong { song ->
            val current = song.difficulties
            val updated = if (difficulty in current) current - difficulty else current + difficulty
            song.copy(difficulties = updated)
        }
    }

    internal fun setActiveSongPracticeNotes(enabled: Boolean) {
        updateActiveSong { it.copy(practiceNotes = enabled) }
    }

    private fun updateActiveSong(block: (SongConfig) -> SongConfig) {
        if (config.songs.isEmpty()) return
        val index = activeSongIndex.coerceIn(0, config.songs.lastIndex)
        val updated = config.songs.toMutableList()
        updated[index] = block(updated[index])
        config = config.copy(songs = updated)
        scheduleAutoSave()
    }

    internal fun clearSongAudio(slot: AudioSlot) {
        if (busy) return
        updateActiveSong { song ->
            when (slot) {
                AudioSlot.INSTRUMENTAL -> song.copy(instrumental = null)
                AudioSlot.VOICES_PLAYER -> song.copy(vocalsPlayer = null)
                AudioSlot.VOICES_OPPONENT -> song.copy(vocalsOpponent = null)
            }
        }
    }

    internal fun clearModIcon() {
        if (busy) return
        config = config.copy(icon = null)
        scheduleAutoSave()
    }

    // ---------------- Personaje propio ----------------

    private fun scheduleAutoSave() {
        projectRevision++
        projectSaveStatus = "Cambios pendientes"
        savedName = null
        builtFiles = emptyList()
        pendingAutoSaveJob?.cancel()
        pendingAutoSaveJob = viewModelScope.launch {
            delay(1000)
            saveAutoSave()
        }
    }

    private fun updateCharacter(block: (CharacterDraft) -> CharacterDraft) {
        config = config.copy(character = block(config.character))
        characterSaved = null
        scheduleAutoSave()
    }

    internal fun setCharacterEnabled(enabled: Boolean) = updateCharacter { it.copy(enabled = enabled) }

    internal fun setCharId(value: String) = updateCharacter {
        it.copy(charId = value.lowercase().filter { c -> c in 'a'..'z' || c in '0'..'9' || c == '-' || c == '_' }.take(64))
    }

    internal fun setCharName(value: String) = updateCharacter { it.copy(displayName = value) }

    internal fun setCharSide(side: CharacterSide) = updateCharacter { it.copy(side = side) }

    internal fun setCharFrameWidth(value: String) =
        updateCharacter { it.copy(frameWidth = value.filter { c -> c.isDigit() }.take(4)) }

    internal fun setCharFrameHeight(value: String) =
        updateCharacter { it.copy(frameHeight = value.filter { c -> c.isDigit() }.take(4)) }

    internal fun setCharScale(value: String) =
        updateCharacter { it.copy(scale = value.filter { c -> c.isDigit() || c == '.' || c == ',' }.replace(',', '.').take(5)) }

    internal fun setCharacterPosition(x: String, y: String) = updateCharacter {
        it.copy(positionX = x.take(7), positionY = y.take(7))
    }

    internal fun setCharacterCamera(x: String, y: String) = updateCharacter {
        it.copy(cameraX = x.take(7), cameraY = y.take(7))
    }

    internal fun setCharFlipX(enabled: Boolean) = updateCharacter { it.copy(flipX = enabled) }

    internal fun setCharIdleFrames(value: String) =
        updateCharacter { it.copy(idleFrames = value.filter { c -> c.isDigit() }.take(3)) }

    fun setCharLeftFrames(value: String) =
        updateCharacter { it.copy(leftFrames = value.filter { c -> c.isDigit() }.take(3)) }

    fun setCharDownFrames(value: String) =
        updateCharacter { it.copy(downFrames = value.filter { c -> c.isDigit() }.take(3)) }

    fun setCharUpFrames(value: String) =
        updateCharacter { it.copy(upFrames = value.filter { c -> c.isDigit() }.take(3)) }

    fun setCharRightFrames(value: String) =
        updateCharacter { it.copy(rightFrames = value.filter { c -> c.isDigit() }.take(3)) }

    internal fun nudgeCharacter(target: CharacterAdjustment, animation: String, dx: Int, dy: Int) {
        if (!busy && animation in config.character.animationNames) updateCharacter { it.nudged(target, animation, dx, dy) }
    }

    internal fun setIndividualFrames(enabled: Boolean) = updateCharacter { it.copy(useIndividualFrames = enabled, useAtlas = false) }
    internal fun setCharacterAtlas() = updateCharacter { it.copy(useAtlas = true) }
    internal fun clearCharacterIcon() = updateCharacter { it.copy(icon = null) }
    internal fun setAtlasPrefix(name: String, prefix: String) {
        if (!busy && name in config.character.animationNames) updateCharacter { it.copy(atlasPrefixes = it.atlasPrefixes + (name to prefix.take(200))) }
    }
    internal fun addCustomAnimation(name: String): Boolean {
        if (busy || !name.matches(Regex("[A-Za-z0-9_-]{1,32}")) || name in config.character.animationNames || config.character.customAnimations.size >= 30) return false
        updateCharacter { it.copy(customAnimations = it.customAnimations + name, customFrameCounts = it.customFrameCounts + (name to "0")) }
        return true
    }
    internal fun removeCustomAnimation(name: String) {
        if (busy || name !in config.character.customAnimations) return
        updateCharacter { it.copy(customAnimations = it.customAnimations - name, customFrameCounts = it.customFrameCounts - name,
            animationFrames = it.animationFrames - name, animationSettings = it.animationSettings - name, atlasPrefixes = it.atlasPrefixes - name) }
    }
    internal fun setCustomFrameCount(name: String, count: String) {
        if (!busy && name in config.character.customAnimations) updateCharacter { it.copy(customFrameCounts = it.customFrameCounts + (name to count.filter(Char::isDigit).take(3))) }
    }


    internal fun updateCharacterAnimation(name: String, update: (CharacterAnimationSettings) -> CharacterAnimationSettings) {
        if (busy || name !in config.character.animationNames) return
        updateCharacter { it.copy(animationSettings = it.animationSettings + (name to update(it.settingsFor(name)))) }
    }

    internal fun moveCharacterFrame(name: String, index: Int, direction: Int) {
        if (busy || name !in config.character.animationNames) return
        updateCharacter { draft ->
            val frames = draft.animationFrames[name].orEmpty().toMutableList()
            val target = index + direction
            if (index in frames.indices && target in frames.indices) {
                java.util.Collections.swap(frames, index, target)
            }
            draft.copy(animationFrames = draft.animationFrames + (name to frames))
        }
    }

    internal fun removeCharacterFrame(name: String, index: Int) {
        if (busy || name !in config.character.animationNames) return
        updateCharacter { draft ->
            val frames = draft.animationFrames[name].orEmpty().toMutableList()
            if (index in frames.indices) frames.removeAt(index)
            draft.copy(animationFrames = draft.animationFrames + (name to frames))
        }
    }

    private var pendingFrameAnimation: String? = null
    internal fun prepareCharacterFramePicker(name: String) {
        if (!busy && name in config.character.animationNames) pendingFrameAnimation = name
    }

    internal fun pickCharacterFrames(uris: List<Uri>) {
        val name = pendingFrameAnimation ?: return
        pendingFrameAnimation = null
        if (busy || uris.isEmpty()) return
        val currentCount = config.character.animationFrames.values.sumOf { it.size }
        if (currentCount + uris.size > 100) {
            error = "El máximo es 100 frames por personaje."
            return
        }
        val app = getApplication<Application>()
        busy = true
        error = null
        status = "Copiando frames para $name…"
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                val created = mutableListOf<File>()
                runCatching {
                    val inputDir = projectAssetDirectory()
                    uris.map { uri ->
                        val displayName = MediaTools.displayName(app, uri)
                        val target = File.createTempFile("frame_", ".png", inputDir).also { created.add(it) }
                        MediaTools.copyTo(app, uri, target)
                        if (!MediaTools.isPng(target)) throw IllegalStateException("$displayName no es un PNG válido.")
                        val size = MediaTools.imageSize(target)
                            ?: throw IllegalStateException("No se pudo leer $displayName.")
                        if (size.first !in 1..4096 || size.second !in 1..4096) {
                            throw IllegalStateException("$displayName supera el máximo de 4096 px por lado.")
                        }
                        CharacterFrame(PickedImage(uri, displayName, target.absolutePath), size.first, size.second)
                    }
                }.onFailure { created.forEach { it.delete() } }
            }
            result.onSuccess { frames ->
                updateCharacter { draft ->
                    val all = draft.animationFrames[name].orEmpty() + frames
                    draft.copy(animationFrames = draft.animationFrames + (name to all))
                }
            }.onFailure { error = it.message ?: "No se pudieron importar los frames." }
            status = ""
            busy = false
        }
    }

    // ---------------- Variaciones de personaje ----------------

    fun addCharacterVariation() {
        if (busy) return
        val newVar = com.fnfmodmaker.model.CharacterVariation(
            variationId = "var-${config.character.variations.size + 1}",
            displayName = "Variación ${config.character.variations.size + 1}",
            isDefault = config.character.variations.isEmpty()
        )
        val updated = config.character.variations + newVar
        config = config.copy(character = config.character.copy(variations = updated))
        scheduleAutoSave()
    }

    fun removeCharacterVariation(index: Int) {
        if (busy) return
        val updated = config.character.variations.toMutableList()
        updated.removeAt(index)
        // Si borramos la default, la primera se vuelve default
        if (updated.isNotEmpty() && !updated.any { it.isDefault }) {
            updated[0] = updated[0].copy(isDefault = true)
        }
        config = config.copy(character = config.character.copy(variations = updated))
        scheduleAutoSave()
    }

    /** Abre el editor de una variación específica. */
    fun openVariationEditor(index: Int) {
        if (busy) return
        activeVariationIndex = index
    }

    /** Cierra el editor de variación. */
    fun closeVariationEditor() {
        activeVariationIndex = null
    }

    /** Obtiene la variación que se está editando. */
    val activeVariation: com.fnfmodmaker.model.CharacterVariation?
        get() = activeVariationIndex?.let { config.character.variations.getOrNull(it) }

    /** Actualiza una variación en el config. */
    fun updateVariation(index: Int, block: (com.fnfmodmaker.model.CharacterVariation) -> com.fnfmodmaker.model.CharacterVariation) {
        if (busy) return
        val updated = config.character.variations.toMutableList()
        updated[index] = block(updated[index])
        config = config.copy(character = config.character.copy(variations = updated))
        scheduleAutoSave()
    }

    /** Establece la variación por defecto. */
    fun setDefaultVariation(index: Int) {
        if (busy) return
        val updated = config.character.variations.mapIndexed { i, v ->
            v.copy(isDefault = i == index)
        }
        config = config.copy(character = config.character.copy(variations = updated))
        scheduleAutoSave()
    }

    // ---------------- Variación: pickers ----------------

    fun pickVariationSheet(uri: Uri) {
        if (busy) return
        val app = getApplication<Application>()
        val index = activeVariationIndex ?: return
        busy = true
        error = null
        status = "Copiando spritesheet de variación…"
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val displayName = MediaTools.displayName(app, uri)
                    val safeName = displayName.replace(Regex("[^A-Za-z0-9._-]"), "_").ifBlank { "spritesheet.png" }
                    val target = newProjectAsset(safeName)
                    MediaTools.copyTo(app, uri, target)
                    val size = MediaTools.imageSize(target)
                        ?: throw IllegalStateException("No se pudo leer la imagen de la hoja de sprites.")
                    com.fnfmodmaker.model.PickedImage(uri = uri, displayName = displayName, path = target.absolutePath) to size
                }
            }
            result.onSuccess { (picked, size) ->
                updateVariation(activeVariationIndex!!) { it.copy(sheet = picked, sheetWidth = size.first, sheetHeight = size.second) }
            }.onFailure {
                error = it.message ?: "No se pudo leer la hoja de sprites."
            }
            status = ""
            busy = false
        }
    }

    fun pickVariationIcon(uri: Uri) {
        if (busy) return
        val app = getApplication<Application>()
        val index = activeVariationIndex ?: return
        busy = true
        error = null
        status = "Copiando icono de variación…"
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val displayName = MediaTools.displayName(app, uri)
                    val safeName = displayName.replace(Regex("[^A-Za-z0-9._-]"), "_").ifBlank { "icono.png" }
                    val target = newProjectAsset(safeName)
                    MediaTools.copyTo(app, uri, target)
                    com.fnfmodmaker.model.PickedImage(uri = uri, displayName = displayName, path = target.absolutePath)
                }
            }
            result.onSuccess { picked ->
                updateVariation(activeVariationIndex!!) { it.copy(icon = picked) }
            }.onFailure {
                error = it.message ?: "No se pudo leer la imagen del icono."
            }
            status = ""
            busy = false
        }
    }

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
                    val target = newProjectAsset(safeName)
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
                    val target = newProjectAsset(safeName)
                    MediaTools.copyTo(app, uri, target)
                    PickedImage(uri = uri, displayName = displayName, path = target.absolutePath)
                }
            }
            result.onSuccess { picked ->
                config = config.copy(icon = picked)
                scheduleAutoSave()
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
                    val target = newProjectAsset(safeName)
                    MediaTools.copyTo(app, uri, target)
                    if (!MediaTools.isPng(target)) {
                        target.delete()
                        throw IllegalStateException("La hoja de sprites debe ser un archivo PNG.")
                    }
                    val size = MediaTools.imageSize(target)
                        ?: throw IllegalStateException("No se pudo leer la imagen de la hoja de sprites.")
                    PickedImage(uri = uri, displayName = displayName, path = target.absolutePath) to size
                }
            }
            result.onSuccess { (picked, size) ->
                updateCharacter {
                    if (it.useAtlas) it.copy(atlasSheet = picked, atlasSheetWidth = size.first, atlasSheetHeight = size.second)
                    else it.copy(sheet = picked, sheetWidth = size.first, sheetHeight = size.second)
                }
            }.onFailure {
                error = it.message ?: "No se pudo leer la hoja de sprites."
            }
            status = ""
            busy = false
        }
    }

    internal fun pickCharacterAtlasXml(uri: Uri) {
        if (busy) return
        val app = getApplication<Application>()
        busy = true
        error = null
        status = "Leyendo XML Sparrow…"
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val input = app.contentResolver.openInputStream(uri) ?: error("No se pudo abrir el XML.")
                    val bytes = input.use { stream ->
                        val output = java.io.ByteArrayOutputStream()
                        val buffer = ByteArray(8192)
                        while (true) {
                            val n = stream.read(buffer)
                            if (n < 0) break
                            require(output.size() + n <= 4_000_000) { "El XML supera el máximo de 4 MB." }
                            output.write(buffer, 0, n)
                        }
                        output.toByteArray()
                    }
                    val xml = bytes.toString(Charsets.UTF_8)
                    val frames = parseSparrowAtlas(xml)
                    val dir = projectAssetDirectory()
                    val target = File.createTempFile("atlas_", ".xml", dir)
                    target.writeBytes(bytes)
                    PickedImage(uri, MediaTools.displayName(app, uri), target.absolutePath) to frames
                }
            }
            result.onSuccess { (xml, frames) ->
                updateCharacter { it.copy(atlasXml = xml, atlasFrames = frames) }
            }.onFailure { error = it.message ?: "No se pudo leer el XML Sparrow." }
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
                    val target = newProjectAsset(safeName)
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
                    val target = newProjectAsset(safeName)
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

    /** Importa un personaje desde un ZIP (generado por esta app) y carga en el config. */
    fun importCharacter(uri: Uri) {
        if (busy) return
        val app = getApplication<Application>()
        busy = true
        error = null
        status = "Importando personaje…"
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val workRoot = File(app.cacheDir, "charimport")
                    if (workRoot.exists()) workRoot.deleteRecursively()
                    workRoot.mkdirs()
                    val tempZip = File(workRoot, "import_${System.currentTimeMillis()}.zip")
                    app.contentResolver.openInputStream(uri)?.use { input ->
                        tempZip.outputStream().use { output -> input.copyTo(output) }
                    }
                    // Extraer y parsear character JSON
                    val extractDir = File(app.cacheDir, "charextract_${System.currentTimeMillis()}")
                    if (extractDir.exists()) extractDir.deleteRecursively()
                    extractDir.mkdirs()
                    java.util.zip.ZipFile(tempZip).use { zip ->
                        val entries = zip.entries()
                        while (entries.hasMoreElements()) {
                            val entry = entries.nextElement()
                            val outFile = File(extractDir, entry.name)
                            if (entry.isDirectory) outFile.mkdirs()
                            else {
                                outFile.parentFile?.mkdirs()
                                zip.getInputStream(entry).use { input -> outFile.outputStream().use { output -> input.copyTo(output) } }
                            }
                        }
                    }
                    // Buscar character JSON (Psych o V-Slice)
                    val charJsonFile = listOf(
                        File(extractDir, "characters").listFiles()?.firstOrNull { it.extension == "json" },
                        File(extractDir, "data/characters").listFiles()?.firstOrNull { it.extension == "json" }
                    ).firstOrNull { it != null } ?: throw IllegalStateException("No se encontró character JSON en el ZIP")
                    
                    val json = JSONObject(charJsonFile.readText())
                    val charId = json.optString("name", "").lowercase().replace(" ", "-")
                    val displayName = json.optString("name", charId)
                    val scale = json.optDouble("scale", 1.0).toFloat()
                    val flipX = json.optBoolean("flip_x", false)
                    val healthIcon = json.optString("healthicon", json.optString("healthIcon", ""))
                    val flipXFinal = flipX
                    
                    // Animaciones
                    val animations = json.optJSONArray("animations")
                    var idleFrames = "2"
                    var leftFrames = "1"
                    var downFrames = "1"
                    var upFrames = "1"
                    var rightFrames = "1"
                    for (index in 0 until (animations?.length() ?: 0)) {
                        val a = animations!!.getJSONObject(index)
                        val animName = a.optString("name", a.optString("anim", ""))
                        when (animName) {
                            "idle" -> idleFrames = "2"
                            "singLEFT" -> leftFrames = "1"
                            "singDOWN" -> downFrames = "1"
                            "singUP" -> upFrames = "1"
                            "singRIGHT" -> rightFrames = "1"
                        }
                    }
                    
                    // Spritesheet e icono
                    val spritesheet = File(extractDir, "images/characters/${charJsonFile.nameWithoutExtension}.png")
                    val iconFile = File(extractDir, "images/icons/icon-${charJsonFile.nameWithoutExtension}.png")
                    
                    val character = com.fnfmodmaker.model.CharacterDraft(
                        enabled = true,
                        charId = charId,
                        displayName = displayName,
                        sheet = if (spritesheet.exists()) com.fnfmodmaker.model.PickedImage(Uri.fromFile(spritesheet), spritesheet.name, spritesheet.absolutePath) else null,
                        sheetWidth = 0,
                        sheetHeight = 0,
                        icon = if (iconFile.exists()) com.fnfmodmaker.model.PickedImage(Uri.fromFile(iconFile), iconFile.name, iconFile.absolutePath) else null,
                        frameWidth = "150",
                        frameHeight = "150",
                        scale = scale.toString(),
                        flipX = flipXFinal,
                        side = com.fnfmodmaker.model.CharacterSide.OPPONENT,
                        idleFrames = idleFrames,
                        leftFrames = leftFrames,
                        downFrames = downFrames,
                        upFrames = upFrames,
                        rightFrames = rightFrames
                    )
                    
                    // Copiar spritesheet a cache si existe
                    val finalChar = if (character.sheet != null) {
                        val src = File(character.sheet!!.path)
                        val target = newProjectAsset(src.name)
                        src.copyTo(target, overwrite = true)
                        character.copy(sheet = com.fnfmodmaker.model.PickedImage(character.sheet!!.uri, character.sheet!!.displayName, target.absolutePath))
                    } else character
                    
                    finalChar
                }
            }
            result.onSuccess { imported ->
                config = config.copy(character = imported)
                status = "¡Personaje importado! Revisa el editor."
            }.onFailure {
                status = ""
                error = it.message ?: "No se pudo importar el personaje: ${it}"
            }
            busy = false
        }
    }

    // ---------------- Stage ----------------
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

    /** Importa un stage desde un ZIP y carga en el config. */
    fun importStage(uri: Uri) {
        if (busy) return
        val app = getApplication<Application>()
        busy = true
        error = null
        status = "Importando stage…"
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val workRoot = File(app.cacheDir, "stageimport")
                    if (workRoot.exists()) workRoot.deleteRecursively()
                    workRoot.mkdirs()
                    val tempZip = File(workRoot, "import_${System.currentTimeMillis()}.zip")
                    app.contentResolver.openInputStream(uri)?.use { input ->
                        tempZip.outputStream().use { output -> input.copyTo(output) }
                    }
                    val imported = com.fnfmodmaker.mod.ModImporter.importStageFromZip(app, tempZip, workRoot) { message -> status = message }
                    imported
                }
            }
            result.onSuccess { imported ->
                config = config.copy(stageDraft = imported)
                status = "¡Stage importado! Revisa el editor visual."
            }.onFailure {
                status = ""
                error = it.message ?: "No se pudo importar el stage: ${it}"
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
                    val target = newProjectAsset(safeName)
                    MediaTools.copyTo(app, uri, target)
                    com.fnfmodmaker.model.PickedImage(uri = uri, displayName = displayName, path = target.absolutePath)
                }
            }
            result.onSuccess { picked ->
                config = config.copy(stageDraft = config.stageDraft.copy(backgroundImage = picked)); scheduleAutoSave()
            }.onFailure {
                error = it.message ?: "No se pudo leer la imagen."
            }
            status = ""
            busy = false
        }
    }

    fun pickStageSprite(uri: Uri) {
        if (busy) return
        val app = getApplication<Application>()
        busy = true
        error = null
        status = "Copiando sprite…"
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val displayName = MediaTools.displayName(app, uri)
                    val safeName = displayName.replace(Regex("[^A-Za-z0-9._-]"), "_").ifBlank { "sprite.png" }
                    val target = newProjectAsset(safeName)
                    MediaTools.copyTo(app, uri, target)
                    com.fnfmodmaker.model.PickedImage(uri = uri, displayName = displayName, path = target.absolutePath)
                }
            }
            result.onSuccess { picked ->
                // El UI maneja la creación del sprite con esta imagen
                // Usamos un callback temporal o guardamos en un estado temporal
                // Por ahora notificamos al UI via status
                status = "SPRITE_PICKED:${picked.path}:${picked.displayName}"
            }.onFailure {
                error = it.message ?: "No se pudo leer la imagen."
            }
            status = ""
            busy = false
        }
    }

    fun setStageId(value: String) {
        if (busy) return
        config = config.copy(stageDraft = config.stageDraft.copy(stageId = slugify(value).take(40))); scheduleAutoSave()
    }
    internal fun setSimpleStageEnabled(enabled: Boolean) {
        if (busy) return
        config = config.copy(stageDraft = config.stageDraft.copy(enabled = enabled)); scheduleAutoSave()
    }
    internal fun setSimpleStageZoom(zoom: Float) {
        if (busy || !zoom.isFinite() || zoom !in 0.4f..2f) return
        config = config.copy(stageDraft = config.stageDraft.copy(cameraPosition = config.stageDraft.cameraPosition.copy(zoom = zoom))); scheduleAutoSave()
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
        if (!exportReady) {
            error = "Completa los datos pendientes antes de exportar."
            return
        }
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
    override fun onCleared() {
        closeLiveChart()
        releaseMediaPlayer()
        super.onCleared()
    }

}
