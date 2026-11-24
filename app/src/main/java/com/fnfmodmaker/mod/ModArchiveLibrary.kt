package com.fnfmodmaker.mod

import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID
import java.util.zip.ZipFile

internal data class ArchivedMod(val id: String, val name: String, val sha256: String, val size: Long,
    val entries: Int, val engines: List<String>, val scripts: Int)

/** Stores the original ZIP verbatim. Nothing is extracted or executed, and no project is modified. */
internal class ModArchiveLibrary(private val root: File) {
    private val idPattern = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
    private val maxArchive = 2L * 1024 * 1024 * 1024
    private val maxUnpacked = 4L * 1024 * 1024 * 1024
    private val maxEntry = 512L * 1024 * 1024
    private fun dir(id: String): File { require(idPattern.matches(id)); return File(root, id) }
    private fun copy(input: InputStream, output: OutputStream, limit: Long, ensureActive: () -> Unit): Pair<Long, String> {
        val hash = MessageDigest.getInstance("SHA-256"); val buf = ByteArray(64 * 1024); var total = 0L
        while (true) {
            ensureActive(); val n = input.read(buf); if (n < 0) break
            if (n == 0) { val b = input.read(); if (b < 0) break; require(total < limit) { "El ZIP supera 2 GiB." }; output.write(b); hash.update(b.toByte()); total++; continue }
            require(n.toLong() <= limit - total) { "El ZIP supera 2 GiB." }
            output.write(buf, 0, n); hash.update(buf, 0, n); total += n
        }
        require(total > 0) { "El ZIP está vacío." }
        return total to hash.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
    }
    private fun validName(name: String, directory: Boolean): Boolean {
        if (name.length > 1024 || name.startsWith('/') || name.contains('\\') || name.any { it.code < 32 || it == ':' }) return false
        val value = if (directory) name.removeSuffix("/") else name
        val parts = value.split('/')
        return parts.size in 1..32 && parts.all { it.isNotEmpty() && it != "." && it != ".." && it.length <= 255 }
    }
    fun import(input: InputStream, name: String, ensureActive: () -> Unit = {}): ArchivedMod {
        check(root.isDirectory || root.mkdirs()) { "No se pudo crear la biblioteca local." }
        val id = UUID.randomUUID().toString(); val stage = File(root, ".pending-$id")
        check(stage.mkdir()) { "No se pudo preparar la copia temporal." }
        try {
            val target = File(stage, "original.zip")
            val (size, digest) = target.outputStream().use { out ->
                val result = copy(input, out, maxArchive, ensureActive); out.fd.sync(); result
            }
            var count = 0; var scripts = 0; var unpacked = 0L
            val names = hashSetOf<String>(); val engines = linkedSetOf<String>()
            ZipFile(target).use { zip ->
                val entries = zip.entries()
                while (entries.hasMoreElements()) {
                    ensureActive(); val entry = entries.nextElement(); count++
                    require(count <= 20_000 && validName(entry.name, entry.isDirectory)) { "ZIP con demasiadas entradas o ruta no segura." }
                    val normalized = entry.name.removeSuffix("/").lowercase(Locale.ROOT)
                    require(names.add(normalized)) { "El ZIP contiene rutas duplicadas." }
                    if (!entry.isDirectory) {
                        require(entry.size in 0..maxEntry && entry.compressedSize >= 0) { "Archivo del ZIP demasiado grande o con tamaño desconocido." }
                        require(entry.size <= maxUnpacked - unpacked) { "El ZIP supera el límite de contenido descomprimido." }
                        unpacked += entry.size
                        if (normalized.endsWith(".lua") || normalized.endsWith(".hx")) scripts++
                        if (normalized == "pack.json" || normalized.endsWith("/pack.json")) engines.add("Psych")
                        if (normalized == "_polymod_meta.json" || normalized.endsWith("/_polymod_meta.json")) engines.add("V-Slice")
                    }
                }
            }
            require(count > 0) { "El ZIP no contiene archivos." }
            val result = ArchivedMod(id, name.substringAfterLast('/').substringAfterLast('\\').filter { it.code >= 32 }.take(160).ifBlank { "Mod importado.zip" }, digest, size, count, engines.toList(), scripts)
            val metadata = JSONObject().put("version", 1).put("id", id).put("name", result.name).put("sha256", digest)
                .put("size", size).put("entries", count).put("engines", org.json.JSONArray(result.engines)).put("scripts", scripts)
            File(stage, "metadata.json").outputStream().use { out -> out.write(metadata.toString().toByteArray(Charsets.UTF_8)); out.fd.sync() }
            check(stage.renameTo(dir(id))) { "No se pudo publicar la copia; el ZIP original no cambió." }
            return result
        } catch (e: Throwable) { stage.deleteRecursively(); throw e }
    }
    fun list(): List<ArchivedMod> = root.listFiles().orEmpty().filter { it.isDirectory && idPattern.matches(it.name) }.mapNotNull { folder ->
        runCatching {
            val metaFile = File(folder, "metadata.json"); require(metaFile.length() in 1..64_000)
            val o = JSONObject(metaFile.readText()); require(o.getInt("version") == 1 && o.getString("id") == folder.name)
            val id = folder.name; val hash = o.getString("sha256"); val size = o.getLong("size")
            require(hash.matches(Regex("[0-9a-f]{64}")) && size in 1..maxArchive && File(folder, "original.zip").isFile)
            val array = o.getJSONArray("engines")
            ArchivedMod(id, o.getString("name").take(160), hash, size, o.getInt("entries"), (0 until array.length()).map { array.getString(it) }, o.getInt("scripts"))
        }.getOrNull()
    }.sortedBy { it.name.lowercase(Locale.ROOT) }
    fun verify(id: String, ensureActive: () -> Unit = {}): File {
        val record = list().firstOrNull { it.id == id } ?: error("Copia de mod no encontrada.")
        val file = File(dir(id), "original.zip")
        require(file.length() == record.size) { "La copia del ZIP está dañada." }
        val (_, hash) = file.inputStream().use { input -> copy(input, object : OutputStream() { override fun write(b: Int) {} ; override fun write(b: ByteArray, off: Int, len: Int) {} }, maxArchive, ensureActive) }
        require(hash == record.sha256) { "La copia del ZIP está dañada; no se exportó." }
        return file
    }
    fun export(id: String, output: OutputStream, ensureActive: () -> Unit = {}) {
        verify(id, ensureActive).inputStream().use { copy(it, output, maxArchive, ensureActive) }
    }
    fun cleanupInterrupted() {
        root.listFiles().orEmpty().filter { it.isDirectory && it.name.startsWith(".pending-") && idPattern.matches(it.name.removePrefix(".pending-")) }.forEach { it.deleteRecursively() }
    }
}
