package com.fnfmodmaker.appearance

import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

/** Only these specific images are imported; archive paths are never extracted to disk. */
internal fun findAppearanceImages(zip: ZipFile, check: () -> Unit = {}): Map<AppearanceSlot, ZipEntry> {
    val suffixes = mapOf(
        AppearanceSlot.BACKGROUND to listOf("preload", "images", "menubg.png"),
        AppearanceSlot.ICON to listOf("preload", "images", "icons", "icon-bf.png")
    )
    val found = mutableMapOf<AppearanceSlot, ZipEntry>()
    val entries = zip.entries(); var count = 0
    while (entries.hasMoreElements()) {
        check()
        val entry = entries.nextElement(); count++
        require(count <= 100_000) { "El ZIP contiene demasiadas entradas." }
        if (entry.isDirectory) continue
        val name = entry.name
        if (name.length > 1024 || name.startsWith('/') || name.contains('\\') || name.any { it.code < 32 || it == ':' }) continue
        val parts = name.split('/')
        if (parts.any { it.isEmpty() || it == "." || it == ".." }) continue
        val lower = parts.map { it.lowercase(Locale.ROOT) }
        suffixes.forEach { (slot, suffix) ->
            // Accept the repo tree or a single ZIP wrapper directory, never arbitrary nesting.
            if ((lower.size == suffix.size || lower.size == suffix.size + 1) && lower.takeLast(suffix.size) == suffix) {
                require(found.put(slot, entry) == null) { "El ZIP contiene imágenes de interfaz duplicadas." }
                require(entry.size in 1..MAX_APPEARANCE_BYTES) { "Una imagen de interfaz está vacía o supera 8 MiB." }
            }
        }
    }
    return found
}

/** Copy compressed bytes only, with an upper bound and cooperative cancellation. */
internal fun copyAppearanceArchive(input: java.io.InputStream, output: java.io.OutputStream, limit: Long, check: () -> Unit = {}) {
    require(limit > 0)
    val buffer = ByteArray(64 * 1024); var total = 0L
    while (true) {
        check()
        val n = input.read(buffer)
        if (n < 0) break
        if (n == 0) {
            val byte = input.read(); if (byte < 0) break
            require(total < limit) { "El ZIP supera el límite de 2 GiB." }
            output.write(byte); total++; continue
        }
        require(n.toLong() <= limit - total) { "El ZIP supera el límite de 2 GiB." }
        output.write(buffer, 0, n); total += n
    }
    require(total > 0) { "El ZIP está vacío." }
}
