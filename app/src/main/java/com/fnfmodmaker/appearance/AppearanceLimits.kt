package com.fnfmodmaker.appearance

import java.io.InputStream
import java.io.OutputStream

internal const val MAX_APPEARANCE_BYTES = 8L * 1024 * 1024
internal const val MAX_APPEARANCE_DIMENSION = 8192
internal const val TARGET_APPEARANCE_DIMENSION = 1600

/** Bounded copy for untrusted document providers; caller owns and removes the temporary file. */
internal fun copyAppearanceInput(input: InputStream, output: OutputStream, limit: Long = MAX_APPEARANCE_BYTES): Long {
    require(limit >= 0)
    val buffer = ByteArray(8192)
    var total = 0L
    while (true) {
        val n = input.read(buffer)
        if (n < 0) break
        if (n == 0) {
            val byte = input.read(); if (byte < 0) break
            require(total < limit) { "La imagen supera el límite de 8 MiB." }
            output.write(byte); total++; continue
        }
        require(n.toLong() <= limit - total) { "La imagen supera el límite de 8 MiB." }
        output.write(buffer, 0, n); total += n
    }
    require(total > 0) { "La imagen está vacía." }
    return total
}
internal fun appearanceSampleSize(width: Int, height: Int): Int {
    require(width in 1..MAX_APPEARANCE_DIMENSION && height in 1..MAX_APPEARANCE_DIMENSION) { "Imagen inválida o dimensiones superiores a 8192 píxeles." }
    var sample = 1
    while (width / sample > TARGET_APPEARANCE_DIMENSION || height / sample > TARGET_APPEARANCE_DIMENSION) sample *= 2
    return sample
}
