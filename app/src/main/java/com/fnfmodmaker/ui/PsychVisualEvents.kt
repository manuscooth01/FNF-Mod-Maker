package com.fnfmodmaker.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.fnfmodmaker.mod.*
import kotlin.math.abs

/** Psych event actions stay grouped at their source timestamp unless individually moved. */
@Composable
internal fun PsychVisualEvents(vm: WizardViewModel) {
    var open by remember { mutableStateOf(false) }
    var draft by remember { mutableStateOf("[]") }
    var original by remember { mutableStateOf("[]") }
    var selected by remember { mutableStateOf<PsychEventRef?>(null) }
    var formOpen by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    var time by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var value1 by remember { mutableStateOf("") }
    var value2 by remember { mutableStateOf("") }
    var null1 by remember { mutableStateOf(false) }
    var null2 by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val enabled = !vm.livePlaying && !vm.liveLoading && !vm.busy && vm.liveChartAvailable && vm.liveChart != null
    val refs = remember(draft) { psychEventRefs(draft).sortedBy { it.time.toBigDecimal() } }
    fun form(ref: PsychEventRef?) {
        selected = ref
        time = ref?.time ?: vm.livePositionMs.toString(); name = ref?.name ?: ""
        value1 = ref?.value1 ?: ""; value2 = ref?.value2 ?: ""
        null1 = ref != null && ref.value1 == null; null2 = ref != null && ref.value2 == null
        error = null; formOpen = true
    }
    TextButton(onClick = { draft = vm.livePsychEvents(); original = draft; selected = null; error = null; open = true }, enabled = enabled) { Text("Eventos Psych visuales") }
    if (open) AlertDialog(onDismissRequest = { open = false }, title = { Text("Eventos Psych · ${vm.liveDifficulty}") },
        text = { Column(Modifier.heightIn(max = 500.dp).verticalScroll(rememberScrollState())) {
            Text("Toca la línea o selecciona un evento. El identificador debe existir en Psych o en el mod. Se conserva la agrupación de eventos simultáneos.")
            val timeline = refs.take(2000)
            val end = maxOf(1.0, vm.liveDurationMs.toDouble(), timeline.maxOfOrNull { it.time.toDouble() } ?: 0.0)
            Canvas(Modifier.fillMaxWidth().height(52.dp).pointerInput(timeline, end) {
                detectTapGestures { point ->
                    timeline.minByOrNull { abs((it.time.toDouble() / end * size.width).toFloat() - point.x) }?.let { selected = it }
                }
            }) {
                drawLine(Color.Gray, Offset(0f, size.height / 2), Offset(size.width, size.height / 2), 3f)
                timeline.forEach { ref ->
                    drawCircle(if (ref == selected) Color(0xFFFFB300) else Color(0xFF4E8BE6), 6.dp.toPx(), Offset((ref.time.toDouble() / end * size.width).toFloat().coerceIn(0f, size.width), size.height / 2))
                }
            }
            Text("0 ms — ${end.toLong()} ms · ${refs.size} acciones", style = MaterialTheme.typography.labelSmall)
            if (refs.size > 300) Text("Se muestran las primeras 300 acciones; usa JSON para las restantes.")
            refs.take(300).forEach { ref ->
                TextButton(onClick = { selected = ref }) {
                    Text("${if (selected == ref) "✓ " else ""}${ref.time} ms · ${ref.name} · ${ref.value1 ?: "null"} / ${ref.value2 ?: "null"}")
                }
            }
            Row(Modifier.horizontalScroll(rememberScrollState())) {
                TextButton(onClick = { form(null) }) { Text("Añadir") }
                TextButton(onClick = { form(selected) }, enabled = selected != null) { Text("Editar") }
                TextButton(onClick = {
                    runCatching { duplicatePsychEvent(draft, selected!!) }.onSuccess { draft = it; selected = null; error = null }.onFailure { error = it.message }
                }, enabled = selected != null) { Text("Duplicar") }
                TextButton(onClick = { deleting = true }, enabled = selected != null) { Text("Eliminar") }
                TextButton(onClick = { draft = sortPsychEvents(draft); selected = null }) { Text("Ordenar") }
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        } },
        confirmButton = { TextButton(onClick = {
            runCatching {
                check(vm.livePsychEvents() == original) { "El chart cambió mientras editabas; vuelve a abrir el editor." }
                vm.saveLivePsychEvents(draft)
            }.onSuccess { open = false }.onFailure { error = it.message ?: "No se pudo guardar." }
        }) { Text("Guardar") } },
        dismissButton = { TextButton(onClick = { open = false }) { Text("Cancelar") } })
    if (open && formOpen) AlertDialog(onDismissRequest = { formOpen = false }, title = { Text(if (selected == null) "Añadir evento" else "Editar evento") },
        text = { Column(Modifier.verticalScroll(rememberScrollState())) {
            OutlinedTextField(time, { time = it; error = null }, label = { Text("Tiempo en ms") }, singleLine = true)
            OutlinedTextField(name, { name = it; error = null }, label = { Text("Identificador") }, singleLine = true)
            OutlinedTextField(value1, { value1 = it; null1 = false; error = null }, label = { Text("Valor 1") })
            Row { Checkbox(checked = null1, onCheckedChange = { null1 = it }); Text("Valor 1 nulo") }
            OutlinedTextField(value2, { value2 = it; null2 = false; error = null }, label = { Text("Valor 2") })
            Row { Checkbox(checked = null2, onCheckedChange = { null2 = it }); Text("Valor 2 nulo") }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        } },
        confirmButton = { TextButton(onClick = {
            runCatching { changePsychEvent(draft, selected, time, name, value1.takeUnless { null1 }, value2.takeUnless { null2 }) }
                .onSuccess { draft = it; selected = null; formOpen = false; error = null }.onFailure { error = it.message ?: "Evento inválido." }
        }) { Text("Aplicar") } }, dismissButton = { TextButton(onClick = { formOpen = false; error = null }) { Text("Cancelar") } })
    if (open && deleting) AlertDialog(onDismissRequest = { deleting = false }, title = { Text("¿Eliminar evento?") },
        text = { Text("Se quitará solo la acción seleccionada. El cambio se aplicará al guardar.") },
        confirmButton = { TextButton(onClick = {
            runCatching { removePsychEvent(draft, selected!!) }.onSuccess { draft = it; selected = null; error = null }.onFailure { error = it.message }
            deleting = false
        }) { Text("Eliminar") } }, dismissButton = { TextButton(onClick = { deleting = false }) { Text("Cancelar") } })
}
