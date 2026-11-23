package com.fnfmodmaker.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.fnfmodmaker.model.ALL_DIFFICULTIES
import com.fnfmodmaker.model.ChartNote
import com.fnfmodmaker.model.RecordingSnap

private fun clock(ms: Long): String {
    val seconds = (ms.coerceAtLeast(0) / 1000)
    return "%d:%02d".format(seconds / 60, seconds % 60)
}
private val laneColors = listOf(Color(0xFFC479E8), Color(0xFF54C8F4), Color(0xFF62D884), Color(0xFFFF7388))
private val arrows = listOf("←", "↓", "↑", "→")
private val arrowNames = listOf("izquierda", "abajo", "arriba", "derecha")

@Composable
internal fun LiveChartEditor(vm: WizardViewModel, onClose: () -> Unit) {
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) vm.pauseLivePlayback()
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer); vm.closeLiveChart() }
    }
    Dialog(onDismissRequest = { vm.closeLiveChart(); onClose() },
        properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Scaffold(containerColor = MaterialTheme.colorScheme.background,
            topBar = {
                Surface(color = MaterialTheme.colorScheme.surface) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Grabación en vivo", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                            Text("${vm.liveSong?.songName?.ifBlank { "Sin nombre" } ?: "Sin canción"} · ${vm.liveDifficulty}", style = MaterialTheme.typography.labelSmall)
                        }
                        TextButton(onClick = { vm.closeLiveChart(); onClose() }) { Text("Listo") }
                    }
                }
            }) { padding ->
            BoxWithConstraints(Modifier.fillMaxSize().padding(padding)) {
                val wide = maxWidth >= 650.dp || (maxWidth > maxHeight && maxWidth >= 480.dp)
                if (wide) {
                    Row(Modifier.fillMaxSize().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) { LiveSetup(vm) }
                        Column(Modifier.weight(1f).fillMaxSize()) {
                            Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) { LiveTransport(vm) }
                            ArrowPad(vm)
                        }
                    }
                } else {
                    Column(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
                        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                            LiveSetup(vm)
                            LiveTransport(vm)
                            Spacer(Modifier.height(12.dp))
                        }
                        ArrowPad(vm)
                    }
                }
            }
        }
    }
}

@Composable
private fun LiveSetup(vm: WizardViewModel) {
    val locked = vm.livePlaying || vm.liveLoading
    Text("1 · Canción y dificultad", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        vm.config.songs.forEachIndexed { index, song ->
            FilterChip(index == vm.liveSongIndex, { vm.selectLiveSong(index) }, enabled = !locked,
                label = { Text(song.songName.ifBlank { "Canción ${index + 1}" }) })
        }
    }
    val song = vm.liveSong
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        ALL_DIFFICULTIES.filter { it in (song?.difficulties ?: emptySet()) }.forEach { difficulty ->
            FilterChip(difficulty == vm.liveDifficulty, { vm.selectLiveDifficulty(difficulty) }, enabled = !locked,
                label = { Text(when (difficulty) { "easy" -> "Fácil"; "normal" -> "Normal"; else -> "Difícil" }) })
        }
    }
    if (song == null || song.songName.isBlank() || vm.config.songs.count { it.songFolder == song.songFolder } > 1)
        Text("La canción debe tener un nombre único en el paso Canciones.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
    else if (song.difficulties.isEmpty()) Text("Activa al menos una dificultad en Canciones.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
    else if (vm.liveChart?.let { it.events.isNotEmpty() || it.sections.isNotEmpty() } == true)
        Text("Este chart contiene eventos o secciones que la app aún no puede exportar. No se puede grabar encima para evitar perderlos.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
    else if (song.instrumental == null || !vm.liveCanPlay)
        Text("Elige una instrumental disponible en el paso Audio.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
    else Text("Se reproduce la instrumental; las voces no se reproducen aquí.", style = MaterialTheme.typography.bodySmall)
    Spacer(Modifier.height(8.dp))
    Text("2 · Lado que vas a grabar", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        FilterChip(!vm.liveOpponent, { vm.selectLiveSide(false) }, enabled = !locked, label = { Text("Jugador") })
        FilterChip(vm.liveOpponent, { vm.selectLiveSide(true) }, enabled = !locked, label = { Text("Rival") })
    }
    Text("Se añaden notas al lado elegido; cambia de lado con la reproducción pausada.", style = MaterialTheme.typography.labelSmall)
    Text("Ajuste al ritmo · opcional", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        RecordingSnap.entries.forEach { snap -> FilterChip(vm.liveSnap == snap, { vm.selectLiveSnap(snap) }, enabled = !locked, label = { Text(snap.label) }) }
    }
    Text("Libre guarda el momento exacto. Los demás usan el BPM de la canción (${song?.bpm ?: "—"}).", style = MaterialTheme.typography.labelSmall)
    Text("Corrección de toque: ${if (vm.liveCorrectionMs > 0) "+" else ""}${vm.liveCorrectionMs} ms", fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodySmall)
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        TextButton(onClick = { vm.setLiveCorrection(vm.liveCorrectionMs - 10) }, enabled = !locked) { Text("−10 ms") }
        TextButton(onClick = { vm.setLiveCorrection(0) }, enabled = !locked) { Text("Restablecer") }
        TextButton(onClick = { vm.setLiveCorrection(vm.liveCorrectionMs + 10) }, enabled = !locked) { Text("+10 ms") }
    }
    Text("− graba antes; + graba después. Es independiente del offset de la canción. La latencia depende del dispositivo; usa auriculares con cable y prueba en el juego.", style = MaterialTheme.typography.labelSmall)
    Spacer(Modifier.height(12.dp))
}

@Composable
private fun LiveTransport(vm: WizardViewModel) {
    var confirmPractice by rememberSaveable { mutableStateOf(false) }
    var confirmDelete by rememberSaveable { mutableStateOf(false) }
    Text("3 · Escucha y graba", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
    Text(if (vm.liveLoading) "Preparando audio…" else if (vm.liveRecording) "● GRABANDO · toca las flechas" else if (vm.livePlaying) "▶ Escuchando · no se graban toques" else "Pausado",
        color = if (vm.liveRecording) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
    vm.liveError?.let { MessageCard(it, error = true) }
    vm.liveNotice?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary) }
    Text("${clock(vm.livePositionMs)} / ${clock(vm.liveDurationMs)}", style = MaterialTheme.typography.bodySmall)
    Slider(value = vm.livePositionMs.coerceIn(0L, vm.liveDurationMs.coerceAtLeast(1L)).toFloat(),
        onValueChange = { vm.setLivePosition(it.toLong()) }, valueRange = 0f..vm.liveDurationMs.coerceAtLeast(1L).toFloat(),
        enabled = !vm.livePlaying && !vm.liveLoading && vm.liveDurationMs > 0)
    Text("Pausa para mover el punto de inicio.", style = MaterialTheme.typography.labelSmall)
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        if (vm.livePlaying || vm.liveLoading) {
            Button(onClick = vm::pauseLivePlayback) { Text(if (vm.liveLoading) "Cancelar" else "Pausar") }
        } else {
            Button(onClick = {
                if (vm.liveChart == null && vm.liveSong?.practiceNotes == true) confirmPractice = true else vm.startLivePlayback(true)
            }, enabled = vm.liveCanPlay) { Text("● Grabar") }
            OutlinedButton(onClick = { vm.startLivePlayback(false) }, enabled = vm.liveCanPlay) { Text("▶ Escuchar") }
            TextButton(onClick = { vm.setLivePosition(0) }, enabled = vm.livePositionMs > 0) { Text("Al inicio") }
        }
    }
    if (confirmPractice) AlertDialog(onDismissRequest = { confirmPractice = false }, title = { Text("Grabar tu propio chart") },
        text = { Text("Al guardar la primera nota se dejarán de generar las notas de práctica para esta dificultad. Las demás dificultades no cambian. Puedes deshacer la nota o borrar el chart personalizado.") },
        confirmButton = { TextButton(onClick = { confirmPractice = false; vm.startLivePlayback(true) }) { Text("Entendido") } },
        dismissButton = { TextButton(onClick = { confirmPractice = false }) { Text("Cancelar") } })
    val chart = vm.liveChart
    Text(if (chart != null) "Chart personalizado: ${chart.notes.size} notas" else if (vm.liveSong?.practiceNotes == true) "Sin chart personalizado: se generarán notas de práctica." else "Sin chart personalizado: se exportará vacío.", style = MaterialTheme.typography.bodySmall)
    LiveTimeline(chart?.notes.orEmpty(), vm.livePositionMs)
    Text("La línea indica el momento actual; arriba: jugador, abajo: rival. Las notas de práctica automáticas no se muestran.", style = MaterialTheme.typography.labelSmall)
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        TextButton(onClick = vm::undoLiveNote, enabled = !vm.livePlaying && !vm.liveLoading && vm.liveUndoCount > 0) { Text("Deshacer último toque") }
        if (chart != null) TextButton(onClick = { confirmDelete = true }, enabled = !vm.livePlaying && !vm.liveLoading) { Text("Borrar chart propio") }
    }
    if (confirmDelete) AlertDialog(onDismissRequest = { confirmDelete = false }, title = { Text("¿Borrar el chart de ${vm.liveDifficulty}?") },
        text = { Text("Se eliminarán todas las notas y cualquier evento o sección de esta dificultad. Las demás dificultades no cambian. Volverán las notas de práctica si están activadas.") },
        confirmButton = { TextButton(onClick = { vm.deleteLiveCustomChart(); confirmDelete = false }) { Text("Borrar") } },
        dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancelar") } })
    if (chart != null && chart.notes.isNotEmpty()) RecentNotes(vm, chart.notes)
}

@Composable
private fun RecentNotes(vm: WizardViewModel, notes: List<ChartNote>) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    var pending by remember { mutableStateOf<ChartNote?>(null) }
    TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "Ocultar últimas notas ↑" else "Ver últimas notas ↓") }
    if (expanded) {
        Text("Se muestran hasta las últimas 30 notas por tiempo.", style = MaterialTheme.typography.labelSmall)
        notes.takeLast(30).asReversed().forEach { note ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("${clock(note.timeMs)}.${(note.timeMs % 1000).toString().padStart(3, '0')} · ${if (note.lane < 4) "Jugador" else "Rival"} ${arrows[note.lane.coerceIn(0, 7) % 4]}${if (note.lengthMs > 0) " · sostenida ${note.lengthMs} ms" else ""}",
                    Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = { pending = note }, enabled = !vm.livePlaying && !vm.liveLoading) { Text("Quitar") }
            }
        }
    }
    pending?.let { note -> AlertDialog(onDismissRequest = { pending = null }, title = { Text("¿Quitar esta nota?") },
        text = { Text("${clock(note.timeMs)} · ${if (note.lane < 4) "Jugador" else "Rival"} ${arrows[note.lane.coerceIn(0, 7) % 4]}") },
        confirmButton = { TextButton(onClick = { vm.deleteLiveNote(note); pending = null }) { Text("Quitar") } },
        dismissButton = { TextButton(onClick = { pending = null }) { Text("Cancelar") } }) }
}

@Composable
private fun LiveTimeline(notes: List<ChartNote>, positionMs: Long) {
    Canvas(Modifier.fillMaxWidth().height(100.dp).semantics { contentDescription = "Línea de tiempo de notas alrededor del momento de reproducción" }) {
        drawRect(Color(0xFF201A29))
        val laneHeight = size.height / 8f
        for (lane in 0..8) drawLine(Color.White.copy(alpha = .13f), Offset(0f, lane * laneHeight), Offset(size.width, lane * laneHeight), 1f)
        val windowMs = 6000f
        for (note in notes) {
            val x = size.width / 2f + (note.timeMs - positionMs) / windowMs * size.width
            if (x in 0f..size.width && note.lane in 0..7) {
                val y = (note.lane + .5f) * laneHeight
                if (note.lengthMs > 0) drawLine(laneColors[note.lane % 4], Offset(x, y), Offset((x + note.lengthMs / windowMs * size.width).coerceAtMost(size.width), y), laneHeight * .4f)
                drawCircle(laneColors[note.lane % 4], radius = laneHeight * .34f, center = Offset(x, y))
            }
        }
        drawLine(Color.White, Offset(size.width / 2f, 0f), Offset(size.width / 2f, size.height), 2f)
    }
}

@Composable
private fun ArrowPad(vm: WizardViewModel) {
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Text(if (vm.liveRecording) "Toca al ritmo · ${if (vm.liveOpponent) "Rival" else "Jugador"}" else "Pulsa Grabar para activar las flechas", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            arrows.forEachIndexed { index, symbol ->
                val enabled = vm.liveRecording && vm.livePlaying
                var pressed by remember(enabled) { mutableStateOf(false) }
                Surface(color = if (pressed) laneColors[index] else laneColors[index].copy(alpha = if (enabled) .35f else .12f),
                    shape = RoundedCornerShape(12.dp), modifier = Modifier.weight(1f).height(80.dp)
                        .pointerInput(enabled, index) {
                            detectTapGestures(onPress = {
                                if (enabled) { pressed = true; vm.recordLiveArrow(index); try { tryAwaitRelease() } finally { pressed = false } }
                            })
                        }.semantics {
                            role = Role.Button
                            contentDescription = "Grabar nota ${arrowNames[index]}"
                            if (!enabled) disabled()
                            onClick { if (enabled) { vm.recordLiveArrow(index); true } else false }
                        }) {
                    Box(contentAlignment = Alignment.Center) { Text(symbol, fontSize = 38.sp, color = if (enabled) Color.White else Color.Gray, fontWeight = FontWeight.Bold) }
                }
            }
        }
        Text("Cada pulsación crea una nota corta. Pausa para deshacer o corregir.", style = MaterialTheme.typography.labelSmall)
    }
}
