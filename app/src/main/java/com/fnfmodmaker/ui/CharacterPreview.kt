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
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
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
import com.fnfmodmaker.model.CharacterAdjustment
import com.fnfmodmaker.model.CharacterDraft
import com.fnfmodmaker.model.atlasFramesForPrefix
import com.fnfmodmaker.model.gridAnimationRegions
import com.fnfmodmaker.model.placement
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

private data class PreviewSource(
    val path: String, val left: Int, val top: Int, val width: Int, val height: Int,
    val isGrid: Boolean, val frameX: Int = 0, val frameY: Int = 0,
    val frameWidth: Int = width, val frameHeight: Int = height
)
private data class PreviewFrame(val image: ImageBitmap, val width: Int, val height: Int,
    val frameX: Int, val frameY: Int, val frameWidth: Int, val frameHeight: Int)

private fun previewSources(draft: CharacterDraft, animation: String): List<PreviewSource> {
    if (draft.useAtlas) {
        val path = draft.atlasSheet?.path ?: return emptyList()
        return atlasFramesForPrefix(draft.atlasFrames, draft.prefixFor(animation)).take(100).map {
            PreviewSource(path, it.x, it.y, it.width, it.height, true, it.frameX, it.frameY, it.frameWidth, it.frameHeight)
        }
    }
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
                source.width, source.height, source.frameX, source.frameY, source.frameWidth, source.frameHeight)
        }
    } catch (_: Exception) {
        null
    } finally {
        decoders.values.forEach { it.recycle() }
    }
}

@Composable
internal fun CharacterPreviewPane(
    vm: WizardViewModel, draft: CharacterDraft, animationName: String,
    adjustment: CharacterAdjustment, onAdjustmentChanged: (CharacterAdjustment) -> Unit,
    zoom: Float, expanded: Boolean, onExpand: () -> Unit = {}
) {
    val sources = remember(draft.useAtlas, draft.atlasSheet?.path, draft.atlasFrames, draft.atlasPrefixes, draft.useIndividualFrames, draft.sheet?.path, draft.sheetWidth, draft.sheetHeight,
        draft.frameWidth, draft.frameHeight, draft.animations, draft.animationFrames, animationName) {
        previewSources(draft, animationName)
    }
    var loaded by remember { mutableStateOf(false) }
    val previews by produceState<List<PreviewFrame>?>(initialValue = null, key1 = sources) {
        loaded = false
        value = withContext(Dispatchers.IO) { decodePreviewFrames(sources) }
        loaded = true
    }
    var playing by remember(sources) { mutableStateOf(true) }
    var index by remember(sources) { mutableIntStateOf(0) }
    val fps = draft.settingsFor(animationName).fps.toIntOrNull()?.coerceIn(1, 60) ?: 24
    val loop = draft.settingsFor(animationName).loop
    LaunchedEffect(sources, fps, playing, loop) {
        if (playing && sources.size > 1) {
            while (true) {
                delay((1000L / fps).coerceAtLeast(16L))
                if (index == sources.lastIndex && !loop) { playing = false; break }
                index = (index + 1) % sources.size
            }
        }
    }
    val currentFrame = if (loaded) previews?.getOrNull(index) else null
    val reference = remember(draft.useAtlas, draft.atlasSheet?.path, draft.atlasFrames, draft.atlasPrefixes, draft.useIndividualFrames, draft.sheet?.path, draft.sheetWidth, draft.sheetHeight,
        draft.frameWidth, draft.frameHeight, draft.animations, draft.animationFrames) {
        previewSources(draft, "idle").firstOrNull()
    }
    var canvasWidth by remember { mutableIntStateOf(0) }
    val currentDraft by rememberUpdatedState(draft)
    val settings = draft.settingsFor(animationName)
    val position = draft.placement()
    val scaleValue = draft.scaleValue.takeIf { it.isFinite() && it > 0f }?.coerceAtMost(20f) ?: 1f
    val animationX = settings.offsetX.toIntOrNull() ?: 0
    val animationY = settings.offsetY.toIntOrNull() ?: 0

    Column {
        if (!expanded) {
            SectionTitle("Vista previa y posiciones")
            Text("Escenario de referencia: arrastra para ajustar. El resultado final depende del escenario y del motor.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedButton(onClick = onExpand, enabled = !vm.busy) { Text("Abrir editor visual a pantalla completa") }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                CharacterAdjustment.entries.forEach { option ->
                    FilterChip(selected = adjustment == option, onClick = { onAdjustmentChanged(option) },
                        enabled = !vm.busy, label = { Text(option.label) })
                }
            }
        }
        Canvas(
            Modifier.fillMaxWidth().aspectRatio(16f / 9f)
                .onSizeChanged { canvasWidth = it.width }
                .semantics { contentDescription = "Vista previa del personaje; arrastra para ajustar ${adjustment.label}" }
                .pointerInput(adjustment, canvasWidth, animationName, vm.busy, zoom) {
                    var startX = 0f
                    var startY = 0f
                    var totalX = 0f
                    var totalY = 0f
                    detectDragGestures(onDragStart = {
                        val initial = currentDraft
                        val initialSettings = initial.settingsFor(animationName)
                        when (adjustment) {
                            CharacterAdjustment.POSITION -> { startX = initial.positionX.toFloatOrNull() ?: 0f; startY = initial.positionY.toFloatOrNull() ?: 0f }
                            CharacterAdjustment.CAMERA -> { startX = initial.cameraX.toFloatOrNull() ?: 0f; startY = initial.cameraY.toFloatOrNull() ?: 0f }
                            CharacterAdjustment.ANIMATION -> { startX = initialSettings.offsetX.toFloatOrNull() ?: 0f; startY = initialSettings.offsetY.toFloatOrNull() ?: 0f }
                        }
                        totalX = 0f
                        totalY = 0f
                    }) { change, amount ->
                        if (!vm.busy && canvasWidth > 0) {
                            change.consume()
                            val pixelScale = canvasWidth / 1280f * zoom
                            val animScale = currentDraft.scaleValue.takeIf { it.isFinite() && it > 0f }?.coerceAtMost(20f) ?: 1f
                            val factor = if (adjustment == CharacterAdjustment.ANIMATION) -1f / animScale else 1f
                            totalX += amount.x / pixelScale * factor
                            totalY += amount.y / pixelScale * factor
                            val x = (startX + totalX).coerceIn(-999999f, 999999f).roundToInt().toString()
                            val y = (startY + totalY).coerceIn(-999999f, 999999f).roundToInt().toString()
                            when (adjustment) {
                                CharacterAdjustment.POSITION -> vm.setCharacterPosition(x, y)
                                CharacterAdjustment.CAMERA -> vm.setCharacterCamera(x, y)
                                CharacterAdjustment.ANIMATION -> vm.updateCharacterAnimation(animationName) { it.copy(offsetX = x, offsetY = y) }
                            }
                        }
                    }
                }
        ) {
            val k = size.width / 1280f
            drawRect(Color(0xFF08070C))
            clipRect {
              scale(scaleX = zoom, scaleY = zoom, pivot = Offset(size.width / 2f, size.height / 2f)) {
            drawReferenceStage(k)
            val baseX = 450f + position.x
            val baseY = 120f + position.y
            if (currentFrame != null) {
                val width = (currentFrame.width * scaleValue * k).coerceIn(1f, 100000f)
                val height = (currentFrame.height * scaleValue * k).coerceIn(1f, 100000f)
                val logicalX = (baseX - animationX * scaleValue) * k
                val x = logicalX - currentFrame.frameX * scaleValue * k
                val y = (baseY - animationY * scaleValue - currentFrame.frameY * scaleValue) * k
                clipRect {
                    if (draft.flipX) {
                        scale(scaleX = -1f, scaleY = 1f, pivot = Offset(logicalX + currentFrame.frameWidth * scaleValue * k / 2f, y + height / 2f)) {
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
            }
        }
        when {
            sources.isEmpty() -> Text("Añade frames válidos para previsualizar $animationName.", style = MaterialTheme.typography.bodySmall)
            !loaded -> Text("Cargando vista previa…", style = MaterialTheme.typography.bodySmall)
            previews == null -> Text("No se pudo cargar la vista previa; comprueba los archivos.", color = MaterialTheme.colorScheme.error)
            else -> Row {
                TextButton(onClick = { if (!playing && index == sources.lastIndex) index = 0; playing = !playing }) { Text(if (playing) "Pausar" else "Reproducir") }
                TextButton(onClick = { playing = false; index = (index - 1 + sources.size) % sources.size }) { Text("‹ Frame") }
                TextButton(onClick = { playing = false; index = (index + 1) % sources.size }) { Text("Frame ›") }
                Text("${index + 1}/${sources.size}", style = MaterialTheme.typography.bodySmall)
            }
        }
        if (draft.useAtlas && atlasFramesForPrefix(draft.atlasFrames, draft.prefixFor(animationName)).size > 100) {
            Text("Vista previa limitada a los primeros 100 frames; el XML completo se exportará.", style = MaterialTheme.typography.labelSmall)
        }
        Spacer(Modifier.height(8.dp))
    }
}

/** Escenario de referencia original, inspirado en un escenario de concierto. */
private fun DrawScope.drawReferenceStage(k: Float) {
    drawRect(Color(0xFF100D16))
    drawRect(Color(0xFF33283C), Offset(85f * k, 65f * k), Size(1110f * k, 580f * k))
    for (i in 0..8) {
        val x = (120f + i * 135f) * k
        drawRect(Color(0xFF382B40), Offset(x, 65f * k), Size(55f * k, 405f * k))
    }
    val floor = Path().apply {
        moveTo(70f * k, 475f * k); lineTo(1210f * k, 475f * k)
        lineTo(1155f * k, 650f * k); lineTo(125f * k, 650f * k); close()
    }
    drawPath(floor, Color(0xFF60495B))
    for (i in 0..9) {
        val x = (90f + i * 123f) * k
        drawLine(Color(0xFF826877), Offset(x, 475f * k), Offset((x / k - 20f) * k, 650f * k), 2f * k)
    }
    drawRect(Color(0xFF49304F), Offset(110f * k, 650f * k), Size(1060f * k, 45f * k))
    drawLine(Color(0xFFB1939C), Offset(110f * k, 650f * k), Offset(1170f * k, 650f * k), 3f * k)
    for (i in 0..8) {
        val x = (160f + i * 120f) * k
        val y = (635f - kotlin.math.abs(4 - i) * 13f) * k
        drawCircle(Color(0xFFB79F5D), 14f * k, Offset(x, y))
        drawCircle(Color(0xFFFFF2B5), 9f * k, Offset(x, y))
    }
}
