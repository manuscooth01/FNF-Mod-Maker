package com.fnfmodmaker.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.ui.geometry.Path
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.drawLine
import androidx.compose.ui.graphics.drawscope.drawPath
import androidx.compose.ui.graphics.drawscope.drawRect
import androidx.compose.ui.graphics.drawscope.drawRoundRect
import androidx.compose.ui.graphics.drawscope.drawText
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

private val STEP_LABELS = listOf("Mod", "Canciones", "Personaje", "Audio", "Chart", "Listo")

@Composable
fun FNFModMakerApp(vm: WizardViewModel = viewModel()) {
    when (vm.tool) {
        Tool.HOME -> HomeScreen(vm)
        Tool.MOD -> WizardScreen(vm)
        Tool.CHARACTER -> CharacterToolScreen(vm)
        Tool.AUDIO -> AudioToolScreen(vm)
        Tool.IMPORT -> ImportScreen(vm)
        Tool.STAGE -> StageToolScreen(vm)
    }
}

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
            Surface(color = MaterialTheme.colorScheme.surface) {
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
                .width(90.dp.min(LocalConfiguration.current.screenWidthDp.dp))
                .height(90.dp.min(LocalConfiguration.current.screenHeightDp.dp))
                .padding(16.dp),
            shape = RoundedCornerShape(16.dp),
            elevation = 8.dp
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
                    androidx.compose.material3.IconButton(onClick = onDismiss) {
                        androidx.compose.material3.Icon(androidx.compose.material.icons.Icons.Default.Close, contentDescription = "Cerrar")
                    }
                }
                Spacer(Modifier.height(16.dp))

                // ID y nombre
                Field(label = "ID de la variación", value = variation.variationId, onChange = { vm.updateVariation(vm.activeVariationIndex!!) { it.copy(variationId = it.filter { it.isLetterOrDigit() || it in "-_" }) } }, hint = "minúsculas, números, guiones")
                Spacer(Modifier.height(8.dp))
                Field(label = "Nombre visible", value = variation.displayName, onChange = { vm.updateVariation(vm.activeVariationIndex!!) { it.copy(displayName = it) } }, hint = "Ej: Erect, Pico, Dead")
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
                    Field(label = "Ancho frame", value = variation.frameWidth, onChange = { vm.updateVariation(vm.activeVariationIndex!!) { it.copy(frameWidth = it.filter { it.isDigit() }) } }, keyboardType = KeyboardType.Number, modifier = Modifier.weight(1f))
                    Field(label = "Alto frame", value = variation.frameHeight, onChange = { vm.updateVariation(vm.activeVariationIndex!!) { it.copy(frameHeight = it.filter { it.isDigit() }) } }, keyboardType = KeyboardType.Number, modifier = Modifier.weight(1f))
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
                    Field(label = "idle", value = variation.idleFrames, onChange = { vm.updateVariation(vm.activeVariationIndex!!) { it.copy(idleFrames = it.filter { it.isDigit() }) } }, keyboardType = KeyboardType.Number, modifier = Modifier.weight(1f))
                    Field(label = "singLEFT", value = variation.leftFrames, onChange = { vm.updateVariation(vm.activeVariationIndex!!) { it.copy(leftFrames = it.filter { it.isDigit() }) } }, keyboardType = KeyboardType.Number, modifier = Modifier.weight(1f))
                }
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Field(label = "singDOWN", value = variation.downFrames, onChange = { vm.updateVariation(vm.activeVariationIndex!!) { it.copy(downFrames = it.filter { it.isDigit() }) } }, keyboardType = KeyboardType.Number, modifier = Modifier.weight(1f))
                    Field(label = "singUP", value = variation.upFrames, onChange = { vm.updateVariation(vm.activeVariationIndex!!) { it.copy(upFrames = it.filter { it.isDigit() }) } }, keyboardType = KeyboardType.Number, modifier = Modifier.weight(1f))
                }
                Spacer(Modifier.height(10.dp))
                Field(label = "singRIGHT", value = variation.rightFrames, onChange = { vm.updateVariation(vm.activeVariationIndex!!) { it.copy(rightFrames = it.filter { it.isDigit() }) } }, keyboardType = KeyboardType.Number)

                Spacer(Modifier.height(16.dp))
                SectionTitle("Ajustes")
                Field(label = "Escala", value = variation.scale, onChange = { vm.updateVariation(vm.activeVariationIndex!!) { it.copy(scale = it.filter { it.isDigit() || it == '.' }) } }, keyboardType = KeyboardType.Decimal)
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) { Text("Voltear horizontalmente", fontWeight = FontWeight.SemiBold) }
                    Spacer(Modifier.width(12.dp))
                    Switch(checked = variation.flipX, onCheckedChange = { vm.updateVariation(vm.activeVariationIndex!!) { it.copy(flipX = it) } })
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
// Piezas de interfaz
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
                    color = if (index == current) MaterialTheme.colorScheme.onSurface
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun StepHeader(title: String, subtitle: String) {
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
private fun SectionTitle(text: String) {
    Spacer(Modifier.height(18.dp))
    Text(text, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
    Spacer(Modifier.height(8.dp))
}

@Composable
private fun Field(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    hint: String = "",
    keyboardType: KeyboardType = KeyboardType.Text,
    singleLine: Boolean = true,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = value,
            onValueChange = onChange,
            label = { Text(label) },
            singleLine = singleLine,
            minLines = if (singleLine) 1 else 3,
            keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
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
private fun HelpCard(text: String) {
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
private fun InfoRow(label: String, value: String) {
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
private fun EngineCard(engine: TargetEngine, selected: Boolean, onClick: () -> Unit) {
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
private fun MessageCard(text: String, error: Boolean) {
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

    Spacer(Modifier.height(16.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("Mostrar en el modo historia", fontWeight = FontWeight.SemiBold)
            Text(
                "Si lo desactivas, la canción aparece solo en Freeplay (lo más seguro).",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = config.showInStoryMode, onCheckedChange = vm::setShowInStoryMode)
    }
    Spacer(Modifier.height(14.dp))
    HelpCard(
        "Aquí definís la identidad del mod. Las canciones se agregan en el siguiente paso. " +
            "V-Slice es el FNF oficial de Android (versión 0.8.x). Psych Engine es el motor de mods " +
            "más usado, tanto en Android como en PC. Si tienes dudas, elige \"Ambos\": la misma " +
            "carpeta funciona en los dos juegos."
    )
}

// ---------------------------------------------------------------------------
// Paso 2: lista de canciones y edición de la canción activa
// ---------------------------------------------------------------------------

@Composable
private fun StepSongs(
    vm: WizardViewModel,
    onAddSong: () -> Unit,
    onRemoveSong: (Int) -> Unit,
    onSelectSong: (Int) -> Unit
) {
    val config = vm.config
    val activeIndex = vm.activeSongIndex
    val songs = config.songs

    StepHeader(
        "2. Canciones (${songs.size})",
        "Agregá una o varias canciones. Cada una tendrá su propio audio, BPM, dificultades y chart. " +
            "Tocá una para editarla."
    )

    // Lista de canciones
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        songs.forEachIndexed { index, song ->
            val isActive = index == activeIndex
            Surface(
                color = if (isActive) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
                shape = RoundedCornerShape(14.dp),
                border = BorderStroke(
                    width = if (isActive) 2.dp else 1.dp,
                    color = if (isActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onSelectSong(index) }
            ) {
                Row(
                    modifier = Modifier.padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            song.songName.ifBlank { "Canción ${index + 1}" },
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            "BPM: ${song.bpm.ifEmpty { "—" }} · Dificultades: ${song.orderedDifficulties.joinToString(", ")}" +
                                " · ${song.instrumental?.let { if (it.isOgg) "OGG" else "convertir a OGG" } ?: "sin instrumental"}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (songs.size > 1) {
                        OutlinedButton(
                            onClick = { onRemoveSong(index) },
                            enabled = !vm.busy,
                            modifier = Modifier.padding(start = 8.dp)
                        ) {
                            Text("Eliminar")
                        }
                    }
}
        }
    }

    // Modal editor de variación
    if (vm.activeVariationIndex != null) {
        VariationEditorModal(
            vm = vm,
            onDismiss = { vm.closeVariationEditor() },
            onPickSheet = { pickVariationSheet.launch(arrayOf("image/*")) },
            onPickIcon = { pickVariationIcon.launch(arrayOf("image/*")) }
        )
    }
}

    Spacer(Modifier.height(12.dp))
    // Botón agregar canción
    OutlinedButton(onClick = onAddSong, enabled = !vm.busy) {
        Text("+ Agregar otra canción")
    }

    Spacer(Modifier.height(16.dp))
    // Formulario de la canción activa
    val activeSong = songs.getOrElse(activeIndex) { SongConfig() }
    SectionTitle("Editar: \"${activeSong.songName.ifBlank { "Canción ${activeIndex + 1}" }}\"")

    Field(
        label = "Nombre de la canción",
        value = activeSong.songName,
        onChange = vm::setActiveSongName,
        hint = "El nombre que verás en Freeplay. Evita acentos si quieres máxima compatibilidad."
    )
    Spacer(Modifier.height(8.dp))
    InfoRow("Carpeta de la canción", activeSong.songFolder)
    Spacer(Modifier.height(12.dp))
    Field(
        label = "Artista",
        value = activeSong.artist,
        onChange = vm::setActiveSongArtist
    )

    Spacer(Modifier.height(12.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Field(
            label = "BPM",
            value = activeSong.bpm,
            onChange = vm::setActiveSongBpm,
            keyboardType = KeyboardType.Number,
            modifier = Modifier.weight(1f)
        )
        Field(
            label = "Velocidad",
            value = activeSong.speed,
            onChange = vm::setActiveSongSpeed,
            keyboardType = KeyboardType.Decimal,
            modifier = Modifier.weight(1f)
        )
    }
    if (activeSong.bpmValue !in 1..400) {
        Spacer(Modifier.height(6.dp))
        Text(
            "El BPM debe estar entre 1 y 400.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.error
        )
    }

    SectionTitle("Personajes")
    Field(label = "Jugador", value = activeSong.player, onChange = vm::setActiveSongPlayer, hint = "Por defecto: bf")
    Spacer(Modifier.height(10.dp))
    Field(label = "Novia / GF", value = activeSong.girlfriend, onChange = vm::setActiveSongGirlfriend, hint = "Por defecto: gf")
    Spacer(Modifier.height(10.dp))
    Field(label = "Oponente", value = activeSong.opponent, onChange = vm::setActiveSongOpponent, hint = "Por defecto: dad")

    SectionTitle("Dificultades")
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ALL_DIFFICULTIES.forEach { difficulty ->
            FilterChip(
                selected = difficulty in activeSong.difficulties,
                onClick = { vm.toggleActiveSongDifficulty(difficulty) },
                label = { Text(difficulty.replaceFirstChar { it.uppercase() }) }
            )
        }
    }
    if (activeSong.difficulties.isEmpty()) {
        Spacer(Modifier.height(6.dp))
        Text(
            "Elige al menos una dificultad.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.error
        )
    } else {
        Spacer(Modifier.height(6.dp))
        Text(
            "Solo se generan las marcadas, y la semana de Psych las declara para no ofrecer " +
                "dificultades vacías.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }

    Spacer(Modifier.height(18.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("Notas de práctica", fontWeight = FontWeight.SemiBold)
            Text(
                "Rellena el chart con notas siguiendo el ritmo para que puedas probar el mod " +
                    "de inmediato. Después las cambias en el editor del juego.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = activeSong.practiceNotes, onCheckedChange = vm::setActiveSongPracticeNotes)
    }

    HorizontalDivider(modifier = Modifier.padding(vertical = 18.dp))

    SectionTitle("Avanzado (opcional)")
    Field(
        label = "Escenario en V-Slice",
        value = activeSong.stageVSlice,
        onChange = vm::setActiveSongStageVSlice,
        hint = "mainStage, phillyStreets, school, mallEvil…"
    )
    Spacer(Modifier.height(10.dp))
    Field(
        label = "Escenario en Psych",
        value = activeSong.stagePsych,
        onChange = vm::setActiveSongStagePsych,
        hint = "stage, stage-psych, etc. (stage es el del juego base)"
    )
    Spacer(Modifier.height(10.dp))
    Field(
        label = "Color de esta canción",
        value = activeSong.weekColor,
        onChange = vm::setActiveSongWeekColor,
        hint = "Formato #RRGGBB. Se usa para el fondo de la semana y el menú."
    )
    Spacer(Modifier.height(10.dp))
    Field(
        label = "Offset en milisegundos",
        value = activeSong.offsetMs,
        onChange = vm::setActiveSongOffsetMs,
        keyboardType = KeyboardType.Number,
        hint = "0 por defecto. Súbelo si tus notas van adelantadas respecto a la música."
    )

    Spacer(Modifier.height(14.dp))
    HelpCard(
        "Los nombres de personajes y escenarios deben existir en el juego; si no, la canción " +
            "puede fallar al cargar. Con bf / gf / dad y mainStage / stage vas sobre seguro. " +
            "Cada canción puede tener su propio BPM, color y offset."
    )
}

// ---------------------------------------------------------------------------
// Paso 3: personaje propio (generador de spritesheet + XML + JSON)
// ---------------------------------------------------------------------------

@Composable
private fun StepCharacter(
    vm: WizardViewModel,
    config: ModConfig,
    onPickSheet: () -> Unit,
    onPickIcon: () -> Unit
) {
    val draft = config.character

    StepHeader(
        "3. Personaje propio (opcional)",
        "Esto es el equivalente móvil del generador de spritesheets de escritorio: corta tu hoja " +
            "en frames, escribe el XML Sparrow y crea el JSON del personaje para los dos motores."
    )

    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("Usar un personaje propio", fontWeight = FontWeight.SemiBold)
            Text(
                "Si lo dejas apagado se usan los personajes del juego (bf, dad, gf).",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = draft.enabled, onCheckedChange = vm::setCharacterEnabled)
    }

    if (!draft.enabled) {
        Spacer(Modifier.height(14.dp))
        HelpCard(
            "Para hacer un personaje necesitas una hoja de sprites en cuadrícula: todas las " +
                "animaciones una detrás de otra, cada frame del mismo tamaño. La app la corta, " +
                "genera el .xml y los .json, y coloca el icono de vida.\n\n" +
                "Orden habitual de los frames: idle, singLEFT, singDOWN, singUP, singRIGHT."
        )
        return
    }

    Spacer(Modifier.height(6.dp))
    CharacterForm(
        vm = vm,
        draft = draft,
        onPickSheet = onPickSheet,
        onPickIcon = onPickIcon
    )
}

/**
 * Formulario del personaje. Se usa igual en el asistente del mod y en la
 * herramienta suelta "Crear un personaje".
 */
@Composable
internal fun CharacterForm(
    vm: WizardViewModel,
    draft: CharacterDraft,
    onPickSheet: () -> Unit,
    onPickIcon: () -> Unit
) {
    Field(
        label = "ID del personaje",
        value = draft.charId,
        onChange = vm::setCharId,
        hint = "Nombre de archivo: minúsculas, números y guiones (ej. mi-personaje)."
    )
    Spacer(Modifier.height(10.dp))
    Field(
        label = "Nombre visible",
        value = draft.displayName,
        onChange = vm::setCharName,
        hint = "Cómo se llama en el editor del juego."
    )

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
                Text(draft.sheet?.displayName ?: "Sin imagen todavía", fontWeight = FontWeight.SemiBold)
                Text(
                    if (draft.sheet != null) "${draft.sheetWidth} x ${draft.sheetHeight} px"
                    else "PNG con la cuadrícula de animaciones",
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
        Field(
            label = "Ancho del frame",
            value = draft.frameWidth,
            onChange = vm::setCharFrameWidth,
            keyboardType = KeyboardType.Number,
            modifier = Modifier.weight(1f)
        )
        Field(
            label = "Alto del frame",
            value = draft.frameHeight,
            onChange = vm::setCharFrameHeight,
            keyboardType = KeyboardType.Number,
            modifier = Modifier.weight(1f)
        )
    }

    if (draft.columns > 0 && draft.rows > 0) {
        Spacer(Modifier.height(8.dp))
        InfoRow("Cuadrícula", "${draft.columns} columnas x ${draft.rows} filas")
        InfoRow("Frames disponibles", "${draft.availableFrames}")
        InfoRow("Frames que usa el personaje", "${draft.totalFrames}")
        if (draft.availableFrames < draft.totalFrames) {
            Spacer(Modifier.height(6.dp))
            Text(
                "No caben: sobran ${draft.totalFrames - draft.availableFrames} frames. " +
                    "Baja los frames por animación o corrige el tamaño del frame.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error
            )
        }
    }

    SectionTitle("Animaciones (frames por animación)")
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Field(
            label = "idle",
            value = draft.idleFrames,
            onChange = vm::setCharIdleFrames,
            keyboardType = KeyboardType.Number,
            modifier = Modifier.weight(1f)
        )
        Field(
            label = "singLEFT",
            value = draft.leftFrames,
            onChange = vm::setCharLeftFrames,
            keyboardType = KeyboardType.Number,
            modifier = Modifier.weight(1f)
        )
    }
    Spacer(Modifier.height(10.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Field(
            label = "singDOWN",
            value = draft.downFrames,
            onChange = vm::setCharDownFrames,
            keyboardType = KeyboardType.Number,
            modifier = Modifier.weight(1f)
        )
        Field(
            label = "singUP",
            value = draft.upFrames,
            onChange = vm::setCharUpFrames,
            keyboardType = KeyboardType.Number,
            modifier = Modifier.weight(1f)
        )
    }
    Spacer(Modifier.height(10.dp))
    Field(
        label = "singRIGHT",
        value = draft.rightFrames,
        onChange = vm::setCharRightFrames,
        keyboardType = KeyboardType.Number
    )
    Spacer(Modifier.height(8.dp))
    HelpCard(
        "Los frames se reparten en ese orden, de izquierda a derecha y de arriba abajo. " +
            "Si tu hoja empieza por otra animación, pon 0 en las que no uses y ordena los números " +
            "según lo que haya en la imagen."
    )

    SectionTitle("Ajustes del personaje")
    Field(
        label = "Escala",
        value = draft.scale,
        onChange = vm::setCharScale,
        keyboardType = KeyboardType.Decimal,
        hint = "1.0 = tamaño original. Súbelo si tu personaje sale pequeño."
    )

    Spacer(Modifier.height(12.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("Voltear horizontalmente", fontWeight = FontWeight.SemiBold)
            Text(
                "Actívalo si tu personaje mira al lado contrario del que debería.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = draft.flipX, onCheckedChange = vm::setCharFlipX)
    }

    Spacer(Modifier.height(14.dp))
    Text("¿Quién lo usa?", fontWeight = FontWeight.SemiBold)
    Spacer(Modifier.height(8.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        CharacterSide.entries.forEach { side ->
            FilterChip(
                selected = draft.side == side,
                onClick = { vm.setCharSide(side) },
                label = { Text(side.label) }
            )
        }
    }

    Spacer(Modifier.height(16.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("Icono de vida", fontWeight = FontWeight.SemiBold)
            Text(
                draft.icon?.displayName
                    ?: "Sin icono: se usará el del personaje base de ese lado",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.width(12.dp))
        OutlinedButton(onClick = onPickIcon, enabled = !vm.busy) { Text("Elegir") }
    }
    Spacer(Modifier.height(10.dp))
    HelpCard(
        "El icono se convierte a 300x150 con dos frames (normal y perdiendo), que es el formato " +
            "que leen Psych y V-Slice. Si tu imagen ya viene con los dos cuadros, se respetan."
    )

    // ---------- VARIACIONES (Erect, Pico, Dead, etc.) ----------
    Spacer(Modifier.height(16.dp))
    SectionTitle("Variaciones (Erect, Pico, Dead, etc.)")
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(14.dp)) {
            Text("Variaciones del personaje", fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(4.dp))
            Text(
                "Cada variación tiene su propia hoja de sprites y animaciones. " +
                    "Ejemplos: Erect, Pico, Dead, Christmas, Halloween, etc.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(12.dp))

            // Lista de variaciones
            if (draft.variations.isNotEmpty()) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    draft.variations.forEachIndexed { index, variation ->
                        val isDefault = variation.isDefault
                        Surface(
                            color = MaterialTheme.colorScheme.surface,
                            shape = RoundedCornerShape(12.dp),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp)
                        ) {
                            Column {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Column(Modifier.weight(1f)) {
                                        Text(variation.displayName, fontWeight = FontWeight.SemiBold)
                                        Text(
                                            "ID: ${variation.variationId}  ·  ${variation.animations.sumOf { it.second }} frames  ·  ${if (variation.isReady) "✅ Lista" else "⚠️ Incompleta"}",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    if (isDefault) {
                                        androidx.compose.material3.Text(
                                            "DEFAULT",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier
                                                .padding(horizontal = 8.dp, vertical = 2.dp)
                                                .background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(4.dp))
                                        )
                                    }
                                    OutlinedButton(onClick = { vm.openVariationEditor(index) }, enabled = !vm.busy) { Text("Editar") }
                                    OutlinedButton(onClick = { vm.removeCharacterVariation(index) }, enabled = !vm.busy, colors = ButtonDefaults.outlinedButtonColors(containerColor = MaterialTheme.colorScheme.errorContainer)) { Text("🗑") }
                                }
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = { vm.addCharacterVariation() }, enabled = !vm.busy) { Text("+ Agregar variación") }
        } else {
            Text("Sin variaciones aún. El personaje base se usa por defecto.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = { vm.addCharacterVariation() }, enabled = !vm.busy) { Text("+ Agregar primera variación") }
        }
    }
    // ---------- FIN VARIACIONES ----------
}

// ---------------------------------------------------------------------------
// Paso 4: audio e imagen de la canción activa
// ---------------------------------------------------------------------------

@Composable
private fun StepAudio(
    vm: WizardViewModel,
    activeSong: SongConfig,
    onPickInstrumental: () -> Unit,
    onPickPlayerVocals: () -> Unit,
    onPickOpponentVocals: () -> Unit,
    onPickIcon: () -> Unit
) {
    StepHeader(
        "4. Audio e imagen de \"${activeSong.songName.ifBlank { "canción ${vm.activeSongIndex + 1}" }}\"",
        "El juego solo reproduce OGG. Si eliges MP3 o WAV, la app lo convierte sola."
    )

    AudioRow(
        title = "Instrumental (obligatoria)",
        audio = activeSong.instrumental,
        emptyText = "Sin instrumental todavía",
        onPick = onPickInstrumental,
        buttonText = "Elegir instrumental"
    )
    Spacer(Modifier.height(10.dp))
    AudioRow(
        title = "Voces del jugador (opcional)",
        audio = activeSong.vocalsPlayer,
        emptyText = "Sin voces del jugador",
        onPick = onPickPlayerVocals,
        buttonText = "Elegir"
    )
    Spacer(Modifier.height(10.dp))
    AudioRow(
        title = "Voces del oponente (opcional)",
        audio = activeSong.vocalsOpponent,
        emptyText = "Sin voces del oponente",
        onPick = onPickOpponentVocals,
        buttonText = "Elegir"
    )

    Spacer(Modifier.height(16.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("Icono del mod (opcional)", fontWeight = FontWeight.SemiBold)
            Text(
                vm.config.icon?.displayName ?: "Se usa el icono por defecto del juego",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.width(12.dp))
        OutlinedButton(onClick = onPickIcon, enabled = !vm.busy) { Text("Elegir") }
    }

    Spacer(Modifier.height(16.dp))
    HelpCard(
        "Cómo funcionan las voces (así lo hacen los mods de GameBanana):\n" +
            "• Dos pistas (jugador + oponente): se guardan como Voices-<personaje>.ogg, " +
            "que es lo que espera V-Slice. Para Psych se mezclan solas en un Voices.ogg.\n" +
            "• Una sola pista mezclada: se guarda como Voices.ogg y sirve para los dos motores.\n" +
            "• Todo debe empezar en el mismo punto y durar lo mismo que la instrumental.\n" +
            "• El BPM debe ser el real; si no, las notas no cuadran con la música.\n" +
            "• La conversión usa FFmpeg en el teléfono: unos segundos por minuto de audio.\n" +
            "• Si ya tienes .ogg, se copia tal cual (más rápido y sin pérdida)."
    )

    val instrumental = activeSong.instrumental
    if (instrumental != null && instrumental.durationMs > 0L) {
        listOf(activeSong.vocalsPlayer, activeSong.vocalsOpponent).filterNotNull().forEach { track ->
            if (track.durationMs > 0L && kotlin.math.abs(instrumental.durationMs - track.durationMs) > 2000L) {
                Spacer(Modifier.height(10.dp))
                MessageCard(
                    "Ojo: la instrumental dura ${instrumental.prettyDuration} y esta pista de voces " +
                        "${track.prettyDuration}. Revisa que sean la misma versión de la canción.",
                    error = false
                )
            }
        }
    }

    vm.warnings.forEach { warning ->
        Spacer(Modifier.height(10.dp))
        MessageCard(warning, error = false)
    }

    if (vm.busy) {
        Spacer(Modifier.height(16.dp))
        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(6.dp))
        Text(vm.status, style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun AudioRow(
    title: String,
    audio: PickedAudio?,
    emptyText: String,
    onPick: () -> Unit,
    buttonText: String
) {
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
                Text(title, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(2.dp))
                Text(
                    audio?.let { "${it.displayName} · ${it.prettyDuration}" } ?: emptyText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.width(10.dp))
            OutlinedButton(onClick = onPick) { Text(buttonText) }
        }
    }
}

// ---------------------------------------------------------------------------
// Paso 4: Editor de Chart (Piano Roll)
// ---------------------------------------------------------------------------

@Composable
private fun StepChart(vm: WizardViewModel) {
    val config = vm.config
    val activeSong = config.songs.getOrElse(vm.activeChartSongIndex) { return }
    val difficulties = activeSong.orderedDifficulties
    val draft = vm.activeChartDraft
    val editorState = vm.chartEditorState

    StepHeader(
        "4. Editor de Chart (${activeSong.songName.ifEmpty { "canción ${vm.activeChartSongIndex + 1}" }})",
        "Piano roll: arrastra horizontalmente, pellizca para zoom. Tap = nota simple, Drag = hold note. Espacio = play/pausa."
    )

    // Selector de canción para chartear
    if (config.songs.size > 1) {
        SectionTitle("Canción a chartear")
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            config.songs.forEachIndexed { index, song ->
                val isSelected = index == vm.activeChartSongIndex
                FilterChip(
                    selected = isSelected,
                    onClick = { vm.selectChartSong(index) },
                    label = { Text(song.songName.ifEmpty { "Canción ${index + 1}" }) }
                )
            }
        }
        Spacer(Modifier.height(12.dp))
    }

    // Selector de dificultad
    SectionTitle("Dificultad")
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        difficulties.forEach { difficulty ->
            FilterChip(
                selected = vm.activeChartDifficulty == difficulty,
                onClick = { vm.selectChartDifficulty(difficulty) },
                label = { Text(difficulty.replaceFirstChar { it.uppercase() }) }
            )
        }
    }

    Spacer(Modifier.height(12.dp))

    // Toolbar principal
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // Play/Pause
        Button(
            onClick = { vm.togglePlayback() },
            enabled = !vm.busy,
            colors = ButtonDefaults.buttonColors(
                containerColor = if (editorState.isPlaying) MaterialTheme.colorScheme.tertiaryContainer else MaterialTheme.colorScheme.primaryContainer
            )
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(if (editorState.isPlaying) "⏸" else "▶", fontSize = 20.sp)
                Text(if (editorState.isPlaying) "Pausa" else "Reproducir")
            }
        }
        // Tiempo actual
        Text(
            formatTime(editorState.currentTimeMs),
            style = MaterialTheme.typography.titleSmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.primary
        )
        Spacer(Modifier.weight(1f))
        // Zoom controls
        OutlinedButton(onClick = { vm.updateChartView((editorState.zoom * 1.5f).coerceIn(0.25f, 8f), editorState.scrollX) }) { Text("🔍+") }
        OutlinedButton(onClick = { vm.updateChartView((editorState.zoom / 1.5f).coerceIn(0.25f, 8f), editorState.scrollX) }) { Text("🔍-") }
        Text("Zoom: ${"%.1f".format(editorState.zoom)}x", style = MaterialTheme.typography.labelSmall)
        Spacer(Modifier.width(16.dp))
        // Snap Resolution
        Text("Snap:", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            com.fnfmodmaker.model.SnapResolution.entries.forEach { res ->
                FilterChip(
                    selected = editorState.snapResolution == res,
                    onClick = { vm.updateSnapResolution(res) },
                    label = { Text(res.label, style = MaterialTheme.typography.labelSmall) }
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        // Actions
        OutlinedButton(onClick = { vm.generatePracticeNotes() }, enabled = !vm.busy) { Text("Generar práctica") }
        OutlinedButton(onClick = { vm.clearChart() }, enabled = !vm.busy) { Text("Limpiar") }
        Button(onClick = { vm.saveChartDraft(draft) }, enabled = !vm.busy) { Text("Guardar") }
    }

    Spacer(Modifier.height(8.dp))

    // Seek Bar / Progress Bar (clickeable para seek)
    val song = activeSong
    val durationMs = song.instrumental?.durationMs ?: 0L
    if (durationMs > 0) {
        val progress = if (durationMs > 0) editorState.currentTimeMs.toFloat() / durationMs else 0f
        Column(modifier = Modifier.fillMaxWidth()) {
            // Barra de progreso
            Slider(
                value = progress.coerceIn(0f, 1f),
                onValueChange = { newProgress ->
                    val seekMs = (newProgress * durationMs).roundToLong()
                    vm.seekTo(seekMs)
                },
                onValueChangeFinished = {},
                enabled = !vm.busy,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp),
                colors = SliderDefaults.colors(
                    thumbColor = MaterialTheme.colorScheme.primary,
                    activeTrackColor = MaterialTheme.colorScheme.primary,
                    inactiveTrackColor = MaterialTheme.colorScheme.surfaceVariant
                )
            )
            // Labels de tiempo
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(formatTime(editorState.currentTimeMs), style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(formatTime(durationMs), style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Spacer(Modifier.height(8.dp))
    }

    // Toolbar secundaria: Undo/Redo, Copy/Paste, Selection
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        OutlinedButton(onClick = { vm.undo() }, enabled = editorState.canUndo && !vm.busy) { Text("↶ Undo") }
        OutlinedButton(onClick = { vm.redo() }, enabled = editorState.canRedo && !vm.busy) { Text("↷ Redo") }
        Spacer(Modifier.width(8.dp))
        OutlinedButton(onClick = { vm.copySelectedNotes() }, enabled = editorState.selectedNotes.isNotEmpty() && !vm.busy) { Text("📋 Copiar") }
        OutlinedButton(onClick = { vm.pasteNotes() }, enabled = !vm.busy) { Text("📄 Pegar") }
        OutlinedButton(onClick = { vm.clearSelection() }, enabled = editorState.selectedNotes.isNotEmpty() && !vm.busy) { Text("✕ Sel.") }
        Spacer(Modifier.weight(1f))
        Text("Seleccionadas: ${editorState.selectedNotes.size}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (editorState.dragState != null) {
            Text("Dragging...", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.tertiary)
        }
    }

    Spacer(Modifier.height(8.dp))

    // Toolbar terciaria: Eventos y Secciones
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // Eventos rápidos en tiempo actual
        OutlinedButton(onClick = {
            val event = com.fnfmodmaker.model.ChartEvent(
                timeMs = editorState.currentTimeMs,
                type = com.fnfmodmaker.model.ChartEvent.EventType.CAMERA,
                value1 = 1f // zoom normal
            )
            vm.addChartEvent(event)
        }, enabled = !vm.busy) { Text("📷 Cámara") }
        OutlinedButton(onClick = {
            val event = com.fnfmodmaker.model.ChartEvent(
                timeMs = editorState.currentTimeMs,
                type = com.fnfmodmaker.model.ChartEvent.EventType.BPM_CHANGE,
                value1 = activeSong.bpmValue.toFloat()
            )
            vm.addChartEvent(event)
        }, enabled = !vm.busy) { Text("🎵 BPM") }
        OutlinedButton(onClick = {
            val event = com.fnfmodmaker.model.ChartEvent(
                timeMs = editorState.currentTimeMs,
                type = com.fnfmodmaker.model.ChartEvent.EventType.SPEED_CHANGE,
                value1 = activeSong.speedValue
            )
            vm.addChartEvent(event)
        }, enabled = !vm.busy) { Text("⚡ Speed") }
        OutlinedButton(onClick = {
            val event = com.fnfmodmaker.model.ChartEvent(
                timeMs = editorState.currentTimeMs,
                type = com.fnfmodmaker.model.ChartEvent.EventType.CAMERA_SHAKE,
                value1 = 5f // intensidad
            )
            vm.addChartEvent(event)
        }, enabled = !vm.busy) { Text("🌊 Shake") }
        OutlinedButton(onClick = {
            val event = com.fnfmodmaker.model.ChartEvent(
                timeMs = editorState.currentTimeMs,
                type = com.fnfmodmaker.model.ChartEvent.EventType.FLASH,
                value1 = 1f
            )
            vm.addChartEvent(event)
        }, enabled = !vm.busy) { Text("⚡ Flash") }
        Spacer(Modifier.weight(1f))
        Text("Eventos: ${draft.events.size}  ·  Secciones: ${draft.sections.size}",
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }

    Spacer(Modifier.height(8.dp))

    // Secciones (mustHit/altAnim/gfSection) - agregar sección en tiempo actual
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        OutlinedButton(onClick = {
            val section = com.fnfmodmaker.model.ChartSection(
                startTimeMs = editorState.currentTimeMs,
                lengthMs = 4000, // 4 beats default
                mustHit = true,
                altAnim = false,
                gfSection = false,
                bpm = activeSong.bpmValue.toFloat(),
                speed = activeSong.speedValue
            )
            vm.addChartSection(section)
        }, enabled = !vm.busy) { Text("➕ Sección mustHit") }
        OutlinedButton(onClick = {
            val section = com.fnfmodmaker.model.ChartSection(
                startTimeMs = editorState.currentTimeMs,
                lengthMs = 4000,
                mustHit = false,
                altAnim = false,
                gfSection = false,
                bpm = activeSong.bpmValue.toFloat(),
                speed = activeSong.speedValue
            )
            vm.addChartSection(section)
        }, enabled = !vm.busy) { Text("➕ Sección opp") }
        OutlinedButton(onClick = {
            val section = com.fnfmodmaker.model.ChartSection(
                startTimeMs = editorState.currentTimeMs,
                lengthMs = 4000,
                mustHit = true,
                altAnim = true,
                gfSection = false,
                bpm = activeSong.bpmValue.toFloat(),
                speed = activeSong.speedValue
            )
            vm.addChartSection(section)
        }, enabled = !vm.busy) { Text("➕ AltAnim") }
        Spacer(Modifier.weight(1f))
        OutlinedButton(onClick = {
            // Limpiar todas las secciones
            val updatedDraft = draft.copy(sections = emptyList())
            vm.saveChartDraft(updatedDraft)
        }, enabled = !vm.busy && draft.sections.isNotEmpty()) { Text("🗑 Limpiar secciones") }
    }

    Spacer(Modifier.height(12.dp))

    // Info line
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        InfoRow("Canción", activeSong.songName.ifEmpty { "—" })
        InfoRow("Dificultad", vm.activeChartDifficulty.replaceFirstChar { it.uppercase() })
        InfoRow("BPM", activeSong.bpmValue.toString())
        InfoRow("Offset", "${activeSong.offsetMs}ms")
        InfoRow("Notas", draft.notes.size.toString())
    }

    Spacer(Modifier.height(8.dp))

    // Piano Roll Canvas
    PianoRoll(
        draft = draft,
        editorState = editorState,
        song = activeSong,
        onStateChange = { newState -> vm.updateChartView(newState.zoom, newState.scrollX) },
        onNoteTap = { timeMs, lane, isHoldStart ->
            val note = com.fnfmodmaker.model.ChartNote(
                timeMs = timeMs,
                lane = lane,
                lengthMs = if (isHoldStart) 500L else 0L,
                isSustain = false
            )
            vm.addChartNote(note)
        },
        onNoteLongPress = { timeMs, lane -> },
        onHoldDrag = { startTimeMs, lane, currentTimeMs -> },
        onNoteDelete = { timeMs, lane ->
            vm.removeChartNote(timeMs, lane)
        },
        vm = vm
    )

    Spacer(Modifier.height(16.dp))
    HelpCard(
        "Controles:\n" +
        "• Arrastra horizontalmente para mover el timeline\n" +
        "• Pellizca (pinch) o botones 🔍+/🔍- para zoom\n" +
        "• Tap en una celda = nota simple (tap)\n" +
        "• Drag vertical en una celda = hold note (sustain)\n" +
        "• Mantén pulsado (long press) + drag = ajustar longitud hold\n" +
        "• Botón ▶ = reproducir/pausar audio\n" +
        "• Generar práctica = rellena con patrón automático\n" +
        "• Guardar = sobrescribe el chart en el mod"
    )

    Spacer(Modifier.height(16.dp))
    vm.warnings.filter { it.contains("chart", ignoreCase = true) || it.contains("Chart", ignoreCase = true) }.forEach { warning ->
        MessageCard(warning, error = false)
        Spacer(Modifier.height(8.dp))
    }
}

/** Formatea ms a mm:ss.fff */
private fun formatTime(ms: Long): String {
    val totalSeconds = ms / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    val millis = ms % 1000
    return "%02d:%02d.%03d".format(minutes, seconds, millis)
}

// ---------------------------------------------------------------------------
// Piano Roll Component (completo con drag states, multi-selección, hold resize)
// ---------------------------------------------------------------------------

@Composable
private fun PianoRoll(
    draft: com.fnfmodmaker.model.ChartDraft,
    editorState: com.fnfmodmaker.model.ChartEditorState,
    song: com.fnfmodmaker.model.SongConfig,
    onStateChange: (com.fnfmodmaker.model.ChartEditorState) -> Unit,
    onNoteTap: (Long, Int, Boolean) -> Unit, // timeMs, lane, isHoldStart
    onNoteLongPress: (Long, Int) -> Unit,
    onHoldDrag: (Long, Int, Long) -> Unit,
    onNoteDelete: (Long, Int) -> Unit,
    vm: WizardViewModel
) {
    val bpm = song.bpmValue
    val beatMs = if (bpm > 0) 60000.0 / bpm else 400.0
    val zoom = editorState.zoom
    val currentTimeMs = editorState.currentTimeMs
    val isPlaying = editorState.isPlaying
    val selectedNotes = editorState.selectedNotes
    val dragState = editorState.dragState

    // Constantes visuales
    val laneCount = 8
    val laneHeight = 40.dp
    val beatWidthBase = 80.dp
    val headerHeight = 40.dp
    val timeRulerHeight = 30.dp

    val canvasHeight = (laneHeight.value * laneCount + headerHeight.value + timeRulerHeight.value).toInt()

    // Estados locales para drag (usando currentPanX para pan/zoom unificado)
    var scrollOffsetPx by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(0f) }
    var lastScrollOffsetPx by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(0f) }

    // Sincronizar scroll/zoom con currentPanX/currentZoom
    androidx.compose.runtime.LaunchedEffect(currentPanX, currentZoom) {
        scrollOffsetPx = currentPanX * 100f
        lastScrollOffsetPx = scrollOffsetPx
    }

    // Modifiers combinados para todos los gestos
    var currentZoom by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(zoom) }
    var currentPanX by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(editorState.scrollX) }

    // Sincronizar con editorState
    androidx.compose.runtime.LaunchedEffect(editorState.zoom, editorState.scrollX) {
        currentZoom = editorState.zoom
        currentPanX = editorState.scrollX
    }

    val canvasModifier = Modifier
        .fillMaxWidth()
        .height(canvasHeight.dp)
        .pointerInput(Unit) {
            // Pinch zoom + pan (transform gestures)
            detectTransformGestures(
                onGesture = { centroid, pan, zoom, rotation ->
                    // Zoom con pinch (centroide = punto focal)
                    val newZoom = (currentZoom * zoom).coerceIn(0.25f, 8f)
                    currentZoom = newZoom
                    vm.updateChartView(newZoom, currentPanX)
                    
                    // Pan con dos dedos
                    val newPanX = (currentPanX - pan.x / 100f).coerceIn(0f, Float.MAX_VALUE)
                    currentPanX = newPanX
                    vm.updateChartView(currentZoom, newPanX)
                }
            )
        }
        .pointerInput(Unit) {
            // Drag para scroll (pan) - un dedo
            detectDragGestures(
                onDragStart = { 
                    if (dragState == null || dragState is com.fnfmodmaker.model.ChartEditorState.DragState.Scroll) {
                        vm.startScrollDrag(it.x, scrollOffsetPx / 100f)
                    }
                },
                onDrag = { change, _ ->
                    val state = vm.chartEditorState.dragState
                    if (state is com.fnfmodmaker.model.ChartEditorState.DragState.Scroll) {
                        val deltaX = change.positionChange().x
                        val newScroll = (state.startScroll - deltaX / 100f).coerceIn(0f, Float.MAX_VALUE)
                        vm.updateChartView(editorState.zoom, newScroll)
                    }
                },
                onDragEnd = { vm.endScrollDrag() }
            )
        }
        .pointerInput(Unit) {
            // Drag para selección box, hold resize, multi-drag
            detectDragGestures(
                onDragStart = { 
                    val x = it.x - scrollOffsetPx
                    val y = it.y
                    when (dragState) {
                        null -> {
                            // Verificar si estamos sobre una nota para hold resize o multi-drag
                            val noteUnder = findNoteAt(x, y, beatMs)
                            if (noteUnder != null) {
                                val (note, noteIndex) = noteUnder
                                if (note.isHold) {
                                    // Iniciar hold resize
                                    vm.startHoldResize(noteIndex, note.timeMs, note.lane, note.lengthMs, it.x)
                                } else if (selectedNotes.any { it.timeMs == note.timeMs && it.lane == note.lane }) {
                                    // Iniciar multi-drag si la nota está seleccionada
                                    vm.startMultiDrag(it.x, it.y)
                                } else {
                                    // Iniciar selection box
                                    vm.startSelectionBox(it.x, it.y)
                                }
                            } else {
                                // Iniciar selection box en vacío
                                vm.startSelectionBox(it.x, it.y)
                            }
                        }
                        is com.fnfmodmaker.model.ChartEditorState.DragState.SelectionBox -> {
                            // Ya iniciado
                        }
                        is com.fnfmodmaker.model.ChartEditorState.DragState.HoldResize -> {
                            // Ya iniciado
                        }
                        is com.fnfmodmaker.model.ChartEditorState.DragState.MultiDrag -> {
                            // Ya iniciado
                        }
                    }
                },
                onDrag = { change, _ ->
                    val x = change.position.x - scrollOffsetPx
                    val y = change.position.y
                    when (vm.chartEditorState.dragState) {
                        is com.fnfmodmaker.model.ChartEditorState.DragState.SelectionBox -> {
                            vm.updateSelectionBox(
                                change.position.x, change.position.y,
                                (beatWidthBase.value * zoom).toPx(),
                                beatMs,
                                laneHeight.toPx(),
                                timeRulerHeight.toPx(),
                                headerHeight.toPx()
                            )
                        }
                        is com.fnfmodmaker.model.ChartEditorState.DragState.HoldResize -> {
                            vm.updateHoldResize(
                                change.position.x,
                                (beatWidthBase.value * zoom).toPx(),
                                beatMs
                            )
                        }
                        is com.fnfmodmaker.model.ChartEditorState.DragState.MultiDrag -> {
                            vm.updateMultiDrag(
                                change.position.x, change.position.y,
                                (beatWidthBase.value * zoom).toPx(),
                                beatMs,
                                laneHeight.toPx()
                            )
                        }
                        else -> {}
                    }
                },
                onDragEnd = {
                    when (vm.chartEditorState.dragState) {
                        is com.fnfmodmaker.model.ChartEditorState.DragState.SelectionBox -> vm.endSelectionBox()
                        is com.fnfmodmaker.model.ChartEditorState.DragState.HoldResize -> vm.endHoldResize()
                        is com.fnfmodmaker.model.ChartEditorState.DragState.MultiDrag -> vm.endMultiDrag()
                        else -> {}
                    }
                }
            )
        }

    fun findNoteAt(x: Float, y: Float, beatMs: Double): Pair<com.fnfmodmaker.model.ChartNote, Int>? {
        if (y < timeRulerHeight.value + headerHeight.value) return null
        val laneY = y - timeRulerHeight.value - headerHeight.value
        val lane = (laneY / laneHeight.value).toInt().coerceIn(0, laneCount - 1)
        val beatX = x / (beatWidthBase.value * zoom)
        val timeMs = (beatX * beatMs * 4).roundToLong()
        val snappedTimeMs = snapToGrid(timeMs, beatMs, editorState.snapResolution)
        
        // Buscar nota en esa posición (con tolerancia basada en resolución)
        val toleranceMs = (beatMs / editorState.snapResolution.divisionsPerBeat).roundToLong()
        return draft.notes
            .mapIndexed { index, note -> note to index }
            .firstOrNull { (note, _) ->
                note.lane == lane && abs(note.timeMs - snappedTimeMs) <= toleranceMs
            }
    }

    fun handleCanvasTap(x: Float, y: Float, beatMs: Double) {
        if (y < timeRulerHeight.value + headerHeight.value) return
        val laneY = y - timeRulerHeight.value - headerHeight.value
        val lane = (laneY / laneHeight.value).toInt().coerceIn(0, laneCount - 1)
        val beatX = x / (beatWidthBase.value * zoom)
        val timeMs = (beatX * beatMs * 4).roundToLong()
        val snappedTimeMs = snapToGrid(timeMs, beatMs, editorState.snapResolution)
        
        // Si hay selección activa y tap en nota seleccionada -> toggle selection
        val existingSel = selectedNotes.indexOfFirst { it.timeMs == snappedTimeMs && it.lane == lane }
        if (existingSel >= 0) {
            vm.toggleNoteSelection(snappedTimeMs, lane)
        } else {
            // Tap normal: agregar nota simple
            onNoteTap(snappedTimeMs, lane, false)
            vm.clearSelection()
        }
    }

    fun handleCanvasLongPress(x: Float, y: Float, beatMs: Double) {
        if (y < timeRulerHeight.value + headerHeight.value) return
        val laneY = y - timeRulerHeight.value - headerHeight.value
        val lane = (laneY / laneHeight.value).toInt().coerceIn(0, laneCount - 1)
        val beatX = x / (beatWidthBase.value * zoom)
        val timeMs = (beatX * beatMs * 4).roundToLong()
        val snappedTimeMs = snapToGrid(timeMs, beatMs, editorState.snapResolution)
        onNoteTap(snappedTimeMs, lane, true) // hold start
        vm.clearSelection()
    }

    fun snapToGrid(timeMs: Long, beatMs: Double, resolution: com.fnfmodmaker.model.SnapResolution): Long {
        val divisionMs = beatMs / resolution.divisionsPerBeat
        val tick = (timeMs / divisionMs).roundToLong()
        return (tick * divisionMs).roundToLong()
    }

    // Canvas drawing
    Canvas(modifier = canvasModifier) {
        val laneHeightPx = laneHeight.toPx()
        val beatWidthPx = (beatWidthBase.value * zoom).toPx()
        val headerHeightPx = headerHeight.toPx()
        val timeRulerHeightPx = timeRulerHeight.toPx()
        val totalWidth = size.width
        val totalHeight = size.height

        // Fondo
        drawRect(Color(0xFF16161F), size = size)

        // Grid vertical (beats)
        val beatPaint = androidx.compose.ui.graphics.Paint().apply {
            color = Color(0xFF3A3A4A)
            strokeWidth = 1f
        }
        val strongBeatPaint = androidx.compose.ui.graphics.Paint().apply {
            color = Color(0xFF5A5A6A)
            strokeWidth = 1f
        }
        val measurePaint = androidx.compose.ui.graphics.Paint().apply {
            color = Color(0xFF00FFFF).copy(alpha = 0.3f)
            strokeWidth = 2f
        }

        val beatsPerMeasure = 4
        val visibleBeats = (totalWidth / beatWidthPx).toInt() + 2
        val startBeat = (scrollOffsetPx / beatWidthPx).toInt()

        for (i in 0..visibleBeats) {
            val beatIndex = startBeat + i
            val x = beatIndex * beatWidthPx - scrollOffsetPx
            val isMeasure = beatIndex % beatsPerMeasure == 0
            val isStrong = beatIndex % 2 == 0
            drawLine(
                if (isMeasure) measurePaint else if (isStrong) strongBeatPaint else beatPaint,
                start = androidx.compose.ui.geometry.Offset(x, timeRulerHeightPx + headerHeightPx),
                end = androidx.compose.ui.geometry.Offset(x, totalHeight)
            )
        }

        // Grid horizontal (lanes)
        val lanePaint = androidx.compose.ui.graphics.Paint().apply {
            color = Color(0xFF3A3A4A)
            strokeWidth = 1f
        }
        val playerLanePaint = androidx.compose.ui.graphics.Paint().apply {
            color = Color(0xFF12FA05).copy(alpha = 0.15f)
        }
        val opponentLanePaint = androidx.compose.ui.graphics.Paint().apply {
            color = Color(0xFFF9393F).copy(alpha = 0.15f)
        }

        for (lane in 0 until laneCount) {
            val y = timeRulerHeightPx + headerHeightPx + lane * laneHeightPx
            val laneBgPaint = if (lane < 4) playerLanePaint else opponentLanePaint
            drawRect(laneBgPaint, topLeft = androidx.compose.ui.geometry.Offset(0f, y), size = androidx.compose.ui.geometry.Size(totalWidth, laneHeightPx))
            drawLine(lanePaint, start = androidx.compose.ui.geometry.Offset(0f, y), end = androidx.compose.ui.geometry.Offset(totalWidth, y))
        }

        // Lane labels
        val laneLabels = listOf("←", "↓", "↑", "→", "←", "↓", "↑", "→")
        for (lane in 0 until laneCount) {
            val y = timeRulerHeightPx + headerHeightPx + lane * laneHeightPx + laneHeightPx / 2
            drawText(
                text = androidx.compose.ui.text.AnnotatedString(laneLabels[lane]),
                topLeft = androidx.compose.ui.geometry.Offset(8f, y - 7f),
                style = androidx.compose.ui.text.TextStyle(
                    fontSize = 14.sp,
                    color = Color(0xFFA0A0B0),
                    fontFamily = FontFamily.Monospace
                )
            )
        }

        // Time ruler
        val rulerBgPaint = androidx.compose.ui.graphics.Paint().apply { color = Color(0xFF0B0B0F) }
        drawRect(rulerBgPaint, topLeft = androidx.compose.ui.geometry.Offset(0f, 0f), size = androidx.compose.ui.geometry.Size(totalWidth, timeRulerHeightPx))
        for (i in 0..visibleBeats) {
            val beatIndex = startBeat + i
            val x = beatIndex * beatWidthPx - scrollOffsetPx
            if (beatIndex % beatsPerMeasure == 0) {
                val measureNum = beatIndex / beatsPerMeasure + 1
                drawText(
                    text = androidx.compose.ui.text.AnnotatedString("$measureNum"),
                    topLeft = androidx.compose.ui.geometry.Offset(x + 4f, 4f),
                    style = androidx.compose.ui.text.TextStyle(
                        fontSize = 12.sp,
                        color = Color(0xFFC24B99),
                        fontFamily = FontFamily.Monospace
                    )
                )
            }
        }

        // Playhead
        if (isPlaying || currentTimeMs > 0) {
            val playheadX = (currentTimeMs / beatMs * beatWidthPx / 4) - scrollOffsetPx
            val playheadPaint = androidx.compose.ui.graphics.Paint().apply {
                color = Color(0xFF00FFFF)
                strokeWidth = 2f
            }
            drawLine(playheadPaint, start = androidx.compose.ui.geometry.Offset(playheadX, 0f), end = androidx.compose.ui.geometry.Offset(playheadX, totalHeight))
            val path = androidx.compose.ui.graphics.Path().apply {
                moveTo(playheadX, 0f); lineTo(playheadX - 6f, 12f); lineTo(playheadX + 6f, 12f); close()
            }
            drawPath(path, playheadPaint)
        }

        // Selection box visual
        if (dragState is com.fnfmodmaker.model.ChartEditorState.DragState.SelectionBox) {
            val box = dragState as com.fnfmodmaker.model.ChartEditorState.DragState.SelectionBox
            val minX = minOf(box.startX - scrollOffsetPx, 0f)
            val maxX = maxOf(box.startX - scrollOffsetPx, 0f) // simplified
            val selPaint = androidx.compose.ui.graphics.Paint().apply {
                color = Color(0xFF00FFFF).copy(alpha = 0.2f)
                style = androidx.compose.ui.graphics.Paint.Style.Fill
            }
            val selStroke = androidx.compose.ui.graphics.Paint().apply {
                color = Color(0xFF00FFFF)
                style = androidx.compose.ui.graphics.Paint.Style.Stroke
                strokeWidth = 1f
                pathEffect = androidx.compose.ui.graphics.PathEffect.makeDashPathEffect(floatArrayOf(5f, 5f), 0f)
            }
            // Draw selection rect (simplified)
        }

        // Highlight selected notes
        val selectedSet = selectedNotes.map { it.timeMs to it.lane }.toSet()

        // Draw notes
        val notePaint = androidx.compose.ui.graphics.Paint().apply { isAntiAlias = true }
        val holdPaint = androidx.compose.ui.graphics.Paint().apply { isAntiAlias = true; style = androidx.compose.ui.graphics.Paint.Style.Fill }
        val noteStrokePaint = androidx.compose.ui.graphics.Paint().apply { isAntiAlias = true; style = androidx.compose.ui.graphics.Paint.Style.Stroke; strokeWidth = 2f }
        val selectionHighlightPaint = androidx.compose.ui.graphics.Paint().apply { isAntiAlias = true; color = Color(0xFF00FFFF).copy(alpha = 0.5f); style = androidx.compose.ui.graphics.Paint.Style.Stroke; strokeWidth = 3f }

        for (note in draft.notes) {
            val noteX = (note.timeMs / beatMs * beatWidthPx / 4) - scrollOffsetPx
            val lane = note.lane.coerceIn(0, laneCount - 1)
            val noteY = timeRulerHeightPx + headerHeightPx + lane * laneHeightPx
            val noteWidth = 24.dp.toPx()
            val noteHeight = (laneHeightPx * 0.7f).toInt()

            val isSelected = selectedSet.contains(note.timeMs to note.lane)
            val laneColor = when (lane) {
                0 -> Color(0xFF12FA05); 1 -> Color(0xFFF9393F); 2 -> Color(0xFF00FFFF); 3 -> Color(0xFFC24B99)
                4 -> Color(0xFF12FA05).copy(alpha = 0.8f); 5 -> Color(0xFFF9393F).copy(alpha = 0.8f)
                6 -> Color(0xFF00FFFF).copy(alpha = 0.8f); else -> Color(0xFFC24B99).copy(alpha = 0.8f)
            }

            if (note.isHold) {
                val holdWidth = (note.lengthMs / beatMs * beatWidthPx / 4).coerceAtLeast(noteWidth)
                holdPaint.color = laneColor.copy(alpha = 0.4f)
                drawRect(holdPaint, topLeft = androidx.compose.ui.geometry.Offset(noteX, noteY + (laneHeightPx - noteHeight) / 2), size = androidx.compose.ui.geometry.Size(holdWidth, noteHeight.toFloat()))
                noteStrokePaint.color = if (isSelected) Color(0xFF00FFFF) else laneColor
                drawRoundRect(noteStrokePaint, topLeft = androidx.compose.ui.geometry.Offset(noteX, noteY + (laneHeightPx - noteHeight) / 2), size = androidx.compose.ui.geometry.Size(holdWidth, noteHeight.toFloat()), cornerRadius = androidx.compose.ui.geometry.Size(4f, 4f))
                notePaint.color = laneColor
                drawRoundRect(notePaint, topLeft = androidx.compose.ui.geometry.Offset(noteX, noteY + (laneHeightPx - noteHeight) / 2), size = androidx.compose.ui.geometry.Size(noteWidth, noteHeight.toFloat()), cornerRadius = androidx.compose.ui.geometry.Size(4f, 4f))
            } else {
                notePaint.color = if (isSelected) Color(0xFF00FFFF) else laneColor
                drawRoundRect(notePaint, topLeft = androidx.compose.ui.geometry.Offset(noteX, noteY + (laneHeightPx - noteHeight) / 2), size = androidx.compose.ui.geometry.Size(noteWidth, noteHeight.toFloat()), cornerRadius = androidx.compose.ui.geometry.Size(4f, 4f))
                // Arrow
                val arrow = when (lane % 4) { 0 -> "←"; 1 -> "↓"; 2 -> "↑"; else -> "→" }
                drawText(text = androidx.compose.ui.text.AnnotatedString(arrow), topLeft = androidx.compose.ui.geometry.Offset(noteX + 4f, noteY + (laneHeightPx - noteHeight) / 2 + noteHeight / 2f + 4f), style = androidx.compose.ui.text.TextStyle(fontSize = 12.sp, color = if (isSelected) Color(0xFF00FFFF) else Color.White, fontFamily = FontFamily.Monospace))
            }

            // Selection highlight
            if (isSelected) {
                val highlightRect = if (note.isHold) {
                    val holdWidth = (note.lengthMs / beatMs * beatWidthPx / 4).coerceAtLeast(noteWidth)
                    androidx.compose.ui.geometry.Offset(noteX - 2f, noteY + (laneHeightPx - noteHeight) / 2 - 2f)
                } else {
                    androidx.compose.ui.geometry.Offset(noteX - 2f, noteY + (laneHeightPx - noteHeight) / 2 - 2f)
                }
                val highlightSize = if (note.isHold) {
                    val holdWidth = (note.lengthMs / beatMs * beatWidthPx / 4).coerceAtLeast(noteWidth)
                    androidx.compose.ui.geometry.Size(holdWidth + 4f, noteHeight.toFloat() + 4f)
                } else {
                    androidx.compose.ui.geometry.Size(noteWidth + 4f, noteHeight.toFloat() + 4f)
                }
                drawRoundRect(selectionHighlightPaint, topLeft = highlightRect, size = highlightSize, cornerRadius = androidx.compose.ui.geometry.Size(6f, 6f))
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Paso 5: exportar
// ---------------------------------------------------------------------------

@Composable
private fun StepExport(vm: WizardViewModel, config: ModConfig, onCreate: () -> Unit) {
    StepHeader(
        "5. Crear el mod",
        "Se generará un ZIP con la carpeta del mod lista para copiar al juego."
    )

    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(14.dp)) {
            InfoRow("Juego", config.engine.label)
            InfoRow("Mod", config.modFolder)
            InfoRow("Canciones", config.songs.map { it.songName.ifEmpty { "sin nombre" } }.joinToString(", "))
            InfoRow("Carpetas", config.songFolders.joinToString(", "))
            InfoRow("BPM", config.songs.map { it.bpm.ifEmpty { "—" } }.joinToString(", "))
            InfoRow("Dificultades", config.songs.map { it.orderedDifficulties.joinToString(", ") }.joinToString(" | "))
            InfoRow(
                "Instrumental",
                config.songs.map { s -> s.instrumental?.let { if (it.isOgg) "OGG" else "convertir" } ?: "—" }.joinToString(", ")
            )
            InfoRow(
                "Voces",
                when {
                    config.songs.any { it.hasSplitVocals } -> "jugador + oponente (por canción)"
                    config.hasAnyVocals -> "una pista (por canción)"
                    else -> "no"
                }
            )
            InfoRow("Personaje propio",
                if (config.character.enabled) {
                    "${config.character.charFolder} (${config.character.side.label.lowercase()})"
                } else {
                    "no"
                }
            )
            InfoRow("Offset", config.songs.map { "${it.songFolder}=${it.offsetMs}ms" }.joinToString(", "))
            InfoRow("Modo historia", if (config.showInStoryMode) "sí" else "solo Freeplay")
            InfoRow("Notas de práctica", if (config.songs.any { it.practiceNotes }) "sí" else "no")
        }
    }

    if (vm.error != null) {
        Spacer(Modifier.height(12.dp))
        MessageCard(vm.error ?: "", error = true)
    }

    if (vm.busy) {
        Spacer(Modifier.height(16.dp))
        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(6.dp))
        Text(vm.status, style = MaterialTheme.typography.labelSmall)
    }

    val saved = vm.savedName
    if (saved != null) {
        Spacer(Modifier.height(14.dp))
        MessageCard("Guardado como: $saved\n\nYa puedes abrirlo desde tu gestor de archivos.", error = false)
        if (vm.builtFiles.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            Text("Contenido del ZIP", fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(6.dp))
            Surface(
                color = MaterialTheme.colorScheme.surface,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.padding(14.dp)) {
                    vm.builtFiles.forEach { file ->
                        Text(
                            file,
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.padding(vertical = 1.dp)
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        TextButton(onClick = { vm.reset() }) { Text("Crear otro mod") }
    }

    vm.warnings.forEach { warning ->
        Spacer(Modifier.height(10.dp))
        MessageCard(warning, error = false)
    }

    Spacer(Modifier.height(16.dp))
    Text("Cómo instalarlo", fontWeight = FontWeight.SemiBold)
    Spacer(Modifier.height(6.dp))
    HelpCard(
        "V-Slice (FNF móvil 0.8.x):\n" +
            "1. Abre el juego → Options → Open Data Folder.\n" +
            "2. Entra en la carpeta mods.\n" +
            "3. Descomprime el ZIP ahí (debe quedar mods/${config.modFolder}/).\n" +
            "4. Cierra el juego por completo y vuelve a abrirlo.\n\n" +
            "Psych Engine:\n" +
            "• PC: descomprime en <Psych Engine>/mods/.\n" +
            "• Android: descomprime en Android/data/<paquete>/files/mods/.\n" +
            "• Las canciones aparecen en Freeplay."
    )

    if (vm.savedName == null) {
        Spacer(Modifier.height(16.dp))
        if (!vm.exportReady) {
            MessageCard(
                "Todavía falta: " + vm.missingForExport.joinToString(", ") +
                    ".\n\nToca los números de arriba para volver al paso que necesites.",
                error = true
            )
            Spacer(Modifier.height(10.dp))
        }
        Button(
            onClick = onCreate,
            modifier = Modifier.fillMaxWidth(),
            enabled = vm.exportReady && !vm.busy
        ) {
            Text(if (vm.busy) "Creando…" else "Crear .zip")
        }
    }
}