package com.fnfmodmaker.project

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

private const val FORMAT = "fnf-mod-maker-project"
private const val VERSION = 1
private const val MAX_FILES = 4096
private const val MAX_ARCHIVE = 1024L * 1024 * 1024
private const val MAX_TOTAL = 1024L * 1024 * 1024
private const val MAX_ASSET = 256L * 1024 * 1024
private const val MAX_JSON = 8L * 1024 * 1024

internal data class PortableSummary(val title: String, val songs: Int, val charts: Int, val assets: Int, val bytes: Long, val songNames: List<String>, val character: Boolean, val stage: Boolean, val scripts: Int)
internal data class PreparedPortable(val directory: File, val config: JsonObject, val summary: PortableSummary, val checksums: Map<String, Pair<Long, String>>)

/** A private project backup format, not a mod ZIP importer. Does not execute scripts. */
internal class PortableProject(private val appRoot: File) {
    private fun validName(name: String): Boolean {
        if (!name.startsWith("assets/") || name.length > 512 || name.contains('\\') || name.any { it.code < 32 || it == ':' }) return false
        val parts = name.split('/')
        return parts.size in 2..17 && parts.all { it.isNotEmpty() && it != "." && it != ".." && it.length <= 255 }
    }
    private fun under(root: File, file: File): Boolean {
        val parent = root.canonicalFile.path
        return file.canonicalFile.path.startsWith(parent + File.separator)
    }
    private fun hex(digest: ByteArray) = digest.joinToString("") { "%02x".format(it.toInt() and 255) }
    private fun sha(data: ByteArray) = hex(MessageDigest.getInstance("SHA-256").digest(data))

    private fun json(bytes: ByteArray): JsonObject {
        require(bytes.size <= MAX_JSON) { "El JSON del proyecto es demasiado grande." }
        // Check nesting before Gson recursively parses untrusted JSON.
        var depth = 0; var inString = false; var escape = false
        for (c in bytes.toString(Charsets.UTF_8)) {
            if (inString) { if (escape) escape = false else if (c == '\\') escape = true else if (c == '"') inString = false }
            else if (c == '"') inString = true else if (c == '{' || c == '[') { depth++; require(depth <= 64) { "JSON demasiado profundo." } }
            else if (c == '}' || c == ']') { depth--; require(depth >= 0) { "JSON inválido." } }
        }
        require(!inString && depth == 0) { "JSON incompleto." }
        return JsonParser.parseString(bytes.toString(Charsets.UTF_8)).asJsonObject
    }
    private fun boundedCopy(input: InputStream, output: OutputStream, limit: Long): Pair<Long, String> {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(64 * 1024); var count = 0L
        while (true) {
            val n = input.read(buffer); if (n < 0) break
            count += n; require(count <= limit) { "El paquete o un recurso supera el tamaño permitido." }
            digest.update(buffer, 0, n); output.write(buffer, 0, n)
        }
        return count to hex(digest.digest())
    }

    fun export(config: JsonObject, assetsDir: File, output: File, progress: (String) -> Unit = {}) {
        val portable = config.deepCopy()
        val sources = linkedMapOf<String, File>()
        val sourcePaths = mutableMapOf<String, String>()
        val base = assetsDir.canonicalFile
        fun add(name: String, file: File) {
            require(validName(name) && under(appRoot, file) && file.isFile) { "Recurso no disponible o fuera del almacenamiento de la app: ${file.name}." }
            require(sources.keys.none { it.equals(name, ignoreCase = true) }) { "Dos recursos tienen nombres incompatibles." }
            require(sources.size < MAX_FILES - 2) { "Demasiados recursos en el proyecto." }
            sources[name] = file; sourcePaths[file.canonicalPath] = name
        }
        // Preserve unreferenced files too. Never follow symlinks while walking.
        var directoryCount = 0
        fun scan(folder: File, prefix: String) {
            require(++directoryCount <= MAX_FILES && (prefix == "assets" || validName(prefix))) { "Demasiadas carpetas o ruta inválida." }
            val files = folder.listFiles() ?: error("No se pudo leer una carpeta de recursos.")
            files.sortedBy { it.name }.forEach { f ->
                require(f.canonicalFile == f.absoluteFile) { "No se pueden empaquetar enlaces simbólicos." }
                val name = "$prefix/${f.name}"
                if (f.isDirectory) scan(f, name) else add(name, f)
            }
        }
        if (base.exists()) {
            require(base.isDirectory && under(appRoot, base) && base.parentFile == assetsDir.parentFile.canonicalFile && base.name == assetsDir.name) { "Carpeta de recursos insegura." }
            scan(base, "assets")
        }
        visitProjectAssets(portable) { ref ->
            val text = ref.get("path").asString
            require(File(text).isAbsolute && '\u0000' !in text && '\\' !in text && text.split('/').none { it == "." || it == ".." }) { "Ruta de recurso inválida." }
            val original = File(text).canonicalFile
            require(under(appRoot, original) && original.isFile) { "Falta el recurso ${ref.get("displayName").asString}; selecciónalo de nuevo." }
            val name = sourcePaths[original.path] ?: run {
                val ext = original.extension.filter(Char::isLetterOrDigit).take(10)
                val candidate = "assets/legacy_${UUID.randomUUID()}${if (ext.isEmpty()) "" else ".$ext"}"
                add(candidate, original); candidate
            }
            ref.addProperty("path", name)
            // The Android document URI is device-specific; the packaged asset is authoritative.
            ref.addProperty("uri", "")
        }
        val configBytes = portable.toString().toByteArray(Charsets.UTF_8)
        require(configBytes.size <= MAX_JSON) { "El proyecto es demasiado grande." }
        output.parentFile?.mkdirs()
        try {
            ZipOutputStream(output.outputStream().buffered()).use { zip ->
                zip.putNextEntry(ZipEntry("project.json")); zip.write(configBytes); zip.closeEntry()
                val manifestAssets = JsonArray(); var total = configBytes.size.toLong()
                sources.entries.forEachIndexed { index, (name, file) ->
                    progress("Empaquetando recurso ${index + 1}/${sources.size}…")
                    require(file.length() <= MAX_ASSET) { "El recurso ${file.name} supera 256 MiB." }
                    zip.putNextEntry(ZipEntry(name))
                    val (size, hash) = file.inputStream().buffered().use { boundedCopy(it, zip, MAX_ASSET) }
                    zip.closeEntry(); total += size; require(total <= MAX_TOTAL) { "El proyecto supera 1 GiB." }
                    manifestAssets.add(JsonObject().apply { addProperty("path", name); addProperty("size", size); addProperty("sha256", hash) })
                }
                val manifest = JsonObject().apply {
                    addProperty("format", FORMAT); addProperty("version", VERSION); addProperty("projectSha256", sha(configBytes)); add("assets", manifestAssets)
                }.toString().toByteArray(Charsets.UTF_8)
                require(manifest.size <= MAX_JSON) { "Manifiesto demasiado grande." }
                zip.putNextEntry(ZipEntry("manifest.json")); zip.write(manifest); zip.closeEntry()
            }
            require(output.length() <= MAX_ARCHIVE) { "El paquete supera 1 GiB." }
        } catch (e: Exception) { output.delete(); throw e }
    }

    fun prepare(archive: File, stage: File, progress: (String) -> Unit = {}): PreparedPortable {
        require(archive.isFile && archive.length() in 1..MAX_ARCHIVE) { "El archivo supera 1 GiB o está vacío." }
        require(!stage.exists()) { "La carpeta temporal ya existe." }
        check(stage.mkdirs()) { "No se pudo crear la carpeta temporal." }
        try {
            ZipFile(archive).use { zip ->
                val entries = linkedMapOf<String, ZipEntry>(); val names = hashSetOf<String>()
                val enumeration = zip.entries()
                while (enumeration.hasMoreElements()) {
                    val entry = enumeration.nextElement(); val name = entry.name
                    require(!entry.isDirectory && (name == "manifest.json" || name == "project.json" || validName(name))) { "Ruta o entrada no permitida: $name" }
                    require(names.add(name.lowercase(java.util.Locale.ROOT)) && entries.size < MAX_FILES) { "Entradas duplicadas o demasiados archivos." }
                    require(entry.size >= 0 && entry.size <= if (name == "manifest.json" || name == "project.json") MAX_JSON else MAX_ASSET) { "Tamaño de entrada no permitido." }
                    entries[name] = entry
                }
                fun readSmall(name: String): ByteArray {
                    val entry = entries[name] ?: error("Falta $name.")
                    val out = java.io.ByteArrayOutputStream()
                    zip.getInputStream(entry).use { boundedCopy(it, out, MAX_JSON) }
                    return out.toByteArray()
                }
                val manifest = json(readSmall("manifest.json"))
                require(manifest.get("format")?.asString == FORMAT && manifest.get("version")?.asInt == VERSION) { "Formato o versión de proyecto no compatible." }
                val configBytes = readSmall("project.json")
                require(manifest.get("projectSha256")?.asString == sha(configBytes)) { "La configuración está dañada." }
                val config = json(configBytes)
                val described = linkedMapOf<String, Pair<Long, String>>()
                val assets = manifest.get("assets")?.takeIf { it.isJsonArray }?.asJsonArray ?: error("Falta la lista de recursos.")
                require(assets.size() <= MAX_FILES - 2) { "Demasiados recursos." }
                var total = configBytes.size.toLong()
                for (asset in assets) {
                    val obj = asset.asJsonObject
                    val path = obj.get("path").asString; val size = obj.get("size").asLong; val hash = obj.get("sha256").asString
                    require(validName(path) && size in 0..MAX_ASSET && hash.matches(Regex("[0-9a-f]{64}")) && described.put(path, size to hash) == null) { "Lista de recursos inválida." }
                    total += size; require(total <= MAX_TOTAL) { "El contenido supera 1 GiB." }
                }
                require(entries.keys == described.keys + setOf("manifest.json", "project.json")) { "El paquete tiene recursos ausentes o inesperados." }
                visitProjectAssets(config) { ref -> require(ref.get("path").asString in described) { "Falta el recurso ${ref.get("displayName").asString}." } }
                described.entries.forEachIndexed { index, (name, expected) ->
                    progress("Comprobando recurso ${index + 1}/${described.size}…")
                    val file = File(stage, name)
                    require(under(stage, file)) { "Ruta fuera de la carpeta temporal." }
                    check(file.parentFile.isDirectory || file.parentFile.mkdirs()) { "No se pudo crear la carpeta temporal." }
                    val actual = zip.getInputStream(entries.getValue(name)).use { input -> file.outputStream().buffered().use { boundedCopy(input, it, expected.first) } }
                    require(actual == expected) { "Recurso dañado: $name." }
                }
                val independentCharts = config.get("independentCharts")?.takeIf { it.isJsonObject }?.asJsonObject
                val independentChartCount = independentCharts?.entrySet()?.sumOf { songEntry ->
                    songEntry.value.asJsonObject.entrySet().sumOf { variantEntry ->
                        if (variantEntry.value.isJsonObject) {
                            variantEntry.value.asJsonObject.get("charts")?.takeIf { it.isJsonObject }?.asJsonObject?.size() ?: 0
                        } else 0
                    }
                } ?: 0
                val charts = config.get("charts")?.takeIf { it.isJsonObject }?.asJsonObject?.entrySet()?.sumOf { it.value.asJsonObject.size() } ?: 0
                val totalCharts = charts + independentChartCount
                val songNames = config.getAsJsonArray("songs").map { it.asJsonObject.get("songName")?.asString?.ifBlank { "Sin nombre" } ?: "Sin nombre" }
                val summary = PortableSummary(config.get("modTitle")?.asString?.ifBlank { "Proyecto sin nombre" } ?: "Proyecto sin nombre", songNames.size, totalCharts, described.size, archive.length(), songNames,
                    (config.getAsJsonObject("character")?.get("enabled")?.asBoolean == true ||
                        config.get("characterLibrary")?.takeIf { it.isJsonArray }?.asJsonArray?.any { it.isJsonObject && it.asJsonObject.getAsJsonObject("draft")?.get("enabled")?.asBoolean == true } == true),
                    config.getAsJsonObject("stageDraft")?.get("enabled")?.asBoolean == true,
                    config.get("psychScripts")?.takeIf { it.isJsonObject }?.asJsonObject?.size() ?: 0)
                return PreparedPortable(stage, config, summary, described)
            }
        } catch (e: Throwable) { stage.deleteRecursively(); throw e }
    }

    /** Recheck staged files immediately before publication and replace relative paths. */
    fun materialize(prepared: PreparedPortable, finalDirectory: File): JsonObject {
        prepared.checksums.forEach { (name, expected) ->
            val file = File(prepared.directory, name)
            require(under(prepared.directory, file) && file.isFile) { "Falta un recurso temporal." }
            val actual = file.inputStream().use { boundedCopy(it, object : OutputStream() { override fun write(b: Int) {} ; override fun write(b: ByteArray, off: Int, len: Int) {} }, expected.first) }
            require(actual == expected) { "Un recurso temporal está dañado." }
        }
        val output = prepared.config.deepCopy()
        visitProjectAssets(output) { ref ->
            val path = ref.get("path").asString
            require(path in prepared.checksums && validName(path)) { "Referencia inválida." }
            // Store the canonical destination so Android's data-directory aliases do not
            // look like links inside the project when the restored mod is exported.
            ref.addProperty("path", File(finalDirectory, path).canonicalPath)
            ref.addProperty("uri", "")
        }
        return output
    }
}
