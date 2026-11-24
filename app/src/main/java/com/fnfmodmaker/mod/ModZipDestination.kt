package com.fnfmodmaker.mod

import kotlinx.coroutines.CancellationException
import java.io.OutputStream

/** Only errors after entering the destination phase receive the selector warning. */
internal class ModZipDestinationException(cause: Exception) : IllegalStateException(
    "No se pudo abrir o escribir el destino: ${cause.message ?: "error desconocido"}. Revisa el destino elegido: el selector podría haber dejado un archivo vacío o incompleto.", cause
)

internal fun writeModZipToDestination(open: () -> OutputStream?, write: (OutputStream) -> Unit) {
    try {
        val stream = open() ?: error("No se pudo abrir el destino elegido")
        stream.use(write)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        throw ModZipDestinationException(e)
    }
}

internal fun <T> buildAndWriteModZip(build: () -> T, open: () -> OutputStream?, write: (T, OutputStream) -> Unit): T {
    val built = build()
    writeModZipToDestination(open) { write(built, it) }
    return built
}
