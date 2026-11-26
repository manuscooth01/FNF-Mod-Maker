package com.fnfmodmaker.mod

import android.net.Uri
import com.fnfmodmaker.model.AssetCategory
import com.fnfmodmaker.model.AssetPack
import com.fnfmodmaker.model.CatalogAsset
import com.fnfmodmaker.model.PickedImage
import com.fnfmodmaker.modimport.ModZipInspector
import com.fnfmodmaker.modimport.ModZipLimits
import com.fnfmodmaker.project.AssetPackCatalog
import java.io.File
import java.io.InputStream
import java.util.UUID

/** Android-safe limits for a full resource pack: looser than the mod inspector's
 *  because original game packs are large, but still bounded for mobile storage.
 *  Extraction is on disk with small buffers; the archive is never held in memory. */
internal data class AssetPackLimits(
    val archiveBytes: Long = 256L * 1024 * 1024,
    val totalBytes: Long = 512L * 1024 * 1024,
    val fileBytes: Long = 64L * 1024 * 1024,
    val entries: Int = 4096,
    val maxRatio: Long = 200,
    val ratioThreshold: Long = 1024L * 1024
)

/** Read-only summary shown before anything touches the project. */
internal data class AssetPackReview(
    val name: String,
    val entries: Int,
    val totalBytes: Long,
    val categories: Map<String, Int>,
    val warnings: List<String>
)

/**
 * Imports a generic game-resource ZIP as an Asset Library pack. It does not require
 * pack.json, _polymod_meta.json, charts or song metadata, and it never runs content.
 * Analysis is read-only; publication stages inside the project assets and finishes
 * with one same-filesystem rename, so a failure or cancellation leaves the previous
 * catalog and project untouched.
 */
internal class AssetPackImporter(
    private val tempRoot: File,
    private val dimensions: (File) -> Pair<Int, Int>? = { null },
    private val limits: AssetPackLimits = AssetPackLimits()
) {
    private val inspector = ModZipInspector(tempRoot, ModZipLimits(
        archiveBytes = limits.archiveBytes, totalBytes = limits.totalBytes,
        fileBytes = limits.fileBytes, entries = limits.entries,
        maxRatio = limits.maxRatio, ratioThreshold = limits.ratioThreshold))

    fun analyze(input: InputStream, name: String, ensureActive: () -> Unit = {}): AssetPackReview =
        inspector.withExtracted(input, ensureActive) { extracted, paths -> review(name, extracted, paths) }

    /**
     * Copies validated files to <projectAssets>/library/<packId>/ keeping the ZIP's
     * hierarchy, hashes every file, and returns the published pack.
     */
    fun publish(
        input: InputStream,
        name: String,
        projectAssets: File,
        ensureActive: () -> Unit = {},
        onProgress: (String) -> Unit = {}
    ): AssetPack {
        val packId = UUID.randomUUID().toString()
        val library = AssetPackCatalog.packDirectory(projectAssets, packId)
        require(!library.exists()) { "No se pudo reservar la carpeta del paquete." }
        val staging = File(projectAssets, "library/.import-${UUID.randomUUID()}")
        try {
            val pack = inspector.withExtracted(input, ensureActive) { extracted, paths ->
                val assets = paths.sorted().mapIndexed { index, path ->
                    onProgress("Catalogando ${index + 1}/${paths.size}…")
                    val source = File(extracted, path)
                    require(source.canonicalPath.startsWith(extracted.canonicalPath + File.separator) && source.isFile) {
                        "Archivo extraído inseguro: $path"
                    }
                    val target = File(staging, path)
                    require(target.canonicalPath.startsWith(staging.canonicalPath + File.separator)) { "Destino inseguro: $path" }
                    check(target.parentFile.isDirectory || target.parentFile.mkdirs()) { "No se pudo crear la carpeta del paquete." }
                    source.inputStream().use { stream -> target.outputStream().use { stream.copyTo(it) } }
                    val (size, hash) = target.inputStream().use { AssetPackCatalog.sha256Of(it, limits.fileBytes) }
                    val dims = if (AssetPackCatalog.isImage(path)) {
                        dimensions(target)?.takeIf { it.first > 0 && it.second > 0 } ?: (0 to 0)
                    } else (0 to 0)
                    val displayName = path.substringAfterLast('/')
                    CatalogAsset(
                        id = UUID.randomUUID().toString(), packId = packId, sourcePath = path,
                        displayName = displayName, category = AssetPackCatalog.classify(path).name,
                        extension = path.substringAfterLast('.', "").take(8).lowercase(),
                        sizeBytes = size, sha256 = hash, width = dims.first, height = dims.second,
                        file = PickedImage(uri = Uri.EMPTY, displayName = displayName, path = target.absolutePath)
                    )
                }
                require(assets.isNotEmpty()) { "El ZIP no contiene archivos importables." }
                AssetPack(packId, name.take(160), System.currentTimeMillis(), engineHint(paths),
                    AssetPackCatalog.pairRelated(assets))
            }
            verifyStagedPack(pack, staging)
            check(library.parentFile.isDirectory || library.parentFile.mkdirs()) { "No se pudo crear library/." }
            check(staging.renameTo(library)) { "No se pudo publicar el paquete; el proyecto no cambió." }
            return pack.copy(assets = pack.assets.map { asset ->
                val file = asset.file ?: return@map asset
                val relative = file.path.removePrefix(staging.absolutePath + "/")
                asset.copy(file = file.copy(path = File(library, relative).canonicalPath, uri = Uri.EMPTY))
            })
        } finally {
            staging.deleteRecursively() // sin efecto si ya se publicó por rename
        }
    }

    /** Defense in depth: the staged bytes must match the catalog before publication. */
    private fun verifyStagedPack(pack: AssetPack, staging: File) {
        val ids = mutableSetOf<String>()
        pack.assets.forEach { asset ->
            require(ids.add(asset.id.lowercase())) { "Catálogo con identificadores repetidos." }
            val stored = asset.file ?: error("Recurso sin referencia: ${asset.sourcePath}")
            require(stored.path.startsWith(staging.absolutePath + File.separator)) { "Recurso fuera del área temporal: ${asset.sourcePath}" }
            val target = File(stored.path)
            val (size, hash) = target.inputStream().use { AssetPackCatalog.sha256Of(it, limits.fileBytes) }
            require(size == asset.sizeBytes && hash == asset.sha256) { "Checksum del recurso ${asset.sourcePath} no coincide." }
        }
    }

    /** Engine evidence from paths only; never a compatibility statement. */
    private fun engineHint(paths: List<String>): String {
        fun matches(path: String, pattern: String) =
            Regex("(?:^|/)$pattern", RegexOption.IGNORE_CASE).containsMatchIn(path)
        val psych = paths.any { matches(it, "pack\\.json$") || matches(it, "weeks/[^/]+\\.json$") ||
            matches(it, "characters/[^/]+\\.json$") || matches(it, "stages/[^/]+\\.json$") }
        val vslice = paths.any { matches(it, "_polymod_meta\\.json$") || matches(it, "data/songs/[^/]+/[^/]+-metadata\\.json$") }
        return when {
            psych && vslice -> "Ambos formatos (indicios contradictorios)"
            psych -> "Psych Engine (indicios)"
            vslice -> "V-Slice (indicios)"
            else -> "Sin indicios de motor"
        }
    }

    private fun review(name: String, extracted: File, paths: List<String>): AssetPackReview {
        val categories = paths.groupingBy { AssetPackCatalog.classify(it).label }.eachCount().toSortedMap()
        val total = paths.sumOf { File(extracted, it).length() }
        val warnings = buildList {
            add("La clasificación usa extensión y rutas; no verifica el contenido ni garantiza compatibilidad con un motor.")
            add("Los scripts se conservan como datos y nunca se ejecutan dentro de la app.")
            val unclassified = paths.count { AssetPackCatalog.classify(it) == AssetCategory.UNCLASSIFIED }
            if (unclassified > 0) add("$unclassified recursos quedarán como Sin clasificar; se conservan igualmente.")
            add("No se requieren pack.json ni metadata de mod; cualquier ZIP de recursos sirve.")
        }
        return AssetPackReview(name.take(160), paths.size, total, categories, warnings)
    }
}
