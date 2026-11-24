package com.fnfmodmaker.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.fnfmodmaker.mod.*
import com.google.gson.JsonParser

@Composable
internal fun VSliceTempoEditor(vm: WizardViewModel) {
    var open by remember { mutableStateOf(false) }
    var draft by remember { mutableStateOf("[]") }
    var original by remember { mutableStateOf("[]") }
    var selected by remember { mutableStateOf<Int?>(null) }
    var mode by remember { mutableStateOf("") }
    var time by remember { mutableStateOf("") }
    var bpm by remember { mutableStateOf("") }
    var n by remember { mutableStateOf("") }
    var d by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var deleting by remember { mutableStateOf(false) }
    val enabled = !vm.livePlaying && !vm.liveLoading && !vm.busy && vm.liveSong != null
    fun edit(index: Int?, action: String) {
        val row = index?.let { JsonParser.parseString(draft).asJsonArray[it].asJsonObject }
        selected = index; mode = action
        time = if (action == "duplicar") "" else row?.get("t")?.asString ?: ""
        bpm = row?.get("bpm")?.asString ?: ""; n = row?.get("n")?.asString ?: "4"; d = row?.get("d")?.asString ?: "4"
        error = null
    }
    TextButton(onClick = {
        runCatching { vm.liveVSliceTempo() }.onSuccess { original = it; draft = it; error = null; open = true }.onFailure { error = it.message }
    }, enabled = enabled) { Text("Cambios de tempo V-Slice") }
    Row(Modifier.horizontalScroll(rememberScrollState())) {
        TextButton(onClick = { runCatching { vm.restoreLiveVSliceTempo(false) }.onFailure { error = it.message } }, enabled = enabled && vm.liveSong?.vSliceTempoUndo?.isNotEmpty() == true) { Text("Deshacer tempo") }
        TextButton(onClick = { runCatching { vm.restoreLiveVSliceTempo(true) }.onFailure { error = it.message } }, enabled = enabled && vm.liveSong?.vSliceTempoRedo?.isNotEmpty() == true) { Text("Rehacer tempo") }
    }
    if (!open) error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    if (open) AlertDialog(onDismissRequest = { open = false }, title = { Text("Tempo V-Slice") }, text = { Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState())) {
        Text("Tiempos en milisegundos. El primer cambio comienza en cero. Un BPM vacío en cambios posteriores hereda el anterior. Las notas conservan su tiempo absoluto.")
        val rows = JsonParser.parseString(draft).asJsonArray
        rows.forEachIndexed { index, item ->
            val row = item.asJsonObject
            Text("${row.get("t").asString} ms · beat ${"%.2f".format(vSliceBeatAt(draft, row.get("t").asDouble))} · BPM ${row.get("bpm")?.asString ?: "heredado"} · ${row.get("n")?.asString ?: "4"}/${row.get("d")?.asString ?: "4"}")
            Row(Modifier.horizontalScroll(rememberScrollState())) {
                TextButton(onClick = { edit(index, "editar") }) { Text("Editar") }
                TextButton(onClick = { edit(index, "duplicar") }) { Text("Duplicar") }
                TextButton(onClick = { selected = index; deleting = true }, enabled = index > 0) { Text("Eliminar") }
            }
        }
        TextButton(onClick = { edit(null, "añadir") }) { Text("Añadir cambio") }
        Text("La cuadrícula y el ajuste de grabación todavía usan el BPM inicial; utiliza tiempos exactos para cambios posteriores.", style = MaterialTheme.typography.labelSmall)
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    } }, confirmButton = { TextButton(onClick = {
        runCatching { check(vm.liveVSliceTempo() == original) { "El tempo cambió mientras editabas; vuelve a abrirlo." }; vm.saveLiveVSliceTempo(draft) }
            .onSuccess { open = false }.onFailure { error = it.message }
    }) { Text("Guardar") } }, dismissButton = { TextButton(onClick = { open = false }) { Text("Cancelar") } })
    if (open && mode.isNotEmpty()) AlertDialog(onDismissRequest = { mode = "" }, title = { Text("Cambio de tempo · $mode") }, text = { Column(Modifier.verticalScroll(rememberScrollState())) {
        OutlinedTextField(time, { time = it; error = null }, label = { Text("Tiempo (ms)") }, singleLine = true, enabled = selected != 0 || mode == "duplicar")
        if (mode != "duplicar") {
            OutlinedTextField(bpm, { bpm = it; error = null }, label = { Text("BPM") }, singleLine = true)
            OutlinedTextField(n, { n = it; error = null }, label = { Text("Numerador") }, singleLine = true)
            OutlinedTextField(d, { d = it; error = null }, label = { Text("Denominador") }, singleLine = true)
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    } }, confirmButton = { TextButton(onClick = {
        runCatching { if (mode == "duplicar") duplicateVSliceTempo(draft, selected!!, time) else changeVSliceTempo(draft, selected, time, bpm, n, d) }
            .onSuccess { draft = it; mode = ""; error = null }.onFailure { error = it.message ?: "Cambio inválido." }
    }) { Text("Aplicar") } }, dismissButton = { TextButton(onClick = { mode = ""; error = null }) { Text("Cancelar") } })
    if (open && deleting) AlertDialog(onDismissRequest = { deleting = false }, title = { Text("¿Eliminar cambio de tempo?") },
        text = { Text("Los tiempos de las notas no se modificarán. El cambio se aplicará al guardar.") },
        confirmButton = { TextButton(onClick = { runCatching { removeVSliceTempo(draft, selected!!) }.onSuccess { draft = it; error = null }.onFailure { error = it.message }; deleting = false }) { Text("Eliminar") } },
        dismissButton = { TextButton(onClick = { deleting = false }) { Text("Cancelar") } })
}
