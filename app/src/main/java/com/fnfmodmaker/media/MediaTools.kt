package com.fnfmodmaker.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import java.io.File

/**
 * Todo lo relacionado con archivos multimedia: copiar lo que el usuario
 * elige, leer duración, convertir audio a OGG (Vorbis) y reescalar imágenes.
 */
object MediaTools {

    fun displayName(context: Context, uri: Uri): String {
        try {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0 && cursor.moveToFirst()) {
                    val name = cursor.getString(index)
                    if (!name.isNullOrBlank()) return name
                }
            }
        } catch (_: Throwable) {
            // seguimos con el fallback
        }
        return uri.lastPathSegment?.substringAfterLast('/')?.ifBlank { "archivo" } ?: "archivo"
    }

    fun copyTo(context: Context, uri: Uri, target: File): File {
        target.parentFile?.mkdirs()
        val input = context.contentResolver.openInputStream(uri)
            ?: throw IllegalStateException("No se pudo abrir el archivo elegido.")
        input.use { source ->
            target.outputStream().use { sink -> source.copyTo(sink) }
        }
        return target
    }

    fun readDurationMs(path: String): Long {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(path)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
        } catch (_: Throwable) {
            0L
        } finally {
            try {
                retriever.release()
            } catch (_: Throwable) {
                // ignorado
            }
        }
    }

    /**
     * Convierte cualquier audio que entienda FFmpeg a OGG Vorbis.
     * @return null si todo fue bien, o un mensaje de error legible.
     */
    fun convertToOgg(input: File, output: File): String? {
        val common = "-hide_banner -loglevel error -y -i ${quote(input.absolutePath)} " +
            "-vn -map_metadata -1"
        return runFfmpeg(
            listOf(
                "$common -c:a libvorbis -q:a 5 -ar 44100 ${quote(output.absolutePath)}",
                "$common -c:a vorbis -strict -2 -q:a 5 -ar 44100 ${quote(output.absolutePath)}"
            ),
            output
        )
    }

    /**
     * Mezcla dos pistas (por ejemplo voces del jugador + del oponente) en un
     * único OGG. Psych Engine solo reproduce un archivo `Voices.ogg`.
     */
    fun mixToOgg(first: File, second: File, output: File): String? {
        val inputs = "-hide_banner -loglevel error -y -i ${quote(first.absolutePath)} " +
            "-i ${quote(second.absolutePath)}"
        return runFfmpeg(
            listOf(
                "$inputs -filter_complex \"[0:a][1:a]amix=inputs=2:duration=longest:normalize=0\" " +
                    "-c:a libvorbis -q:a 5 -ar 44100 ${quote(output.absolutePath)}",
                "$inputs -filter_complex \"[0:a][1:a]amix=inputs=2:duration=longest\" " +
                    "-c:a libvorbis -q:a 5 -ar 44100 ${quote(output.absolutePath)}",
                "$inputs -filter_complex \"[0:a][1:a]amix=inputs=2:duration=longest\" " +
                    "-c:a vorbis -strict -2 -q:a 5 -ar 44100 ${quote(output.absolutePath)}"
            ),
            output
        )
    }

    /**
     * Genera el título de la semana que V-Slice muestra en la lista de niveles.
     * Medidas tomadas de los assets reales del juego (359 x 89 px).
     */
    fun makeTitleCard(text: String, accentHex: String, output: File): Boolean {
        return try {
            val width = 359
            val height = 89
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            canvas.drawColor(Color.parseColor("#1F1F2B"))

            val accent = try {
                Color.parseColor(accentHex)
            } catch (_: Throwable) {
                Color.parseColor("#C24B99")
            }
            canvas.drawRect(0f, 0f, 9f, height.toFloat(), Paint().apply {
                color = accent
                style = Paint.Style.FILL
            })

            val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                textAlign = Paint.Align.CENTER
            }
            val label = text.trim().ifEmpty { "MI MOD" }.uppercase()

            var size = 34f
            textPaint.textSize = size
            while (textPaint.measureText(label) > (width - 34) && size > 11f) {
                size -= 1f
                textPaint.textSize = size
            }

            val metrics = textPaint.fontMetrics
            val baseline = height / 2f - (metrics.ascent + metrics.descent) / 2f
            canvas.drawText(label, width / 2f + 5f, baseline, textPaint)

            output.parentFile?.mkdirs()
            val ok = output.outputStream().use { stream ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
            }
            bitmap.recycle()
            ok
        } catch (_: Throwable) {
            false
        }
    }

    /** Ejecuta varios comandos FFmpeg en orden y para en el primero que funcione. */
    private fun runFfmpeg(attempts: List<String>, output: File): String? {
        output.parentFile?.mkdirs()
        if (output.exists()) output.delete()

        var lastError = "FFmpeg no devolvió información."
        for (command in attempts) {
            try {
                val session = FFmpegKit.execute(command)
                val code = session.returnCode
                if (ReturnCode.isSuccess(code) && output.exists() && output.length() > 0L) {
                    return null
                }
                val raw = session.output
                lastError = if (!raw.isNullOrBlank()) {
                    raw.takeLast(600)
                } else {
                    "FFmpeg terminó con código ${code?.value ?: -1}"
                }
            } catch (t: Throwable) {
                // UnsatisfiedLinkError si la ABI no está incluida en el APK
                lastError = t.message ?: t.javaClass.simpleName
            }
            if (output.exists()) output.delete()
        }
        return lastError
    }

    /** La hoja se copia sin convertir: comprobamos su contenido, no solo la extensión. */
    fun isPng(input: File): Boolean = try {
        val signature = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a)
        input.inputStream().use { stream ->
            val header = ByteArray(signature.size)
            var offset = 0
            while (offset < header.size) {
                val read = stream.read(header, offset, header.size - offset)
                if (read <= 0) break
                offset += read
            }
            offset == header.size && header.contentEquals(signature)
        }
    } catch (_: Exception) {
        false
    }

    /** Tamaño real de una imagen, sin cargarla entera en memoria. */
    fun imageSize(input: File): Pair<Int, Int>? {
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(input.absolutePath, bounds)
            if (bounds.outWidth > 0 && bounds.outHeight > 0) {
                bounds.outWidth to bounds.outHeight
            } else {
                null
            }
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * Icono de vida en formato clásico: 300x150 con dos frames (normal y
     * "perdiendo"). Es lo que entienden los dos motores:
     *  - Psych -> images/icons/icon-<id>.png
     *  - V-Slice -> images/icons/icon-<id>.png (150 px por frame)
     * Si la imagen de entrada ya trae dos frames, se respetan.
     */
    fun prepareHealthIcon(input: File, output: File): Boolean {
        return try {
            val source = BitmapFactory.decodeFile(input.absolutePath) ?: return false
            val looksLikeTwoFrames = source.width >= source.height * 2 - 4

            val first: Bitmap
            val second: Bitmap
            if (looksLikeTwoFrames) {
                val half = source.width / 2
                first = Bitmap.createBitmap(source, 0, 0, half, source.height)
                second = Bitmap.createBitmap(source, half, 0, half, source.height)
            } else {
                first = source
                second = source
            }

            val canvasBitmap = Bitmap.createBitmap(300, 150, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(canvasBitmap)
            val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
            canvas.drawBitmap(first, null, RectF(0f, 0f, 150f, 150f), paint)
            canvas.drawBitmap(second, null, RectF(150f, 0f, 300f, 150f), paint)

            output.parentFile?.mkdirs()
            val ok = output.outputStream().use { stream ->
                canvasBitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
            }
            canvasBitmap.recycle()
            ok
        } catch (_: Throwable) {
            false
        }
    }

    /** Reescala una imagen a PNG de como máximo [maxSize] px por lado. */
    fun preparePng(input: File, output: File, maxSize: Int = 512): Boolean {
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(input.absolutePath, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return false

            var sample = 1
            while (bounds.outWidth / (sample * 2) >= maxSize && bounds.outHeight / (sample * 2) >= maxSize) {
                sample *= 2
            }

            val options = BitmapFactory.Options().apply { inSampleSize = sample }
            val decoded = BitmapFactory.decodeFile(input.absolutePath, options) ?: return false

            val scale = minOf(
                maxSize.toFloat() / decoded.width,
                maxSize.toFloat() / decoded.height,
                1f
            )
            val finalBitmap = if (scale < 1f) {
                Bitmap.createScaledBitmap(
                    decoded,
                    (decoded.width * scale).toInt().coerceAtLeast(1),
                    (decoded.height * scale).toInt().coerceAtLeast(1),
                    true
                )
            } else {
                decoded
            }

            output.parentFile?.mkdirs()
            val ok = output.outputStream().use { stream ->
                finalBitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
            }
            if (finalBitmap !== decoded) finalBitmap.recycle()
            decoded.recycle()
            ok
        } catch (t: Throwable) {
            false
        }
    }

    private fun quote(value: String): String = "\"" + value.replace("\"", "\\\"") + "\""
}
