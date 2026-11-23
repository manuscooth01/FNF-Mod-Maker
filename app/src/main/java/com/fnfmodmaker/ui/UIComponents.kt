package com.fnfmodmaker.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.fnfmodmaker.model.CHARACTER_ANIMATIONS
import com.fnfmodmaker.model.ALL_DIFFICULTIES
import com.fnfmodmaker.model.AudioSlot
import com.fnfmodmaker.model.CharacterDraft
import com.fnfmodmaker.model.CharacterSide
import com.fnfmodmaker.model.ModConfig
import com.fnfmodmaker.model.PickedAudio
import com.fnfmodmaker.model.PickedImage
import com.fnfmodmaker.model.SongConfig
import com.fnfmodmaker.model.StageDraft
import com.fnfmodmaker.model.StageSprite
import com.fnfmodmaker.model.TargetEngine

// Shared UI components

@Composable
fun SectionTitle(text: String) {
    Spacer(Modifier.height(18.dp))
    androidx.compose.material3.Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.Bold
    )
    Spacer(Modifier.height(8.dp))
}

@Composable
fun Field(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    hint: String = "",
    keyboardType: KeyboardType = KeyboardType.Text,
    singleLine: Boolean = true,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    Column(modifier = modifier.fillMaxWidth()) {
        androidx.compose.material3.OutlinedTextField(
            value = value,
            onValueChange = onChange,
            enabled = enabled,
            label = { Text(label) },
            singleLine = singleLine,
            minLines = if (singleLine) 1 else 3,
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = keyboardType),
            modifier = Modifier.fillMaxWidth()
        )
        if (hint.isNotEmpty()) {
            Text(
                hint,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 4.dp, top = 4.dp)
            )
        }
    }
}

@Composable
fun StepHeader(title: String, subtitle: String) {
    Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
    Spacer(Modifier.height(4.dp))
    Text(
        subtitle,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    Spacer(Modifier.height(14.dp))
}

@Composable
fun HelpCard(text: String) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(
            text,
            modifier = Modifier.padding(14.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
fun EngineCard(engine: TargetEngine, selected: Boolean, onClick: () -> Unit) {
    Surface(
        color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(
            width = if (selected) 2.dp else 1.dp,
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
        ),
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
    ) {
        Column(Modifier.padding(14.dp)) {
            Text(engine.label, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(2.dp))
            Text(
                engine.description,
                style = MaterialTheme.typography.bodySmall,
                color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer
                else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
fun MessageCard(text: String, error: Boolean) {
    Surface(
        color = if (error) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer,
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(
            text,
            modifier = Modifier.padding(14.dp),
            style = MaterialTheme.typography.bodySmall,
            color = if (error) MaterialTheme.colorScheme.onErrorContainer
            else MaterialTheme.colorScheme.onSecondaryContainer
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FilterChip(
    selected: Boolean,
    onClick: () -> Unit,
    label: @Composable () -> Unit
) {
    androidx.compose.material3.FilterChip(
        selected = selected,
        onClick = onClick,
        label = label,
        modifier = Modifier.fillMaxWidth()
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ToolTopBar(
    title: String,
    subtitle: String = "",
    onHome: (() -> Unit)? = null
) {
    TopAppBar(
        navigationIcon = {
            if (onHome != null) {
                TextButton(onClick = onHome) { Text("‹ Inicio") }
            }
        },
        title = {
            Column {
                Text(title, fontWeight = FontWeight.Bold)
                if (subtitle.isNotBlank()) {
                    Text(
                        subtitle,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.surface,
            titleContentColor = MaterialTheme.colorScheme.onSurface
        )
    )
}

@Composable
fun ToolCard(
    glyph: String,
    title: String,
    description: String,
    badge: String?,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(18.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(modifier = Modifier.padding(16.dp)) {
            Text(glyph, style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(title, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                    if (badge != null) {
                        Spacer(Modifier.width(8.dp))
                        Surface(
                            color = MaterialTheme.colorScheme.primary,
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text(
                                badge,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onPrimary
                            )
                        }
                    }
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
fun ToolStatus(
    status: String,
    error: String?,
    saved: String?,
    warnings: List<String>,
    savedLabel: String
) {
    if (status.isNotBlank()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(modifier = Modifier.height(16.dp).width(16.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(10.dp))
            Text(status, style = MaterialTheme.typography.bodySmall)
        }
        Spacer(Modifier.height(8.dp))
    }

    if (error != null) {
        Surface(
            color = MaterialTheme.colorScheme.errorContainer,
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                error,
                modifier = Modifier.padding(14.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer
            )
        }
        Spacer(Modifier.height(8.dp))
    }

    if (saved != null) {
        Surface(
            color = MaterialTheme.colorScheme.surface,
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(Modifier.padding(14.dp)) {
                Text("$savedLabel:", style = MaterialTheme.typography.labelSmall)
                Text(saved, fontWeight = FontWeight.SemiBold)
            }
        }
        Spacer(Modifier.height(8.dp))
    }

    warnings.forEach { message ->
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant,
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                "Aviso: $message",
                modifier = Modifier.padding(14.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
fun InfoLine(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(
            label,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
fun InfoRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(
            label,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold
        )
    }
}

@Composable
fun CharacterForm(
    vm: WizardViewModel,
    draft: CharacterDraft,
    onPickSheet: () -> Unit,
    onPickIcon: () -> Unit,
    onPickFrames: (String) -> Unit
) {
    var selectedAnimation by remember { mutableStateOf("idle") }
    Column(Modifier.fillMaxWidth()) {
        SectionTitle("Identidad")
        Field("Nombre visible", draft.displayName, vm::setCharName,
            hint = "El nombre que verás en el juego.", enabled = !vm.busy)
        Spacer(Modifier.height(10.dp))
        Field("ID del personaje", draft.charId, vm::setCharId,
            hint = "Opcional; ID de salida: ${draft.charFolder}.", enabled = !vm.busy)
        Spacer(Modifier.height(12.dp))
        Text("Lado en el mod", fontWeight = FontWeight.SemiBold)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CharacterSide.entries.forEach { side ->
                androidx.compose.material3.FilterChip(
                    selected = draft.side == side,
                    onClick = { vm.setCharSide(side) },
                    enabled = !vm.busy,
                    label = { Text(side.label) }
                )
            }
        }

        SectionTitle("Origen de las imágenes")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            androidx.compose.material3.FilterChip(
                selected = !draft.useIndividualFrames, onClick = { vm.setIndividualFrames(false) },
                enabled = !vm.busy, label = { Text("Cuadrícula") })
            androidx.compose.material3.FilterChip(
                selected = draft.useIndividualFrames, onClick = { vm.setIndividualFrames(true) },
                enabled = !vm.busy, label = { Text("Frames PNG") })
        }
        if (!draft.useIndividualFrames) {
        SectionTitle("Hoja de sprites")
        HelpCard("Usa un PNG con frames del mismo tamaño, en cuadrícula y sin huecos. " +
            "Se leen de izquierda a derecha y de arriba abajo: idle, LEFT, DOWN, UP y RIGHT.")
        Spacer(Modifier.height(10.dp))
        Text(draft.sheet?.displayName ?: "Ninguna hoja elegida", fontWeight = FontWeight.SemiBold)
        if (draft.sheet != null) {
            Text("Imagen: ${draft.sheetWidth} × ${draft.sheetHeight} px",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.height(6.dp))
        OutlinedButton(onClick = onPickSheet, enabled = !vm.busy) { Text("Elegir hoja PNG") }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Field("Ancho del frame", draft.frameWidth, vm::setCharFrameWidth,
                keyboardType = KeyboardType.Number, modifier = Modifier.weight(1f), enabled = !vm.busy)
            Field("Alto del frame", draft.frameHeight, vm::setCharFrameHeight,
                keyboardType = KeyboardType.Number, modifier = Modifier.weight(1f), enabled = !vm.busy)
        }
        if (draft.sheet != null && draft.columns > 0 && draft.rows > 0) {
            Spacer(Modifier.height(8.dp))
            Text("Cuadrícula: ${draft.columns} × ${draft.rows} · ${draft.availableFrames} frames disponibles",
                style = MaterialTheme.typography.bodySmall)
            Text("Frames usados: ${draft.totalFrames}", style = MaterialTheme.typography.bodySmall)
            if (draft.sheetWidth % draft.frameWidthValue != 0 || draft.sheetHeight % draft.frameHeightValue != 0) {
                Text("La imagen no se divide exactamente; se ignorarán los píxeles sobrantes.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        SectionTitle("Frames por animación")
        Text("Idle es obligatorio; usa 0 para las demás animaciones que no necesites.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Field("Idle", draft.idleFrames, vm::setCharIdleFrames, keyboardType = KeyboardType.Number,
                modifier = Modifier.weight(1f), enabled = !vm.busy)
            Field("Izquierda", draft.leftFrames, vm::setCharLeftFrames, keyboardType = KeyboardType.Number,
                modifier = Modifier.weight(1f), enabled = !vm.busy)
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Field("Abajo", draft.downFrames, vm::setCharDownFrames, keyboardType = KeyboardType.Number,
                modifier = Modifier.weight(1f), enabled = !vm.busy)
            Field("Arriba", draft.upFrames, vm::setCharUpFrames, keyboardType = KeyboardType.Number,
                modifier = Modifier.weight(1f), enabled = !vm.busy)
        }
        Spacer(Modifier.height(8.dp))
        Field("Derecha", draft.rightFrames, vm::setCharRightFrames, keyboardType = KeyboardType.Number,
            enabled = !vm.busy)

        } else {
            Spacer(Modifier.height(8.dp))
            HelpCard("Añade PNG independientes a cada animación. Revisa el orden: algunos selectores de archivos no conservan el orden de selección. Máximo 100 frames y una hoja final de 4096 × 4096.")
        }

        SectionTitle("Editar animación")
        CHARACTER_ANIMATIONS.chunked(3).forEach { group ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                group.forEach { name ->
                    androidx.compose.material3.FilterChip(
                        selected = selectedAnimation == name, onClick = { selectedAnimation = name },
                        enabled = !vm.busy, label = { Text(name.removePrefix("sing")) })
                }
            }
        }
        val animation = draft.settingsFor(selectedAnimation)
        CharacterPreview(vm, draft, selectedAnimation)
        Text("Posición del personaje (px)", fontWeight = FontWeight.SemiBold)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Field("Personaje X", draft.positionX, { vm.setCharacterPosition(it, draft.positionY) },
                modifier = Modifier.weight(1f), enabled = !vm.busy)
            Field("Personaje Y", draft.positionY, { vm.setCharacterPosition(draft.positionX, it) },
                modifier = Modifier.weight(1f), enabled = !vm.busy)
        }
        Spacer(Modifier.height(8.dp))
        Text("Desplazamiento de cámara (px)", fontWeight = FontWeight.SemiBold)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Field("Cámara X", draft.cameraX, { vm.setCharacterCamera(it, draft.cameraY) },
                modifier = Modifier.weight(1f), enabled = !vm.busy)
            Field("Cámara Y", draft.cameraY, { vm.setCharacterCamera(draft.cameraX, it) },
                modifier = Modifier.weight(1f), enabled = !vm.busy)
        }
        Spacer(Modifier.height(12.dp))
        if (draft.useIndividualFrames) {
            val frames = draft.animationFrames[selectedAnimation].orEmpty()
            Text("${frames.size} frames · $selectedAnimation", fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = { onPickFrames(selectedAnimation) }, enabled = !vm.busy) {
                Text("Añadir imágenes PNG")
            }
            frames.forEachIndexed { index, frame ->
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("${index + 1}. ${frame.image.displayName}", modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodySmall, maxLines = 1)
                    TextButton(onClick = { vm.moveCharacterFrame(selectedAnimation, index, -1) },
                        enabled = !vm.busy && index > 0) { Text("↑") }
                    TextButton(onClick = { vm.moveCharacterFrame(selectedAnimation, index, 1) },
                        enabled = !vm.busy && index < frames.lastIndex) { Text("↓") }
                    TextButton(onClick = { vm.removeCharacterFrame(selectedAnimation, index) },
                        enabled = !vm.busy) { Text("✕") }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Field("FPS de $selectedAnimation", animation.fps, { value ->
            vm.updateCharacterAnimation(selectedAnimation) { it.copy(fps = value.filter(Char::isDigit).take(2)) }
        }, keyboardType = KeyboardType.Number, enabled = !vm.busy)
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Field("Offset X", animation.offsetX, { value ->
                vm.updateCharacterAnimation(selectedAnimation) { it.copy(offsetX = value.take(7)) }
            }, modifier = Modifier.weight(1f), enabled = !vm.busy)
            Field("Offset Y", animation.offsetY, { value ->
                vm.updateCharacterAnimation(selectedAnimation) { it.copy(offsetY = value.take(7)) }
            }, modifier = Modifier.weight(1f), enabled = !vm.busy)
        }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text("Repetir animación en el juego", modifier = Modifier.weight(1f))
            Switch(checked = animation.loop, onCheckedChange = { value ->
                vm.updateCharacterAnimation(selectedAnimation) { it.copy(loop = value) }
            }, enabled = !vm.busy)
        }

        SectionTitle("Apariencia")
        Field("Escala", draft.scale, vm::setCharScale, keyboardType = KeyboardType.Decimal, enabled = !vm.busy)
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text("Voltear horizontalmente", modifier = Modifier.weight(1f))
            Switch(checked = draft.flipX, onCheckedChange = vm::setCharFlipX, enabled = !vm.busy)
        }
        Text(draft.icon?.displayName ?: "Sin icono propio: se usará bf o dad según el lado.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(6.dp))
        OutlinedButton(onClick = onPickIcon, enabled = !vm.busy) { Text("Elegir icono de vida (opcional)") }

        if (draft.problems.isNotEmpty()) {
            Spacer(Modifier.height(16.dp))
            MessageCard(draft.problems.joinToString("\n") { "• $it" }, error = true)
        } else {
            Spacer(Modifier.height(16.dp))
            MessageCard("Personaje listo para generar.", error = false)
        }
    }
}
