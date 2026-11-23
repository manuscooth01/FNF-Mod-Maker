package com.fnfmodmaker.ui

import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Rect
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.fnfmodmaker.model.CharacterDraft
import com.fnfmodmaker.model.gridAnimationRegions
import com.fnfmodmaker.model.placement
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

private enum class PreviewAdjustment(val label: String) {
    POSITION("Personaje"), ANIMATION("Animación"), CAMERA("Cámara")
}

private data class PreviewSource(
    val path: String, val left: Int, val top: Int, val width: Int, val height: Int,
    val isGrid: Boolean
)
private data class PreviewFrame(val image: ImageBitmap, val width: Int, val height: Int)

private fun previewSources(draft: CharacterDraft, animation: String): List<PreviewSource> {
    if (draft.useIndividualFrames) {
        return draft.animationFrames[animation].orEmpty().map {
            PreviewSource(it.image.path, 0, 0, it.width, it.height, false)
        }
    }
    val path = draft.sheet?.path ?: return emptyList()
    return gridAnimationRegions(draft.sheetWidth, draft.sheetHeight, draft.frameWidthValue,
        draft.frameHeightValue, draft.animations, animation).map {
        PreviewSource(path, it.x, it.y, it.width, it.height, true)
    }
}

/** Las miniaturas se generan en IO y con límites de tamaño para no cargar el atlas completo. */
private fun decodePreviewFrames(sources: List<PreviewSource>): List<PreviewFrame>? {
    if (sources.isEmpty()) return emptyList()
    val decoders = mutableMapOf<String, BitmapRegionDecoder>()
    return try {
        sources.map { source ->
            var sample = 1
            while (source.width / sample > 256 || source.height / sample > 256) sample *= 2
            val options = BitmapFactory.Options().apply { inSampleSize = sample }
            val bitmap = if (source.isGrid) {
                val decoder = decoders.getOrPut(source.path) {
                    BitmapRegionDecoder.newInstance(source.path, false)
                        ?: throw IllegalStateException("No se pudo abrir la hoja.")
                }
                decoder.decodeRegion(Rect(source.left, source.top, source.left + source.width,
                    source.top + source.height), options)
            } else BitmapFactory.decodeFile(source.path, options)
            PreviewFrame((bitmap ?: throw IllegalStateException("No se pudo leer un frame.")).asImageBitmap(),
                source.width, source.height)
        }
    } catch (_: Exception) {
        null
    } finally {
        decoders.values.forEach { it.recycle() }
    }
}

/** Escenario orientativo; las coordenadas reales dependen del escenario y del motor. */
@Composable
internal fun CharacterPreview(vm: WizardViewModel, draft: CharacterDraft, animationName: String) {
    val sources = remember(draft.useIndividualFrames, draft.sheet?.path, draft.sheetWidth, draft.sheetHeight,
        draft.frameWidth, draft.frameHeight, draft.animations, draft.animationFrames, animationName) {
        previewSources(draft, animationName)
    }
    var loaded by remember { mutableStateOf(false) }
    val previews by produceState<List<PreviewFrame>?>(initialValue = null, key1 = sources) {
        loaded = false
        value = withContext(Dispatchers.IO) { decodePreviewFrames(sources) }
        loaded = true
    }
    var playing by remember { mutableStateOf(true) }
    var index by remember(sources) { mutableIntStateOf(0) }
    val fps = draft.settingsFor(animationName).fps.toIntOrNull()?.coerceIn(1, 60) ?: 24
    LaunchedEffect(sources, fps, playing) {
        if (playing && sources.size > 1) {
            while (true) {
                delay((1000L / fps).coerceAtLeast(16L))
                index = (index + 1) % sources.size
            }
        }
    }
    val currentFrame = if (loaded) previews?.getOrNull(index) else null
    val reference = remember(draft.useIndividualFrames, draft.sheet?.path, draft.sheetWidth, draft.sheetHeight,
        draft.frameWidth, draft.frameHeight, draft.animations, draft.animationFrames) {
        previewSources(draft, "idle").firstOrNull()
    }
    var adjustment by remember { mutableStateOf(PreviewAdjustment.POSITION) }
    var canvasWidth by remember { mutableIntStateOf(0) }
    val currentDraft by rememberUpdatedState(draft)
    val settings = draft.settingsFor(animationName)
    val position = draft.placement()
    val scaleValue = draft.scaleValue.takeIf { it.isFinite() && it > 0f }?.coerceAtMost(20f) ?: 1f
    val animationX = settings.offsetX.toIntOrNull() ?: 0
    val animationY = settings.offsetY.toIntOrNull() ?: 0

    Column {
        SectionTitle("Vista previa y posiciones")
        Text("Escenario de referencia: arrastra para ajustar. El resultado final depende del escenario y del motor.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            PreviewAdjustment.entries.forEach { option ->
                FilterChip(selected = adjustment == option, onClick = { adjustment = option },
                    enabled = !vm.busy, label = { Text(option.label) })
            }
        }
        Canvas(
            Modifier.fillMaxWidth().aspectRatio(16f / 9f)
                .onSizeChanged { canvasWidth = it.width }
                .semantics { contentDescription = "Vista previa del personaje; arrastra para ajustar ${adjustment.label}" }
                .pointerInput(adjustment, canvasWidth, animationName, vm.busy) {
                    var startX = 0f
                    var startY = 0f
                    var totalX = 0f
                    var totalY = 0f
                    detectDragGestures(onDragStart = {
                        val initial = currentDraft
                        val initialSettings = initial.settingsFor(animationName)
                        when (adjustment) {
                            PreviewAdjustment.POSITION -> { startX = initial.positionX.toFloatOrNull() ?: 0f; startY = initial.positionY.toFloatOrNull() ?: 0f }
                            PreviewAdjustment.CAMERA -> { startX = initial.cameraX.toFloatOrNull() ?: 0f; startY = initial.cameraY.toFloatOrNull() ?: 0f }
                            PreviewAdjustment.ANIMATION -> { startX = initialSettings.offsetX.toFloatOrNull() ?: 0f; startY = initialSettings.offsetY.toFloatOrNull() ?: 0f }
                        }
                        totalX = 0f
                        totalY = 0f
                    }) { change, amount ->
                        if (!vm.busy && canvasWidth > 0) {
                            change.consume()
                            val pixelScale = canvasWidth / 1280f
                            val animScale = currentDraft.scaleValue.takeIf { it.isFinite() && it > 0f }?.coerceAtMost(20f) ?: 1f
                            val factor = if (adjustment == PreviewAdjustment.ANIMATION) -1f / animScale else 1f
                            totalX += amount.x / pixelScale * factor
                            totalY += amount.y / pixelScale * factor
                            val x = (startX + totalX).coerceIn(-999999f, 999999f).roundToInt().toString()
                            val y = (startY + totalY).coerceIn(-999999f, 999999f).roundToInt().toString()
                            when (adjustment) {
                                PreviewAdjustment.POSITION -> vm.setCharacterPosition(x, y)
                                PreviewAdjustment.CAMERA -> vm.setCharacterCamera(x, y)
                                PreviewAdjustment.ANIMATION -> vm.updateCharacterAnimation(animationName) { it.copy(offsetX = x, offsetY = y) }
                            }
                        }
                    }
                }
        ) {
            val k = size.width / 1280f
            drawRect(Color(0xFF1D2032))
            drawRect(Color(0xFF292E42), topLeft = Offset(0f, 570f * k), size = Size(size.width, size.height - 570f * k))
            drawLine(Color(0xFF5C6482), Offset(0f, 570f * k), Offset(size.width, 570f * k), strokeWidth = 2f)
            val baseX = 450f + position.x
            val baseY = 120f + position.y
            if (currentFrame != null) {
                val width = (currentFrame.width * scaleValue * k).coerceIn(1f, 100000f)
                val height = (currentFrame.height * scaleValue * k).coerceIn(1f, 100000f)
                val x = (baseX - animationX * scaleValue) * k
                val y = (baseY - animationY * scaleValue) * k
                clipRect {
                    if (draft.flipX) {
                        scale(scaleX = -1f, scaleY = 1f, pivot = Offset(x + width / 2f, y + height / 2f)) {
                            drawImage(currentFrame.image, dstOffset = IntOffset(x.roundToInt(), y.roundToInt()),
                                dstSize = IntSize(width.roundToInt(), height.roundToInt()))
                        }
                    } else {
                        drawImage(currentFrame.image, dstOffset = IntOffset(x.roundToInt(), y.roundToInt()),
                            dstSize = IntSize(width.roundToInt(), height.roundToInt()))
                    }
                }
            }
            // El foco es orientativo: no sigue los offsets específicos de una animación.
            val centerX = (baseX + (reference?.width ?: 180) * scaleValue / 2f + position.cameraX) * k
            val centerY = (baseY + (reference?.height ?: 180) * scaleValue / 2f + position.cameraY) * k
            drawCircle(Color(0xFFFFD75E), radius = 8f * k, center = Offset(centerX, centerY))
            drawLine(Color(0xFFFFD75E), Offset(centerX - 18f * k, centerY), Offset(centerX + 18f * k, centerY), 2f)
            drawLine(Color(0xFFFFD75E), Offset(centerX, centerY - 18f * k), Offset(centerX, centerY + 18f * k), 2f)
        }
        when {
            sources.isEmpty() -> Text("Añade frames válidos para previsualizar $animationName.", style = MaterialTheme.typography.bodySmall)
            !loaded -> Text("Cargando vista previa…", style = MaterialTheme.typography.bodySmall)
            previews == null -> Text("No se pudo cargar la vista previa; comprueba los archivos.", color = MaterialTheme.colorScheme.error)
            else -> Row {
                TextButton(onClick = { playing = !playing }) { Text(if (playing) "Pausar" else "Reproducir") }
                TextButton(onClick = { playing = false; index = (index - 1 + sources.size) % sources.size }) { Text("‹ Frame") }
                TextButton(onClick = { playing = false; index = (index + 1) % sources.size }) { Text("Frame ›") }
                Text("${index + 1}/${sources.size}", style = MaterialTheme.typography.bodySmall)
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}
