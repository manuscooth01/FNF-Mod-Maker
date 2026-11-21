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
import com.fnfmodmaker.model.ModConfig
import com.fnfmodmaker.model.PickedAudio
import com.fnfmodmaker.model.TargetEngine

private val STEP_LABELS = listOf("Mod", "Canción", "Audio", "Listo")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FNFModMakerApp(vm: WizardViewModel = viewModel()) {
    val config = vm.config

    val pickInstrumental = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.pickAudio(uri, AudioSlot.INSTRUMENTAL)
    }
    val pickPlayerVocals = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.pickAudio(uri, AudioSlot.VOICES_PLAYER)
    }
    val pickOpponentVocals = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.pickAudio(uri, AudioSlot.VOICES_OPPONENT)
    }
    val pickIcon = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.pickIcon(uri)
    }
    val createZip = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri != null) vm.export(uri)
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("FNF Mod Maker", fontWeight = FontWeight.Bold) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface
                )
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
                    if (vm.step < 3) {
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
                            enabled = !vm.busy && config.instrumental != null
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
            StepBar(current = vm.step)
            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp)
            ) {
                Spacer(Modifier.height(8.dp))
                when (vm.step) {
                    0 -> StepMod(vm, config)
                    1 -> StepSong(vm, config)
                    2 -> StepAudio(
                        vm = vm,
                        config = config,
                        onPickInstrumental = { pickInstrumental.launch(arrayOf("*/*")) },
                        onPickPlayerVocals = { pickPlayerVocals.launch(arrayOf("*/*")) },
                        onPickOpponentVocals = { pickOpponentVocals.launch(arrayOf("*/*")) },
                        onPickIcon = { pickIcon.launch(arrayOf("image/*")) }
                    )
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
private fun StepBar(current: Int) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        STEP_LABELS.forEachIndexed { index, label ->
            val active = index <= current
            Column(
                modifier = Modifier.weight(1f),
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

    Spacer(Modifier.height(14.dp))
    HelpCard(
        "V-Slice es el FNF oficial de Android (versión 0.8.x). Psych Engine es el motor de mods " +
            "más usado, tanto en Android como en PC. Si tienes dudas, elige \"Ambos\": la misma " +
            "carpeta funciona en los dos juegos."
    )
}

// ---------------------------------------------------------------------------
// Paso 2: canción
// ---------------------------------------------------------------------------

@Composable
private fun StepSong(vm: WizardViewModel, config: ModConfig) {
    StepHeader(
        "2. La canción",
        "Estos datos definen cómo se ve y se juega tu canción."
    )

    Field(
        label = "Nombre de la canción",
        value = config.songName,
        onChange = vm::setSongName,
        hint = "El nombre que verás en Freeplay. Evita acentos si quieres máxima compatibilidad."
    )
    Spacer(Modifier.height(8.dp))
    InfoRow("Carpeta de la canción", config.songFolder)
    Spacer(Modifier.height(12.dp))
    Field(
        label = "Artista",
        value = config.artist,
        onChange = vm::setArtist
    )

    Spacer(Modifier.height(12.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Field(
            label = "BPM",
            value = config.bpm,
            onChange = vm::setBpm,
            keyboardType = KeyboardType.Number,
            modifier = Modifier.weight(1f)
        )
        Field(
            label = "Velocidad",
            value = config.speed,
            onChange = vm::setSpeed,
            keyboardType = KeyboardType.Decimal,
            modifier = Modifier.weight(1f)
        )
    }
    if (config.bpmValue !in 1..400) {
        Spacer(Modifier.height(6.dp))
        Text(
            "El BPM debe estar entre 1 y 400.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.error
        )
    }

    SectionTitle("Personajes")
    Field(label = "Jugador", value = config.player, onChange = vm::setPlayer, hint = "Por defecto: bf")
    Spacer(Modifier.height(10.dp))
    Field(label = "Novia / GF", value = config.girlfriend, onChange = vm::setGirlfriend, hint = "Por defecto: gf")
    Spacer(Modifier.height(10.dp))
    Field(label = "Oponente", value = config.opponent, onChange = vm::setOpponent, hint = "Por defecto: dad")

    SectionTitle("Dificultades")
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ALL_DIFFICULTIES.forEach { difficulty ->
            FilterChip(
                selected = difficulty in config.difficulties,
                onClick = { vm.toggleDifficulty(difficulty) },
                label = { Text(difficulty.replaceFirstChar { it.uppercase() }) }
            )
        }
    }
    if (config.difficulties.isEmpty()) {
        Spacer(Modifier.height(6.dp))
        Text(
            "Elige al menos una dificultad.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.error
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
        Switch(checked = config.practiceNotes, onCheckedChange = vm::setPracticeNotes)
    }

    HorizontalDivider(modifier = Modifier.padding(vertical = 18.dp))

    SectionTitle("Avanzado (opcional)")
    Field(
        label = "Escenario en V-Slice",
        value = config.stageVSlice,
        onChange = vm::setStageVSlice,
        hint = "mainStage, phillyStreets, school, mallEvil…"
    )
    Spacer(Modifier.height(10.dp))
    Field(
        label = "Escenario en Psych",
        value = config.stagePsych,
        onChange = vm::setStagePsych,
        hint = "stage, stage-psych, etc. (stage es el del juego base)"
    )
    Spacer(Modifier.height(10.dp))
    Field(
        label = "Color del mod",
        value = config.weekColor,
        onChange = vm::setWeekColor,
        hint = "Formato #RRGGBB. Se usa para el fondo de la semana y el menú."
    )
    Spacer(Modifier.height(10.dp))
    Field(
        label = "Offset en milisegundos",
        value = config.offsetMs,
        onChange = vm::setOffsetMs,
        keyboardType = KeyboardType.Number,
        hint = "0 por defecto. Súbelo si tus notas van adelantadas respecto a la música."
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
        "Los nombres de personajes y escenarios deben existir en el juego; si no, la canción " +
            "puede fallar al cargar. Con bf / gf / dad y mainStage / stage vas sobre seguro."
    )
}

// ---------------------------------------------------------------------------
// Paso 3: audio e imagen
// ---------------------------------------------------------------------------

@Composable
private fun StepAudio(
    vm: WizardViewModel,
    config: ModConfig,
    onPickInstrumental: () -> Unit,
    onPickPlayerVocals: () -> Unit,
    onPickOpponentVocals: () -> Unit,
    onPickIcon: () -> Unit
) {
    StepHeader(
        "3. Audio e imagen",
        "El juego solo reproduce OGG. Si eliges MP3 o WAV, la app lo convierte sola."
    )

    AudioRow(
        title = "Instrumental (obligatoria)",
        audio = config.instrumental,
        emptyText = "Sin instrumental todavía",
        onPick = onPickInstrumental,
        buttonText = "Elegir instrumental"
    )
    Spacer(Modifier.height(10.dp))
    AudioRow(
        title = "Voces del jugador (opcional)",
        audio = config.vocalsPlayer,
        emptyText = "Sin voces del jugador",
        onPick = onPickPlayerVocals,
        buttonText = "Elegir"
    )
    Spacer(Modifier.height(10.dp))
    AudioRow(
        title = "Voces del oponente (opcional)",
        audio = config.vocalsOpponent,
        emptyText = "Sin voces del oponente",
        onPick = onPickOpponentVocals,
        buttonText = "Elegir"
    )

    Spacer(Modifier.height(16.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("Icono del mod (opcional)", fontWeight = FontWeight.SemiBold)
            Text(
                config.icon?.displayName ?: "Se usa el icono por defecto del juego",
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

    val instrumental = config.instrumental
    if (instrumental != null && instrumental.durationMs > 0L) {
        listOf(config.vocalsPlayer, config.vocalsOpponent).filterNotNull().forEach { track ->
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
// Paso 4: exportar
// ---------------------------------------------------------------------------

@Composable
private fun StepExport(vm: WizardViewModel, config: ModConfig, onCreate: () -> Unit) {
    StepHeader(
        "4. Crear el mod",
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
            InfoRow("Canción", config.songName.ifBlank { "—" })
            InfoRow("Carpeta", config.songFolder)
            InfoRow("BPM", config.bpm.ifBlank { "—" })
            InfoRow("Dificultades", config.orderedDifficulties.joinToString(", "))
            InfoRow(
                "Instrumental",
                config.instrumental?.let { if (it.isOgg) "OGG (se copia)" else "se convierte a OGG" } ?: "—"
            )
            InfoRow(
                "Voces",
                when {
                    config.hasSplitVocals -> "jugador + oponente"
                    config.hasVocals -> "una pista"
                    else -> "no"
                }
            )
            InfoRow("Offset", "${config.offsetMs} ms")
            InfoRow("Modo historia", if (config.showInStoryMode) "sí" else "solo Freeplay")
            InfoRow("Notas de práctica", if (config.practiceNotes) "sí" else "no")
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
            "• La canción aparece en Freeplay."
    )

    if (!vm.busy && vm.savedName == null) {
        Spacer(Modifier.height(16.dp))
        Button(onClick = onCreate, modifier = Modifier.fillMaxWidth()) {
            Text(if (config.instrumental == null) "Falta la instrumental" else "Crear .zip")
        }
    }
}
