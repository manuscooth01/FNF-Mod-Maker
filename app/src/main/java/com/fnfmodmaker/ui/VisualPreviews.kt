package com.fnfmodmaker.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.fnfmodmaker.model.ALL_DIFFICULTIES
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
internal fun imageThumbnail(path: String?): ImageBitmap? {
    val image by produceState<ImageBitmap?>(null, path) {
        value = if (path == null) null else withContext(Dispatchers.IO) {
            runCatching {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(path, bounds)
                var sample = 1
                while (bounds.outWidth / sample > 800 || bounds.outHeight / sample > 600) sample *= 2
                BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })?.asImageBitmap()
            }.getOrNull()
        }
    }
    return image
}

@Composable
internal fun ImageAssetPreview(path: String?, description: String) {
    if (path == null) return
    val image = imageThumbnail(path)
    if (image == null) Text("Preparando vista previa o imagen no disponible.", style = MaterialTheme.typography.labelSmall)
    else Image(image, description, Modifier.size(120.dp).clip(RoundedCornerShape(16.dp)).background(Color(0xFF252138)), contentScale = ContentScale.Fit)
}

/** A conceptual composition, not a faithful render of engine coordinates or sprite sizes. */
@Composable
internal fun StageScenePreview(path: String?, zoom: Float) {
    val image = imageThumbnail(path)
    Column {
        Text("VISTA DE COMPOSICIÓN · orientativa", color = MaterialTheme.colorScheme.secondary, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelSmall)
        Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(16.dp)).background(Color(0xFF17132A))
            .semantics { contentDescription = "Composición aproximada con fondo y siluetas de referencia; no representa el resultado exacto del juego" }) {
            Box(Modifier.matchParentSize().graphicsLayer { scaleX = zoom; scaleY = zoom }) {
                if (image != null) Image(image, null, Modifier.matchParentSize(), contentScale = ContentScale.Crop)
                Canvas(Modifier.matchParentSize()) {
                    drawRect(Color.Black.copy(alpha = .28f))
                    val heads = listOf(Offset(size.width * .22f, size.height * .51f), Offset(size.width * .50f, size.height * .42f), Offset(size.width * .78f, size.height * .51f))
                    heads.forEachIndexed { i, head ->
                        val radius = size.minDimension * if (i == 1) .075f else .095f
                        val color = listOf(Color(0xFFE465A6), Color(0xFFF4B257), Color(0xFF5ADCF0))[i]
                        drawCircle(color.copy(alpha = .8f), radius, head)
                        drawRoundRect(color.copy(alpha = .68f), topLeft = Offset(head.x - radius * .9f, head.y + radius * .85f),
                            size = androidx.compose.ui.geometry.Size(radius * 1.8f, radius * 1.9f), cornerRadius = androidx.compose.ui.geometry.CornerRadius(radius * .4f))
                    }
                }
            }
            if (image == null) Text("Elige un fondo para verlo aquí", Modifier.align(Alignment.TopCenter).padding(8.dp), color = Color.White, style = MaterialTheme.typography.labelSmall)
        }
        Text("Rival · acompañante · jugador: siluetas de referencia. Zoom simulado; posiciones, tamaño y encuadre reales dependen del motor y los personajes.", style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
internal fun ChartSummaryPreview(vm: WizardViewModel) {
    val song = vm.activeSong
    var difficulty by rememberSaveable(vm.activeSongIndex) { mutableStateOf(song.orderedDifficulties.firstOrNull() ?: "normal") }
    if (difficulty !in song.difficulties) difficulty = song.orderedDifficulties.firstOrNull() ?: "normal"
    val chart = vm.config.getChart(song.songFolder, difficulty)
    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            Text("VISTA DEL CHART", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.secondary)
            Row(Modifier.horizontalScroll(rememberScrollState())) { song.orderedDifficulties.forEach { d ->
                FilterChip(d == difficulty, { difficulty = d }, modifier = Modifier.padding(end = 4.dp), label = { Text(when (d) { "easy" -> "Fácil"; "hard" -> "Difícil"; "normal" -> "Normal"; else -> d }) })
            } }
            if (chart == null) Text(if (song.practiceNotes) "Sin chart propio: se generarán notas de práctica al exportar." else "Sin chart propio: se exportará vacío.", style = MaterialTheme.typography.bodySmall)
            else {
                Text("${chart.notes.size} notas · vista general por tiempo · arriba jugador / abajo rival", style = MaterialTheme.typography.labelSmall)
                val colors = listOf(Color(0xFFC479E8), Color(0xFF54C8F4), Color(0xFF62D884), Color(0xFFFF7388))
                Canvas(Modifier.fillMaxWidth().height(88.dp).semantics { contentDescription = "Vista general de ${chart.notes.size} notas en $difficulty" }) {
                    drawRect(Color(0xFF17132A))
                    val h = size.height / 8f
                    for (line in 1..7) drawLine(Color.White.copy(alpha = .16f), Offset(0f, h * line), Offset(size.width, h * line), 1f)
                    val total = maxOf(song.instrumental?.durationMs ?: 0L, chart.notes.maxOfOrNull { it.endTimeMs } ?: 0L, 1L)
                    chart.notes.forEach { note ->
                        if (note.lane in 0..7 && note.timeMs >= 0) {
                            val x = (note.timeMs.toDouble() / total * size.width).toFloat().coerceIn(0f, size.width)
                            val y = (note.lane + .5f) * h
                            val endX = ((note.endTimeMs.toDouble() / total) * size.width).toFloat().coerceIn(x, size.width)
                            if (note.lengthMs > 0) drawLine(colors[note.lane % 4], Offset(x, y), Offset(endX, y), h * .35f)
                            drawCircle(colors[note.lane % 4], h * .28f, Offset(x, y))
                        }
                    }
                }
            }
        }
    }
}
