package com.fnfmodmaker.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.fnfmodmaker.model.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

private val previewColors = listOf(Color(0xFFC479E8), Color(0xFF54C8F4), Color(0xFF62D884), Color(0xFFFF7388))
private val previewArrows = listOf("←", "↓", "↑", "→")

/** Overview and lightweight timing exercise, not a runtime for either game engine. */
@Composable
internal fun ModPreviewScreen(vm: WizardViewModel, onClose: () -> Unit) {
    val config = vm.config
    var index by remember { mutableIntStateOf(vm.activeSongIndex.coerceIn(0, (config.songs.size - 1).coerceAtLeast(0))) }
    val song = config.songs.getOrNull(index)
    var difficulty by remember(index) { mutableStateOf(song?.orderedDifficulties?.firstOrNull() ?: "normal") }
    val audio = rememberAudioPreview()
    val hit = remember(index, difficulty) { mutableStateListOf<Int>() }
    var feedback by remember(index, difficulty) { mutableStateOf("Toca las flechas del jugador cuando las notas lleguen a los círculos.") }
    var animation by remember { mutableStateOf("idle") }
    val draft = config.character
    val animNames = remember(draft) { (listOf("idle") + draft.animations.filter { it.second > 0 }.map { it.first }).distinct() }
    if (animation !in animNames) animation = "idle"
    val custom = song?.let { config.getChart(it.songFolder, difficulty) }
    val duration = song?.instrumental?.durationMs ?: 0L
    val practice = remember(song?.bpmValue, duration, song?.practiceNotes) {
        if (song?.practiceNotes == true) previewPracticeNotes(song.bpmValue, duration) else emptyList()
    }
    val notes = custom?.notes ?: practice
    val isAudio = song?.instrumental != null && audio.path == song.instrumental.path
    val position = if (isAudio) audio.position else 0L

    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Scaffold(topBar = { Surface(tonalElevation = 4.dp) { Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) { Text("Vista conjunta del mod", fontWeight = FontWeight.Bold); Text("Simulación aproximada · no es el motor", style = MaterialTheme.typography.labelSmall) }
            TextButton(onClick = onClose) { Text("Cerrar") }
        } } }) { padding ->
            Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(config.modTitle.ifBlank { "Mod sin nombre" } + " · " + config.engine.label, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                if (song == null) Text("Añade una canción para ver y escuchar su chart.") else {
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        config.songs.forEachIndexed { i, value -> FilterChip(i == index, onClick = { audio.stop(); index = i }, label = { Text(value.songName.ifBlank { "Canción ${i + 1}" }) }) }
                    }
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        ALL_DIFFICULTIES.filter { it in song.difficulties }.forEach { d -> FilterChip(d == difficulty, onClick = { difficulty = d }, label = { Text(when (d) { "easy" -> "Fácil"; "hard" -> "Difícil"; else -> "Normal" }) }) }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Escena + notas · aproximación", modifier = Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
                    song?.instrumental?.let { track ->
                        TextButton(onClick = { audio.play(track) }, enabled = !audio.loading) { Text(if (audio.playing && isAudio) "Pausar" else "▶ Escuchar") }
                    }
                }
                CombinedScene(config.stageDraft, draft, animation, notes, hit.toSet(), position)
                if (song != null) {
                    Text("Prueba táctil orientativa · aciertos ${hit.size}", fontWeight = FontWeight.SemiBold)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                        previewArrows.forEachIndexed { lane, arrow ->
                            Button(onClick = {
                                val match = previewHitIndex(notes, hit.toSet(), lane, audio.currentPositionMs())
                                if (match != null) { hit.add(match); feedback = "¡Nota detectada!" } else feedback = "No había una nota cercana en esa flecha."
                            }, enabled = isAudio && audio.playing, modifier = Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 2.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = previewColors[lane], contentColor = Color.Black)) { Text(arrow, style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { contentDescription = "Probar nota ${listOf("izquierda", "abajo", "arriba", "derecha")[lane]}" }) }
                        }
                    }
                    Text(feedback, style = MaterialTheme.typography.labelSmall)
                    TextButton(onClick = { hit.clear(); feedback = "Contador reiniciado." }) { Text("Reiniciar contador") }
                }
                Text("Rival a la izquierda · jugador a la derecha. Fondo, escala y posiciones son aproximados; los personajes no proporcionados son siluetas.", style = MaterialTheme.typography.labelSmall)
                if (draft.enabled) {
                    if (draft.useAtlas && draft.atlasSheet == null || !draft.useAtlas && !draft.useIndividualFrames && draft.sheet == null) Text("Faltan imágenes del personaje para mostrarlo.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall)
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        animNames.forEach { name -> FilterChip(animation == name, onClick = { animation = name }, label = { Text(name) }) }
                    }
                    Text("Animación seleccionada manualmente; no responde automáticamente a las notas.", style = MaterialTheme.typography.labelSmall)
                }
                if (song != null) {
                    Text("${song.songName.ifBlank { "Canción sin nombre" }} · BPM ${song.bpm} · velocidad ${song.speed} · offset ${song.offsetMs} ms", style = MaterialTheme.typography.bodySmall)
                    Text(when { custom != null -> "Chart propio: ${notes.size} notas"; song.practiceNotes && song.bpmValue > 0 -> "Notas de práctica generadas: ${notes.size}"; song.practiceNotes -> "Notas de práctica: indica un BPM válido para previsualizarlas"; else -> "Sin chart propio ni notas de práctica" }, style = MaterialTheme.typography.bodySmall)
                    if (custom?.events?.isNotEmpty() == true || custom?.sections?.isNotEmpty() == true) Text("Eventos y secciones avanzadas no se reproducen.", color = MaterialTheme.colorScheme.error)
                    song.instrumental?.let { track ->
                        Text("Instrumental: ${track.displayName}", style = MaterialTheme.typography.labelMedium)
                        AudioPreviewControls(audio, track)
                        if (audio.error != null) Text(audio.error!!, color = MaterialTheme.colorScheme.error)
                    } ?: Text("Selecciona una instrumental en Audio para escuchar.")
                    Text("Voces: ${if (song.hasVocals) "añadidas, pero no se mezclan en esta vista" else "no añadidas"}.", style = MaterialTheme.typography.labelSmall)

                }
                HorizontalDivider()
                Text("Elementos del borrador", fontWeight = FontWeight.Bold)
                Text("Autor: ${config.author.ifBlank { "sin indicar" }} · ID: ${config.modFolder} · ${if (config.showInStoryMode) "Historia y Freeplay" else "Solo Freeplay"}", style = MaterialTheme.typography.bodySmall)
                config.icon?.let { Text("Icono: ${it.displayName}", style = MaterialTheme.typography.labelSmall); ImageAssetPreview(it.path, "Icono del mod") }
                Text("Canciones: ${config.songs.size} · Personaje propio: ${if (draft.enabled) draft.charId.ifBlank { "sin nombre" } else "no"} · Escenario propio: ${if (config.stageDraft.enabled) config.stageDraft.stageId.ifBlank { "sin nombre" } else "no"} · Scripts Psych: ${config.psychScripts.size}", style = MaterialTheme.typography.bodySmall)
                if (config.psychScripts.isNotEmpty()) Text("Scripts sin ejecutar: ${config.psychScripts.keys.sorted().joinToString()}", style = MaterialTheme.typography.labelSmall)
                if (draft.enabled) Text("Personaje: escala ${draft.scale} · posición ${draft.positionX}, ${draft.positionY} · cámara ${draft.cameraX}, ${draft.cameraY} · ${if (draft.flipX) "volteado" else "sin voltear"}", style = MaterialTheme.typography.labelSmall)
                Text("Esta prueba usa solo la instrumental, una ventana de toque aproximada y la velocidad visual fija. No ejecuta scripts, eventos, voces, cámara dinámica, sustain, físicas ni reglas de Psych/V-Slice. La latencia y el offset pueden diferir. Exporta y prueba en el juego para comprobar el resultado real.", style = MaterialTheme.typography.bodySmall)
                Text("Los cambios del borrador se reflejan al abrir la vista. No importa ZIP de otros mods; el proyecto actual se conserva mediante el autoguardado de la app.", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun CombinedScene(stage: StageDraft, draft: CharacterDraft, animation: String, notes: List<ChartNote>, hit: Set<Int>, position: Long) {
    val bg = imageThumbnail(if (stage.enabled) stage.backgroundImage?.path else null)
    val sources = remember(draft, animation) { if (draft.enabled) previewSources(draft, animation) else emptyList() }
    val frames by produceState<List<PreviewFrame>?>(null, sources) { value = withContext(Dispatchers.IO) { decodePreviewFrames(sources) } }
    var frameIndex by remember(sources) { mutableIntStateOf(0) }
    val settings = draft.settingsFor(animation)
    val fps = settings.fps.toIntOrNull()?.coerceIn(1, 60) ?: 24
    LaunchedEffect(sources, fps, settings.loop) {
        if (sources.size > 1) while (true) {
            delay((1000L / fps).coerceAtLeast(16))
            if (frameIndex == sources.lastIndex && !settings.loop) break
            frameIndex = (frameIndex + 1) % sources.size
        }
    }
    val frame = frames?.getOrNull(frameIndex)
    Canvas(Modifier.fillMaxWidth().aspectRatio(16f / 9f).semantics { contentDescription = "Escena aproximada del mod con fondo, personaje y notas. Las posiciones y reglas no son las del juego." }) {
        drawRect(Color(0xFF171322))
        val k = size.width / 1280f
        val zoom = if (stage.enabled) stage.cameraPosition.zoom.takeIf { it.isFinite() && it > 0 }?.coerceIn(.3f, 3f) ?: 1f else 1f
        clipRect {
            scale(zoom, zoom, pivot = Offset(size.width / 2, size.height / 2)) {
                if (bg != null) drawImage(bg, dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt()))
                drawRect(Color.Black.copy(alpha = .25f))
                val guides = listOf(GUIDE_OPPONENT, GUIDE_COMPANION, GUIDE_PLAYER)
                guides.forEachIndexed { i, guide ->
                    if (frame == null || !draft.enabled || (draft.side == CharacterSide.PLAYER && i != 2) || (draft.side == CharacterSide.OPPONENT && i != 0)) {
                        val c = listOf(Color(0xFFFF8D92), Color(0xFFDC83BF), Color(0xFF73CBFF))[i]
                        drawCircle(c.copy(alpha = .75f), 40f * k, Offset((guide.x + 90f) * k, (guide.y + 72f) * k))
                        drawRoundRect(c.copy(alpha = .55f), Offset((guide.x + 38f) * k, (guide.y + 120f) * k), Size(104f * k, 160f * k))
                    }
                }
                if (frame != null && draft.enabled) {
                    val anchor = characterGuideAnchor(draft.side)
                    val p = draft.placement()
                    val sc = draft.scaleValue.takeIf { it.isFinite() && it > 0 }?.coerceAtMost(20f) ?: 1f
                    val logicalX = (anchor.x + p.x - (settings.offsetX.toIntOrNull() ?: 0) * sc) * k
                    val x = logicalX - frame.frameX * sc * k
                    val y = (anchor.y + p.y - (settings.offsetY.toIntOrNull() ?: 0) * sc - frame.frameY * sc) * k
                    val w = (frame.width * sc * k).coerceIn(1f, 100000f).roundToInt()
                    val h = (frame.height * sc * k).coerceIn(1f, 100000f).roundToInt()
                    if (draft.flipX) scale(-1f, 1f, pivot = Offset(logicalX + frame.frameWidth * sc * k / 2, y + h / 2)) {
                        drawImage(frame.image, dstOffset = IntOffset(x.roundToInt(), y.roundToInt()), dstSize = IntSize(w, h))
                    } else drawImage(frame.image, dstOffset = IntOffset(x.roundToInt(), y.roundToInt()), dstSize = IntSize(w, h))
                }
            }
            val cell = size.width / 8f
            val target = size.height * .18f
            for (col in 0..7) drawCircle(previewColors[col % 4].copy(alpha = .35f), cell * .25f, Offset((col + .5f) * cell, target))
            notes.forEachIndexed { i, note ->
                if (note.lane !in 0..7 || i in hit) return@forEachIndexed
                val delta = note.timeMs - position
                if (delta !in -100L..2000L) return@forEachIndexed
                val col = if (note.lane < 4) note.lane + 4 else note.lane - 4
                val x = (col + .5f) * cell
                val y = target + delta / 2000f * (size.height - target)
                if (note.lengthMs > 0) {
                    val endY = (target + (delta.toDouble() + note.lengthMs) / 2000.0 * (size.height - target)).toFloat().coerceAtMost(size.height)
                    drawLine(previewColors[note.lane % 4].copy(alpha = .8f), Offset(x, y), Offset(x, endY), cell * .18f)
                }
                drawCircle(previewColors[note.lane % 4], cell * .22f, Offset(x, y))
            }
        }
    }
}
