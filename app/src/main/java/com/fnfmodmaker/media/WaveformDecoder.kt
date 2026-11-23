package com.fnfmodmaker.media

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import com.fnfmodmaker.model.WaveformPeaks
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File
import java.nio.ByteOrder
import kotlin.math.abs

/** Streaming decode to bounded peaks; PCM buffers are released after each codec output. */
internal suspend fun decodeWaveform(path: String, durationMs: Long): FloatArray {
    require(File(path).isFile && durationMs in 1..86_400_000L) { "Audio ausente o duración no compatible." }
    val context = currentCoroutineContext()
    val extractor = MediaExtractor()
    var codec: MediaCodec? = null
    try {
        extractor.setDataSource(path)
        val track = (0 until extractor.trackCount).firstOrNull {
            extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
        } ?: error("No hay pista de audio compatible.")
        extractor.selectTrack(track)
        val format = extractor.getTrackFormat(track)
        val mime = format.getString(MediaFormat.KEY_MIME) ?: error("No se reconoce el formato de audio.")
        val decoder = MediaCodec.createDecoderByType(mime); codec = decoder
        decoder.configure(format, null, null, 0); decoder.start()
        val peaks = WaveformPeaks(durationMs * 1000L)
        val info = MediaCodec.BufferInfo()
        var rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        var encoding = AudioFormat.ENCODING_PCM_16BIT
        var inputDone = false; var outputDone = false
        var lastProgress = System.nanoTime()
        while (!outputDone) {
            context.ensureActive()
            check(System.nanoTime() - lastProgress < 30_000_000_000L) { "La lectura de audio no respondió." }
            if (!inputDone) {
                val index = decoder.dequeueInputBuffer(10_000)
                if (index >= 0) {
                    lastProgress = System.nanoTime()
                    val buffer = decoder.getInputBuffer(index) ?: error("No se pudo leer el audio.")
                    buffer.clear()
                    val count = extractor.readSampleData(buffer, 0)
                    if (count < 0) { decoder.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inputDone = true }
                    else { decoder.queueInputBuffer(index, 0, count, extractor.sampleTime.coerceAtLeast(0L), 0); extractor.advance() }
                }
            }
            val index = decoder.dequeueOutputBuffer(info, 10_000)
            if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                lastProgress = System.nanoTime()
                val out = decoder.outputFormat
                rate = out.getInteger(MediaFormat.KEY_SAMPLE_RATE); channels = out.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                encoding = if (out.containsKey(MediaFormat.KEY_PCM_ENCODING)) out.getInteger(MediaFormat.KEY_PCM_ENCODING) else AudioFormat.ENCODING_PCM_16BIT
            } else if (index >= 0) {
                lastProgress = System.nanoTime()
                try {
                    val bytes = when (encoding) { AudioFormat.ENCODING_PCM_16BIT -> 2; AudioFormat.ENCODING_PCM_8BIT -> 1; AudioFormat.ENCODING_PCM_FLOAT -> 4; else -> error("Formato PCM no compatible para la onda.") }
                    require(rate in 1..384000 && channels in 1..32) { "Parámetros de audio no compatibles." }
                    val buffer = decoder.getOutputBuffer(index)?.duplicate()?.order(ByteOrder.nativeOrder())
                    if (buffer != null && info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                        val frames = info.size / (bytes * channels)
                        val stride = (rate / 400).coerceAtLeast(1)
                        var windowPeak = 0f
                        var windowStart = 0
                        for (frame in 0 until frames) {
                            var amplitude = 0f
                            for (channel in 0 until channels) {
                                val at = info.offset + (frame * channels + channel) * bytes
                                if (at < 0 || at + bytes > buffer.limit()) continue
                                val value = when (encoding) {
                                    AudioFormat.ENCODING_PCM_16BIT -> buffer.getShort(at) / 32768f
                                    AudioFormat.ENCODING_PCM_8BIT -> ((buffer.get(at).toInt() and 255) - 128) / 128f
                                    else -> buffer.getFloat(at)
                                }
                                if (value.isFinite()) amplitude = maxOf(amplitude, abs(value))
                            }
                            windowPeak = maxOf(windowPeak, amplitude)
                            if (frame % stride == stride - 1 || frame == frames - 1) {
                                peaks.add(info.presentationTimeUs + windowStart.toLong() * 1_000_000L / rate, windowPeak)
                                windowStart = frame + 1; windowPeak = 0f
                            }
                        }
                    }
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                } finally { decoder.releaseOutputBuffer(index, false) }
            }
        }
        return peaks.snapshot()
    } finally {
        runCatching { codec?.stop() }; runCatching { codec?.release() }; extractor.release()
    }
}
