package com.fnfmodmaker.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.fnfmodmaker.model.PreviewTrack
import com.fnfmodmaker.model.track

@Composable
internal fun ChartAudioControls(vm: WizardViewModel, onClose: () -> Unit) {
    val song = vm.liveSong ?: return
    val tracks = listOfNotNull(song.instrumental?.let { PreviewTrack.INSTRUMENTAL to "Instrumental" },
        song.vocalsPlayer?.let { PreviewTrack.PLAYER to "Voz jugador" }, song.vocalsOpponent?.let { PreviewTrack.OPPONENT to "Voz rival" })
    AlertDialog(onDismissRequest = onClose, title = { Text("Mezcla de escucha") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            Text("Ajustes solo de previsualización, no alteran los archivos exportados. Pausa para cambiar offsets.", style = MaterialTheme.typography.labelSmall)
            tracks.forEach { (track, label) ->
                val setting = song.chartAudioMix.track(track)
                Text("$label · ${if (setting.muted) "silenciada" else "${(setting.volume * 100).toInt()}%"} · offset ${setting.offsetMs} ms", style = MaterialTheme.typography.labelMedium)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = { vm.updatePreviewMix(track) { it.copy(muted = !it.muted) } }, enabled = !vm.liveLoading) { Text(if (setting.muted) "Activar" else "Silenciar") }
                    Slider(value = setting.volume.coerceIn(0f, 1f), onValueChange = { value -> vm.updatePreviewMix(track) { it.copy(volume = value) } }, enabled = !vm.liveLoading, modifier = Modifier.weight(1f))
                }
                Row(Modifier.horizontalScroll(rememberScrollState())) {
                    listOf(-100, -10, 10, 100).forEach { delta ->
                        TextButton(onClick = { vm.updatePreviewMix(track) { it.copy(offsetMs = it.offsetMs + delta) } }, enabled = !vm.livePlaying && !vm.liveLoading) { Text(if (delta > 0) "+$delta" else "$delta") }
                    }
                    TextButton(onClick = { vm.updatePreviewMix(track) { it.copy(offsetMs = 0) } }, enabled = !vm.livePlaying && !vm.liveLoading) { Text("0") }
                }
            }
            Text("Offset positivo retrasa la pista respecto a las demás. La instrumental es el reloj maestro; la sincronización depende del dispositivo.", style = MaterialTheme.typography.labelSmall)
        }
    }, confirmButton = { TextButton(onClick = onClose) { Text("Listo") } })
}
