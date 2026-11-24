package com.fnfmodmaker.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
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
            Text("Los cambios se aplican a todas las dificultades. El valor v se escribe como JSON; los campos adicionales se conservan.")
            items().forEachIndexed { index, entry ->
                val item = entry.asJsonObject
                Text("${item.get("t")} ms · ${item.get("e").asString}", style = MaterialTheme.typography.titleSmall)
                Row(Modifier.horizontalScroll(rememberScrollState())) {
                    TextButton(onClick = { edit(index) }) { Text("Editar") }
                    TextButton(onClick = {
                        runCatching { duplicateVSliceEvent(draft, index) }.onSuccess { draft = it; error = null }.onFailure { error = it.message }
                    }) { Text("Duplicar") }
                    TextButton(onClick = {
                        runCatching { removeVSliceEvent(draft, index) }.onSuccess { draft = it; error = null }.onFailure { error = it.message }
                    }) { Text("Eliminar") }
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
