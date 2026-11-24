package com.fnfmodmaker.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import kotlin.math.abs
import androidx.compose.ui.unit.dp
import com.fnfmodmaker.mod.changeVSliceEvent
import com.fnfmodmaker.mod.duplicateVSliceEvent
import com.fnfmodmaker.mod.removeVSliceEvent
import com.google.gson.JsonArray
import com.google.gson.JsonParser

/** Edits a private copy; only Guardar changes the project. Extra event fields are retained. */
@Composable
internal fun VSliceVisualEvents(vm: WizardViewModel) {
    var open by remember { mutableStateOf(false) }
    var draft by remember { mutableStateOf("[]") }
    var original by remember { mutableStateOf("[]") }
    var error by remember { mutableStateOf<String?>(null) }
    var selected by remember { mutableStateOf<Int?>(null) }
    var adding by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<Int?>(null) }
    var time by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var value by remember { mutableStateOf("") }
    val enabled = !vm.livePlaying && !vm.liveLoading && !vm.busy && vm.liveSong != null
    fun items(): JsonArray = JsonParser.parseString(draft).asJsonArray
    fun edit(index: Int?) {
        val item = index?.let { items()[it].asJsonObject }
        selected = index; adding = index == null
        time = item?.get("t")?.asString ?: "0"
        name = item?.get("e")?.asString ?: ""
        value = item?.get("v")?.toString() ?: "null"
        error = null
    }
    TextButton(onClick = {
        runCatching { vm.liveVSliceEvents() }.onSuccess { draft = it; original = it; error = null; open = true }
            .onFailure { error = it.message ?: "No se pudieron leer los eventos." }
    }, enabled = enabled) { Text("Eventos V-Slice visuales") }
    if (!open) error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    if (open) AlertDialog(onDismissRequest = { open = false }, title = { Text("Eventos V-Slice") },
        text = { Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState())) {
            Text("Los cambios se aplican a todas las dificultades. Toca un marcador para editarlo. El valor v se escribe como JSON; los campos adicionales se conservan.")
            val marks = items().mapIndexed { index, entry -> index to entry.asJsonObject.get("t").asDouble }
            val end = maxOf(1.0, vm.liveDurationMs.toDouble(), marks.maxOfOrNull { it.second } ?: 0.0)
            Canvas(Modifier.fillMaxWidth().height(52.dp).pointerInput(marks, end) {
                detectTapGestures { point -> marks.minByOrNull { abs((it.second / end * size.width).toFloat() - point.x) }?.let { edit(it.first) } }
            }) {
                drawLine(Color.Gray, Offset(0f, size.height / 2), Offset(size.width, size.height / 2), 3f)
                marks.forEach { (_, t) -> drawCircle(Color(0xFFFFB300), 6.dp.toPx(), Offset((t / end * size.width).toFloat().coerceIn(0f, size.width), size.height / 2)) }
            }
            Text("0 ms — ${end.toLong()} ms", style = MaterialTheme.typography.labelSmall)
            items().forEachIndexed { index, entry ->
                val item = entry.asJsonObject
                Text("${item.get("t")} ms · ${item.get("e").asString}", style = MaterialTheme.typography.titleSmall)
                Row(Modifier.horizontalScroll(rememberScrollState())) {
                    TextButton(onClick = { edit(index) }) { Text("Editar") }
                    TextButton(onClick = {
                        runCatching { duplicateVSliceEvent(draft, index) }.onSuccess { draft = it; error = null }.onFailure { error = it.message }
                    }) { Text("Duplicar") }
                    TextButton(onClick = { pendingDelete = index }) { Text("Eliminar") }
                }
            }
            TextButton(onClick = { edit(null) }) { Text("Añadir evento") }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        } },
        confirmButton = { TextButton(onClick = {
            runCatching {
                check(vm.liveVSliceEvents() == original) { "Los eventos cambiaron mientras editabas; vuelve a abrir el editor." }
                vm.saveLiveVSliceEvents(draft)
            }.onSuccess { open = false }.onFailure { error = it.message ?: "No se pudo guardar." }
        }) { Text("Guardar") } },
        dismissButton = { TextButton(onClick = { open = false }) { Text("Cancelar") } })
    if (open && pendingDelete != null) AlertDialog(onDismissRequest = { pendingDelete = null }, title = { Text("¿Eliminar evento?") },
        text = { Text("Se quitará el evento seleccionado al guardar.") },
        confirmButton = { TextButton(onClick = {
            runCatching { removeVSliceEvent(draft, pendingDelete!!) }.onSuccess { draft = it; error = null }.onFailure { error = it.message }
            pendingDelete = null
        }) { Text("Eliminar") } }, dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("Cancelar") } })
    if (open && (adding || selected != null)) AlertDialog(onDismissRequest = { adding = false; selected = null }, title = { Text(if (adding) "Añadir evento" else "Editar evento") },
        text = { Column(Modifier.verticalScroll(rememberScrollState())) {
            OutlinedTextField(time, { time = it; error = null }, label = { Text("Tiempo (ms)") }, singleLine = true)
            OutlinedTextField(name, { name = it; error = null }, label = { Text("Tipo de evento") }, singleLine = true)
            OutlinedTextField(value, { value = it; error = null }, label = { Text("Valor v (JSON)") })
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        } },
        confirmButton = { TextButton(onClick = {
            runCatching {
                draft = changeVSliceEvent(draft, selected, time, name, value)
            }.onSuccess { adding = false; selected = null; error = null }.onFailure { error = it.message ?: "Evento inválido." }
        }) { Text("Aplicar") } },
        dismissButton = { TextButton(onClick = { adding = false; selected = null; error = null }) { Text("Cancelar") } })
}
