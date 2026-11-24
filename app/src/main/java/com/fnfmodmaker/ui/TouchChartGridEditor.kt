package com.fnfmodmaker.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.fnfmodmaker.model.ChartNote
import com.fnfmodmaker.model.TouchGrid
import com.fnfmodmaker.model.translateGridNotes
import kotlin.math.abs
import kotlin.math.roundToLong

private val gridColors = listOf(Color(0xFFC479E8), Color(0xFF54C8F4), Color(0xFF62D884), Color(0xFFFF7388))
private val gridArrows = listOf("←", "↓", "↑", "→")
private fun gridClock(ms: Long): String = "%d:%02d.%03d".format(ms / 60000, ms / 1000 % 60, ms % 1000)

@Composable
internal fun TouchChartGridEditor(vm: WizardViewModel, onClose: () -> Unit) {
    val song = vm.liveSong ?: return
    if (song.bpmValue !in 1..400 || vm.liveDurationMs <= 0L) return
    var divisions by rememberSaveable { mutableStateOf(4) }
    val tempos = remember(vm.config, vm.liveSongIndex) {
        if (vm.config.engine.includesVSlice) runCatching { com.fnfmodmaker.mod.vSliceGridTempos(vm.liveVSliceTempo()) }.getOrNull() else emptyList()
    }
    val grid = remember(song.bpmValue, divisions, vm.liveDurationMs, tempos) { TouchGrid(song.bpmValue, divisions, vm.liveDurationMs, tempos.orEmpty()) }
    var page by rememberSaveable { mutableStateOf(grid.pageForTime(vm.livePositionMs)) }
    page = page.coerceIn(0, grid.pageCount - 1)
    var selected by remember { mutableStateOf<ChartNote?>(null) }
    var resizeMode by rememberSaveable { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var multiMode by rememberSaveable { mutableStateOf(false) }
    var group by remember { mutableStateOf<Set<ChartNote>>(emptySet()) }
    var clipboard by remember { mutableStateOf<List<ChartNote>>(emptyList()) }
    var editTime by remember { mutableStateOf(false) }
    var timeText by remember { mutableStateOf("") }
    var timeError by remember { mutableStateOf<String?>(null) }
    var showButtonEditor by remember { mutableStateOf(false) }
    var showAudioControls by remember { mutableStateOf(false) }
    val audioActive = vm.livePlaying || vm.liveLoading
    val locked = vm.liveRecording || vm.liveLoading || vm.busy
    val notes = vm.liveChart?.notes.orEmpty()
    DisposableEffect(Unit) { vm.beginGridSession(); onDispose { vm.endGridSession() } }

    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 8.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Cuadrícula de notas", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Text("${song.songName} · ${vm.liveDifficulty} · ${gridClock(grid.pageStart(page))}", style = MaterialTheme.typography.labelSmall)
                        if (tempos == null) Text("No se pudo leer el mapa de tempo; se usa el BPM inicial. Los tiempos exactos no cambian.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall)
                        else Text("Página de cuatro beats · compás ${kotlin.math.floor(grid.measureAt(grid.pageStart(page).toDouble())).toInt() + 1}. Líneas violetas: límites de compás.", style = MaterialTheme.typography.labelSmall)
                    }
                    LocalToolbarIcon()
                    TextButton(onClick = onClose) { Text("Listo") }
                }
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Ajuste", style = MaterialTheme.typography.labelMedium)
                    listOf(1 to "1/4", 2 to "1/8", 4 to "1/16", 8 to "1/32").forEach { (value, label) ->
                        FilterChip(divisions == value, { val time = grid.pageStart(page); divisions = value; page = TouchGrid(song.bpmValue, value, vm.liveDurationMs, tempos.orEmpty()).pageForTime(time) },
                            enabled = !locked, label = { Text(label) })
                    }
                    FilterChip(!resizeMode, { resizeMode = false }, enabled = !locked, label = { Text("Mover") })
                    FilterChip(resizeMode, { resizeMode = true }, enabled = !locked && !multiMode, label = { Text("Sostenido") })
                    FilterChip(multiMode, { multiMode = !multiMode; group = emptySet(); selected = null; resizeMode = false }, enabled = !locked, label = { Text("Selección múltiple") })
                }
                Text("RIVAL    ${gridArrows.joinToString("  ")}       JUGADOR    ${gridArrows.joinToString("  ")}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.secondary)
                Text(if (multiMode) "Toca notas para seleccionarlas, incluso en otras páginas. Usa los botones para mover, copiar o borrar el grupo." else "Toca para añadir o seleccionar; arrastra para mover o, en Sostenido, cambiar el final. Tiempo exacto permite movimiento libre.", style = MaterialTheme.typography.labelSmall)
                vm.liveNotice?.let { Text(it, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelSmall) }
                vm.liveError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall) }
                ChartWaveform(song, vm.liveDurationMs, grid, page, vm.livePositionMs, onPage = { page = it })
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { page = (page - 1).coerceAtLeast(0) }, enabled = !locked && page > 0) { Text("‹") }
                    Slider(value = page.toFloat(), onValueChange = { page = it.toInt().coerceIn(0, grid.pageCount - 1) },
                        valueRange = 0f..(grid.pageCount - 1).coerceAtLeast(1).toFloat(), enabled = !locked && grid.pageCount > 1, modifier = Modifier.weight(1f))
                    TextButton(onClick = { page = (page + 1).coerceAtMost(grid.pageCount - 1) }, enabled = !locked && page < grid.pageCount - 1) { Text("›") }
                    Text("${page + 1}/${grid.pageCount}", style = MaterialTheme.typography.labelSmall)
                }
                Box(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) {
                    GridCanvas(grid, page, notes, vm.livePositionMs, if (multiMode) group else setOfNotNull(selected), resizeMode, multiMode, locked,
                        onSelect = { note ->
                            if (multiMode && note != null) group = if (note in group) group - note else group + note
                            else if (!multiMode) selected = note
                        }, onChange = { old, new ->
                            if (!multiMode && vm.applyGridNote(old, new)) selected = new
                        })
                }
                val note = selected
                if (!multiMode && note != null) {
                    Text("Seleccionada: ${if (note.lane < 4) "Jugador" else "Rival"} ${gridArrows[note.lane % 4]} · ${gridClock(note.timeMs)} · ${note.lengthMs} ms", style = MaterialTheme.typography.labelMedium)
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        TextButton(onClick = {
                            val updated = note.copy(lengthMs = (grid.shiftTime(note.endTimeMs, -1.0 / grid.divisionsPerBeat) - note.timeMs).coerceAtLeast(0L))
                            if (vm.applyGridNote(note, updated)) selected = updated
                        }, enabled = !locked && note.lengthMs > 0) { Text("Sostenido −") }
                        TextButton(onClick = {
                            val updated = note.copy(lengthMs = (grid.shiftTime(note.endTimeMs, 1.0 / grid.divisionsPerBeat) - note.timeMs).coerceAtLeast(0L))
                            if (vm.applyGridNote(note, updated)) selected = updated
                        }, enabled = !locked) { Text("Sostenido +") }
                        TextButton(onClick = { timeText = note.timeMs.toString(); timeError = null; editTime = true }, enabled = !locked) { Text("Tiempo exacto") }
                        TextButton(onClick = { confirmDelete = true }, enabled = !locked) { Text("Quitar") }
                    }
                }
                if (multiMode) {
                    val chosen = group.sortedWith(compareBy<ChartNote> { it.timeMs }.thenBy { it.lane })
                    fun moveGroup(deltaMs: Long, deltaColumn: Int) {
                        val moved = translateGridNotes(chosen, deltaMs, deltaColumn)
                        if (moved != null && vm.applyGridChanges(chosen, moved)) group = moved.toSet()
                    }
                    Text("${group.size} seleccionadas · ${clipboard.size} copiadas", style = MaterialTheme.typography.labelMedium)
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        TextButton(onClick = { clipboard = chosen }, enabled = chosen.isNotEmpty() && !locked) { Text("Copiar") }
                        TextButton(onClick = {
                            val first = clipboard.minOfOrNull { it.timeMs }
                            val pasted = first?.let { translateGridNotes(clipboard, grid.pageStart(page) - it, 0) }
                            if (pasted != null && vm.applyGridChanges(emptyList(), pasted)) group = pasted.toSet()
                        }, enabled = clipboard.isNotEmpty() && !locked) { Text("Pegar al inicio de página") }
                        TextButton(onClick = {
                            val copies = chosen.map { it.copy(timeMs = grid.shiftMeasure(it.timeMs, 1.0)) }
                            if (vm.applyGridChanges(emptyList(), copies)) group = copies.toSet()
                        }, enabled = chosen.isNotEmpty() && !locked) { Text("Duplicar +1 compás") }
                        TextButton(onClick = { confirmDelete = true }, enabled = chosen.isNotEmpty() && !locked) { Text("Quitar grupo") }
                        TextButton(onClick = { group = emptySet() }, enabled = chosen.isNotEmpty()) { Text("Deseleccionar") }
                    }
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
                        TextButton(onClick = { val moved = chosen.map { it.copy(timeMs = grid.shiftTime(it.timeMs, -1.0 / grid.divisionsPerBeat)) }; if (vm.applyGridChanges(chosen, moved)) group = moved.toSet() }, enabled = chosen.isNotEmpty() && !locked) { Text("Tiempo −") }
                        TextButton(onClick = { val moved = chosen.map { it.copy(timeMs = grid.shiftTime(it.timeMs, 1.0 / grid.divisionsPerBeat)) }; if (vm.applyGridChanges(chosen, moved)) group = moved.toSet() }, enabled = chosen.isNotEmpty() && !locked) { Text("Tiempo +") }
                        TextButton(onClick = { moveGroup(0, -1) }, enabled = chosen.isNotEmpty() && !locked) { Text("Carril ←") }
                        TextButton(onClick = { moveGroup(0, 1) }, enabled = chosen.isNotEmpty() && !locked) { Text("Carril →") }
                    }
                }
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (audioActive) Button(onClick = vm::pauseLivePlayback) { Text(if (vm.liveLoading) "Cancelar" else "Pausar") }
                    else OutlinedButton(onClick = { vm.setLivePosition(grid.pageStart(page)); vm.startLivePlayback(false) }, enabled = vm.liveCanPlay) { Text("▶ Escuchar desde aquí") }
                    TextButton(onClick = { vm.setLivePosition((vm.livePositionMs - 5000).coerceAtLeast(0)) }, enabled = !vm.liveLoading && !vm.liveRecording) { Text("−5 s") }
                    TextButton(onClick = { vm.setLivePosition((vm.livePositionMs + 5000).coerceAtMost(vm.liveDurationMs)) }, enabled = !vm.liveLoading && !vm.liveRecording) { Text("+5 s") }
                    TextButton(onClick = { showAudioControls = true }) { Text("Mezcla") }
                    TextButton(onClick = { showButtonEditor = true }, enabled = !locked && !vm.busy && !multiMode) { Text("Edición por botones") }
                    TextButton(onClick = { vm.undoGridEdit(); selected = null; group = emptySet() }, enabled = !locked && vm.gridUndoCount > 0) { Text("Deshacer (${vm.gridUndoCount})") }
                    TextButton(onClick = { vm.redoGridEdit(); selected = null; group = emptySet() }, enabled = !locked && vm.gridRedoCount > 0) { Text("Rehacer (${vm.gridRedoCount})") }
                }
                Text("Puedes editar mientras escuchas. La mezcla es aproximada y debe probarse en el dispositivo y en el juego.", style = MaterialTheme.typography.labelSmall)
            }
        }
    }
    if (showAudioControls) ChartAudioControls(vm) { showAudioControls = false }
    if (editTime && selected != null) AlertDialog(onDismissRequest = { editTime = false }, title = { Text("Tiempo exacto") },
        text = { Column {
            Text("Introduce el tiempo desde el inicio de la canción en milisegundos, sin ajuste a la cuadrícula.")
            OutlinedTextField(value = timeText, onValueChange = { timeText = it.filter(Char::isDigit).take(12); timeError = null }, label = { Text("Tiempo en ms") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true)
            timeError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        } },
        confirmButton = { TextButton(onClick = {
            val old = selected; val time = timeText.toLongOrNull()
            if (old != null && time != null) {
                if (time == old.timeMs) editTime = false
                else { val updated = old.copy(timeMs = time); if (vm.applyGridNote(old, updated)) { selected = updated; page = grid.pageForTime(time); editTime = false } else timeError = "No cabe en la canción o coincide con otra nota." }
            } else timeError = "Indica un tiempo válido."
        }, enabled = !locked) { Text("Guardar") } }, dismissButton = { TextButton(onClick = { editTime = false }) { Text("Cancelar") } })
    if (showButtonEditor && !multiMode) GridButtonEditor(grid, page, notes, selected, onClose = { showButtonEditor = false },
        onSelect = { selected = it }, onChange = { old, new -> if (vm.applyGridNote(old, new)) { selected = new; showButtonEditor = false } else { showButtonEditor = false } })
    if (confirmDelete && (selected != null || group.isNotEmpty())) AlertDialog(onDismissRequest = { confirmDelete = false }, title = { Text(if (multiMode) "¿Quitar ${group.size} notas?" else "¿Quitar esta nota?") },
        text = { Text("Puedes deshacer esta acción desde el historial de esta canción y dificultad.") },
        confirmButton = { TextButton(onClick = {
            if (multiMode) { if (vm.applyGridChanges(group.toList(), emptyList())) group = emptySet() }
            else if (vm.applyGridNote(selected, null)) selected = null
            confirmDelete = false
        }, enabled = !locked) { Text("Quitar") } },
        dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancelar") } })
}

@Composable
private fun GridCanvas(grid: TouchGrid, page: Int, notes: List<ChartNote>, playheadMs: Long, selected: Set<ChartNote>, resizeMode: Boolean,
    multiMode: Boolean, locked: Boolean, onSelect: (ChartNote?) -> Unit, onChange: (ChartNote?, ChartNote?) -> Unit) {
    val currentNotes by rememberUpdatedState(notes)
    val currentOnChange by rememberUpdatedState(onChange)
    val currentOnSelect by rememberUpdatedState(onSelect)
    var dragNote by remember { mutableStateOf<ChartNote?>(null) }
    var dragPosition by remember { mutableStateOf<Offset?>(null) }
    val height = (grid.rows * 36).dp
    Canvas(Modifier.fillMaxWidth().height(height).semantics { contentDescription = "Cuadrícula táctil: rival en las cuatro columnas izquierdas, jugador en las cuatro derechas. Tiempo de arriba abajo." }
        .pointerInput(grid, page, resizeMode, multiMode, locked) {
            val rowH = size.height.toFloat() / grid.rows
            val colW = size.width.toFloat() / 8f
            fun noteY(time: Long) = (grid.rowPosition(time, page) + .5).toFloat() * rowH
            fun hit(point: Offset): ChartNote? {
                val col = (point.x / colW).toInt()
                val lane = grid.laneForColumn(col) ?: return null
                val laneNotes = currentNotes.filter { it.lane == lane }
                val head = laneNotes.filter { abs(noteY(it.timeMs) - point.y) <= rowH * .42f }.minByOrNull { abs(noteY(it.timeMs) - point.y) }
                return head ?: laneNotes.firstOrNull { it.lengthMs > 0 && point.y >= noteY(it.timeMs) && point.y <= noteY(it.endTimeMs) }
            }
            fun cell(point: Offset): Pair<Int, Long>? {
                if (point.x < 0 || point.x >= size.width || point.y < 0 || point.y >= size.height) return null
                val lane = grid.laneForColumn((point.x / colW).toInt()) ?: return null
                val time = grid.timeAt(page, (point.y / rowH).toInt()) ?: return null
                return lane to time
            }
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                val origin = hit(down.position)
                var dragging = false
                var consumedByParent = down.isConsumed
                var last = down.position
                do {
                    val event = awaitPointerEvent()
                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                    if (change.isConsumed && !dragging) consumedByParent = true
                    last = change.position
                    if (!locked && !multiMode && origin != null && !consumedByParent && (last - down.position).getDistance() > viewConfiguration.touchSlop) dragging = true
                    if (dragging) { change.consume(); dragNote = origin; dragPosition = last }
                    if (!change.pressed) {
                        if (!locked && !consumedByParent) {
                            if (dragging && origin != null) {
                                val target = cell(last)
                                if (target != null) {
                                    val replacement = if (resizeMode) origin.copy(lengthMs = (target.second - origin.timeMs).coerceAtLeast(0L))
                                        else origin.copy(timeMs = target.second, lane = target.first)
                                    currentOnChange(origin, replacement)
                                }
                            } else if (!dragging) {
                                if (origin != null) currentOnSelect(origin)
                                else cell(last)?.let { (lane, time) -> currentOnChange(null, ChartNote(timeMs = time, lane = lane)) }
                            }
                        }
                        break
                    }
                } while (true)
                dragNote = null; dragPosition = null
            }
        }) {
        val rowH = size.height / grid.rows
        val colW = size.width / 8f
        drawRect(Color(0xFF171322))
        for (column in 0..7) drawRect(gridColors[column % 4].copy(alpha = .08f), Offset(column * colW, 0f), Size(colW, size.height))
        for (column in 0..8) drawLine(Color.White.copy(alpha = if (column == 4) .7f else .2f), Offset(column * colW, 0f), Offset(column * colW, size.height), if (column == 4) 3f else 1f)
        for (row in 0..grid.rows) drawLine(Color.White.copy(alpha = if (row % grid.divisionsPerBeat == 0) .45f else .14f), Offset(0f, row * rowH), Offset(size.width, row * rowH), if (row % grid.divisionsPerBeat == 0) 2f else 1f)
        val startMeasure = grid.measureAt(grid.pageStart(page).toDouble())
        val endMeasure = grid.measureAt(grid.pageEnd(page).toDouble())
        var boundary = kotlin.math.ceil(startMeasure - 0.0000001).toInt()
        while (boundary <= kotlin.math.floor(endMeasure + 0.0000001).toInt()) {
            val time = grid.timeAtMeasure(boundary.toDouble()).roundToLong()
            val y = (grid.rowPosition(time, page) * rowH).toFloat()
            if (y in 0f..size.height) drawLine(Color(0xFFD69AF9), Offset(0f, y), Offset(size.width, y), 3f)
            boundary++
        }
        fun noteY(time: Long) = (grid.rowPosition(time, page) + .5).toFloat() * rowH
        for (note in notes) {
            val col = grid.columnForLane(note.lane) ?: continue
            val y = noteY(note.timeMs); val endY = noteY(note.endTimeMs)
            if (endY < 0 || y > size.height) continue
            val x = (col + .5f) * colW
            val color = gridColors[note.lane % 4]
            if (note.lengthMs > 0) drawLine(color.copy(alpha = .8f), Offset(x, y.coerceAtLeast(0f)), Offset(x, endY.coerceAtMost(size.height)), colW * .32f)
            if (y in 0f..size.height) {
                if (note in selected) drawCircle(Color.White, colW * .36f, Offset(x, y))
                drawCircle(color, colW * .27f, Offset(x, y))
            }
        }
        val playY = noteY(playheadMs)
        if (playY in 0f..size.height) drawLine(Color.White.copy(alpha = .8f), Offset(0f, playY), Offset(size.width, playY), 2f)
        dragPosition?.let { pos -> if (dragNote != null) drawCircle(Color.White.copy(alpha = .5f), colW * .26f, pos) }
    }
}

/** An alternative to gestures, including for screen-reader users. */
@Composable
private fun GridButtonEditor(grid: TouchGrid, page: Int, notes: List<ChartNote>, selected: ChartNote?,
    onClose: () -> Unit, onSelect: (ChartNote?) -> Unit, onChange: (ChartNote?, ChartNote?) -> Unit) {
    var row by remember(grid, page) { mutableStateOf(0) }
    var column by remember { mutableStateOf(0) }
    val lane = grid.laneForColumn(column)!!
    val time = grid.timeAt(page, row)
    val nearby = if (time == null) null else notes.filter { it.lane == lane && abs(it.timeMs - time) < grid.stepMsAt(time) / 2 }
        .minByOrNull { abs(it.timeMs - time) }
    AlertDialog(onDismissRequest = onClose, title = { Text("Edición por botones") },
        text = { Column(Modifier.verticalScroll(rememberScrollState())) {
            Text("Elige una columna y una fila de la página actual. Selecciona una nota cercana o crea una nota en la fila. Después puedes mover la seleccionada aquí.")
            Text("Columna: ${if (lane < 4) "Jugador" else "Rival"} ${gridArrows[lane % 4]}")
            Row(Modifier.horizontalScroll(rememberScrollState())) {
                repeat(8) { col -> FilterChip(column == col, { column = col }, label = { Text("${if (col < 4) "R" else "J"} ${gridArrows[col % 4]}") }, modifier = Modifier.padding(end = 4.dp)) }
            }
            Text("Fila ${row + 1} de ${grid.rows} · ${time?.let(::gridClock) ?: "fuera de la canción"}")
            Row {
                TextButton(onClick = { row = (row - 1).coerceAtLeast(0) }, enabled = row > 0) { Text("Fila anterior") }
                TextButton(onClick = { row = (row + 1).coerceAtMost(grid.rows - 1) }, enabled = row < grid.rows - 1) { Text("Fila siguiente") }
            }
            Text(if (nearby == null) "Sin nota cercana" else "Nota cercana: ${gridClock(nearby.timeMs)} · ${nearby.lengthMs} ms")
            Text(if (selected == null) "Ninguna nota seleccionada" else "Seleccionada: ${gridClock(selected.timeMs)} · ${if (selected.lane < 4) "Jugador" else "Rival"} ${gridArrows[selected.lane % 4]}")
            TextButton(onClick = { onSelect(nearby) }, enabled = nearby != null) { Text("Seleccionar nota cercana") }
            TextButton(onClick = { if (time != null) onChange(null, ChartNote(timeMs = time, lane = lane)) }, enabled = time != null && nearby == null) { Text("Añadir nota aquí") }
            TextButton(onClick = { if (time != null && selected != null) onChange(selected, selected.copy(timeMs = time, lane = lane)) }, enabled = time != null && selected != null) { Text("Mover seleccionada aquí") }
        } }, confirmButton = { TextButton(onClick = onClose) { Text("Volver") } })
}
