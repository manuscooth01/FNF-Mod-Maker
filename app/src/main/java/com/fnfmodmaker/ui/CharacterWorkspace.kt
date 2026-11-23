package com.fnfmodmaker.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.fnfmodmaker.model.CharacterAdjustment
import com.fnfmodmaker.model.CharacterDraft
import com.fnfmodmaker.model.CharacterSide
import com.fnfmodmaker.model.atlasFramesForPrefix

private val characterSections = listOf("Personaje", "Imágenes", "Animación", "Posición", "Revisar")

/** A shared workspace for the standalone tool and the mod wizard. */
@Composable
internal fun CharacterWorkspace(
    vm: WizardViewModel, draft: CharacterDraft, onPickSheet: () -> Unit, onPickXml: () -> Unit,
    onPickIcon: () -> Unit, onPickFrames: (String) -> Unit, modifier: Modifier = Modifier
) {
    var section by rememberSaveable { mutableStateOf("Imágenes") }
    var selected by rememberSaveable { mutableStateOf("idle") }
    var adjustment by rememberSaveable { mutableStateOf(CharacterAdjustment.ANIMATION) }
    var zoom by rememberSaveable { mutableStateOf(1f) }
    if (selected !in draft.animationNames) selected = "idle"

    BoxWithConstraints(modifier.fillMaxSize()) {
        val landscape = androidx.compose.ui.platform.LocalConfiguration.current.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
        val wide = maxWidth >= 650.dp || (landscape && maxWidth >= 480.dp)
        val showPreview = wide || maxHeight >= 460.dp
        @Composable fun Preview() {
            Column(Modifier.fillMaxWidth()) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("VISTA PREVIA · $selected", Modifier.weight(1f), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                    TextButton(onClick = { zoom = (zoom / 1.25f).coerceAtLeast(.25f) }) { Text("−") }
                    Text("${(zoom * 100).toInt()}%", style = MaterialTheme.typography.labelSmall)
                    TextButton(onClick = { zoom = (zoom * 1.25f).coerceAtMost(3f) }) { Text("+") }
                }
                CharacterPreviewPane(vm, draft, selected, adjustment, { adjustment = it }, zoom, expanded = true)
                Text("Arrastra para ajustar ${adjustment.label.lowercase()}. La escena es orientativa; prueba el resultado en el juego.",
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        @Composable fun Controls(mod: Modifier) {
            Column(mod) {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    characterSections.forEach { label ->
                        FilterChip(selected = section == label, onClick = { section = label },
                            label = { Text(if (label == "Revisar" && vm.characterIssues.isNotEmpty()) "Revisar · ${vm.characterIssues.size}" else label) })
                    }
                }
                Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) {
                    if (vm.busy) Text(vm.status, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall)
                    vm.error?.let { MessageCard(it, error = true); Spacer(Modifier.height(8.dp)) }
                    when (section) {
                        "Personaje" -> CharacterIdentity(vm, draft, onPickIcon)
                        "Imágenes" -> CharacterImages(vm, draft, selected, { selected = it }, onPickSheet, onPickXml, onPickFrames)
                        "Animación" -> CharacterAnimation(vm, draft, selected, { selected = it })
                        "Posición" -> CharacterPosition(vm, draft, selected, { selected = it }, adjustment, { adjustment = it })
                        else -> CharacterReview(vm, { section = it })
                    }
                    Spacer(Modifier.height(28.dp))
                }
            }
        }
        if (wide) {
            Row(Modifier.fillMaxSize().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) { Preview() }
                Controls(Modifier.weight(1f).fillMaxSize())
            }
        } else {
            Column(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
                if (showPreview) Preview()
                else Text("Vista previa oculta para dejar espacio a los controles.", style = MaterialTheme.typography.labelSmall)
                Controls(Modifier.weight(1f).fillMaxWidth())
            }
        }
    }
}

@Composable
private fun CharacterIdentity(vm: WizardViewModel, draft: CharacterDraft, onPickIcon: () -> Unit) {
    Text("Tu personaje", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
    Field("Nombre visible", draft.displayName, vm::setCharName, hint = "Opcional; ID de salida: ${draft.charFolder}", enabled = !vm.busy)
    Spacer(Modifier.height(8.dp))
    Field("ID de archivo", draft.charId, vm::setCharId, hint = "Opcional. Se usa en los archivos exportados.", enabled = !vm.busy)
    SectionTitle("Lado en la canción")
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        CharacterSide.entries.forEach { side -> FilterChip(side == draft.side, { vm.setCharSide(side) }, enabled = !vm.busy, label = { Text(side.label) }) }
    }
    Field("Escala", draft.scale, vm::setCharScale, keyboardType = KeyboardType.Decimal, enabled = !vm.busy)
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text("Voltear horizontalmente", Modifier.weight(1f)); Switch(draft.flipX, vm::setCharFlipX, enabled = !vm.busy)
    }
    SectionTitle("Icono de vida · opcional")
    Text(draft.icon?.displayName ?: "Se usará el icono del juego base.", style = MaterialTheme.typography.bodySmall)
    Text("Recomendado: PNG de 300 × 150 px, con normal y derrota de 150 × 150.", style = MaterialTheme.typography.labelSmall)
    Row {
        OutlinedButton(onClick = onPickIcon, enabled = !vm.busy) { Text(if (draft.icon == null) "Elegir icono" else "Cambiar icono") }
        if (draft.icon != null) TextButton(onClick = vm::clearCharacterIcon, enabled = !vm.busy) { Text("Quitar") }
    }
}

@Composable
private fun AnimationSelector(draft: CharacterDraft, selected: String, onSelect: (String) -> Unit) {
    val counts = remember(draft) { draft.animations.toMap() }
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        draft.animationNames.forEach { name ->
            val count = counts[name] ?: 0
            FilterChip(name == selected, { onSelect(name) }, label = { Text("$name · $count") })
        }
    }
}

@Composable
private fun CharacterImages(vm: WizardViewModel, draft: CharacterDraft, selected: String, onSelect: (String) -> Unit,
    onPickSheet: () -> Unit, onPickXml: () -> Unit, onPickFrames: (String) -> Unit) {
    Text("Origen de imágenes", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        FilterChip(!draft.useAtlas && draft.useIndividualFrames, { vm.setIndividualFrames(true) }, enabled = !vm.busy, label = { Text("Frames PNG") })
        FilterChip(!draft.useAtlas && !draft.useIndividualFrames, { vm.setIndividualFrames(false) }, enabled = !vm.busy, label = { Text("Cuadrícula") })
        FilterChip(draft.useAtlas, vm::setCharacterAtlas, enabled = !vm.busy, label = { Text("Atlas PNG + XML") })
    }
    Text("Cambiar de origen no borra lo que ya seleccionaste.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    if (draft.useAtlas) {
        SectionTitle("Atlas Sparrow existente")
        Text("Selecciona el PNG y su XML Sparrow. Los atlas rotados y otros formatos no son compatibles.", style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(8.dp))
        Text("PNG: ${draft.atlasSheet?.displayName ?: "sin elegir"}", style = MaterialTheme.typography.bodySmall)
        OutlinedButton(onClick = onPickSheet, enabled = !vm.busy) { Text("Elegir PNG") }
        Text("XML: ${draft.atlasXml?.displayName ?: "sin elegir"} · ${draft.atlasFrames.size} frames", style = MaterialTheme.typography.bodySmall)
        OutlinedButton(onClick = onPickXml, enabled = !vm.busy) { Text("Elegir XML Sparrow") }
        Text("Después asigna los prefijos en la pestaña Animación. Idle es obligatorio.", style = MaterialTheme.typography.bodySmall)
    } else if (draft.useIndividualFrames) {
        SectionTitle("Imágenes por animación")
        AnimationSelector(draft, selected, onSelect)
        val frames = draft.animationFrames[selected].orEmpty()
        Text("${frames.size} frames en $selected · máximo 100 en total", style = MaterialTheme.typography.bodySmall)
        OutlinedButton(onClick = { onPickFrames(selected) }, enabled = !vm.busy) { Text("Añadir PNG a $selected") }
        Text("Comprueba el orden; el selector del dispositivo puede cambiarlo.", style = MaterialTheme.typography.labelSmall)
        var frameToRemove by rememberSaveable(selected) { mutableStateOf<Int?>(null) }
        frameToRemove?.let { index ->
            androidx.compose.material3.AlertDialog(onDismissRequest = { frameToRemove = null },
                title = { Text("¿Quitar este frame?") },
                text = { Text("Se quitará de $selected; el archivo original no se modifica.") },
                confirmButton = { TextButton(onClick = { vm.removeCharacterFrame(selected, index); frameToRemove = null }) { Text("Quitar") } },
                dismissButton = { TextButton(onClick = { frameToRemove = null }) { Text("Cancelar") } })
        }
        frames.forEachIndexed { index, frame ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("${index + 1}. ${frame.image.displayName}", Modifier.weight(1f), maxLines = 1, style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = { vm.moveCharacterFrame(selected, index, -1) }, enabled = !vm.busy && index > 0,
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(4.dp)) { Text("↑") }
                TextButton(onClick = { vm.moveCharacterFrame(selected, index, 1) }, enabled = !vm.busy && index < frames.lastIndex,
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(4.dp)) { Text("↓") }
                TextButton(onClick = { frameToRemove = index }, enabled = !vm.busy,
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(4.dp)) { Text("Quitar") }
            }
        }
    } else {
        SectionTitle("Hoja en cuadrícula")
        Text("Usa frames del mismo tamaño, sin huecos. Se leen por filas en el orden indicado abajo.", style = MaterialTheme.typography.bodySmall)
        Text(draft.sheet?.displayName ?: "No has elegido una hoja.", style = MaterialTheme.typography.bodySmall)
        OutlinedButton(onClick = onPickSheet, enabled = !vm.busy) { Text(if (draft.sheet == null) "Elegir PNG" else "Cambiar PNG") }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Field("Ancho px", draft.frameWidth, vm::setCharFrameWidth, keyboardType = KeyboardType.Number, modifier = Modifier.weight(1f), enabled = !vm.busy)
            Field("Alto px", draft.frameHeight, vm::setCharFrameHeight, keyboardType = KeyboardType.Number, modifier = Modifier.weight(1f), enabled = !vm.busy)
        }
        Text("Cuadrícula: ${draft.columns} × ${draft.rows} · ${draft.totalFrames}/${draft.availableFrames} frames usados", style = MaterialTheme.typography.bodySmall)
        if (draft.sheet != null && draft.frameWidthValue > 0 && draft.frameHeightValue > 0 &&
            (draft.sheetWidth % draft.frameWidthValue != 0 || draft.sheetHeight % draft.frameHeightValue != 0))
            Text("La hoja no se divide exactamente: se ignorarán los bordes sobrantes.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall)
        SectionTitle("Número de frames en orden")
        listOf(Triple("idle", draft.idleFrames, vm::setCharIdleFrames), Triple("singLEFT", draft.leftFrames, vm::setCharLeftFrames),
            Triple("singDOWN", draft.downFrames, vm::setCharDownFrames), Triple("singUP", draft.upFrames, vm::setCharUpFrames),
            Triple("singRIGHT", draft.rightFrames, vm::setCharRightFrames)).forEach { (label, value, change) ->
            Field(label, value, change, keyboardType = KeyboardType.Number, enabled = !vm.busy)
        }
        draft.customAnimations.forEach { name ->
            Field(name, draft.customFrameCounts[name] ?: "0", { vm.setCustomFrameCount(name, it) }, keyboardType = KeyboardType.Number, enabled = !vm.busy)
        }
    }
}

@Composable
private fun CharacterAnimation(vm: WizardViewModel, draft: CharacterDraft, selected: String, onSelect: (String) -> Unit) {
    Text("Animaciones", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
    AnimationSelector(draft, selected, onSelect)
    var newName by rememberSaveable { mutableStateOf("") }
    var nameError by rememberSaveable { mutableStateOf(false) }
    Field("Nueva animación", newName, { newName = it; nameError = false }, hint = "Ejemplo: hey o singLEFTmiss. Letras, números, guion o guion bajo.", enabled = !vm.busy)
    var confirmRemove by rememberSaveable(selected) { mutableStateOf(false) }
    if (confirmRemove) androidx.compose.material3.AlertDialog(onDismissRequest = { confirmRemove = false },
        title = { Text("¿Eliminar $selected?") },
        text = { Text("Se quitarán sus ajustes y frames del personaje. Los archivos originales no se modifican.") },
        confirmButton = { TextButton(onClick = { vm.removeCustomAnimation(selected); onSelect("idle"); confirmRemove = false }) { Text("Eliminar") } },
        dismissButton = { TextButton(onClick = { confirmRemove = false }) { Text("Cancelar") } })
    Row {
        OutlinedButton(onClick = { if (vm.addCustomAnimation(newName)) { onSelect(newName); newName = ""; nameError = false } else nameError = true }, enabled = !vm.busy) { Text("Añadir") }
        if (selected in draft.customAnimations) TextButton(onClick = { confirmRemove = true }, enabled = !vm.busy) { Text("Eliminar $selected") }
    }
    if (nameError) Text("Nombre inválido, repetido o límite de 30 animaciones adicionales.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
    SectionTitle("Ajustes de $selected")
    if (draft.useAtlas) {
        Field("Prefijo del XML", draft.prefixFor(selected), { vm.setAtlasPrefix(selected, it) },
            hint = "Debe coincidir con el inicio del nombre de los frames del atlas.", enabled = !vm.busy)
        Text("${atlasFramesForPrefix(draft.atlasFrames, draft.prefixFor(selected)).size} frames coinciden", style = MaterialTheme.typography.bodySmall)
        val suggestions = draft.atlasFrames.map { it.name.replace(Regex("[0-9]+$"), "") }.distinct().take(50)
        if (suggestions.isNotEmpty()) {
            Text("Prefijos detectados (toca para asignar):", style = MaterialTheme.typography.labelSmall)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                suggestions.forEach { prefix -> FilterChip(prefix == draft.prefixFor(selected), { vm.setAtlasPrefix(selected, prefix) }, label = { Text(prefix) }) }
            }
        }
    } else Text("El prefijo se genera automáticamente al exportar.", style = MaterialTheme.typography.bodySmall)
    val settings = draft.settingsFor(selected)
    Field("FPS", settings.fps, { v -> vm.updateCharacterAnimation(selected) { it.copy(fps = v.filter(Char::isDigit).take(2)) } }, keyboardType = KeyboardType.Number, enabled = !vm.busy)
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text("Repetir animación", Modifier.weight(1f)); Switch(settings.loop, { v -> vm.updateCharacterAnimation(selected) { it.copy(loop = v) } }, enabled = !vm.busy)
    }
    Text("Los offsets se ajustan en Posición o arrastrando el personaje con Animación seleccionada.", style = MaterialTheme.typography.bodySmall)
}

@Composable
private fun CharacterPosition(vm: WizardViewModel, draft: CharacterDraft, selected: String, onSelect: (String) -> Unit,
    adjustment: CharacterAdjustment, onAdjustment: (CharacterAdjustment) -> Unit) {
    Text("Posición y offsets", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
    AnimationSelector(draft, selected, onSelect)
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        CharacterAdjustment.entries.forEach { mode -> FilterChip(mode == adjustment, { onAdjustment(mode) }, label = { Text(mode.label) }) }
    }
    val settings = draft.settingsFor(selected)
    val x = when (adjustment) { CharacterAdjustment.POSITION -> draft.positionX; CharacterAdjustment.ANIMATION -> settings.offsetX; CharacterAdjustment.CAMERA -> draft.cameraX }
    val y = when (adjustment) { CharacterAdjustment.POSITION -> draft.positionY; CharacterAdjustment.ANIMATION -> settings.offsetY; CharacterAdjustment.CAMERA -> draft.cameraY }
    fun set(xValue: String, yValue: String) {
        when (adjustment) {
            CharacterAdjustment.POSITION -> vm.setCharacterPosition(xValue, yValue)
            CharacterAdjustment.CAMERA -> vm.setCharacterCamera(xValue, yValue)
            CharacterAdjustment.ANIMATION -> vm.updateCharacterAnimation(selected) { it.copy(offsetX = xValue.take(7), offsetY = yValue.take(7)) }
        }
    }
    Text("${adjustment.label} · ${if (adjustment == CharacterAdjustment.ANIMATION) selected else "todas las animaciones"}", style = MaterialTheme.typography.bodySmall)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Field("X", x, { set(it, y) }, modifier = Modifier.weight(1f), keyboardType = KeyboardType.Text, enabled = !vm.busy)
        Field("Y", y, { set(x, it) }, modifier = Modifier.weight(1f), keyboardType = KeyboardType.Text, enabled = !vm.busy)
    }
    var step by rememberSaveable { mutableStateOf(1) }
    TextButton(onClick = { step = if (step == 1) 10 else 1 }) { Text("Paso: $step px · cambiar") }
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        listOf(Triple("←", -step, 0), Triple("↑", 0, -step), Triple("↓", 0, step), Triple("→", step, 0)).forEach { (symbol, dx, dy) ->
            OutlinedButton(onClick = { vm.nudgeCharacter(adjustment, selected, dx, dy) }, enabled = !vm.busy,
                modifier = Modifier.weight(1f), contentPadding = androidx.compose.foundation.layout.PaddingValues(2.dp)) { Text(symbol) }
        }
    }
    Text("Punto amarillo: cámara. También puedes arrastrar sobre la vista previa.", style = MaterialTheme.typography.bodySmall)
}

@Composable
private fun CharacterReview(vm: WizardViewModel, onSection: (String) -> Unit) {
    Text("Revisión", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
    if (vm.characterIssues.isEmpty()) MessageCard("El personaje está listo para generar.", error = false)
    else {
        Text("Corrige lo siguiente antes de exportar:", style = MaterialTheme.typography.bodySmall)
        vm.characterIssues.forEach { Text("• $it", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
    }
    Row(Modifier.horizontalScroll(rememberScrollState())) {
        TextButton(onClick = { onSection("Imágenes") }) { Text("Ir a imágenes") }
        TextButton(onClick = { onSection("Animación") }) { Text("Ir a animaciones") }
        TextButton(onClick = { onSection("Posición") }) { Text("Ir a posición") }
    }
    Text("Se generan definiciones para Psych y/o V-Slice según el destino. Las animaciones extra se exportan, pero cada juego decide cuándo reproducirlas.", style = MaterialTheme.typography.bodySmall)
    vm.characterSaved?.let { MessageCard("ZIP guardado: $it", error = false) }
    vm.warnings.forEach { Text("Aviso: $it", style = MaterialTheme.typography.bodySmall) }
}
