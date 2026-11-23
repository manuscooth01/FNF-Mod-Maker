package com.fnfmodmaker.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
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

@Composable
fun FNFModMakerApp(vm: WizardViewModel = viewModel()) {
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner, vm) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) vm.forceAutoSave() }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    when (vm.tool) {
        Tool.HOME -> HomeScreen(vm)
        Tool.MOD -> WizardScreen(vm)
        Tool.CHARACTER -> CharacterToolScreen(vm)
        Tool.AUDIO -> AudioToolScreen(vm)
        Tool.IMPORT -> ImportScreen(vm)
        Tool.STAGE -> StageToolScreen(vm)
        Tool.ASSISTANT -> ModAiScreen(vm)
    }
}

// ---------------------------------------------------------------------------
// Inicio: elegir herramienta
// ---------------------------------------------------------------------------

@Composable
internal fun HomeScreen(vm: WizardViewModel) {
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            ToolTopBar(
                title = "FNF Mod Maker",
                subtitle = "Herramientas para mods de FNF (V-Slice y Psych)"
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
        ) {
            Spacer(Modifier.height(12.dp))
            androidx.compose.material3.Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(20.dp)) {
                    Text("TU ESTUDIO DE MODS", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.secondary, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(6.dp))
                    Text("Crea a tu ritmo", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.ExtraBold)
                    Text("Una idea, una canción y un paso a la vez.", style = MaterialTheme.typography.bodyMedium)
                }
            }
            Spacer(Modifier.height(20.dp))
            Text(
                "¿Qué quieres hacer?",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "Cada herramienta funciona por su cuenta. Si solo quieres un personaje o " +
                    "convertir un audio, no necesitas rellenar los datos del mod.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(16.dp))

            Text("Proyectos locales", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text("Cada proyecto tiene sus propias canciones, charts y recursos. Se guardan en este dispositivo.", style = MaterialTheme.typography.bodySmall)
            if (vm.busy) Text("Preparando proyectos…", style = MaterialTheme.typography.bodySmall)
            vm.projectMessage?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            Row {
                Button(onClick = { vm.switchProject() }, enabled = !vm.busy && vm.currentProjectId != null) { Text("Nuevo proyecto") }
                TextButton(onClick = { vm.switchProject(duplicate = true) }, enabled = !vm.busy && vm.currentProjectId != null) { Text("Duplicar actual") }
            }
            vm.projects.forEach { project ->
                Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(project.title, fontWeight = FontWeight.SemiBold)
                            Text(project.problem ?: if (project.id == vm.currentProjectId) "Proyecto actual" else "Guardado en el dispositivo", style = MaterialTheme.typography.labelSmall)
                        }
                        TextButton(onClick = { if (project.id == vm.currentProjectId) vm.openTool(Tool.MOD) else vm.switchProject(project.id) },
                            enabled = !vm.busy && project.problem == null && vm.currentProjectId != null) { Text(if (project.id == vm.currentProjectId) "Continuar" else "Abrir") }
                    }
                }
            }
            Text("La importación de ZIP externos aún no está disponible. El autoguardado no es una copia de seguridad portable.", style = MaterialTheme.typography.labelSmall)
            Spacer(Modifier.height(12.dp))
            ToolCard(
                icon = com.fnfmodmaker.R.drawable.tool_person,
                title = "Crear un personaje",
                description = "Corta tu hoja de sprites y genera el XML Sparrow, los JSON de " +
                    "Psych y V-Slice y el icono de vida. Sin datos del mod.",
                badge = null,
                onClick = { vm.openTool(Tool.CHARACTER) }
            )
            Spacer(Modifier.height(12.dp))
            ToolCard(
                icon = com.fnfmodmaker.R.drawable.tool_music_note,
                title = "Convertir audio a OGG",
                description = "Pasa un MP3, WAV, M4A o FLAC al formato que leen los dos motores.",
                badge = null,
                onClick = { vm.openTool(Tool.AUDIO) }
            )
            Spacer(Modifier.height(12.dp))
            ToolCard(icon = com.fnfmodmaker.R.drawable.tool_auto_awesome, title = "Asistente de IA",
                description = "Pide un plan para tu mod o revisa propuestas antes de aplicarlas.", badge = "Opcional",
                onClick = { vm.openTool(Tool.ASSISTANT) })
            Spacer(Modifier.height(12.dp))
            Text("Escenario sencillo disponible dentro de Canciones. Importación de mods y cinemáticas: aún no disponibles.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

            Spacer(Modifier.height(20.dp))
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.padding(14.dp)) {
                    Text("¿Y después?", fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Descomprime el ZIP dejando la carpeta del mod directamente dentro de " +
                            "mods/. En Android: Android/data/me.funkin.fnf/files/mods/ (V-Slice) " +
                            "o Android/data/<paquete-de-Psych>/files/mods/. En PC, la carpeta " +
                            "mods/ de Psych.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

// ---------------------------------------------------------------------------
// Herramienta: importar mod existente
// ---------------------------------------------------------------------------

@Composable
internal fun ImportScreen(vm: WizardViewModel) {
    val pickImport = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.importMod(uri)
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            ToolTopBar(
                title = "Importar mod existente",
                subtitle = "Carga un ZIP de V-Slice o Psych Engine",
                onHome = vm::goHome
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
        ) {
            Spacer(Modifier.height(12.dp))
            Text(
                "Importar mod",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "Selecciona un archivo .zip de un mod de FNF (V-Slice o Psych Engine). " +
                    "La app leerá la estructura, canciones, charts, personajes y audio " +
                    "para que puedas editarlo y re-exportarlo.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(16.dp))

            Surface(
                color = MaterialTheme.colorScheme.surface,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("Archivo ZIP del mod", fontWeight = FontWeight.SemiBold)
                            Spacer(Modifier.height(4.dp))
                            Text(
                                "Debe contener _polymod_meta.json (V-Slice) o pack.json (Psych)",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Spacer(Modifier.width(10.dp))
                        OutlinedButton(onClick = {
                            // Import placeholder
                        }, enabled = false) { Text("📥 Importar ZIP (pendiente)") }
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
            HelpCard(
                "Formatos soportados:\n" +
                "• V-Slice (FNF 0.8.x Android): _polymod_meta.json, data/songs/, data/levels/\n" +
                "• Psych Engine: pack.json, weeks/, data/, songs/\n" +
                "• Ambos: si el ZIP tiene los dos, se detectan automáticamente\n\n" +
                "Qué se importa:\n" +
                "• Metadatos del mod (nombre, autor, descripción, color)\n" +
                "• Canciones: nombre, artista, BPM, offset, speed, dificultades\n" +
                "• Audio: instrumental y voces (se copian, no se reconvierten)\n" +
                "• Charts: notas existentes (se cargan en el editor de chart)\n" +
                "• Personaje propio: spritesheet, XML, JSON, icono de vida\n" +
                "• Icono del mod\n\n" +
                "Limitaciones:\n" +
                "• Escenarios (stages) no se importan aún\n" +
                "• Eventos de cámara / cambios de BPM no se leen\n" +
                "• Múltiples semanas por mod: se intenta detectar"
            )

            Spacer(Modifier.height(16.dp))
            ToolStatus(
                status = "",
                error = null,
                saved = null,
                warnings = emptyList(),
                savedLabel = ""
            )
            Spacer(Modifier.height(24.dp))
        }
    }
}

// ---------------------------------------------------------------------------
// Herramienta: crear un stage (escenario) personalizado (placeholder)
// ---------------------------------------------------------------------------

@Composable
internal fun StageToolScreen(vm: WizardViewModel) {
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            ToolTopBar(
                title = "Crear un stage (escenario)",
                subtitle = "En desarrollo",
                onHome = vm::goHome
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
        ) {
            Spacer(Modifier.height(12.dp))
            Text(
                "Editor de stage",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "Esta herramienta está en desarrollo. Próximamente permitirá:\n" +
                "• Elegir imagen de fondo\n" +
                "• Agregar sprites con drag & drop\n" +
                "• Configurar cámara y límites\n" +
                "• Animaciones de sprites (frames, fps, loop)\n" +
                "• Exportar ZIP listo para V-Slice y Psych",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(24.dp))
        }
    }
}

// ---------------------------------------------------------------------------
// Herramienta: crear un personaje suelto
// ---------------------------------------------------------------------------

@Composable
internal fun CharacterToolScreen(vm: WizardViewModel) {
    val draft = vm.config.character
    val pickSheet = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) vm.pickCharacterSheet(uri) }
    val pickXml = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) vm.pickCharacterAtlasXml(uri) }
    val pickIcon = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) vm.pickCharacterIcon(uri) }
    val pickFrames = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris -> vm.pickCharacterFrames(uris) }
    val saveZip = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri -> if (uri != null) vm.exportCharacter(uri) }
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = { ToolTopBar("Personaje", draft.displayName.ifBlank { draft.charFolder }, vm::goHome) },
        bottomBar = {
            Surface(color = MaterialTheme.colorScheme.surface) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                    if (vm.characterIssues.isNotEmpty()) Text("${vm.characterIssues.size} pendientes · consulta Revisar", style = MaterialTheme.typography.labelSmall)
                    if (vm.characterSaved != null) Text("ZIP guardado: ${vm.characterSaved}", style = MaterialTheme.typography.labelSmall)
                    Button(onClick = { saveZip.launch(vm.characterZipName) }, modifier = Modifier.fillMaxWidth(),
                        enabled = vm.characterOnlyReady && !vm.busy) { Text(if (vm.busy) "Procesando…" else "Guardar ZIP del personaje") }
                }
            }
        }
    ) { padding ->
        CharacterWorkspace(vm, draft,
            onPickSheet = { pickSheet.launch(arrayOf("image/png")) },
            onPickXml = { pickXml.launch(arrayOf("application/xml", "text/xml", "text/plain", "application/octet-stream")) },
            onPickIcon = { pickIcon.launch(arrayOf("image/*")) },
            onPickFrames = { name -> vm.prepareCharacterFramePicker(name); pickFrames.launch(arrayOf("image/png")) },
            modifier = Modifier.padding(padding))
    }
}

// ---------------------------------------------------------------------------
// Herramienta: convertir audio a OGG
// ---------------------------------------------------------------------------

@Composable
internal fun AudioToolScreen(vm: WizardViewModel) {
    val audio = vm.toolAudio
    val preview = rememberAudioPreview()
    androidx.compose.runtime.LaunchedEffect(audio?.path, vm.busy) { preview.stop() }

    val pickAudio = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.pickToolAudio(uri)
    }
    val saveOgg = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("audio/ogg")) { uri ->
        if (uri != null) vm.convertToolAudio(uri)
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            ToolTopBar(
                title = "Convertir audio a OGG",
                subtitle = "Sin crear un mod",
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
                    Button(
                        onClick = { saveOgg.launch(vm.toolAudioFileName) },
                        modifier = Modifier.weight(1f),
                        enabled = audio != null && !vm.busy
                    ) {
                        Text(if (vm.busy) "Convirtiendo…" else "Convertir y guardar")
                    }
                }
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
        ) {
            Spacer(Modifier.height(8.dp))
            Text(
                "Elige una canción o una pista de voces y la app la deja en OGG Vorbis, que es " +
                    "lo que leen V-Slice y Psych. Si ya es OGG, se copia tal cual.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(14.dp))

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
                        Text(
                            audio?.displayName ?: "Ningún archivo elegido",
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            when {
                                audio == null -> "MP3, WAV, M4A, FLAC u OGG"
                                audio.isOgg -> "Ya es OGG · se copia sin recomprimir"
                                else -> "Se convierte a OGG Vorbis"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Spacer(Modifier.width(10.dp))
                    OutlinedButton(onClick = { pickAudio.launch(arrayOf("*/*")) }, enabled = !vm.busy) {
                        Text("Elegir")
                    }
                }
            }

            if (audio != null) AudioPreviewControls(preview, audio, !vm.busy)
            preview.error?.let { MessageCard(it, error = true) }

            if (audio != null && audio.durationMs > 0L) {
                Spacer(Modifier.height(8.dp))
                InfoLine("Duración", audio.prettyDuration)
            }

            Spacer(Modifier.height(12.dp))
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    "Para un mod hace falta el instrumental (Inst.ogg) y, si la canción tiene " +
                        "voces, una pista de voces (Voices.ogg). Conviértelas aquí y luego " +
                        "elígelas en el asistente del mod.",
                    modifier = Modifier.padding(14.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(Modifier.height(16.dp))
            ToolStatus(
                status = vm.status,
                error = vm.error,
                saved = vm.toolAudioSaved,
                warnings = emptyList(),
                savedLabel = "Archivo guardado como"
            )
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
internal fun ModAiScreen(vm: WizardViewModel) {
    Scaffold(containerColor = MaterialTheme.colorScheme.background,
        topBar = { ToolTopBar("Asistente del mod", "Propuestas con revisión", vm::returnToMod, "‹ Mod") }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
            CharacterAiPanel(vm)
            Spacer(Modifier.height(24.dp))
        }
    }
}
