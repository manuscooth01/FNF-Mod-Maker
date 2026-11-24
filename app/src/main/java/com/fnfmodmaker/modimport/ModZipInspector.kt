package com.fnfmodmaker.modimport

import org.apache.commons.compress.archivers.zip.ZipArchiveEntry
import org.apache.commons.compress.archivers.zip.ZipFile
import java.io.File
import java.io.InputStream
import java.text.Normalizer
import java.util.Locale
import java.util.UUID
import java.util.zip.CRC32

/** Mobile-oriented limits: at most 128 MiB input and 256 MiB extracted, with 64 MiB per file.
 * Extraction and archive coexist temporarily; ~384 MiB of free space may be needed. */
internal data class ModZipLimits(
    val archiveBytes: Long = 128L * 1024 * 1024,
    val totalBytes: Long = 256L * 1024 * 1024,
    val fileBytes: Long = 64L * 1024 * 1024,
    val entries: Int = 2048,
    val maxRatio: Long = 200,
    val ratioThreshold: Long = 1024L * 1024
)
internal data class InventoriedFile(val path: String, val kind: String, val status: String, val detail: String)
internal data class ModZipReview(
    val name: String, val engines: List<String>, val songs: List<String>, val difficulties: List<String>,
    val characters: Int, val stages: Int, val audio: Int, val scripts: Int,
    val files: List<InventoriedFile>, val warnings: List<String>
)

/** Inspect only: never publishes or runs content. All temporary bytes are removed, even on cancellation. */
internal class ModZipInspector(private val tempRoot: File, private val limits: ModZipLimits = ModZipLimits()) {
    private fun safePath(name: String, directory: Boolean): String {
        require(name.length <= 1024 && !name.startsWith('/') && !name.contains('\\')) { "Ruta ZIP no segura: $name" }
        val path = if (directory) name.removeSuffix("/") else name
        val parts = path.split('/')
        require(parts.size in 1..32 && parts.all { part ->
            part.isNotEmpty() && part != "." && part != ".." && part.length <= 255 &&
                !part.endsWith('.') && !part.endsWith(' ') &&
                part.none { it.code < 32 || it.code == 127 || it in ":*?\"<>|" } &&
                !Normalizer.normalize(part, Normalizer.Form.NFKC).let { it == "." || it == ".." || it.contains('/') || it.contains('\\') || it.contains(':') }
        }) { "Ruta ZIP no segura: $name" }
        return path
    }
    private fun key(path: String) = Normalizer.normalize(path, Normalizer.Form.NFKC).lowercase(Locale.ROOT)
    private fun validateType(entry: ZipArchiveEntry) {
        require(entry.rawName?.none { it.toInt() == 0x5c } != false) { "Separador ZIP no permitido: ${entry.name}" }
        val mode = entry.unixMode
        val type = mode and 0xF000
        // Archives without Unix attributes report zero. Reject special files and symlinks.
        require(type == 0 || (type == 0x8000 && !entry.isDirectory) || (type == 0x4000 && entry.isDirectory)) {
            "Tipo de entrada ZIP no permitido: ${entry.name}"
        }
        require(!entry.generalPurposeBit.usesEncryption()) { "ZIP cifrado no compatible: ${entry.name}" }
    }
    fun inspect(input: InputStream, name: String, ensureActive: () -> Unit = {}): ModZipReview =
        withExtracted(input, ensureActive) { _, names -> inventory(name, names) }

    /** The callback may copy validated files into a separate staging area; this temporary tree is always removed. */
    fun <T> withExtracted(input: InputStream, ensureActive: () -> Unit = {}, block: (File, List<String>) -> T): T {
        check(tempRoot.isDirectory || tempRoot.mkdirs()) { "No se pudo crear la carpeta temporal." }
        val stage = File(tempRoot, "inspect-${UUID.randomUUID()}")
        check(stage.mkdir()) { "No se pudo crear la carpeta temporal aislada." }
        try {
            val archive = File(stage, "archive.zip")
            input.use { source -> archive.outputStream().buffered().use { out ->
                val buffer = ByteArray(32 * 1024); var size = 0L
                while (true) {
                    ensureActive(); val n = source.read(buffer); if (n < 0) break
                    if (n == 0) continue
                    require(n.toLong() <= limits.archiveBytes - size) { "El ZIP supera el límite comprimido." }
                    size += n; out.write(buffer, 0, n)
                }
                require(size > 0) { "El ZIP está vacío." }
            } }
            val extracted = File(stage, "files"); check(extracted.mkdir())
            val paths = linkedMapOf<String, Pair<String, Boolean>>()
            val fileNames = mutableListOf<String>()
            ZipFile(archive).use { zip ->
                val enumeration = zip.entries
                val all = mutableListOf<ZipArchiveEntry>()
                while (enumeration.hasMoreElements()) {
                    ensureActive(); val e = enumeration.nextElement()
                    require(all.size < limits.entries) { "Demasiadas entradas ZIP." }
                    val path = safePath(e.name, e.isDirectory); validateType(e)
                    require(zip.canReadEntryData(e)) { "Compresión ZIP no compatible: $path" }
                    val k = key(path)
                    require(paths.put(k, path to e.isDirectory) == null) { "Ruta ZIP duplicada o normalizada: $path" }
                    require(e.size >= 0 && e.compressedSize >= 0 && e.crc >= 0) { "Entrada ZIP incompleta: $path" }
                    require(e.size <= limits.fileBytes) { "Archivo demasiado grande: $path" }
                    all.add(e)
                }
                require(all.isNotEmpty()) { "El ZIP no contiene entradas." }
                val directorySpellings = mutableMapOf<String, String>()
                paths.values.forEach { (path, directory) ->
                    val components = path.split('/')
                    val count = if (directory) components.size else components.size - 1
                    for (i in 1..count) {
                        val prefix = components.take(i).joinToString("/")
                        val previous = directorySpellings.putIfAbsent(key(prefix), prefix)
                        require(previous == null || previous == prefix) { "Carpeta ZIP ambigua: $prefix" }
                    }
                }
                paths.forEach { (path, _) ->
                    var parent = path.substringBeforeLast('/', "")
                    while (parent.isNotEmpty()) {
                        require(paths[parent]?.second != false) { "Conflicto entre archivo y carpeta: $path" }
                        parent = parent.substringBeforeLast('/', "")
                    }
                }
                var total = 0L
                for (e in all) {
                    ensureActive(); val path = safePath(e.name, e.isDirectory)
                    val target = File(extracted, path)
                    require(target.canonicalPath.startsWith(extracted.canonicalPath + File.separator)) { "Ruta fuera de carpeta temporal." }
                    if (e.isDirectory) {
                        require(e.size == 0L) { "Una carpeta contiene datos: $path" }
                        check(target.isDirectory || target.mkdirs()); continue
                    }
                    check(target.parentFile.isDirectory || target.parentFile.mkdirs())
                    val crc = CRC32(); var size = 0L
                    zip.getInputStream(e).use { source -> target.outputStream().buffered().use { out ->
                        val buffer = ByteArray(32 * 1024)
                        while (true) {
                            ensureActive(); val n = source.read(buffer); if (n < 0) break
                            if (n == 0) continue
                            require(n.toLong() <= limits.fileBytes - size && n.toLong() <= limits.totalBytes - total) { "El contenido descomprimido supera el límite." }
                            size += n; total += n
                            require(size <= limits.ratioThreshold || (e.compressedSize > 0 && size / limits.maxRatio <= e.compressedSize)) { "Relación de compresión sospechosa: $path" }
                            crc.update(buffer, 0, n); out.write(buffer, 0, n)
                        }
                    } }
                    require(size == e.size && crc.value == e.crc) { "Archivo ZIP dañado o CRC incorrecto: $path" }
                    fileNames.add(path)
                }
            }
            require(fileNames.isNotEmpty()) { "El ZIP no contiene archivos." }
            ensureActive()
            return block(extracted, fileNames)
        } finally { stage.deleteRecursively() }
    }

    private fun inventory(name: String, paths: List<String>): ModZipReview {
        // A single enclosing mod folder is common. Match known structure at any directory depth.
        val lower = paths.associateWith { it.lowercase(Locale.ROOT) }
        fun matches(path: String, pattern: String) = Regex("(?:^|/)$pattern", RegexOption.IGNORE_CASE).containsMatchIn(path)
        val psychCharts = paths.filter { matches(it, "data/(?!(?:songs|characters|stages|levels|weeks)/)[^/]+/[^/]+\\.json$") }
        val vsCharts = paths.filter { matches(it, "data/songs/[^/]+/[^/]+-chart(?:-[^/]+)?\\.json$") }
        val vsMetadata = paths.filter { matches(it, "data/songs/[^/]+/[^/]+-metadata(?:-[^/]+)?\\.json$") }
        val psychIndicators = paths.any { matches(it, "pack\\.json$") } || paths.any { matches(it, "weeks/[^/]+\\.json$") }
        val vsIndicators = paths.any { matches(it, "_polymod_meta\\.json$") }
        val engines = buildList {
            if (psychCharts.isNotEmpty() || psychIndicators) add("Psych Engine (indicios)")
            if (vsCharts.isNotEmpty() || vsMetadata.isNotEmpty() || vsIndicators) add("V-Slice (indicios)")
            if (isEmpty()) add("Desconocido o incompleto")
        }
        fun songInPsych(p: String) = Regex("(?:^|/)data/([^/]+)/[^/]+\\.json$", RegexOption.IGNORE_CASE).find(p)?.groupValues?.get(1)
        fun songInVs(p: String) = Regex("(?:^|/)data/songs/([^/]+)/", RegexOption.IGNORE_CASE).find(p)?.groupValues?.get(1)
        val songs = (psychCharts.mapNotNull(::songInPsych) + (vsCharts + vsMetadata).mapNotNull(::songInVs)).distinctBy { key(it) }.sorted()
        val difficulties = psychCharts.mapNotNull { p ->
            val folder = songInPsych(p) ?: return@mapNotNull null
            val file = p.substringAfterLast('/').removeSuffix(".json")
            when { file.equals(folder, true) -> "$folder: normal (posible)"; file.startsWith("$folder-", true) -> "$folder: ${file.substring(folder.length + 1)} (posible)"; else -> "$folder: ${file} (revisar)" }
        }.distinct().sorted()
        val files = paths.sorted().map { p ->
            val l = lower.getValue(p)
            val kind = when {
                l.endsWith(".lua") || l.endsWith(".hx") || l.endsWith(".hscript") -> "Script"
                p in psychCharts || p in vsCharts -> "Chart o JSON de canción"
                p in vsMetadata -> "Metadata de canción"
                matches(p, "(?:data/)?characters/[^/]+\\.json$") -> "Personaje"
                matches(p, "(?:data/)?stages/[^/]+\\.json$") -> "Escenario"
                l.endsWith(".ogg") || l.endsWith(".mp3") || l.endsWith(".wav") -> "Audio"
                l.endsWith(".png") || l.endsWith(".jpg") -> "Imagen"
                l.endsWith(".xml") -> "XML"
                else -> "Otro recurso"
            }
            InventoriedFile(p, kind, "Requiere revisión", if (kind == "Script") "No se ejecuta. Podría ejecutarse en el motor; el análisis no lo guarda." else "Detectado por ruta o extensión; aún no se convierte ni se conserva en un proyecto.")
        }
        val warnings = buildList {
            add("Vista preliminar V1.1: no se crea ningún proyecto, no se convierten charts ni se conservan recursos. El ZIP original no se modifica.")
            add("La detección usa indicios de rutas; no valida esquemas, compatibilidad ni contenido de los elementos.")
            if (psychCharts.isNotEmpty() && (vsCharts.isNotEmpty() || vsMetadata.isNotEmpty())) add("Hay indicios de ambos formatos. Las canciones y charts podrían diferir: todavía no se comparan ni se combinan.")
            if (files.any { it.kind == "Script" }) add("Los scripts son datos en la app; pueden ejecutarse en el motor del juego. Revisa su código antes de instalar un mod.")
            if (vsCharts.isNotEmpty()) add("Las dificultades y variaciones V-Slice requieren leer los JSON; aún no se analizan.")
        }
        return ModZipReview(name.take(160), engines, songs, difficulties, files.count { it.kind == "Personaje" },
            files.count { it.kind == "Escenario" }, files.count { it.kind == "Audio" }, files.count { it.kind == "Script" }, files, warnings)
    }
}
