package com.fnfmodmaker.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
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
import com.fnfmodmaker.model.StageSprite
import com.fnfmodmaker.model.TargetEngine
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ToolTopBar(
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

            ToolCard(
                glyph = "🧩",
                title = "Crear un mod completo",
                description = "Nombre, canción, dificultades y audio. Al final sale un ZIP " +
                    "que puedes meter en mods/ y jugar.",
                badge = "Desde cero",
                onClick = { vm.openTool(Tool.MOD) }
            )
            Spacer(Modifier.height(12.dp))
            ToolCard(
                glyph = "🧍",
                title = "Crear un personaje",
                description = "Corta tu hoja de sprites y genera el XML Sparrow, los JSON de " +
                    "Psych y V-Slice y el icono de vida. Sin datos del mod.",
                badge = null,
                onClick = { vm.openTool(Tool.CHARACTER) }
            )
            Spacer(Modifier.height(12.dp))
            ToolCard(
                glyph = "🎧",
                title = "Convertir audio a OGG",
                description = "Pasa un MP3, WAV, M4A o FLAC al formato que leen los dos motores.",
                badge = null,
                onClick = { vm.openTool(Tool.AUDIO) }
            )
            Spacer(Modifier.height(12.dp))
ToolCard(
                glyph = "📥",
                title = "Importar mod existente",
                description = "Carga un ZIP de mod (V-Slice o Psych Engine) y edítalo: canciones, charts, personajes, audio.",
                badge = "Nuevo",
                onClick = { vm.openTool(Tool.IMPORT) }
            )
            Spacer(Modifier.height(12.dp))
            ToolCard(
                glyph = "🏞️",
                title = "Crear un stage (escenario)",
                description = "Fondo personalizado, sprites, cámara y límites. Genera archivos para V-Slice y Psych.",
                badge = "Nuevo",
                onClick = { vm.openTool(Tool.STAGE) }
            )

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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ToolCard(
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
                        OutlinedButton(onClick = { pickImport.launch(arrayOf("application/zip")) }, enabled = !vm.busy) {
                            Text("Elegir ZIP")
}
}

// ---------------------------------------------------------------------------
// Herramienta: crear un stage (escenario) personalizado
// ---------------------------------------------------------------------------

@Composable
internal fun StageToolScreen(vm: WizardViewModel) {
    val draft = vm.config.stageDraft

    val pickBackground = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.pickStageBackground(uri)
    }
    val pickSprite = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.pickStageSprite(uri)
    }
    val saveZip = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri != null) vm.exportStage(uri)
    }
    val pickImportStage = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.importStage(uri)
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            ToolTopBar(
                title = "Crear un stage (escenario)",
                subtitle = draft.stageFolder,
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
                    OutlinedButton(
                        onClick = { pickImportStage.launch(arrayOf("application/zip")) },
                        enabled = !vm.busy
                    ) { Text("📥 Importar stage ZIP") }
                    Spacer(Modifier.width(8.dp))
                    Button(
                        onClick = { saveZip.launch("${draft.stageFolder}-stage.zip") },
                        modifier = Modifier.weight(1f),
                        enabled = draft.isReady && !vm.busy
                    ) {
                        Text(if (vm.busy) "Generando…" else "Guardar ZIP del stage")
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
                "Crea un escenario personalizado con fondo, sprites y configuración de cámara. " +
                    "El ZIP generado se descomprime dentro de un mod.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(8.dp))

            StageForm(
                vm = vm,
                draft = draft,
                onPickBackground = { pickBackground.launch(arrayOf("image/*")) },
                onPickSprite = { pickSprite.launch(arrayOf("image/*")) }
            )

            if (!draft.isReady && draft.backgroundImage != null) {
                Spacer(Modifier.height(10.dp))
                Text(
                    "Completa el ID del stage y elige una imagen de fondo.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error
                )
            }

            Spacer(Modifier.height(16.dp))
            ToolStatus(
                status = vm.status,
                error = vm.error,
                saved = null,
                warnings = vm.warnings,
                savedLabel = "Stage guardado como"
            )
            Spacer(Modifier.height(24.dp))
        }
}
    }

    // ---------------------------------------------------------------------------
    // Stage Sprite Editor Visual (drag & drop, scale, rotate, layers)
    // ---------------------------------------------------------------------------

    @Composable
    internal fun StageSpriteEditor(
        vm: WizardViewModel,
        draft: com.fnfmodmaker.model.StageDraft,
        onPickSprite: () -> Unit
    ) {
        val sprites = draft.sprites
        var selectedSpriteIndex by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<Int?>(null) }
        var isAddingSprite by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
        var newSpriteImage by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<com.fnfmodmaker.model.PickedImage?>(null) }
        var newSpriteId by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf("") }
        var newSpriteName by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf("") }

        // Vista previa del stage con sprites posicionados
        SectionTitle("Editor visual de sprites")
        Surface(
            color = MaterialTheme.colorScheme.surface,
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier
                .fillMaxWidth()
                .height(300.dp)
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                // Fondo del stage
                draft.backgroundImage?.let { bg ->
                    val painter = androidx.compose.ui.res.painterResource(id = 0) // placeholder
                    // Usamos Canvas para dibujar fondo + sprites
                }
                StagePreviewCanvas(
                    backgroundImage = draft.backgroundImage,
                    sprites = sprites,
                    selectedIndex = selectedSpriteIndex,
                    onSpriteClick = { index -> selectedSpriteIndex = index },
                    onSpriteDrag = { index, deltaX, deltaY ->
                        val sprite = sprites[index]
                        val updated = sprites.toMutableList()
                        updated[index] = sprite.copy(
                            x = (sprite.x + deltaX).coerceIn(-1920f, 1920f),
                            y = (sprite.y + deltaY).coerceIn(-1080f, 1080f)
                        )
                        vm.config = vm.config.copy(stageDraft = draft.copy(sprites = updated))
                    },
                    onSpriteScale = { index, scale ->
                        val sprite = sprites[index]
                        val updated = sprites.toMutableList()
                        updated[index] = sprite.copy(scale = (sprite.scale * scale).coerceIn(0.1f, 5f))
                        vm.config = vm.config.copy(stageDraft = draft.copy(sprites = updated))
                    },
                    onSpriteRotate = { index, rotation ->
                        val sprite = sprites[index]
                        val updated = sprites.toMutableList()
                        updated[index] = sprite.copy(rotation = (sprite.rotation + rotation) % 360)
                        vm.config = vm.config.copy(stageDraft = draft.copy(sprites = updated))
                    }
                )
            }
        }

        Spacer(Modifier.height(12.dp))

        // Lista de sprites con controles
        if (sprites.isNotEmpty()) {
            SectionTitle("Sprites (${sprites.size}) - Tap para seleccionar, drag para mover")
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                sprites.forEachIndexed { index, sprite ->
                    val isSelected = selectedSpriteIndex == index
                    val playState = sprite.animated ?: false
                    val currentFrame = if (sprite.animated) sprite.getCurrentFrame(System.currentTimeMillis() % sprite.animationDurationMs) else 0
                    
                    Surface(
                        color = if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
                        shape = RoundedCornerShape(12.dp),
                        border = if (isSelected) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { selectedSpriteIndex = if (isSelected) null else index }
                            .padding(12.dp)
                    ) {
                        Column {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                // Miniatura del sprite
                                sprite.image?.let { img ->
                                    Box(
                                        modifier = Modifier
                                            .size(60.dp)
                                            .background(Color(0xFF16161F), RoundedCornerShape(8.dp))
                                    ) {
                                        // Mostrar frame actual si está animado
                                        if (sprite.animated) {
                                            Text(
                                                "Frame ${currentFrame + 1}/${sprite.animationFrames}",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.primary,
                                                modifier = Modifier.align(Alignment.Center)
                                            )
                                        } else {
                                            Text(
                                                sprite.displayName.take(2).uppercase(),
                                                style = MaterialTheme.typography.titleMedium,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                modifier = Modifier.align(Alignment.Center)
                                            )
                                        }
                                    }
                                }
                                // Info
                                Column(Modifier.weight(1f)) {
                                    Text(sprite.displayName, fontWeight = FontWeight.SemiBold)
                                    Text(
                                        "ID: ${sprite.spriteId}  ·  Layer: ${sprite.layer.label}  ·  Pos: (${"%.0f".format(sprite.x)}, ${"%.0f".format(sprite.y)})  ·  Scale: ${"%.2f".format(sprite.scale)}  ·  Rot: ${"%.0f".format(sprite.rotation)}°",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    if (sprite.animated) {
                                        Text(
                                            "Animado: ${sprite.animationFrames} frames @ ${sprite.animationFps}fps  ·  ${if (sprite.animationLoop) "Loop" else "Once"}  ·  Dur: ${sprite.animationDurationMs}ms  ·  Frame: ${currentFrame + 1}",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                }
                                // Controles
                                Column(
                                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    // Animation toggle
                                    OutlinedButton(onClick = {
                                        val updated = sprites.toMutableList()
                                        updated[index] = sprite.copy(animated = !sprite.animated)
                                        vm.config = vm.config.copy(stageDraft = draft.copy(sprites = updated))
                                    }, enabled = !vm.busy) {
                                        Text(if (sprite.animated) "⏸ Anim" else "▶ Anim", style = MaterialTheme.typography.labelSmall)
                                    }
                                    // Layer selector
                                    OutlinedButton(onClick = {
                                        val layers = com.fnfmodmaker.model.StageSprite.Layer.entries.toList()
                                        val currentIdx = layers.indexOf(sprite.layer)
                                        val nextLayer = layers[(currentIdx + 1) % layers.size]
                                        val updated = sprites.toMutableList()
                                        updated[index] = sprite.copy(layer = nextLayer)
                                        vm.config = vm.config.copy(stageDraft = draft.copy(sprites = updated))
                                    }, enabled = !vm.busy) {
                                        Text(sprite.layer.label, style = MaterialTheme.typography.labelSmall)
                                    }
                                    // Delete
                                    OutlinedButton(onClick = {
                                        val updated = sprites.toMutableList()
                                        updated.removeAt(index)
                                        if (selectedSpriteIndex == index) selectedSpriteIndex = null
                                        else if (selectedSpriteIndex != null && selectedSpriteIndex! > index) selectedSpriteIndex = selectedSpriteIndex!! - 1
                                        vm.config = vm.config.copy(stageDraft = draft.copy(sprites = updated))
                                    }, enabled = !vm.busy, colors = ButtonDefaults.outlinedButtonColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                                        Text("🗑", style = MaterialTheme.typography.labelSmall)
                                    }
                                }
                            }
                        }
                        // Animation controls (expanded when selected and animated)
                        if (isSelected && sprite.animated) {
                            Spacer(Modifier.height(8.dp))
                            Surface(
                                color = MaterialTheme.colorScheme.surfaceVariant,
                                shape = RoundedCornerShape(10.dp),
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp)
                            ) {
                                Column(Modifier.padding(12.dp)) {
                                    Text("Controles de animación", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                                    Spacer(Modifier.height(8.dp))
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Field(
                                            label = "Frames",
                                            value = sprite.animationFrames.toString(),
                                            onChange = { 
                                                val v = it.toIntOrNull()?.coerceIn(1, 128) ?: 1
                                                val updated = sprites.toMutableList()
                                                updated[index] = sprite.copy(animationFrames = v)
                                                vm.config = vm.config.copy(stageDraft = draft.copy(sprites = updated))
                                            },
                                            keyboardType = KeyboardType.Number,
                                            modifier = Modifier.weight(1f)
                                        )
                                        Field(
                                            label = "FPS",
                                            value = sprite.animationFps.toString(),
                                            onChange = { 
                                                val v = it.toIntOrNull()?.coerceIn(1, 60) ?: 24
                                                val updated = sprites.toMutableList()
                                                updated[index] = sprite.copy(animationFps = v)
                                                vm.config = vm.config.copy(stageDraft = draft.copy(sprites = updated))
                                            },
                                            keyboardType = KeyboardType.Number,
                                            modifier = Modifier.weight(1f)
                                        )
                                    }
                                    Spacer(Modifier.height(8.dp))
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                                    ) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text("Loop:", style = MaterialTheme.typography.bodySmall)
                                            Spacer(Modifier.width(8.dp))
                                            Switch(checked = sprite.animationLoop, onCheckedChange = { loop ->
                                                val updated = sprites.toMutableList()
                                                updated[index] = sprite.copy(animationLoop = loop)
                                                vm.config = vm.config.copy(stageDraft = draft.copy(sprites = updated))
                                            })
                                        }
                                        Spacer(Modifier.width(16.dp))
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text("Frame actual: ${currentFrame + 1}", style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.primary)
                                        }
                                        Spacer(Modifier.weight(1f))
                                        OutlinedButton(onClick = {
                                            // Reset animation to frame 0
                                            val updated = sprites.toMutableList()
                                            vm.config = vm.config.copy(stageDraft = draft.copy(sprites = updated))
                                        }) { Text("↺ Reset") }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        // Agregar nuevo sprite
        SectionTitle("Agregar sprite")
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant,
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Nuevo sprite", fontWeight = FontWeight.SemiBold)
                        Text("Elige una imagen PNG para el sprite", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Spacer(Modifier.width(10.dp))
                    OutlinedButton(onClick = { isAddingSprite = true }, enabled = !vm.busy) { Text("+ Elegir imagen") }
                }
            }
        }

        if (isAddingSprite) {
            Spacer(Modifier.height(12.dp))
            Surface(
                color = MaterialTheme.colorScheme.surface,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text("Configurar sprite nuevo", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(12.dp))
                    Field(label = "ID del sprite", value = newSpriteId, onChange = { newSpriteId = it.filter { it.isLetterOrDigit() || it in "-_" } }, hint = "minúsculas, números, guiones")
                    Spacer(Modifier.height(10.dp))
                    Field(label = "Nombre visible", value = newSpriteName, onChange = { newSpriteName = it }, hint = "Nombre en el editor")
                    Spacer(Modifier.height(16.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(newSpriteImage?.displayName ?: "Sin imagen", fontWeight = FontWeight.SemiBold)
                        }
                        Spacer(Modifier.width(10.dp))
                        OutlinedButton(onClick = onPickSprite, enabled = !vm.busy) { Text("Elegir imagen") }
                    }
                    Spacer(Modifier.height(16.dp))
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        OutlinedButton(onClick = { isAddingSprite = false; newSpriteImage = null; newSpriteId = ""; newSpriteName = "" }, enabled = !vm.busy) { Text("Cancelar") }
                        Button(
                            onClick = {
                                newSpriteImage?.let { img ->
                                    val sprite = com.fnfmodmaker.model.StageSprite(
                                        spriteId = newSpriteId.ifEmpty { "sprite-${sprites.size + 1}" },
                                        displayName = newSpriteName.ifEmpty { newSpriteId },
                                        image = img,
                                        x = 0f, y = 0f,
                                        scale = 1f,
                                        rotation = 0f,
                                        layer = com.fnfmodmaker.model.StageSprite.Layer.FOREGROUND
                                    )
                                    val updated = sprites.toMutableList() + sprite
                                    vm.config = vm.config.copy(stageDraft = draft.copy(sprites = updated))
                                    selectedSpriteIndex = updated.size - 1
                                }
                                isAddingSprite = false
                                newSpriteImage = null
                                newSpriteId = ""
                                newSpriteName = ""
                            },
                            enabled = newSpriteImage != null && newSpriteId.isNotBlank() && !vm.busy
                        ) { Text("Agregar") }
                    }
                }
            }
        }
    }

    @Composable
    private fun StagePreviewCanvas(
        backgroundImage: com.fnfmodmaker.model.PickedImage?,
        sprites: List<com.fnfmodmaker.model.StageSprite>,
        selectedIndex: Int?,
        onSpriteClick: (Int) -> Unit,
        onSpriteDrag: (Int, Float, Float) -> Unit,
        onSpriteScale: (Int, Float) -> Unit,
        onSpriteRotate: (Int, Float) -> Unit
    ) {
        // Animación: tiempo actual para frame
        val currentTimeMs = System.currentTimeMillis()
        
        Canvas(modifier = Modifier.fillMaxSize()) {
            val width = size.width
            val height = size.height
            val scale = (width / 1920f).coerceAtMost(height / 1080f)

            // Dibujar fondo (placeholder)
            val bgPaint = androidx.compose.ui.graphics.Paint().apply { color = Color(0xFF16161F) }
            drawRect(bgPaint, size = size)

            // Grid de referencia
            val gridPaint = androidx.compose.ui.graphics.Paint().apply {
                color = Color(0xFF3A3A4A)
                strokeWidth = 1f
                pathEffect = androidx.compose.ui.graphics.PathEffect.makeDashPathEffect(floatArrayOf(10f, 10f), 0f)
            }
            for (x in 0..1920 step 100) {
                val px = x * scale
                drawLine(gridPaint, start = androidx.compose.ui.geometry.Offset(px, 0f), end = androidx.compose.ui.geometry.Offset(px, height))
            }
            for (y in 0..1080 step 100) {
                val py = y * scale
                drawLine(gridPaint, start = androidx.compose.ui.geometry.Offset(0f, py), end = androidx.compose.ui.geometry.Offset(width, py))
            }

            // Dibujar sprites
            sprites.forEachIndexed { index, sprite ->
                sprite.image?.let { _ ->
                    val sx = (sprite.x + 960f) * scale // centrar en 960
                    val sy = (sprite.y + 540f) * scale // centrar en 540
                    val s = sprite.scale * scale
                    val rot = sprite.rotation

                    val paint = androidx.compose.ui.graphics.Paint().apply {
                        color = if (selectedIndex == index) Color(0xFF00FFFF) else Color(0xFFFFFFFF)
                        style = androidx.compose.ui.graphics.Paint.Style.Fill
                        isAntiAlias = true
                    }
                    val strokePaint = androidx.compose.ui.graphics.Paint().apply {
                        color = if (selectedIndex == index) Color(0xFF00FFFF) else Color(0xFFA0A0B0)
                        style = androidx.compose.ui.graphics.Paint.Style.Stroke
                        strokeWidth = if (selectedIndex == index) 3f else 1f
                        isAntiAlias = true
                    }

                    // Rect del sprite (50x50 base * scale)
                    val halfW = 50f * s
                    val halfH = 50f * s

                    // Frame actual si está animado
                    val currentFrame = if (sprite.animated) sprite.getCurrentFrame(currentTimeMs) else 0

                    // Transformación: translate + rotate + scale
                    androidx.compose.runtime.save()
                    translate(sx, sy)
                    rotate(rot)
                    
                    // Dibujar rect principal
                    drawRect(paint, topLeft = androidx.compose.ui.geometry.Offset(-halfW, -halfH), size = androidx.compose.ui.geometry.Size(halfW * 2, halfH * 2))
                    drawRect(strokePaint, topLeft = androidx.compose.ui.geometry.Offset(-halfW, -halfH), size = androidx.compose.ui.geometry.Size(halfW * 2, halfH * 2))

                    // Indicador de frame si animado
                    if (sprite.animated) {
                        val framePaint = androidx.compose.ui.graphics.Paint().apply {
                            color = Color(0xFF00FFFF)
                            style = androidx.compose.ui.graphics.Paint.Style.Fill
                            isAntiAlias = true
                        }
                        val frameStroke = androidx.compose.ui.graphics.Paint().apply {
                            color = Color(0xFF00FFFF)
                            style = androidx.compose.ui.graphics.Paint.Style.Stroke
                            strokeWidth = 2f
                            isAntiAlias = true
                        }
                        // Mini barra de progreso de frame
                        val barWidth = halfW * 2
                        val barHeight = 4f
                        val progress = if (sprite.animationFrames > 1) (currentFrame + 1).toFloat() / sprite.animationFrames else 1f
                        drawRect(
                            frameStroke,
                            topLeft = androidx.compose.ui.geometry.Offset(-halfW, -halfH - barHeight - 4f),
                            size = androidx.compose.ui.geometry.Size(barWidth, barHeight)
                        )
                        drawRect(
                            framePaint,
                            topLeft = androidx.compose.ui.geometry.Offset(-halfW, -halfH - barHeight - 4f),
                            size = androidx.compose.ui.geometry.Size(barWidth * (currentFrame.toFloat() / (sprite.animationFrames - 1).coerceAtLeast(1)), barHeight)
                        )
                        // Número de frame
                        val frameTextPaint = androidx.compose.ui.graphics.Paint().apply {
                            color = Color(0xFF00FFFF)
                            textSize = 10f
                            isAntiAlias = true
                        }
                        drawText(
                            text = androidx.compose.ui.text.AnnotatedString("F${currentFrame + 1}/${sprite.animationFrames}"),
                            topLeft = androidx.compose.ui.geometry.Offset(-halfW, -halfH - barHeight - 18f),
                            style = androidx.compose.ui.text.TextStyle(fontSize = 10.sp, color = Color(0xFF00FFFF))
                        )
                    }

                    // Label
                    val textPaint = androidx.compose.ui.graphics.Paint().apply {
                        color = Color(0xFFFFFFFF)
                        textSize = 12f
                        isAntiAlias = true
                    }
                    drawText(
                        text = androidx.compose.ui.text.AnnotatedString(sprite.spriteId),
                        topLeft = androidx.compose.ui.geometry.Offset(-halfW, -halfH - (if (sprite.animated) 36f else 16f)),
                        style = androidx.compose.ui.text.TextStyle(fontSize = 12.sp, color = Color.White)
                    )
                    androidx.compose.runtime.restore()
                }
            }
        }
    }

    /**
     * Formulario del stage. Se usa en la herramienta suelta y en el wizard.
     */
    @Composable
    internal fun StageForm(
    vm: WizardViewModel,
    draft: com.fnfmodmaker.model.StageDraft,
    onPickBackground: () -> Unit,
    onPickSprite: () -> Unit
) {
    Field(
        label = "ID del stage",
        value = draft.stageId,
        onChange = vm::setStageId,
        hint = "Nombre de archivo: minúsculas, números y guiones (ej. mi-stage)"
    )
    Spacer(Modifier.height(10.dp))
    Field(
        label = "Nombre visible",
        value = draft.displayName,
        onChange = vm::setStageDisplayName,
        hint = "Cómo se llama en el juego."
    )

    SectionTitle("Imagen de fondo")
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
                Text(draft.backgroundImage?.displayName ?: "Sin imagen todavía", fontWeight = FontWeight.SemiBold)
                Text(
                    if (draft.backgroundImage != null) "Se reescalará a máx. 1920px ancho"
                    else "PNG/JPG del fondo del escenario",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.width(10.dp))
            OutlinedButton(onClick = onPickBackground, enabled = !vm.busy) { Text("Elegir fondo") }
        }
    }
    Spacer(Modifier.height(8.dp))
    Field(
        label = "Color de fondo (fallback)",
        value = draft.backgroundColor,
        onChange = vm::setStageBackgroundColor,
        hint = "#RRGGBB. Se usa si la imagen no carga."
    )

    SectionTitle("Cámara")
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Field(label = "Cam X", value = draft.cameraPosition.x.toString(), onChange = vm::setStageCamX, keyboardType = KeyboardType.Decimal, modifier = Modifier.weight(1f))
        Field(label = "Cam Y", value = draft.cameraPosition.y.toString(), onChange = vm::setStageCamY, keyboardType = KeyboardType.Decimal, modifier = Modifier.weight(1f))
    }
    Spacer(Modifier.height(8.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Field(label = "Zoom", value = draft.cameraPosition.zoom.toString(), onChange = vm::setStageCamZoom, keyboardType = KeyboardType.Decimal, modifier = Modifier.weight(1f))
    }
    Spacer(Modifier.height(8.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("Seguir al jugador", fontWeight = FontWeight.SemiBold)
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = draft.cameraPosition.followPlayer, onCheckedChange = vm::setStageCamFollow)
    }

    SectionTitle("Límites de cámara")
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Field(label = "Izq", value = draft.cameraPosition.bounds.left.toString(), onChange = vm::setStageCamBoundLeft, keyboardType = KeyboardType.Decimal, modifier = Modifier.weight(1f))
        Field(label = "Der", value = draft.cameraPosition.bounds.right.toString(), onChange = vm::setStageCamBoundRight, keyboardType = KeyboardType.Decimal, modifier = Modifier.weight(1f))
    }
    Spacer(Modifier.height(8.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Field(label = "Arriba", value = draft.cameraPosition.bounds.top.toString(), onChange = vm::setStageCamBoundTop, keyboardType = KeyboardType.Decimal, modifier = Modifier.weight(1f))
        Field(label = "Abajo", value = draft.cameraPosition.bounds.bottom.toString(), onChange = vm::setStageCamBoundBottom, keyboardType = KeyboardType.Decimal, modifier = Modifier.weight(1f))
    }

    SectionTitle("Sprites del stage (props, foreground, etc.)")
    StageSpriteEditor(
        vm = vm,
        draft = draft,
        onPickSprite = onPickSprite
    )

    Spacer(Modifier.height(10.dp))
    HelpCard(
        "El stage personalizado genera:\n" +
        "• images/stages/<id>/background.png (fondo reescalado a 1920px)\n" +
        "• images/stages/<id>/sprites/ (sprites individuales)\n" +
        "• images/stages/<id>/sprites.xml (atlas Sparrow)\n" +
        "• data/stages/<id>.json (V-Slice)\n" +
        "• stages/<id>.json (Psych Engine)\n\n" +
        "Para usar el stage en una canción, selecciona su ID en el campo 'Escenario' del paso de canción."
    )
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
                status = vm.status,
                error = vm.error,
                saved = null,
                warnings = vm.warnings,
                savedLabel = ""
            )
            Spacer(Modifier.height(24.dp))
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

// ---------------------------------------------------------------------------
// Herramienta: crear un personaje suelto
// ---------------------------------------------------------------------------

@Composable
internal fun CharacterToolScreen(vm: WizardViewModel) {
    val draft = vm.config.character

    val pickSheet = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.pickCharacterSheet(uri)
    }
    val pickIcon = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.pickCharacterIcon(uri)
    }
    val saveZip = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri != null) vm.exportCharacter(uri)
    }
    val pickImportChar = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.importCharacter(uri)
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            ToolTopBar(
                title = "Crear un personaje",
                subtitle = draft.charFolder,
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
                    OutlinedButton(
                        onClick = { pickImportChar.launch(arrayOf("application/zip")) },
                        enabled = !vm.busy
                    ) { Text("📥 Importar ZIP") }
                    Spacer(Modifier.width(8.dp))
                    Button(
                        onClick = { saveZip.launch(vm.characterZipName) },
                        modifier = Modifier.weight(1f),
                        enabled = vm.characterOnlyReady && !vm.busy
                    ) {
                        Text(if (vm.busy) "Generando…" else "Guardar ZIP del personaje")
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
                "Solo se generan los archivos del personaje: no hace falta nombre de mod, " +
                    "canción ni audio.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(8.dp))
            CharacterForm(
                vm = vm,
                draft = draft,
                onPickSheet = { pickSheet.launch(arrayOf("image/*")) },
                onPickIcon = { pickIcon.launch(arrayOf("image/*")) }
            )

            if (!vm.characterOnlyReady && draft.sheet != null) {
                Spacer(Modifier.height(10.dp))
                Text(
                    "Revisa el tamaño de frame y los frames por animación para poder generar.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error
                )
            }

            Spacer(Modifier.height(16.dp))
            ToolStatus(
                status = vm.status,
                error = vm.error,
                saved = vm.characterSaved,
                warnings = vm.warnings,
                savedLabel = "Personaje guardado como"
            )
            Spacer(Modifier.height(24.dp))
        }
    }
}

// ---------------------------------------------------------------------------
// Herramienta: convertir audio a OGG
// ---------------------------------------------------------------------------

@Composable
internal fun AudioToolScreen(vm: WizardViewModel) {
    val audio = vm.toolAudio

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

// ---------------------------------------------------------------------------
// Piezas compartidas de las herramientas
// ---------------------------------------------------------------------------

@Composable
private fun InfoLine(label: String, value: String) {
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
internal fun ToolStatus(
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

