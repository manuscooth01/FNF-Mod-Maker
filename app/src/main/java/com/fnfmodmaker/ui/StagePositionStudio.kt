package com.fnfmodmaker.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.fnfmodmaker.model.StageRole
import kotlin.math.roundToInt

/** Stage offsets are stored independently from a character's own placement and animation offsets. */
@Composable
internal fun StagePositionStudio(vm: WizardViewModel) {
    val stage = vm.config.stageDraft
    var vSlice by remember(vm.config.engine) { mutableStateOf(vm.config.engine.includesVSlice) }
    var role by remember { mutableStateOf(StageRole.DAD) }
    var camera by remember { mutableStateOf(false) }
    val layout = if (vSlice) stage.vSliceLayout else stage.psychLayout
    val actor = layout.actor(role)
    val background = imageThumbnail(stage.backgroundImage?.path)
    Text("Stage & Character Studio · posiciones y cámara", style = MaterialTheme.typography.titleMedium)
    Text("Coordenadas separadas por motor; no alteran animaciones ni offsets del personaje. Vista orientativa, no reproduce el motor.", style = MaterialTheme.typography.labelSmall)
    Row(Modifier.horizontalScroll(rememberScrollState())) {
        if (vm.config.engine.includesVSlice) FilterChip(vSlice, { vSlice = true }, label = { Text("V-Slice") })
        if (vm.config.engine.includesPsych) FilterChip(!vSlice, { vSlice = false }, label = { Text("Psych") }, modifier = Modifier.padding(start = 6.dp))
    }
    Row(Modifier.horizontalScroll(rememberScrollState())) {
        StageRole.entries.forEach { item -> FilterChip(item == role, { role = item }, label = { Text(item.label) }, modifier = Modifier.padding(end = 4.dp)) }
    }
    Row {
        FilterChip(!camera, { camera = false }, label = { Text("Posición") })
        FilterChip(camera, { camera = true }, label = { Text("Cámara") }, modifier = Modifier.padding(start = 6.dp))
    }
    val colors = listOf(Color(0xFF54C8F4), Color(0xFFFF7588), Color(0xFFE0A3F5))
    Canvas(Modifier.fillMaxWidth().aspectRatio(16f / 9f).background(Color(0xFF171322))
        .pointerInput(camera, vSlice, vm.busy) {
            if (!vm.busy) detectTapGestures { point ->
                val current = if (vSlice) vm.config.stageDraft.vSliceLayout else vm.config.stageDraft.psychLayout
                StageRole.entries.minByOrNull { r ->
                    val p = current.actor(r)
                    val x = ((p.x + if (camera) p.cameraX else 0f) + 600f) / 2000f * size.width
                    val y = ((p.y + if (camera) p.cameraY else 0f) + 200f) / 1200f * size.height
                    (x - point.x) * (x - point.x) + (y - point.y) * (y - point.y)
                }?.let { role = it }
            }
        }.pointerInput(camera, vSlice, vm.busy) {
            if (!vm.busy) detectDragGestures(onDragStart = { point ->
                val currentLayout = if (vSlice) vm.config.stageDraft.vSliceLayout else vm.config.stageDraft.psychLayout
                val nearest = StageRole.entries.minByOrNull { r ->
                    val p = currentLayout.actor(r)
                    val x = ((p.x + if (camera) p.cameraX else 0f) + 600f) / 2000f * size.width
                    val y = ((p.y + if (camera) p.cameraY else 0f) + 200f) / 1200f * size.height
                    (x - point.x) * (x - point.x) + (y - point.y) * (y - point.y)
                }
                if (nearest != null) role = nearest
            }, onDrag = { change, delta ->
                change.consume()
                val current = (if (vSlice) vm.config.stageDraft.vSliceLayout else vm.config.stageDraft.psychLayout).actor(role)
                val dx = delta.x * 2000f / size.width; val dy = delta.y * 1200f / size.height
                val updated = if (camera) current.copy(cameraX = (current.cameraX + dx).coerceIn(-100000f, 100000f), cameraY = (current.cameraY + dy).coerceIn(-100000f, 100000f))
                    else current.copy(x = (current.x + dx).coerceIn(-100000f, 100000f), y = (current.y + dy).coerceIn(-100000f, 100000f))
                vm.updateStageActor(vSlice, role, updated)
            })
        }) {
        if (background != null) drawImage(background, dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt()))
        drawRect(Color.Black.copy(alpha = .35f))
        StageRole.entries.forEachIndexed { i, r ->
            val p = layout.actor(r)
            val point = Offset((p.x + 600f) / 2000f * size.width, (p.y + 200f) / 1200f * size.height)
            val focus = Offset((p.x + p.cameraX + 600f) / 2000f * size.width, (p.y + p.cameraY + 200f) / 1200f * size.height)
            drawLine(colors[i], point, focus, strokeWidth = 2f)
            drawCircle(colors[i], if (r == role) 15f else 10f, point)
            drawCircle(colors[i], 8f, focus, style = Stroke(2f))
        }
    }
    Text("Arrastra un punto para elegir y mover; círculo vacío: offset de cámara aproximado.", style = MaterialTheme.typography.labelSmall)
    if (!camera) {
        StudioNumber("X", actor.x) { vm.updateStageActor(vSlice, role, actor.copy(x = it)) }
        StudioNumber("Y", actor.y) { vm.updateStageActor(vSlice, role, actor.copy(y = it)) }
        if (vSlice) {
            StudioNumber("Escala de escenario (V-Slice)", actor.scale) { vm.updateStageActor(true, role, actor.copy(scale = it)) }
            StudioNumber("Z-Index (V-Slice)", actor.zIndex.toFloat()) { vm.updateStageActor(true, role, actor.copy(zIndex = it.roundToInt())) }
            StudioNumber("Scroll X (V-Slice)", actor.scrollX) { vm.updateStageActor(true, role, actor.copy(scrollX = it)) }
            StudioNumber("Scroll Y (V-Slice)", actor.scrollY) { vm.updateStageActor(true, role, actor.copy(scrollY = it)) }
        } else Text("Psych no define escala, Z ni scroll de personaje en su JSON de escenario.", style = MaterialTheme.typography.labelSmall)
    } else {
        StudioNumber("Offset de cámara X", actor.cameraX) { vm.updateStageActor(vSlice, role, actor.copy(cameraX = it)) }
        StudioNumber("Offset de cámara Y", actor.cameraY) { vm.updateStageActor(vSlice, role, actor.copy(cameraY = it)) }
        if (!vSlice) StudioNumber("Velocidad de cámara Psych", stage.psychCameraSpeed, vm::setPsychCameraSpeed)
    }
}

@Composable
private fun StudioNumber(label: String, value: Float, onChange: (Float) -> Unit) {
    var text by remember(label, value) { mutableStateOf(value.toString()) }
    Field(label, text, { input ->
        text = input.take(16)
        input.toFloatOrNull()?.takeIf { it.isFinite() }?.let(onChange)
    }, keyboardType = androidx.compose.ui.text.input.KeyboardType.Decimal)
    val step = if (label.contains("Escala") || label.contains("Scroll") || label.contains("Velocidad")) .1f else 10f
    Row {
        TextButton(onClick = { onChange(value - step) }) { Text("− $step") }
        TextButton(onClick = { onChange(value + step) }) { Text("+ $step") }
    }
}
