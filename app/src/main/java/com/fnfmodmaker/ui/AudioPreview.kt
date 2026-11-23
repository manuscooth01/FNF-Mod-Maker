package com.fnfmodmaker.ui

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.fnfmodmaker.model.PickedAudio
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

/** One player per screen; no simultaneous previews. Never plays or changes the exported file. */
internal class AudioPreviewController(context: Context, private val scope: CoroutineScope) {
    private val manager = context.applicationContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var player: MediaPlayer? = null
    private var focus = false
    private var timer: Job? = null
    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        if (change <= 0) stop()
    }
    var path by mutableStateOf<String?>(null)
        private set
    var loading by mutableStateOf(false)
        private set
    var playing by mutableStateOf(false)
        private set
    var position by mutableLongStateOf(0L)
        private set
    var duration by mutableLongStateOf(0L)
        private set
    var error by mutableStateOf<String?>(null)
        private set

    @Suppress("DEPRECATION")
    private fun requestFocus(): Boolean {
        if (focus) return true
        focus = manager.requestAudioFocus(focusListener, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        return focus
    }
    @Suppress("DEPRECATION")
    fun stop() {
        timer?.cancel(); timer = null
        val old = player; player = null
        runCatching { old?.release() }
        if (focus) manager.abandonAudioFocus(focusListener)
        focus = false; path = null; loading = false; playing = false; position = 0; duration = 0
    }
    fun play(audio: PickedAudio) {
        if (path == audio.path && playing) { pause(); return }
        if (path == audio.path && !loading && player != null) {
            if (!requestFocus()) { error = "No se pudo obtener el audio."; return }
            try { player!!.start(); playing = true; poll(player!!) } catch (_: Exception) { stop(); error = "No se pudo reanudar el audio." }
            return
        }
        stop(); error = null
        if (!File(audio.path).isFile) { error = "El archivo ya no está disponible. Vuelve a elegirlo."; return }
        if (!requestFocus()) { error = "No se pudo obtener el audio."; return }
        val next = MediaPlayer(); player = next; path = audio.path; loading = true
        try {
            next.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
            next.setDataSource(audio.path)
            next.setOnPreparedListener { mp ->
                if (player === mp) try {
                    duration = mp.duration.toLong().coerceAtLeast(0); mp.start(); loading = false; playing = true; poll(mp)
                } catch (_: Exception) { stop(); error = "No se pudo reproducir este audio en el dispositivo." }
            }
            next.setOnCompletionListener { if (player === it) stop() }
            next.setOnErrorListener { mp, _, _ ->
                if (player === mp) { stop(); error = "No se pudo reproducir este audio en el dispositivo." }
                true
            }
            next.prepareAsync()
        } catch (_: Exception) { stop(); error = "No se pudo abrir este audio." }
    }
    private fun poll(mp: MediaPlayer) {
        timer?.cancel()
        timer = scope.launch {
            while (player === mp && playing) {
                position = runCatching { mp.currentPosition.toLong() }.getOrDefault(position)
                delay(150)
            }
        }
    }
    fun pause() {
        if (loading) { stop(); return }
        val mp = player ?: return
        runCatching { position = mp.currentPosition.toLong(); mp.pause() }
        timer?.cancel(); playing = false
        @Suppress("DEPRECATION")
        if (focus) manager.abandonAudioFocus(focusListener)
        focus = false
    }
    fun seek(value: Long) {
        if (loading || duration <= 0) return
        val target = value.coerceIn(0, duration).coerceAtMost(Int.MAX_VALUE.toLong())
        runCatching { player?.seekTo(target.toInt()); position = target }
    }
}

@Composable
internal fun rememberAudioPreview(): AudioPreviewController {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val owner = LocalLifecycleOwner.current
    val controller = remember(context, scope) { AudioPreviewController(context, scope) }
    DisposableEffect(owner, controller) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) controller.stop() }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer); controller.stop() }
    }
    return controller
}

private fun audioClock(ms: Long) = "%d:%02d".format(ms / 60000, (ms / 1000) % 60)

@Composable
internal fun AudioPreviewControls(controller: AudioPreviewController, audio: PickedAudio, enabled: Boolean = true) {
    val selected = controller.path == audio.path
    Column(Modifier.fillMaxWidth().padding(top = 4.dp)) {
        Row {
            Button(onClick = { controller.play(audio) }, enabled = enabled && !(selected && controller.loading)) {
                Text(if (selected && controller.loading) "Preparando…" else if (selected && controller.playing) "❚❚ Pausar" else "▶ Escuchar")
            }
            if (selected) androidx.compose.material3.TextButton(onClick = controller::stop) { Text("Detener") }
        }
        if (selected) {
            Text("${audioClock(controller.position)} / ${audioClock(controller.duration)}", style = MaterialTheme.typography.labelSmall)
            Slider(value = controller.position.coerceIn(0L, controller.duration.coerceAtLeast(1L)).toFloat(),
                onValueChange = { controller.seek(it.toLong()) }, valueRange = 0f..controller.duration.coerceAtLeast(1).toFloat(),
                enabled = !controller.loading && controller.duration > 0)
        }
    }
}
