package com.fnfmodmaker.appearance

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.AtomicFile
import java.io.File

internal enum class AppearanceSlot(val fileName: String) { BACKGROUND("background.png"), ICON("icon.png") }

/** Separate from projects and backups: user-selected images never enter mod or project exports. */
internal class LocalAppearanceStore(private val context: Context) {
    private val directory get() = File(context.noBackupFilesDir, "appearance")
    private fun atomic(slot: AppearanceSlot) = AtomicFile(File(directory, slot.fileName))

    fun replace(slot: AppearanceSlot, uri: Uri) {
        require(uri.scheme == "content") { "Selecciona una imagen desde el selector del dispositivo." }
        val input = context.contentResolver.openInputStream(uri) ?: error("No se pudo abrir la imagen elegida.")
        input.use { replaceFromStream(slot, it) }
    }

    fun replaceFromStream(slot: AppearanceSlot, input: java.io.InputStream) {
        check(directory.isDirectory || directory.mkdirs()) { "No se pudo crear la carpeta local." }
        // Temporary input is disposable cache; only the bounded, decoded PNG is kept.
        val temporary = File.createTempFile("appearance-", ".tmp", context.cacheDir)
        try {
            temporary.outputStream().use { copyAppearanceInput(input, it) }
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(temporary.path, bounds)
            require(bounds.outMimeType in setOf("image/png", "image/jpeg", "image/webp")) { "Selecciona un PNG, JPG o WebP válido." }
            val sample = appearanceSampleSize(bounds.outWidth, bounds.outHeight)
            val bitmap = BitmapFactory.decodeFile(temporary.path, BitmapFactory.Options().apply { inSampleSize = sample })
                ?: error("No se pudo decodificar la imagen.")
            try {
                val output = atomic(slot)
                val stream = output.startWrite()
                try {
                    check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)) { "No se pudo guardar la imagen." }
                    output.finishWrite(stream)
                } catch (e: Exception) { output.failWrite(stream); throw e }
            } finally { bitmap.recycle() }
        } finally { temporary.delete() }
    }

    fun discardAbandonedArchives() {
        context.cacheDir.listFiles().orEmpty().filter { it.isFile && it.name.startsWith("appearance-archive-") && it.name.endsWith(".zip") }.forEach { it.delete() }
    }

    fun read(slot: AppearanceSlot): Bitmap? {
        val file = atomic(slot)
        return try { file.openRead().use { BitmapFactory.decodeStream(it) ?: error("La imagen local está dañada.") } }
        catch (e: java.io.FileNotFoundException) { null }
    }
    fun remove(slot: AppearanceSlot) { atomic(slot).delete() }
}
