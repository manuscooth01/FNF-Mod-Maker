package com.fnfmodmaker.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
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

/** Each lane uses the same instrumental clock, width and cursor; offsets shift voices. */
@Composable
internal fun ChartWaveform(song: SongConfig, durationMs: Long, grid: TouchGrid, page: Int, positionMs: Long, onPage: (Int) -> Unit) {
    val mix = song.chartAudioMix
    Column {
        song.instrumental?.let { WaveRow("Instrumental", it, durationMs, 0, grid, page, positionMs, onPage) }
        song.vocalsPlayer?.let { WaveRow("Voz jugador", it, durationMs, mix.player.offsetMs - mix.instrumental.offsetMs, grid, page, positionMs, onPage) }
        song.vocalsOpponent?.let { WaveRow("Voz rival", it, durationMs, mix.opponent.offsetMs - mix.instrumental.offsetMs, grid, page, positionMs, onPage) }
        Text("Misma escala y cursor; toca una onda para ir a esa página.", style = MaterialTheme.typography.labelSmall)
    }
}
@Composable
private fun WaveRow(label: String, audio: PickedAudio, durationMs: Long, relativeOffset: Int, grid: TouchGrid, page: Int, positionMs: Long, onPage: (Int) -> Unit) {
    val sourceDuration = audio.durationMs.takeIf { it > 0 } ?: (durationMs + kotlin.math.abs(relativeOffset.toLong()))
    val state by produceState<WaveState>(WaveState.Loading, audio.path, sourceDuration) {
        value = WaveState.Loading
        try { value = WaveState.Ready(withContext(Dispatchers.IO) { decodeWaveform(audio.path, sourceDuration) }) }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { value = WaveState.Failed(e.message ?: "No se pudo leer la onda.") }
    }
    Text(label, style = MaterialTheme.typography.labelSmall)
    when (val wave = state) {
        WaveState.Loading -> Text("Calculando onda…", style = MaterialTheme.typography.labelSmall)
        is WaveState.Failed -> Text("Onda no disponible: ${wave.message}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
        is WaveState.Ready -> Canvas(Modifier.fillMaxWidth().height(30.dp)
            .semantics { contentDescription = "Onda de $label, vista general; toca para saltar a otra página." }
            .pointerInput(grid, durationMs) { detectTapGestures { onPage(grid.pageForTime((it.x / size.width * durationMs).toLong())) } }) {
            drawRect(Color(0xFF171322))
            val start = grid.pageStart(page).toDouble() / durationMs * size.width
            val endTime = (grid.pageStart(page) + (grid.stepMs * grid.rows).toLong()).coerceAtMost(durationMs)
            val end = endTime.toDouble() / durationMs * size.width
            drawRect(Color(0xFF7B4C99).copy(alpha = .35f), Offset(start.toFloat(), 0f), Size((end - start).toFloat().coerceAtLeast(1f), size.height))
            wave.values.forEachIndexed { index, peak ->
                val time = index.toDouble() / wave.values.size * sourceDuration + relativeOffset
                val x = (time / durationMs * size.width).toFloat()
                if (x in 0f..size.width) {
                    val half = peak * size.height * .45f
                    drawLine(Color(0xFF5ADCF0), Offset(x, size.height / 2f - half), Offset(x, size.height / 2f + half), 1f)
                }
            }
            val cursor = (positionMs.toDouble() / durationMs * size.width).toFloat().coerceIn(0f, size.width)
            drawLine(Color.White, Offset(cursor, 0f), Offset(cursor, size.height), 2f)
        }
    }
}
