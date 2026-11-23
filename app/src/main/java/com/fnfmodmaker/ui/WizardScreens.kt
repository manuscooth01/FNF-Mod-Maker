package com.fnfmodmaker.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.fnfmodmaker.model.AudioSlot

private val STEP_LABELS = listOf("Mod", "Canciones", "Personaje", "Audio", "Notas", "Exportar")

@Composable
internal fun WizardScreen(vm: WizardViewModel) {
    val config = vm.config
    val instrumental = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.pickActiveSongAudio(uri, AudioSlot.INSTRUMENTAL)
    }
    val playerVocals = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.pickActiveSongAudio(uri, AudioSlot.VOICES_PLAYER)
    }
    val opponentVocals = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.pickActiveSongAudio(uri, AudioSlot.VOICES_OPPONENT)
    }
    val modIcon = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.pickIcon(uri)
    }
    val charSheet = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.pickCharacterSheet(uri)
    }
    val charIcon = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.pickCharacterIcon(uri)
    }
    val charFrames = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        vm.pickCharacterFrames(uris)
    }
    val createZip = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri != null) vm.export(uri)
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = { ToolTopBar("Tu mod", config.modTitle.ifBlank { "Nuevo proyecto" }, vm::goHome) },
        bottomBar = {
            Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 6.dp) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
                    if (vm.step < 5 && vm.currentStepIssues.isNotEmpty()) {
                        Text("Completa lo pendiente para continuar", style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        if (vm.step > 0) {
                            OutlinedButton(onClick = vm::back, enabled = !vm.busy, modifier = Modifier.weight(1f)) { Text("Atrás") }
                        }
                        if (vm.step < 5) {
                            Button(onClick = vm::next, enabled = vm.canAdvance && !vm.busy,
                                modifier = Modifier.weight(1.5f)) { Text("Continuar") }
                        } else {
                            Button(onClick = { createZip.launch(vm.zipFileName) }, enabled = vm.exportReady && !vm.busy,
                                modifier = Modifier.weight(1.5f)) { Text(if (vm.busy) "Creando…" else "Guardar ZIP") }
                        }
                    }
                }
            }
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            WizardProgress(vm.step, !vm.busy, vm::goTo)
            key(vm.step) {
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
                    Spacer(Modifier.height(8.dp))
                    if (vm.busy) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(modifier = Modifier.height(18.dp), strokeWidth = 2.dp)
                            Text("  ${vm.status}", style = MaterialTheme.typography.bodySmall)
                        }
                        Spacer(Modifier.height(10.dp))
                    }
                    vm.error?.let { MessageCard(it, error = true); Spacer(Modifier.height(10.dp)) }
                    if (vm.step < 5 && vm.currentStepIssues.isNotEmpty()) {
                        WizardIssueCard(vm.currentStepIssues, vm::resolveIssue)
                        Spacer(Modifier.height(12.dp))
                    }
                    when (vm.step) {
                        0 -> WizardModStep(vm, config)
                        1 -> WizardSongsStep(vm)
                        2 -> WizardCharacterStep(vm, config,
                            onPickSheet = { charSheet.launch(arrayOf("image/png")) },
                            onPickIcon = { charIcon.launch(arrayOf("image/*")) },
                            onPickFrames = { name -> vm.prepareCharacterFramePicker(name); charFrames.launch(arrayOf("image/png")) })
                        3 -> WizardAudioStep(vm,
                            onPickInstrumental = { instrumental.launch(arrayOf("audio/*", "application/ogg")) },
                            onPickPlayer = { playerVocals.launch(arrayOf("audio/*", "application/ogg")) },
                            onPickOpponent = { opponentVocals.launch(arrayOf("audio/*", "application/ogg")) },
                            onPickIcon = { modIcon.launch(arrayOf("image/*")) })
                        4 -> WizardNotesStep(vm)
                        else -> WizardExportStep(vm)
                    }
                    Spacer(Modifier.height(28.dp))
                }
            }
        }
    }
}

@Composable
private fun WizardProgress(current: Int, enabled: Boolean, onSelect: (Int) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 3.dp)) {
        Text("PASO ${current + 1} DE ${STEP_LABELS.size}", modifier = Modifier.padding(horizontal = 16.dp),
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            STEP_LABELS.forEachIndexed { index, label ->
                FilterChip(selected = current == index, onClick = { onSelect(index) }, enabled = enabled,
                    label = { Text("${index + 1}  $label") })
            }
        }
    }
}

@Composable
internal fun WizardIssueCard(issues: List<com.fnfmodmaker.model.WizardIssue>, onSelect: (com.fnfmodmaker.model.WizardIssue) -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = androidx.compose.foundation.shape.RoundedCornerShape(14.dp)) {
        Column(Modifier.fillMaxWidth().padding(12.dp)) {
            Text("Para continuar", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
            issues.forEach { issue ->
                TextButton(onClick = { onSelect(issue) }, modifier = Modifier.fillMaxWidth()) {
                    Text("• ${issue.message}", modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                    Text("›")
                }
            }
        }
    }
}

@Composable
private fun WizardModStep(vm: WizardViewModel, config: com.fnfmodmaker.model.ModConfig) {
    StepHeader("Empecemos por tu mod", "Dale un nombre y elige el juego al que lo vas a llevar.")
    Spacer(Modifier.height(16.dp))
    Field("Nombre del mod", config.modTitle, vm::setModTitle, hint = "Por ejemplo: Mi primera batalla", enabled = !vm.busy)
    SectionTitle("Juego de destino")
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        com.fnfmodmaker.model.TargetEngine.entries.forEach { engine ->
            EngineCard(engine, config.engine == engine, { vm.setEngine(engine) })
        }
    }
    SectionTitle("Detalles opcionales")
    Field("Autor", config.author, vm::setAuthor, enabled = !vm.busy)
    Spacer(Modifier.height(10.dp))
    Field("Descripción", config.description, vm::setDescription, singleLine = false, enabled = !vm.busy)
    Spacer(Modifier.height(10.dp))
    Field("Identificador de carpeta", config.modId, vm::setModId, hint = "Carpeta de salida: ${config.modFolder}", enabled = !vm.busy)
    Spacer(Modifier.height(10.dp))
    Field("Color", config.weekColor, vm::setWeekColor, hint = "Formato #RRGGBB", enabled = !vm.busy)
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text("Mostrar en modo historia", Modifier.weight(1f))
        androidx.compose.material3.Switch(config.showInStoryMode, vm::setShowInStoryMode, enabled = !vm.busy)
    }
}

@Composable
private fun SongSelector(vm: WizardViewModel) {
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        vm.config.songs.forEachIndexed { index, song ->
            FilterChip(selected = index == vm.activeSongIndex, onClick = { vm.selectSong(index) }, enabled = !vm.busy,
                label = { Text(song.songName.ifBlank { "Canción ${index + 1}" }) })
        }
    }
}

@Composable
private fun WizardSongsStep(vm: WizardViewModel) {
    val song = vm.activeSong
    StepHeader("Tus canciones", "Añade una o varias canciones. Configura cada una por separado.")
    SongSelector(vm)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = vm::addSong, enabled = !vm.busy) { Text("+ Añadir canción") }
        if (vm.config.songs.size > 1) {
            var confirm by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
            TextButton(onClick = { confirm = true }, enabled = !vm.busy) { Text("Eliminar") }
            if (confirm) androidx.compose.material3.AlertDialog(
                onDismissRequest = { confirm = false }, title = { Text("¿Eliminar esta canción?") },
                text = { Text("Se eliminará del proyecto junto con sus ajustes.") },
                confirmButton = { TextButton(onClick = { vm.removeSong(vm.activeSongIndex); confirm = false }) { Text("Eliminar") } },
                dismissButton = { TextButton(onClick = { confirm = false }) { Text("Cancelar") } })
        }
    }
    Spacer(Modifier.height(10.dp))
    Field("Nombre de la canción", song.songName, vm::setActiveSongName, enabled = !vm.busy)
    Spacer(Modifier.height(10.dp))
    Field("Artista", song.artist, vm::setActiveSongArtist, hint = "Opcional", enabled = !vm.busy)
    Spacer(Modifier.height(10.dp))
    Field("BPM", song.bpm, vm::setActiveSongBpm, hint = "Pulsos por minuto; de 1 a 400.",
        keyboardType = androidx.compose.ui.text.input.KeyboardType.Number, enabled = !vm.busy)
    SectionTitle("Dificultades")
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        com.fnfmodmaker.model.ALL_DIFFICULTIES.forEach { difficulty ->
            FilterChip(difficulty in song.difficulties, { vm.toggleActiveSongDifficulty(difficulty) }, enabled = !vm.busy,
                label = { Text(when (difficulty) { "easy" -> "Fácil"; "normal" -> "Normal"; else -> "Difícil" }) })
        }
    }
    var advanced by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf(false) }
    TextButton(onClick = { advanced = !advanced }) { Text(if (advanced) "Ocultar ajustes avanzados ↑" else "Ajustes avanzados ↓") }
    if (advanced) {
        Field("Velocidad de notas", song.speed, vm::setActiveSongSpeed, enabled = !vm.busy)
        Spacer(Modifier.height(10.dp))
        Field("Desfase (ms)", song.offsetMs, vm::setActiveSongOffsetMs, enabled = !vm.busy)
        Spacer(Modifier.height(10.dp))
        Field("ID del jugador", song.player, vm::setActiveSongPlayer, enabled = !vm.busy)
        Spacer(Modifier.height(10.dp))
        Field("ID del rival", song.opponent, vm::setActiveSongOpponent, enabled = !vm.busy)
        Spacer(Modifier.height(10.dp))
        Field("ID de Girlfriend", song.girlfriend, vm::setActiveSongGirlfriend, enabled = !vm.busy)
        Spacer(Modifier.height(10.dp))
        if (vm.config.engine.includesVSlice) { Field("Escenario V-Slice", song.stageVSlice, vm::setActiveSongStageVSlice, enabled = !vm.busy); Spacer(Modifier.height(10.dp)) }
        if (vm.config.engine.includesPsych) { Field("Escenario Psych", song.stagePsych, vm::setActiveSongStagePsych, enabled = !vm.busy); Spacer(Modifier.height(10.dp)) }
        Field("Color de la canción", song.weekColor, vm::setActiveSongWeekColor, hint = "Formato #RRGGBB", enabled = !vm.busy)
    }
}

@Composable
private fun WizardCharacterStep(vm: WizardViewModel, config: com.fnfmodmaker.model.ModConfig,
    onPickSheet: () -> Unit, onPickIcon: () -> Unit, onPickFrames: (String) -> Unit) {
    StepHeader("Personaje propio", "Este paso es opcional. También puedes usar los personajes del juego.")
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text("Añadir personaje", Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
        androidx.compose.material3.Switch(config.character.enabled, vm::setCharacterEnabled, enabled = !vm.busy)
    }
    if (config.character.enabled) CharacterForm(vm, config.character, onPickSheet, onPickIcon, onPickFrames)
    else HelpCard("El mod usará los personajes indicados en los ajustes de cada canción.")
}

@Composable
private fun AudioPicker(label: String, detail: String?, required: Boolean, busy: Boolean, onPick: () -> Unit, onClear: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = androidx.compose.foundation.shape.RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            Text("$label ${if (required) "· obligatorio" else "· opcional"}", fontWeight = FontWeight.SemiBold)
            Text(detail ?: "Sin archivo", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onPick, enabled = !busy) { Text(if (detail == null) "Elegir archivo" else "Cambiar") }
                if (detail != null) TextButton(onClick = onClear, enabled = !busy) { Text("Quitar") }
            }
        }
    }
    Spacer(Modifier.height(10.dp))
}

@Composable
private fun WizardAudioStep(vm: WizardViewModel, onPickInstrumental: () -> Unit, onPickPlayer: () -> Unit,
    onPickOpponent: () -> Unit, onPickIcon: () -> Unit) {
    StepHeader("Añade el audio", "Selecciona cada canción y su instrumental. Las voces son opcionales.")
    SongSelector(vm)
    val song = vm.activeSong
    AudioPicker("Instrumental", song.instrumental?.let { "${it.displayName} · ${it.prettyDuration}" }, true, vm.busy, onPickInstrumental) { vm.clearSongAudio(AudioSlot.INSTRUMENTAL) }
    AudioPicker("Voz del jugador", song.vocalsPlayer?.let { "${it.displayName} · ${it.prettyDuration}" }, false, vm.busy, onPickPlayer) { vm.clearSongAudio(AudioSlot.VOICES_PLAYER) }
    AudioPicker("Voz del rival", song.vocalsOpponent?.let { "${it.displayName} · ${it.prettyDuration}" }, false, vm.busy, onPickOpponent) { vm.clearSongAudio(AudioSlot.VOICES_OPPONENT) }
    HelpCard("Se aceptan audios compatibles con el dispositivo; al exportar se convierten a OGG cuando sea necesario.")
    SectionTitle("Icono del mod")
    AudioPicker("Imagen", vm.config.icon?.displayName, false, vm.busy, onPickIcon, vm::clearModIcon)
}

@Composable
private fun WizardNotesStep(vm: WizardViewModel) {
    StepHeader("Notas de la canción", "Elige una canción y decide cómo empezar su chart.")
    SongSelector(vm)
    val song = vm.activeSong
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("Generar notas de práctica", fontWeight = FontWeight.SemiBold)
            Text("Una secuencia simple basada en el BPM.", style = MaterialTheme.typography.bodySmall)
        }
        androidx.compose.material3.Switch(song.practiceNotes, vm::setActiveSongPracticeNotes, enabled = !vm.busy)
    }
    Spacer(Modifier.height(12.dp))
    HelpCard("Las notas generadas son una base, no un chart terminado. Para crear o ajustar notas, abre el mod en el editor del juego. Si desactivas esta opción, se exportará un chart vacío.")
}

@Composable
private fun WizardExportStep(vm: WizardViewModel) {
    val config = vm.config
    StepHeader("Revisa tu proyecto", "Corrige lo pendiente y guarda el ZIP cuando esté listo.")
    Spacer(Modifier.height(14.dp))
    InfoLine("Mod", config.modTitle.ifBlank { "Sin nombre" })
    InfoLine("Destino", config.engine.label)
    InfoLine("Canciones", config.songs.size.toString())
    InfoLine("Personaje", if (config.character.enabled) config.character.displayName.ifBlank { "Personalizado" } else "Del juego")
    Spacer(Modifier.height(14.dp))
    if (vm.validationIssues.isNotEmpty()) WizardIssueCard(vm.validationIssues, vm::resolveIssue)
    else HelpCard("Todo listo para guardar. Después, descomprime el ZIP en la carpeta mods del juego y pruébalo allí.")
    Spacer(Modifier.height(12.dp))
    ToolStatus(status = "", error = null, saved = vm.savedName, warnings = vm.warnings, savedLabel = "ZIP guardado")
}
