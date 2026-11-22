package com.fnfmodmaker.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.size
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
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
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

private val STEP_LABELS = listOf("Mod", "Canciones", "Personaje", "Audio", "Chart", "Listo")

@Composable
fun FNFModMakerApp(vm: WizardViewModel = viewModel()) {
    when (vm.tool) {
        Tool.HOME -> HomeScreen(vm)
        Tool.MOD -> WizardScreen(vm)
        Tool.CHARACTER -> CharacterToolScreen(vm)
        Tool.AUDIO -> AudioToolScreen(vm)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WizardScreen(vm: WizardViewModel) {
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
// Piezas de interfaz
// ---------------------------------------------------------------------------

@Composable
private fun StepBar(current: Int, onSelect: (Int) -> Unit) {
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
// Paso 4: Editor de Chart
// ---------------------------------------------------------------------------

@Composable
private fun StepChart(vm: WizardViewModel) {
    val config = vm.config
    val activeSong = config.songs.getOrElse(vm.activeChartSongIndex) { return }
    val difficulties = activeSong.orderedDifficulties

    StepHeader(
        "4. Editor de Chart (${activeSong.songName.ifEmpty { "canción ${vm.activeChartSongIndex + 1}" }})",
        "Dibuja las notas manualmente. Selecciona la canción y dificultad, luego toca en la cuadrícula para colocar notas."
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

    Spacer(Modifier.height(16.dp))

    // Piano Roll placeholder
    val draft = vm.activeChartDraft
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier
            .fillMaxWidth()
            .height(300.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp)
        ) {
            Text(
                "🎹 Piano Roll Editor",
                fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.titleMedium
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "Canción: ${activeSong.songName.ifEmpty { "—" }}  ·  Dificultad: ${vm.activeChartDifficulty.replaceFirstChar { it.uppercase() }}  ·  BPM: ${activeSong.bpmValue}  ·  Offset: ${activeSong.offsetMs}ms",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(12.dp))
            Text(
                "Notas actuales: ${draft.notes.size}  ·  Speed: ${draft.scrollSpeed}  ·  ${if (draft.notes.isEmpty()) "Generado automáticamente (práctica)" else "Editado manualmente"}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(24.dp))

            // Controles básicos
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedButton(onClick = { /* TODO: generar notas de práctica */ }, enabled = !vm.busy) {
                    Text("Generar práctica")
                }
                OutlinedButton(onClick = { /* TODO: limpiar chart */ }, enabled = !vm.busy) {
                    Text("Limpiar chart")
                }
                Button(onClick = {
                    // Guardar chart actual
                    vm.saveChartDraft(draft)
                }, enabled = !vm.busy) {
                    Text("Guardar chart")
                }
            }

            Spacer(Modifier.height(16.dp))
            HelpCard(
                "Editor en desarrollo. Próximamente:\n" +
                "• Piano roll con scroll horizontal y timeline\n" +
                "• Reproducción de audio sincronizada\n" +
                "• Tap para colocar notas, drag para sostener (hold)\n" +
                "• Zoom, selección múltiple, copiar/pegar\n" +
                "• Eventos de cámara, cambios de BPM/velocidad\n" +
                "• Guardado directo sobre los JSON del mod"
            )
        }
    }

    Spacer(Modifier.height(16.dp))
    // Mostrar warnings del chart si hay
    vm.warnings.filter { it.contains("chart", ignoreCase = true) || it.contains("Chart", ignoreCase = true) }.forEach { warning ->
        MessageCard(warning, error = false)
        Spacer(Modifier.height(8.dp))
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