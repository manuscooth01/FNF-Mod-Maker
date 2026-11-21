package com.fnfmodmaker.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
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
        output.parentFile?.mkdirs()
        if (output.exists()) output.delete()

        val common = "-hide_banner -loglevel error -y -i ${quote(input.absolutePath)} " +
            "-vn -map_metadata -1"

        val attempts = listOf(
            "$common -c:a libvorbis -q:a 5 -ar 44100 ${quote(output.absolutePath)}",
            "$common -c:a vorbis -strict -2 -q:a 5 -ar 44100 ${quote(output.absolutePath)}"
        )

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
