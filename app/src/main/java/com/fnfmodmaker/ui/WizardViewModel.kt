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
import com.fnfmodmaker.model.track
import com.fnfmodmaker.model.withTrack
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
import com.fnfmodmaker.mod.buildAndWriteModZip
import com.fnfmodmaker.mod.ModArchiveLibrary
import com.fnfmodmaker.mod.ArchivedMod
import com.fnfmodmaker.modimport.ModZipInspector
import com.fnfmodmaker.modimport.ModZipReview
import com.fnfmodmaker.modimport.InventoriedFile
import com.fnfmodmaker.modimport.PsychModImporter
import com.fnfmodmaker.modimport.PreparedPsychImport
import com.fnfmodmaker.modimport.VSliceModImporter
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import com.fnfmodmaker.project.ModImportSession
import com.fnfmodmaker.project.ProjectConfigValidator
import com.fnfmodmaker.project.migrateCharacterLibrary
import com.fnfmodmaker.project.requireSupportedChartSchema
import com.fnfmodmaker.project.ProjectStore
import com.fnfmodmaker.project.ProjectSummary
import com.fnfmodmaker.project.PortableProject
import com.fnfmodmaker.project.PreparedPortable
import com.fnfmodmaker.project.PortableSummary
import com.fnfmodmaker.model.DragState
import com.fnfmodmaker.model.SelectedNote
import java.io.File
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import com.fnfmodmaker.model.ChartEngine
import com.fnfmodmaker.model.IndependentChartKey
import com.fnfmodmaker.project.LegacyChartMigration
import com.fnfmodmaker.model.ChartPersistentHistory
import com.fnfmodmaker.model.ChartPersistentHistory.Companion.recordChartHistory
import com.fnfmodmaker.model.ChartPersistentHistory.Companion.restoreChartHistory

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

    private var lastAiApplied: com.fnfmodmaker.ai.ProjectProposal? = null
    private var lastAiProjectId: String? = null
    val canUndoAi: Boolean get() = lastAiApplied != null && currentProjectId == lastAiProjectId && config == lastAiApplied?.result

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
                com.fnfmodmaker.ai.AiClient.propose(baseUrl, key, model, question, context)
            } }
            result.onSuccess { response -> acceptAiProposal(snapshot, response, characterOnly) }
                .onFailure { aiError = if (it is com.fnfmodmaker.ai.AiException) it.message else "No se pudo preparar la propuesta. Comprueba el proveedor y que el modelo admita herramientas." }
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
        lastAiApplied = proposal; lastAiProjectId = currentProjectId
        config = updated
        if (proposal.base.charts != updated.charts) { updateChartEditorState(); initChartHistory() }
        characterSaved = null
        aiProposal = null
        aiError = null
        aiProposalNotice = "Cambios aplicados al proyecto; guardando automáticamente. Revisa el estado de guardado."
        scheduleAutoSave(); forceAutoSave()
    }

    internal fun undoAiBatch() {
        val batch = lastAiApplied ?: return
        if (busy || aiBusy) return
        if (!canUndoAi) { aiError = "El proyecto cambió después del lote; no se puede deshacer sin sobrescribir cambios posteriores."; return }
        config = batch.base
        if (batch.base.charts != batch.result.charts) { updateChartEditorState(); initChartHistory() }
        lastAiApplied = null; lastAiProjectId = null; aiProposal = null; characterSaved = null
        aiError = null; aiProposalNotice = "Lote de IA deshecho; guardando el proyecto."
        scheduleAutoSave(); forceAutoSave()
    }

    /** Shared response path used by the network adapter and deterministic tests. */
    internal fun acceptAiProposal(snapshot: ModConfig, response: com.fnfmodmaker.ai.AiProtocol.ProjectResponse, characterOnly: Boolean) {
        aiResult = response.text
        if (config != snapshot) { aiError = "El proyecto cambió mientras se preparaba la propuesta. Vuelve a solicitarla."; return }
        if (response.arguments == null) { aiProposalNotice = "El modelo no devolvió llamadas a herramientas. Comprueba que el proveedor y el modelo admiten herramientas; no se aplicó ningún cambio."; return }
        try { aiProposal = com.fnfmodmaker.ai.ProjectProposalPlanner.plan(snapshot, response.arguments, characterOnly) }
        catch (e: com.fnfmodmaker.ai.AiException) { aiError = e.message }
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
                val output = app.contentResolver.openOutputStream(uri, "w") ?: throw IllegalStateException()
                output.use { output -> file.inputStream().use { input -> input.copyTo(output) } }
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

    /** Motor que se está editando (v2): Psych o V-Slice. Block ambiguous edits. */
    var activeChartEngine by mutableStateOf<ChartEngine?>(null)
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
            if (chartRepository.isV2()) {
                val engine = activeChartEngine ?: return ChartDraft(
                    songFolder = song.songFolder, difficulty = difficulty,
                    scrollSpeed = song.speedValue, bpm = song.bpmValue, offsetMs = song.offsetValue,
                    needsVoices = song.hasVocals, player1 = song.player, player2 = song.opponent,
                    gfVersion = song.girlfriend, stage = song.stagePsych)
                val key = IndependentChartKey(song.songFolder, engine, difficulty)
                return chartRepository.getChart(key)
                    ?: ChartDraft(
                        songFolder = song.songFolder, difficulty = difficulty,
                        scrollSpeed = song.speedValue, bpm = song.bpmValue, offsetMs = song.offsetValue,
                        needsVoices = song.hasVocals, player1 = song.player, player2 = song.opponent,
                        gfVersion = song.girlfriend, stage = song.stagePsych)
            }
            return config.getChart(song.songFolder, difficulty)
                ?: ChartDraft(
                    songFolder = song.songFolder, difficulty = difficulty,
                    scrollSpeed = song.speedValue, bpm = song.bpmValue, offsetMs = song.offsetValue,
                    needsVoices = song.hasVocals, player1 = song.player, player2 = song.opponent,
                    gfVersion = song.girlfriend, stage = song.stagePsych)
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
    private data class PreviewVoice(val track: com.fnfmodmaker.model.PreviewTrack, val player: MediaPlayer, var prepared: Boolean = false, var seeking: Boolean = false)
    private val previewVoices = mutableListOf<PreviewVoice>()
    private var livePolling: kotlinx.coroutines.Job? = null
    private var liveFocusHeld = false
    private data class LiveUndo(val note: ChartNote, val createdChart: Boolean)
    private val liveUndo = mutableListOf<LiveUndo>()
    private data class LiveHold(val id: Long, val direction: Int, val note: ChartNote, val pressedAt: Long)
    private val liveHolds = mutableMapOf<Int, LiveHold>()
    private var nextLiveHoldId = 0L
    // Snapshot once per recording session: tempo cannot be edited during playback.
    private var liveRecordingGrid: com.fnfmodmaker.model.TouchGrid? = null
    internal val liveUsesVSliceSnap: Boolean get() = config.engine.includesVSlice &&
        !(config.engine == TargetEngine.BOTH && liveSong?.psychSnapReference == true)
    internal fun setLivePsychSnapReference(psych: Boolean) {
        check(config.engine == TargetEngine.BOTH && !busy && !livePlaying && !liveLoading) { "Pausa el editor para cambiar el mapa de ajuste." }
        val song = liveSong ?: error("Selecciona una canción.")
        if (song.psychSnapReference == psych) return
        val songs = config.songs.toMutableList()
        songs[liveSongIndex] = song.copy(psychSnapReference = psych)
        config = config.copy(songs = songs)
        scheduleAutoSave(); forceAutoSave()
    }
    internal fun liveTempoGrid(divisions: Int): com.fnfmodmaker.model.TouchGrid {
        val song = liveSong ?: error("Selecciona una canción.")
        val vslice = liveUsesVSliceSnap
        val tempos = if (vslice) com.fnfmodmaker.mod.vSliceGridTempos(liveVSliceTempo())
            else liveChart?.psychLayout?.takeIf { it.isNotEmpty() }?.let { com.fnfmodmaker.model.psychGridTempos(it, song.bpmValue) }.orEmpty()
        return com.fnfmodmaker.model.TouchGrid(song.bpmValue, divisions, liveDurationMs.coerceAtLeast(1L), tempos, vslice)
    }
    internal fun recordingTempoGrid(): com.fnfmodmaker.model.TouchGrid? {
        if (liveSnap == com.fnfmodmaker.model.RecordingSnap.FREE) return null
        return liveTempoGrid(liveSnap.divisionsPerBeat)
    }
    val livePreviewNotes: List<ChartNote> get() {
        val chart = liveChart ?: return emptyList()
        val song = liveSong ?: return chart.notes
        if (liveHolds.isEmpty()) return chart.notes
        return chart.notes.map { note ->
            val hold = liveHolds.values.firstOrNull { it.note == note } ?: return@map note
            note.copy(lengthMs = com.fnfmodmaker.model.recordedHoldLength(note, hold.pressedAt, livePositionMs,
                liveCorrectionMs, song.bpmValue, liveSnap, liveDurationMs, chart.notes, liveRecordingGrid))
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
    val liveChart: ChartDraft? get() {
        val song = liveSong ?: return null
        return if (chartRepository.isV2()) {
            val engine = activeChartEngine ?: return null
            chartRepository.getChart(IndependentChartKey(song.songFolder, engine, liveDifficulty))
        } else {
            config.getChart(song.songFolder, liveDifficulty)
        }
    }
    val liveChartAvailable: Boolean get() {
        val song = liveSong ?: return false
        val chart = liveChart
        return song.songName.isNotBlank() && config.songs.count { it.songFolder == song.songFolder } == 1 &&
            liveDifficulty in song.difficulties && !(chart == null && liveDifficulty in (song.vSliceOrigin?.uneditableDifficulties ?: emptySet())) &&
            (chart == null || (chart.events.isEmpty() && chart.sections.isEmpty()))
    }
    val liveCanPlay: Boolean get() = liveChartAvailable && liveSong?.instrumental?.let { File(it.path).isFile } == true

    /** V-Slice events belong to the song chart, not an individual difficulty. */
    internal fun liveVSliceEvents(): String {
        val song = liveSong ?: error("Selecciona una canción.")
        if (chartRepository.isV2()) {
            return chartRepository.getVSliceEvents(song.songFolder, ChartEngine.VSLICE) ?: ""
        }
        song.vSliceEventsJson?.let { return com.fnfmodmaker.mod.validateVSliceEvents(it) }
        val origin = song.vSliceOrigin ?: return "[]"
        val source = com.fnfmodmaker.mod.originalVSliceText(config, origin.chartPath, File(getApplication<Application>().applicationInfo.dataDir), true)
        com.fnfmodmaker.modimport.readVSliceJson(source, "chart original")
        com.fnfmodmaker.mod.rejectDuplicateJsonKeys(source)
        val tree = com.google.gson.JsonParser.parseString(source).asJsonObject
        val events = tree.get("events") ?: return "[]"
        return com.fnfmodmaker.mod.validateVSliceEvents(events.toString())
    }
    internal fun saveLiveVSliceEvents(text: String) {
        check((if (chartRepository.isV2()) activeChartEngine == ChartEngine.VSLICE else config.engine.includesVSlice)
            && !livePlaying && !liveLoading && !busy) { "Pausa el editor antes de guardar." }
        val song = liveSong ?: error("Selecciona una canción.")
        if (chartRepository.isV2()) {
            config = chartRepository.saveVSliceEvents(song.songFolder, ChartEngine.VSLICE, text)
        } else {
            val edited = com.fnfmodmaker.mod.validateVSliceEvents(text)
            val before = liveVSliceEvents()
            if (edited == before) return
            val songs = config.songs.toMutableList()
            songs[liveSongIndex] = song.copy(vSliceEventsJson = edited, vSliceEventsUndo = (song.vSliceEventsUndo + before).takeLast(10), vSliceEventsRedo = emptyList())
            config = config.copy(songs = songs)
        }
        scheduleAutoSave(); forceAutoSave()
    }
    internal fun undoLiveVSliceEvents() {
        check((if (chartRepository.isV2()) activeChartEngine == ChartEngine.VSLICE else config.engine.includesVSlice)
            && !livePlaying && !liveLoading && !busy) { "Pausa el editor antes de deshacer." }
        val song = liveSong ?: error("Selecciona una canción.")
        if (chartRepository.isV2()) {
            config = chartRepository.undoVSliceEvents(song.songFolder, ChartEngine.VSLICE)
        } else {
            val previous = song.vSliceEventsUndo.lastOrNull() ?: error("No hay cambios de eventos para deshacer.")
            com.fnfmodmaker.mod.validateVSliceEvents(previous)
            val songs = config.songs.toMutableList()
            songs[liveSongIndex] = song.copy(vSliceEventsJson = previous, vSliceEventsUndo = song.vSliceEventsUndo.dropLast(1), vSliceEventsRedo = (song.vSliceEventsRedo + liveVSliceEvents()).takeLast(10))
            config = config.copy(songs = songs)
        }
        scheduleAutoSave(); forceAutoSave()
    }

    internal fun redoLiveVSliceEvents() {
        check((if (chartRepository.isV2()) activeChartEngine == ChartEngine.VSLICE else config.engine.includesVSlice)
            && !livePlaying && !liveLoading && !busy) { "Pausa el editor antes de rehacer." }
        val song = liveSong ?: error("Selecciona una canción.")
        if (chartRepository.isV2()) {
            config = chartRepository.redoVSliceEvents(song.songFolder, ChartEngine.VSLICE)
        } else {
            val next = song.vSliceEventsRedo.lastOrNull() ?: error("No hay cambios de eventos para rehacer.")
            com.fnfmodmaker.mod.validateVSliceEvents(next)
            val songs = config.songs.toMutableList()
            songs[liveSongIndex] = song.copy(vSliceEventsJson = next, vSliceEventsRedo = song.vSliceEventsRedo.dropLast(1), vSliceEventsUndo = (song.vSliceEventsUndo + liveVSliceEvents()).takeLast(10))
            config = config.copy(songs = songs)
        }
        scheduleAutoSave(); forceAutoSave()
    }

    /** Song-wide V-Slice time changes. Original metadata stays untouched in project assets. */
    internal fun liveVSliceTempo(): String {
        val song = liveSong ?: error("Selecciona una canción.")
        if (chartRepository.isV2()) {
            return chartRepository.getVSliceTempo(song.songFolder, ChartEngine.VSLICE) ?: ""
        }
        val stored = song.vSliceTempoJson ?: song.vSliceOrigin?.let { origin ->
            val text = com.fnfmodmaker.mod.originalVSliceText(config, origin.metadataPath, File(getApplication<Application>().applicationInfo.dataDir), false)
            com.fnfmodmaker.modimport.readVSliceJson(text, "metadata original")
            com.fnfmodmaker.mod.rejectDuplicateJsonKeys(text)
            com.google.gson.JsonParser.parseString(text).asJsonObject.get("timeChanges")?.toString() ?: error("Falta timeChanges.")
        } ?: """[{"t":0,"bpm":${song.bpmValue},"n":4,"d":4}]"""
        return if (song.vSliceIndependentInitialBpm && song.vSliceTempoJson != null) com.fnfmodmaker.mod.validateVSliceTempo(stored)
            else com.fnfmodmaker.mod.tempoWithInitialBpm(stored, song.bpmValue)
    }
    internal fun saveLiveVSliceTempo(text: String) {
        check((if (chartRepository.isV2()) activeChartEngine == ChartEngine.VSLICE else config.engine.includesVSlice)
            && !livePlaying && !liveLoading && !busy) { "Pausa el editor y selecciona V-Slice." }

        val song = liveSong ?: error("Selecciona una canción.")
        if (chartRepository.isV2()) {
            config = chartRepository.saveVSliceTempo(song.songFolder, ChartEngine.VSLICE, text)
        } else {
            val before = liveVSliceTempo()
            val edited = com.fnfmodmaker.mod.validateVSliceTempo(text)
            if (before == edited) return
            val bpm = com.google.gson.JsonParser.parseString(edited).asJsonArray[0].asJsonObject.get("bpm").asString
            val songs = config.songs.toMutableList()
            val independent = song.vSliceIndependentInitialBpm || config.engine == TargetEngine.BOTH
            songs[liveSongIndex] = song.copy(bpm = if (independent) song.bpm else bpm.toString(), vSliceIndependentInitialBpm = independent, vSliceTempoJson = edited, vSliceTempoUndo = (song.vSliceTempoUndo + before).takeLast(10), vSliceTempoRedo = emptyList())
            config = config.copy(songs = songs)
        }
        scheduleAutoSave(); forceAutoSave()
    }
    internal fun restoreLiveVSliceTempo(redo: Boolean) {
        check((if (chartRepository.isV2()) activeChartEngine == ChartEngine.VSLICE else config.engine.includesVSlice)
            && !livePlaying && !liveLoading && !busy) { "Pausa el editor y selecciona V-Slice." }
        val song = liveSong ?: error("Selecciona una canción.")
        if (chartRepository.isV2()) {
            config = if (redo) chartRepository.redoVSliceTempo(song.songFolder, ChartEngine.VSLICE)
            else chartRepository.undoVSliceTempo(song.songFolder, ChartEngine.VSLICE)
        } else {
            val previous = (if (redo) song.vSliceTempoRedo else song.vSliceTempoUndo).lastOrNull() ?: error("No hay cambio de tempo que restaurar.")
            val checked = com.fnfmodmaker.mod.validateVSliceTempo(previous)
            val bpm = com.google.gson.JsonParser.parseString(checked).asJsonArray[0].asJsonObject.get("bpm").asString
            val current = liveVSliceTempo()
            val independent = song.vSliceIndependentInitialBpm || config.engine == TargetEngine.BOTH
            val songs = config.songs.toMutableList()
            songs[liveSongIndex] = if (redo) song.copy(bpm = if (independent) song.bpm else bpm.toString(), vSliceIndependentInitialBpm = independent, vSliceTempoJson = checked, vSliceTempoRedo = song.vSliceTempoRedo.dropLast(1), vSliceTempoUndo = (song.vSliceTempoUndo + current).takeLast(10))
                else song.copy(bpm = if (independent) song.bpm else bpm.toString(), vSliceIndependentInitialBpm = independent, vSliceTempoJson = checked, vSliceTempoUndo = song.vSliceTempoUndo.dropLast(1), vSliceTempoRedo = (song.vSliceTempoRedo + current).takeLast(10))
            config = config.copy(songs = songs)
        }
        scheduleAutoSave(); forceAutoSave()
    }
}