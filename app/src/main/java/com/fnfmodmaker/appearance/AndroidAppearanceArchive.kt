package com.fnfmodmaker.appearance

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import kotlinx.coroutines.ensureActive
import java.io.File
import java.util.zip.ZipFile
import kotlin.coroutines.CoroutineContext

private const val MAX_ZIP_BYTES = 2L * 1024 * 1024 * 1024

/** A seekable SAF descriptor needs no full copy. Non-seekable providers use bounded cache storage. */
internal fun <T> withAppearanceArchive(context: Context, uri: Uri, job: CoroutineContext,
    onFallback: () -> Unit, block: (ZipFile) -> T): T {
    require(uri.scheme == "content") { "Selecciona un ZIP desde el dispositivo." }
    var descriptor: ParcelFileDescriptor? = null
    var direct: ZipFile? = null
    try {
        descriptor = runCatching { context.contentResolver.openFileDescriptor(uri, "r") }.getOrNull()
        if (descriptor != null) {
            val size = descriptor.statSize
            require(size <= MAX_ZIP_BYTES) { "El ZIP supera el límite de 2 GiB." }
            if (size > 0) direct = runCatching { ZipFile(File("/proc/self/fd/${descriptor.fd}")) }.getOrNull()
        }
        if (direct != null) return direct.use { block(it) }
    } finally { descriptor?.close() }

    onFallback()
    val temporary = File.createTempFile("appearance-archive-", ".zip", context.cacheDir)
    try {
        val input = context.contentResolver.openInputStream(uri) ?: error("No se pudo abrir el ZIP.")
        input.use { source -> temporary.outputStream().use { dest ->
            copyAppearanceArchive(source, dest, MAX_ZIP_BYTES) { job.ensureActive() }
        } }
        job.ensureActive()
        return ZipFile(temporary).use(block)
    } finally { temporary.delete() }
}
