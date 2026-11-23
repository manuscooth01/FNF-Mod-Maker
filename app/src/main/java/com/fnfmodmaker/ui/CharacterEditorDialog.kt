package com.fnfmodmaker.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.fnfmodmaker.model.CHARACTER_ANIMATIONS
import com.fnfmodmaker.model.CharacterAdjustment
import com.fnfmodmaker.model.CharacterDraft
import kotlin.math.roundToInt

private val EditorBackground = Color(0xFF08070C)
private val EditorPanel = Color(0xFF17121E)
private val Accent = Color(0xFF64EAAC)

/** Editor táctil a pantalla completa, con un diseño horizontal y una alternativa vertical. */
@Composable
internal fun CharacterEditorDialog(
    vm: WizardViewModel,
    draft: CharacterDraft,
    selectedAnimation: String,
    onAnimationSelected: (String) -> Unit,
    onClose: () -> Unit
) {
    var adjustment by remember { mutableStateOf(CharacterAdjustment.ANIMATION) }
    var zoom by remember { mutableFloatStateOf(1f) }
    var step by remember { mutableIntStateOf(1) }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(modifier = Modifier.fillMaxSize(), color = EditorBackground) {
            BoxWithConstraints(Modifier.fillMaxSize()) {
                val wide = maxWidth >= 650.dp && maxWidth > maxHeight
                if (wide) {
                    Column(Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 6.dp)) {
                        EditorHeader(draft, onClose)
                        Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Column(Modifier.width(140.dp).fillMaxSize().verticalScroll(rememberScrollState())) {
                                EditorAnimationList(draft, selectedAnimation, onAnimationSelected)
                            }
                            Column(Modifier.weight(1f).fillMaxSize().verticalScroll(rememberScrollState())) {
                                EditorPreviewHeader(zoom, { zoom = (zoom / 1.25f).coerceAtLeast(0.25f) },
                                    { zoom = (zoom * 1.25f).coerceAtMost(3f) })
                                CharacterPreviewPane(vm, draft, selectedAnimation, adjustment, { adjustment = it }, zoom, expanded = true)
                                EditorCaption()
                            }
                            Column(Modifier.width(220.dp).fillMaxSize().verticalScroll(rememberScrollState())) {
                                EditorTools(vm, draft, selectedAnimation, adjustment, { adjustment = it }, step, { step = it })
                            }
                        }
                    }
                } else {
                    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp)) {
                        EditorHeader(draft, onClose)
                        Text("Gira el teléfono para usar el editor horizontal.", color = Color(0xFFABA0B2),
                            style = MaterialTheme.typography.labelSmall)
                        Text("ANIMACIONES", color = Color.White, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            CHARACTER_ANIMATIONS.forEach { name ->
                                FilterChip(selected = name == selectedAnimation,
                                    onClick = { onAnimationSelected(name) }, label = { Text(name) })
                            }
                        }
                        EditorPreviewHeader(zoom, { zoom = (zoom / 1.25f).coerceAtLeast(0.25f) },
                            { zoom = (zoom * 1.25f).coerceAtMost(3f) })
                        CharacterPreviewPane(vm, draft, selectedAnimation, adjustment, { adjustment = it }, zoom, expanded = true)
                        EditorCaption()
                        EditorTools(vm, draft, selectedAnimation, adjustment, { adjustment = it }, step, { step = it })
                        Spacer(Modifier.height(16.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun EditorHeader(draft: CharacterDraft, onClose: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("EDITOR DE PERSONAJE", color = Color.White, fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.ExtraBold, fontSize = 16.sp)
            Text(draft.displayName.ifBlank { draft.charFolder }, color = Accent,
                style = MaterialTheme.typography.labelSmall)
        }
        TextButton(onClick = onClose) { Text("Guardar y cerrar", color = Color.White) }
    }
}

@Composable
private fun EditorAnimationList(draft: CharacterDraft, selected: String, onSelect: (String) -> Unit) {
    Text("ANIMACIONES", color = Color.White, fontWeight = FontWeight.Bold,
        fontFamily = FontFamily.Monospace, fontSize = 12.sp)
    Spacer(Modifier.height(8.dp))
    CHARACTER_ANIMATIONS.forEach { name ->
        val setting = draft.settingsFor(name)
        val count = draft.animations.firstOrNull { it.first == name }?.second ?: 0
        val active = name == selected
        Surface(
            color = if (active) Color(0xFF20382D) else Color.Transparent,
            shape = RoundedCornerShape(4.dp),
            modifier = Modifier.fillMaxWidth().clickable { onSelect(name) }
        ) {
            Column(Modifier.padding(horizontal = 6.dp, vertical = 6.dp)) {
                Text(name, color = if (active) Accent else Color.White,
                    fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                Text("[${setting.offsetX}, ${setting.offsetY}] · $count frames", color = Color(0xFFB9ADBE),
                    fontFamily = FontFamily.Monospace, fontSize = 10.sp)
            }
        }
    }
}

@Composable
private fun EditorPreviewHeader(zoom: Float, onZoomOut: () -> Unit, onZoomIn: () -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = onZoomOut) { Text("−", fontSize = 20.sp) }
        Text("ZOOM ${(zoom * 100).roundToInt()}%", color = Color.White, fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace, fontSize = 12.sp)
        TextButton(onClick = onZoomIn) { Text("+", fontSize = 20.sp) }
    }
}

@Composable
private fun EditorCaption() {
    Text("Arrastra para ajustar · Punto amarillo: cámara\nVista orientativa; comprueba el resultado en el juego.",
        color = Color(0xFFABA0B2), style = MaterialTheme.typography.labelSmall)
}

@Composable
private fun EditorTools(
    vm: WizardViewModel, draft: CharacterDraft, animation: String,
    adjustment: CharacterAdjustment, onAdjustment: (CharacterAdjustment) -> Unit,
    step: Int, onStep: (Int) -> Unit
) {
    Text("AJUSTES", color = Color.White, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
    Text("Mover", color = Color(0xFFB9ADBE), style = MaterialTheme.typography.labelSmall)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        CharacterAdjustment.entries.forEach { mode ->
            TextButton(onClick = { onAdjustment(mode) }, modifier = Modifier.weight(1f), enabled = !vm.busy,
                contentPadding = androidx.compose.foundation.layout.PaddingValues(2.dp)) {
                Text(when (mode) {
                    CharacterAdjustment.POSITION -> "Pos"
                    CharacterAdjustment.ANIMATION -> "Anim"
                    CharacterAdjustment.CAMERA -> "Cám"
                }, color = if (mode == adjustment) Accent else Color.White, fontSize = 11.sp)
            }
        }
    }
    val settings = draft.settingsFor(animation)
    val x = when (adjustment) {
        CharacterAdjustment.POSITION -> draft.positionX
        CharacterAdjustment.CAMERA -> draft.cameraX
        CharacterAdjustment.ANIMATION -> settings.offsetX
    }
    val y = when (adjustment) {
        CharacterAdjustment.POSITION -> draft.positionY
        CharacterAdjustment.CAMERA -> draft.cameraY
        CharacterAdjustment.ANIMATION -> settings.offsetY
    }
    fun setValues(newX: String, newY: String) {
        when (adjustment) {
            CharacterAdjustment.POSITION -> vm.setCharacterPosition(newX, newY)
            CharacterAdjustment.CAMERA -> vm.setCharacterCamera(newX, newY)
            CharacterAdjustment.ANIMATION -> vm.updateCharacterAnimation(animation) { it.copy(offsetX = newX.take(7), offsetY = newY.take(7)) }
        }
    }
    Text("${adjustment.label} · X / Y", color = Color.White, style = MaterialTheme.typography.labelMedium)
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        OutlinedTextField(value = x, onValueChange = { setValues(it, y) }, singleLine = true,
            enabled = !vm.busy, modifier = Modifier.weight(1f), textStyle = MaterialTheme.typography.bodySmall)
        OutlinedTextField(value = y, onValueChange = { setValues(x, it) }, singleLine = true,
            enabled = !vm.busy, modifier = Modifier.weight(1f), textStyle = MaterialTheme.typography.bodySmall)
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
        Text("Paso $step px", color = Color.White, style = MaterialTheme.typography.labelSmall)
        TextButton(onClick = { onStep(if (step == 1) 10 else 1) }) { Text(if (step == 1) "Usar 10" else "Usar 1") }
    }
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        EditorArrow("↑", !vm.busy) { vm.nudgeCharacter(adjustment, animation, 0, -step) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            EditorArrow("←", !vm.busy) { vm.nudgeCharacter(adjustment, animation, -step, 0) }
            EditorArrow("↓", !vm.busy) { vm.nudgeCharacter(adjustment, animation, 0, step) }
            EditorArrow("→", !vm.busy) { vm.nudgeCharacter(adjustment, animation, step, 0) }
        }
    }
    Spacer(Modifier.height(8.dp))
    OutlinedTextField(value = draft.scale, onValueChange = vm::setCharScale, singleLine = true,
        enabled = !vm.busy, label = { Text("Escala") }, modifier = Modifier.fillMaxWidth())
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text("Voltear X", color = Color.White, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
        Switch(checked = draft.flipX, onCheckedChange = vm::setCharFlipX, enabled = !vm.busy)
    }
    Text("$animation · ${settings.fps} FPS", color = Color(0xFFB9ADBE), style = MaterialTheme.typography.labelSmall)
}

@Composable
private fun EditorArrow(symbol: String, enabled: Boolean, onClick: () -> Unit) {
    Box(Modifier.size(48.dp).padding(3.dp).background(EditorPanel, RoundedCornerShape(10.dp))
        .clickable(enabled = enabled, onClick = onClick), contentAlignment = Alignment.Center) {
        Text(symbol, color = if (enabled) Accent else Color.Gray, fontSize = 24.sp, fontWeight = FontWeight.Bold)
    }
}
