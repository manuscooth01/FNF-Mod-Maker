package com.fnfmodmaker.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.fnfmodmaker.model.*
import kotlin.math.roundToLong

/** Edits the section structure belonging to one chart/difficulty, never its V-Slice tempo. */
@Composable
internal fun PsychSectionEditor(vm: WizardViewModel) {
    var open by remember { mutableStateOf(false) }
    var confirmCreate by remember { mutableStateOf(false) }
    var selected by remember { mutableIntStateOf(0) }
    var edit by remember { mutableStateOf(false) }
    var remove by remember { mutableStateOf(false) }
    var beats by remember { mutableStateOf("") }
    var bpm by remember { mutableStateOf("") }
    var mustHit by remember { mutableStateOf(false) }
    var alt by remember { mutableStateOf(false) }
    var gf by remember { mutableStateOf(false) }
    var change by remember { mutableStateOf(false) }
    var base by remember { mutableStateOf<ChartDraft?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val enabled = !vm.busy && !vm.livePlaying && !vm.liveLoading && vm.liveChartAvailable
    TextButton(onClick = {
        if (vm.liveChart == null) confirmCreate = true else runCatching {
            val song = vm.liveSong ?: error("Selecciona una canción.")
            psychSectionSpans(materializePsychLayout(vm.liveChart!!, song.bpmValue), song.bpmValue)
        }.onSuccess { selected = 0; error = null; open = true }.onFailure { error = it.message ?: "No se pueden abrir las secciones." }
    }, enabled = enabled) { Text("Secciones Psych") }
    if (confirmCreate) AlertDialog(onDismissRequest = { confirmCreate = false }, title = { Text("Crear chart Psych") },
        text = { Text("Se creará un chart vacío para esta dificultad. Si había notas de práctica automáticas, dejarán de generarse para ella. Puedes deshacer la creación.") },
        confirmButton = { TextButton(onClick = {
            runCatching { vm.createLivePsychChart() }.onSuccess { confirmCreate = false; selected = 0; error = null; open = true }.onFailure { error = it.message }
        }) { Text("Crear") } }, dismissButton = { TextButton(onClick = { confirmCreate = false }) { Text("Cancelar") } })
    if (!open) error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    val chart = vm.liveChart
    val song = vm.liveSong
    if (open && chart != null && song != null) {
        val layout = remember(chart, song.bpm) { materializePsychLayout(chart, song.bpmValue) }
        val spans = remember(layout, song.bpm) { psychSectionSpans(layout, song.bpmValue) }
        selected = selected.coerceIn(layout.indices)
        fun sectionNotes(index: Int) = chart.notes.filter { psychSectionIndex(spans, it.timeMs) == index }
        val section = layout[selected]; val span = spans[selected]
        val notes = sectionNotes(selected).sortedBy { it.timeMs }
        fun save(sections: List<PsychSectionData>, notes: List<ChartNote>? = null) {
            runCatching { vm.saveLivePsychSections(sections, notes) }.onSuccess { error = null }.onFailure { error = it.message ?: "No se pudo cambiar la sección." }
        }
        AlertDialog(onDismissRequest = { open = false }, title = { Text("Secciones Psych · ${vm.liveDifficulty}") }, text = {
            Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState())) {
                Text("Los tiempos de las notas no cambian al editar secciones. Al cambiar límites, las notas pasan a la sección de su tiempo; una nota fuera del final bloquea el cambio.")
                Row(Modifier.horizontalScroll(rememberScrollState())) {
                    layout.forEachIndexed { i, _ -> FilterChip(selected == i, { selected = i; error = null }, label = { Text("${i + 1}") }, modifier = Modifier.padding(end = 4.dp)) }
                }
                Text("Sección ${selected + 1}: ${"%.2f".format(span.startMs)}–${"%.2f".format(span.endMs)} ms · ${section.beats} beats · BPM efectivo ${span.bpm}")
                Text("${if (section.mustHit) "Jugador" else "Rival"} · alt ${if (section.altAnim) "sí" else "no"} · GF ${if (section.gfSection) "sí" else "no"} · cambio BPM ${if (section.changeBpm) "sí" else "no"}")
                Text("Notas en esta sección: ${notes.size}")
                notes.take(30).forEach { note -> Text("${note.timeMs} ms · ${if (note.lane < 4) "Jugador" else "Rival"} ${note.lane % 4 + 1} · sostenido ${note.lengthMs} ms${if (note.kind.isNotBlank()) " · ${note.kind}" else ""}", style = MaterialTheme.typography.labelSmall) }
                if (notes.size > 30) Text("Se muestran las primeras 30 notas; edítalas en la cuadrícula.", style = MaterialTheme.typography.labelSmall)
                Row(Modifier.horizontalScroll(rememberScrollState())) {
                    TextButton(onClick = {
                        base = chart; beats = section.beats.toString(); bpm = section.bpm.toString(); mustHit = section.mustHit
                        alt = section.altAnim; gf = section.gfSection; change = section.changeBpm; error = null; edit = true
                    }, enabled = enabled) { Text("Editar") }
                    TextButton(onClick = {
                        val next = layout + PsychSectionData(bpm = spans.last().bpm)
                        save(next); if (vm.liveChart?.psychLayout == next) selected = next.lastIndex
                    }, enabled = enabled && layout.size < 1000) { Text("Añadir al final") }
                    TextButton(onClick = {
                        runCatching {
                            val next = layout + section
                            var combined = chart.notes
                            val shift = spans.last().endMs - span.startMs
                            for (note in notes) {
                                val copy = note.copy(timeMs = (note.timeMs + shift).roundToLong())
                                combined = appendRecordedNote(combined, copy) ?: error("La copia de notas colisiona con otra nota.")
                            }
                            require(combined.size <= 100_000) { "Demasiadas notas." }
                            vm.saveLivePsychSections(next, combined); selected = next.lastIndex; error = null
                        }.onFailure { error = it.message ?: "No se pudo duplicar." }
                    }, enabled = enabled && layout.size < 1000) { Text("Duplicar al final") }
                    TextButton(onClick = { remove = true }, enabled = enabled && layout.size > 1) { Text("Eliminar") }
                }
                Row(Modifier.horizontalScroll(rememberScrollState())) {
                    TextButton(onClick = vm::undoLiveChartEdit, enabled = enabled && vm.gridUndoCount > 0) { Text("Deshacer") }
                    TextButton(onClick = vm::redoLiveChartEdit, enabled = enabled && vm.gridRedoCount > 0) { Text("Rehacer") }
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        }, confirmButton = { TextButton(onClick = { open = false }) { Text("Listo") } })
        if (edit) AlertDialog(onDismissRequest = { edit = false }, title = { Text("Editar sección ${selected + 1}") }, text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(beats, { beats = it; error = null }, label = { Text("Duración (beats)") }, singleLine = true)
                OutlinedTextField(bpm, { bpm = it; error = null }, label = { Text("BPM de sección") }, singleLine = true)
                Row { Checkbox(mustHit, { mustHit = it }); Text("mustHitSection · jugador") }
                Row { Checkbox(alt, { alt = it }); Text("altAnim") }
                Row { Checkbox(gf, { gf = it }); Text("gfSection") }
                Row { Checkbox(change, { change = it }); Text("Activar cambio BPM") }
                Text("Psych redondea la duración a pasos de ¼ de beat.", style = MaterialTheme.typography.labelSmall)
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        }, confirmButton = { TextButton(onClick = {
            runCatching {
                check(vm.liveChart == base) { "El chart cambió; vuelve a abrir el editor de sección." }
                val b = beats.replace(',', '.').toDoubleOrNull() ?: error("Duración inválida.")
                val p = bpm.replace(',', '.').toDoubleOrNull() ?: error("BPM inválido.")
                val changed = layout.toMutableList().apply { this[selected] = section.copy(beats = b, bpm = p, mustHit = mustHit, altAnim = alt, gfSection = gf, changeBpm = change) }
                vm.saveLivePsychSections(changed)
            }.onSuccess { edit = false; error = null }.onFailure { error = it.message ?: "Sección inválida." }
        }) { Text("Guardar") } }, dismissButton = { TextButton(onClick = { edit = false; error = null }) { Text("Cancelar") } })
        if (remove) AlertDialog(onDismissRequest = { remove = false }, title = { Text("¿Eliminar sección ${selected + 1}?") },
            text = { Text("Se perderán las propiedades de esta sección. Las notas mantienen sus tiempos y se reasignan según los nuevos límites; el cambio se rechaza si alguna queda fuera.") },
            confirmButton = { TextButton(onClick = { save(layout.filterIndexed { i, _ -> i != selected }); remove = false }) { Text("Eliminar") } },
            dismissButton = { TextButton(onClick = { remove = false }) { Text("Cancelar") } })
    }
}
