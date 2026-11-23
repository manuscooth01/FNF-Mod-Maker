package com.fnfmodmaker.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.fnfmodmaker.media.decodeWaveform
import com.fnfmodmaker.model.PickedAudio
import com.fnfmodmaker.model.SongConfig
import com.fnfmodmaker.model.TouchGrid
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private sealed interface WaveState {
    data object Loading : WaveState
    data class Ready(val values: FloatArray) : WaveState
    data class Failed(val message: String) : WaveState
}

/** Envelope is decoded once per selected track, never on each redraw. */
@Composable
internal fun ChartWaveform(song: SongConfig, durationMs: Long, grid: TouchGrid, page: Int, positionMs: Long, onPage: (Int) -> Unit) {
    val tracks = listOfNotNull(song.instrumental?.let { "Instrumental" to it }, song.vocalsPlayer?.let { "Voz jugador" to it }, song.vocalsOpponent?.let { "Voz rival" to it })
    var choice by rememberSaveable(song.songFolder) { mutableStateOf("Instrumental") }
    val selected = tracks.firstOrNull { it.first == choice } ?: tracks.firstOrNull()
    val audio: PickedAudio = selected?.second ?: return
    val state by produceState<WaveState>(WaveState.Loading, audio.path, durationMs) {
        value = WaveState.Loading
        try { value = WaveState.Ready(withContext(Dispatchers.IO) { decodeWaveform(audio.path, durationMs) }) }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { value = WaveState.Failed(e.message ?: "No se pudo leer la onda.") }
    }
    Column {
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            tracks.forEach { (label, _) -> FilterChip(selected.first == label, { choice = label }, label = { Text(label, style = MaterialTheme.typography.labelSmall) }) }
        }
        when (val wave = state) {
            WaveState.Loading -> Text("Calculando onda de ${selected.first}…", style = MaterialTheme.typography.labelSmall)
            is WaveState.Failed -> Text("Onda no disponible: ${wave.message}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
            is WaveState.Ready -> {
                Canvas(Modifier.fillMaxWidth().height(42.dp)
                    .semantics { contentDescription = "Onda de ${selected.first}, vista general; toca para saltar a otra página." }
                    .pointerInput(grid, durationMs) { detectTapGestures { offset -> onPage(grid.pageForTime((offset.x / size.width * durationMs).toLong())) } }) {
                    drawRect(Color(0xFF171322))
                    val start = grid.pageStart(page).toDouble() / durationMs * size.width
                    val endTime = (grid.pageStart(page) + (grid.stepMs * grid.rows).toLong()).coerceAtMost(durationMs)
                    val end = endTime.toDouble() / durationMs * size.width
                    drawRect(Color(0xFF7B4C99).copy(alpha = .35f), Offset(start.toFloat(), 0f), Size((end - start).toFloat().coerceAtLeast(1f), size.height))
                    val values = wave.values
                    values.forEachIndexed { index, peak ->
                        val x = index.toFloat() / values.size * size.width
                        val half = peak * size.height * .45f
                        drawLine(Color(0xFF5ADCF0), Offset(x, size.height / 2f - half), Offset(x, size.height / 2f + half), 1f)
                    }
                    val cursor = (positionMs.toDouble() / durationMs * size.width).toFloat().coerceIn(0f, size.width)
                    drawLine(Color.White, Offset(cursor, 0f), Offset(cursor, size.height), 2f)
                }
            }
        }
        Text("Vista general; toca la onda para navegar. Voces solo se visualizan: no se mezclan ni sincronizan en reproducción.", style = MaterialTheme.typography.labelSmall)
    }
}
