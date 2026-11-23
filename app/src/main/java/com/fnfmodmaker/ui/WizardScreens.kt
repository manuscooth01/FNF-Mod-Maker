package com.fnfmodmaker.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.fnfmodmaker.model.ALL_DIFFICULTIES
import com.fnfmodmaker.model.AudioSlot
import com.fnfmodmaker.model.ChartDraft
import com.fnfmodmaker.model.CharacterDraft
import com.fnfmodmaker.model.CharacterSide
import com.fnfmodmaker.model.ModConfig
import com.fnfmodmaker.model.PickedAudio
import com.fnfmodmaker.model.PickedImage
import com.fnfmodmaker.model.SongConfig
import com.fnfmodmaker.model.TargetEngine
import kotlinx.coroutines.delay
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.platform.LocalConfiguration
import kotlin.math.min
import kotlin.math.max

private val STEP_LABELS = listOf("Mod", "Canciones", "Personaje", "Audio", "Chart", "Listo")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun WizardScreen(vm: WizardViewModel) {
    val config = vm.config

    val pickActiveSongAudio = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            // Determinar qué slot según el paso actual (se maneja en StepAudio)
        }
    }
    val pickInstrumental = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.pickActiveSongAudio(uri, AudioSlot.INSTRUMENTAL)
    }
    val pickPlayerVocals = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.pickActiveSongAudio(uri, AudioSlot.VOICES_PLAYER)
    }
    val pickOpponentVocals = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.pickActiveSongAudio(uri, AudioSlot.VOICES_OPPONENT)
    }
    val pickIcon = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.pickIcon(uri)
    }
    val pickCharSheet = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.pickCharacterSheet(uri)
    }
    val pickCharIcon = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.pickCharacterIcon(uri)
    }
    val createZip = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri != null) vm.export(uri)
    }
    val pickImportZip = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.importMod(uri)
    }
    val pickVariationSheet = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.pickVariationSheet(uri)
    }
    val pickVariationIcon = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.pickVariationIcon(uri)
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            ToolTopBar(
                title = "Mod nuevo",
                subtitle = config.modTitle.ifBlank { "sin nombre todavía" },
                onHome = vm::goHome
            )
        },
        bottomBar = {
            Surface(
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 8.dp
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (vm.step > 0) {
                        OutlinedButton(
                            onClick = { vm.back() },
                            modifier = Modifier.weight(1f),
                            enabled = !vm.busy
                        ) {
                            Text("Atrás")
                        }
                    }
                    if (vm.step < 5) {
                        Button(
                            onClick = { vm.next() },
                            modifier = Modifier.weight(1f),
                            enabled = vm.canAdvance && !vm.busy
                        ) {
                            Text("Siguiente")
                        }
                    } else {
                        Button(
                            onClick = { createZip.launch(vm.zipFileName) },
                            modifier = Modifier.weight(1f),
                            enabled = !vm.busy && vm.exportReady
                        ) {
                            Text(if (vm.busy) "Creando…" else "Crear .zip")
                        }
                    }
                }
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            StepBar(current = vm.step, onSelect = vm::goTo)
            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp)
            ) {
                Spacer(Modifier.height(8.dp))
                when (vm.step) {
                    0 -> StepMod(vm, config)
                    1 -> StepSongs(
                        vm = vm,
                        onAddSong = { vm.addSong() },
                        onRemoveSong = { vm.removeSong(it) },
                        onSelectSong = { vm.selectSong(it) }
                    )
                    2 -> StepCharacter(
                        vm = vm,
                        config = config,
                        onPickSheet = { pickCharSheet.launch(arrayOf("image/*")) },
                        onPickIcon = { pickCharIcon.launch(arrayOf("image/*")) }
                    )
                    3 -> StepAudio(
                        vm = vm,
                        activeSong = vm.activeSong,
                        onPickInstrumental = { pickInstrumental.launch(arrayOf("*/*")) },
                        onPickPlayerVocals = { pickPlayerVocals.launch(arrayOf("*/*")) },
                        onPickOpponentVocals = { pickOpponentVocals.launch(arrayOf("*/*")) },
                        onPickIcon = { pickIcon.launch(arrayOf("image/*")) }
                    )
                    4 -> StepChart(vm = vm)
                    else -> StepExport(
                        vm = vm,
                        config = config,
                        onCreate = { createZip.launch(vm.zipFileName) }
                    )
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Modal Editor de Variación de Personaje
// ---------------------------------------------------------------------------

@Composable
private fun VariationEditorModal(
    vm: WizardViewModel,
    onDismiss: () -> Unit,
    onPickSheet: () -> Unit,
    onPickIcon: () -> Unit
) {
    val variation = vm.activeVariation ?: return
    val index = vm.activeVariationIndex!!

    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        // Fondo semitransparente
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0x80000000))
                .clickable { onDismiss() }
        )

        // Modal content
        Surface(
            modifier = Modifier
                .width(minOf(90.dp, LocalConfiguration.current.screenWidthDp.dp))
                .height(minOf(90.dp, LocalConfiguration.current.screenHeightDp.dp))
                .padding(16.dp),
            shape = RoundedCornerShape(16.dp),
            tonalElevation = 8.dp
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp)
            ) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Editar variación: ${variation.displayName}", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    TextButton(onClick = onDismiss) {
                        Text("✕", style = MaterialTheme.typography.labelLarge)
                    }
                }
                Spacer(Modifier.height(16.dp))

                // ID y nombre
                Field(label = "ID de la variación", value = variation.variationId, onChange = { vm.updateVariation(vm.activeVariationIndex!!) { variation -> variation.copy(variationId = variation.variationId.filter { c -> Character.isLetterOrDigit(c) || c in "-_" }) } }, hint = "minúsculas, números, guiones")
                Spacer(Modifier.height(8.dp))
                Field(label = "Nombre visible", value = variation.displayName, onChange = { vm.updateVariation(vm.activeVariationIndex!!) { variation -> variation.copy(displayName = variation.displayName) } }, hint = "Ej: Erect, Pico, Dead")
                Spacer(Modifier.height(8.dp))

                // Default toggle
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Variación por defecto", fontWeight = FontWeight.SemiBold)
                        Text("Se usa cuando el chart no especifica variación", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Spacer(Modifier.width(12.dp))
                    Switch(checked = variation.isDefault, onCheckedChange = { vm.setDefaultVariation(vm.activeVariationIndex!!) })
                }
                Spacer(Modifier.height(16.dp))

                // Spritesheet
                SectionTitle("Hoja de sprites")
                Surface(
                    color = MaterialTheme.colorScheme.surface,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(variation.sheet?.displayName ?: "Sin imagen", fontWeight = FontWeight.SemiBold)
                            Text(
                                if (variation.sheet != null) "${variation.sheetWidth} x ${variation.sheetHeight} px"
                                else "PNG con cuadrícula de animaciones",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Spacer(Modifier.width(10.dp))
                        OutlinedButton(onClick = onPickSheet, enabled = !vm.busy) { Text("Elegir PNG") }
                    }
                }
                Spacer(Modifier.height(12.dp))

                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Field(label = "Ancho frame", value = variation.frameWidth, onChange = { vm.updateVariation(vm.activeVariationIndex!!) { v -> v.copy(frameWidth = v.frameWidth.filter { c -> Character.isDigit(c) }) } }, keyboardType = KeyboardType.Number, modifier = Modifier.weight(1f))
                    Field(label = "Alto frame", value = variation.frameHeight, onChange = { vm.updateVariation(vm.activeVariationIndex!!) { v -> v.copy(frameHeight = v.frameHeight.filter { c -> Character.isDigit(c) }) } }, keyboardType = KeyboardType.Number, modifier = Modifier.weight(1f))
                }

                if (variation.columns > 0 && variation.rows > 0) {
                    Spacer(Modifier.height(8.dp))
                    InfoRow("Cuadrícula", "${variation.columns} x ${variation.rows}")
                    InfoRow("Frames disp.", "${variation.availableFrames}")
                    InfoRow("Frames usados", "${variation.totalFrames}")
                    if (variation.availableFrames < variation.totalFrames) {
                        Spacer(Modifier.height(6.dp))
                        Text("No caben: ${variation.totalFrames - variation.availableFrames} frames sobran", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                    }
                }

                Spacer(Modifier.height(16.dp))
                SectionTitle("Animaciones (frames por animación)")
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Field(label = "idle", value = variation.idleFrames, onChange = { vm.updateVariation(vm.activeVariationIndex!!) { v -> v.copy(idleFrames = v.idleFrames.filter { c -> Character.isDigit(c) }) } }, keyboardType = KeyboardType.Number, modifier = Modifier.weight(1f))
                    Field(label = "singLEFT", value = variation.leftFrames, onChange = { vm.updateVariation(vm.activeVariationIndex!!) { v -> v.copy(leftFrames = v.leftFrames.filter { c -> Character.isDigit(c) }) } }, keyboardType = KeyboardType.Number, modifier = Modifier.weight(1f))
                }
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Field(label = "singDOWN", value = variation.downFrames, onChange = { vm.updateVariation(vm.activeVariationIndex!!) { v -> v.copy(downFrames = v.downFrames.filter { c -> Character.isDigit(c) }) } }, keyboardType = KeyboardType.Number, modifier = Modifier.weight(1f))
                    Field(label = "singUP", value = variation.upFrames, onChange = { vm.updateVariation(vm.activeVariationIndex!!) { v -> v.copy(upFrames = v.upFrames.filter { c -> Character.isDigit(c) }) } }, keyboardType = KeyboardType.Number, modifier = Modifier.weight(1f))
                }
                Spacer(Modifier.height(10.dp))
                Field(label = "singRIGHT", value = variation.rightFrames, onChange = { vm.updateVariation(vm.activeVariationIndex!!) { v -> v.copy(rightFrames = v.rightFrames.filter { c -> Character.isDigit(c) }) } }, keyboardType = KeyboardType.Number)

                Spacer(Modifier.height(16.dp))
                SectionTitle("Ajustes")
                Field(label = "Escala", value = variation.scale, onChange = { vm.updateVariation(vm.activeVariationIndex!!) { v -> v.copy(scale = v.scale.filter { c -> Character.isDigit(c) || c == '.' }) } }, keyboardType = KeyboardType.Decimal)
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) { Text("Voltear horizontalmente", fontWeight = FontWeight.SemiBold) }
                    Spacer(Modifier.width(12.dp))
                    Switch(checked = variation.flipX, onCheckedChange = { vm.updateVariation(vm.activeVariationIndex!!) { v -> v.copy(flipX = v.flipX) } })
                }

                Spacer(Modifier.height(16.dp))
                // Icono de vida
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Icono de vida", fontWeight = FontWeight.SemiBold)
                        Text(variation.icon?.displayName ?: "Sin icono: usa el del personaje base", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Spacer(Modifier.width(12.dp))
                    OutlinedButton(onClick = onPickIcon, enabled = !vm.busy) { Text("Elegir") }
                }

                Spacer(Modifier.height(16.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onDismiss) { Text("Cerrar") }
                    Button(onClick = { /* Guardado automático */ onDismiss() }, modifier = Modifier.weight(1f)) { Text("Guardar y cerrar") }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Piezas de interfaz (solo StepBar, las demás están en UIComponents.kt)
// ---------------------------------------------------------------------------

@Composable
internal fun StepBar(current: Int, onSelect: (Int) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        STEP_LABELS.forEachIndexed { index, label ->
            val active = index <= current
            Column(
                modifier = Modifier
                    .weight(1f)
                    .clickable { onSelect(index) }
                    .padding(vertical = 2.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Surface(
                    shape = CircleShape,
                    color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                    tonalElevation = 0.dp,
                    modifier = Modifier.size(24.dp)
                ) {
                    Text(
                        text = "${index + 1}",
                        modifier = Modifier.padding(top = 3.dp),
                        style = MaterialTheme.typography.labelSmall,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        color = if (active) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (index == current) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Paso 1: datos del mod
// ---------------------------------------------------------------------------

@Composable
private fun StepMod(vm: WizardViewModel, config: ModConfig) {
    StepHeader(
        "1. Tu mod",
        "Un mod es una carpeta con un nombre, tus canciones y los datos que el juego necesita. Empecemos por lo básico."
    )

    Field(
        label = "Nombre del mod",
        value = config.modTitle,
        onChange = vm::setModTitle,
        hint = "Ejemplo: Mi Mod Increíble"
    )
    Spacer(Modifier.height(12.dp))
    Field(
        label = "Carpeta del mod",
        value = config.modId,
        onChange = vm::setModId,
        hint = "Solo minúsculas, números y guiones. Se rellena solo con el nombre."
    )
    Spacer(Modifier.height(12.dp))
    Field(
        label = "Autor",
        value = config.author,
        onChange = vm::setAuthor,
        hint = "Tu nombre o nick. Aparecerá en la lista de mods."
    )
    Spacer(Modifier.height(12.dp))
    Field(
        label = "Descripción",
        value = config.description,
        onChange = vm::setDescription,
        singleLine = false
    )

    SectionTitle("¿Para qué juego?")
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        TargetEngine.entries.forEach { engine ->
            EngineCard(
                engine = engine,
                selected = config.engine == engine,
                onClick = { vm.setEngine(engine) }
            )
        }
    }

    Spacer(Modifier.height(12.dp))
    Field(
        label = "Color del mod",
        value = config.weekColor,
        onChange = vm::setWeekColor,
        hint = "Formato #RRGGBB. Se usa para el fondo de la semana y el menú."
    )

    Spacer(Modifier.height(12.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("También en el modo historia", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.width(8.dp))
        Switch(checked = config.showInStoryMode, onCheckedChange = { vm.setShowInStoryMode(it) })
    }
    Spacer(Modifier.height(24.dp))
}

// ---------------------------------------------------------------------------
// Paso 2: canciones
// ---------------------------------------------------------------------------

@Composable
private fun StepSongs(vm: WizardViewModel, onAddSong: () -> Unit, onRemoveSong: (Int) -> Unit, onSelectSong: (Int) -> Unit) {
    StepHeader("2. Canciones", "Añade las canciones de tu mod.")
    Spacer(Modifier.height(8.dp))
    Text("Paso en desarrollo: selecciona la instrumental y el BPM en cada canción.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Spacer(Modifier.height(12.dp))
    Button(onClick = onAddSong, enabled = !vm.busy) { Text("Añadir canción") }
}

// ---------------------------------------------------------------------------
// Paso 3: personaje (delegate al CharacterToolScreen)
// ---------------------------------------------------------------------------

@Composable
private fun StepCharacter(vm: WizardViewModel, config: ModConfig, onPickSheet: () -> Unit, onPickIcon: () -> Unit) {
    StepHeader("3. Personaje", "Crea tu propio personaje a partir de una hoja de sprites.")
    Spacer(Modifier.height(8.dp))
    Text("Configura el personaje en la herramienta 'Crear un personaje' del inicio.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Spacer(Modifier.height(12.dp))
    Button(onClick = { vm.openTool(Tool.CHARACTER) }, enabled = !vm.busy) { Text("Ir al creador de personajes") }
}

// ---------------------------------------------------------------------------
// Paso 4: audio
// ---------------------------------------------------------------------------

@Composable
private fun StepAudio(vm: WizardViewModel, activeSong: com.fnfmodmaker.model.SongConfig, onPickInstrumental: () -> Unit, onPickPlayerVocals: () -> Unit, onPickOpponentVocals: () -> Unit, onPickIcon: () -> Unit) {
    StepHeader("4. Audio", "Elige la instrumental y las voces de la canción.")
    Spacer(Modifier.height(8.dp))
    Text("Paso en desarrollo: elige los archivos en la herramienta 'Convertir audio a OGG'.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Spacer(Modifier.height(12.dp))
    Button(onClick = { vm.openTool(Tool.AUDIO) }, enabled = !vm.busy) { Text("Ir al conversor de audio") }
}

// ---------------------------------------------------------------------------
// Paso 5: chart
// ---------------------------------------------------------------------------

@Composable
private fun StepChart(vm: WizardViewModel) {
    StepHeader("5. Chart", "El chart de práctica se genera automáticamente desde el BPM.")
    Spacer(Modifier.height(8.dp))
    Text("Puedes editar notas en el editor de chart del juego (Psych: tecla 7; V-Slice: menú de debug).", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Spacer(Modifier.height(12.dp))
    Button(onClick = { vm.openTool(Tool.MOD) }, enabled = !vm.busy) { Text("Seguir") }
}

// ---------------------------------------------------------------------------
// Paso 6: exportar
// ---------------------------------------------------------------------------

@Composable
private fun StepExport(vm: WizardViewModel, config: ModConfig, onCreate: () -> Unit) {
    StepHeader("6. Listo", "Revisa y crea el ZIP del mod.")
    Spacer(Modifier.height(8.dp))
    Text("Motor: ${config.engine.label}", style = MaterialTheme.typography.bodyMedium)
    Text("Canciones: ${config.songs.size}", style = MaterialTheme.typography.bodyMedium)
    Spacer(Modifier.height(16.dp))
    Button(onClick = onCreate, enabled = !vm.busy && vm.exportReady) {
        Text(if (vm.busy) "Creando…" else "Crear .zip")
    }
}